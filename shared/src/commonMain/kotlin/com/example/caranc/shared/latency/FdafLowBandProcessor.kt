package com.example.caranc.shared.latency

import androidx.annotation.Keep
import com.example.caranc.shared.FftUtils

/**
 * 低頻 overlap-save 塊頻域 NLMS，適合恆定路噪/怠速 rumble。
 * P1#6+7: push buffer reuse (extendedBuf etc) to eliminate allocs in push(); owned via processor layer.
 * NOTE: native low-freq candidate (overlap-save FFT NLMS for rumble).
 * CYCLE3_EXTRA: counters added; see NativeLowBandProcessor.cpp comments for porting this to C++/NEON.
 */
@Keep
class FdafLowBandProcessor(
    private val blockSize: Int = 64,
    filterLength: Int = 64
) {
    private val filterLength = filterLength.coerceAtMost(blockSize)
    private val fftSize = FftUtils.nextPowerOfTwo(blockSize + filterLength - 1)
    private val half = fftSize / 2

    private val inputBlock = FloatArray(blockSize)
    private var blockIndex = 0
    private val overlapBuffer = FloatArray(filterLength - 1)
    private val freqWeights = FloatArray(fftSize * 2)
    private val outputQueue = FloatArray(blockSize)
    private var outputReadIndex = 0
    private var outputAvailable = 0

    // push buffer reuse (P1 #6+7 hot-path opt): temps preallocated/reused here (fdaf owned by MultiBandANCProcessor processor layer)
    // avoids FloatArray allocs on every block in push/process hot path for low-freq
    private val extendedBuf = FloatArray(fftSize * 2)

    // CYCLE3_EXTRA native low-freq proto: simple profiling counters (mirrors BandFxLms style).
    // Updated in processBlock; consumed by processor facade exposure + AudioEngine timing.
    var fdafLmsUpdateCount = 0L
        private set
    var fdafProcessCalls = 0L
        private set
    private val inputFftBuf = FloatArray(fftSize * 2)
    private val productBuf = FloatArray(fftSize * 2)
    private val errorBlockBuf = FloatArray(blockSize)
    private val errorFftBuf = FloatArray(fftSize * 2)

    init {
        freqWeights[0] = 1f
    }

    var lastBlockProcessed = false
        private set

    @Keep
    fun push(sample: Float): Float {
        if (outputAvailable > 0) {
            val out = outputQueue[outputReadIndex]
            outputReadIndex = (outputReadIndex + 1) % blockSize
            outputAvailable--
            inputBlock[blockIndex++] = sample
            if (blockIndex >= blockSize) {
                blockIndex = 0
                processBlock()
            }
            return out
        }

        inputBlock[blockIndex++] = sample
        if (blockIndex >= blockSize) {
            blockIndex = 0
            processBlock()
            if (outputAvailable > 0) {
                val out = outputQueue[outputReadIndex]
                outputReadIndex = (outputReadIndex + 1) % blockSize
                outputAvailable--
                return out
            }
        }
        return 0f
    }

    private fun processBlock() {
        lastBlockProcessed = true
        fdafProcessCalls++
        // reuse push buffers (no alloc)
        extendedBuf.fill(0f)
        for (i in overlapBuffer.indices) {
            extendedBuf[i * 2] = overlapBuffer[i]
        }
        for (i in inputBlock.indices) {
            extendedBuf[(overlapBuffer.size + i) * 2] = inputBlock[i]
        }

        inputFftBuf.fill(0f)
        for (i in extendedBuf.indices) inputFftBuf[i] = extendedBuf[i]
        FftUtils.complexForward(inputFftBuf)

        productBuf.fill(0f)
        for (i in 0 until half) {
            val ri = i * 2
            val ii = ri + 1
            val wr = freqWeights[ri]
            val wi = freqWeights[ii]
            val xr = inputFftBuf[ri]
            val xi = inputFftBuf[ii]
            productBuf[ri] = wr * xr - wi * xi
            productBuf[ii] = wr * xi + wi * xr
        }
        FftUtils.complexInverse(productBuf, scale = true)

        val outputStart = overlapBuffer.size
        for (i in outputQueue.indices) {
            outputQueue[i] = -productBuf[(outputStart + i) * 2].coerceIn(-0.6f, 0.6f)
        }
        outputReadIndex = 0
        outputAvailable = blockSize

        updateOverlapBuffer()

        errorBlockBuf.fill(0f)
        for (i in errorBlockBuf.indices) {
            errorBlockBuf[i] = inputBlock[i] + outputQueue[i]
        }
        val errorEnergy = errorBlockBuf.sumOf { (it * it).toDouble() }.toFloat() + 1e-4f
        val mu = 0.08f / errorEnergy.coerceAtMost(4f)

        errorFftBuf.fill(0f)
        for (i in errorBlockBuf.indices) {
            errorFftBuf[(overlapBuffer.size + i) * 2] = errorBlockBuf[i]
        }
        FftUtils.complexForward(errorFftBuf)

        for (i in 0 until half) {
            val ri = i * 2
            val ii = ri + 1
            val conjXr = inputFftBuf[ri]
            val conjXi = -inputFftBuf[ii]
            val er = errorFftBuf[ri]
            val ei = errorFftBuf[ii]
            freqWeights[ri] = (freqWeights[ri] + mu * (er * conjXr - ei * conjXi)).coerceIn(-2f, 2f)
            freqWeights[ii] = (freqWeights[ii] + mu * (er * conjXi + ei * conjXr)).coerceIn(-2f, 2f)
        }
        fdafLmsUpdateCount++
    }

    private fun updateOverlapBuffer() {
        val overlapLen = overlapBuffer.size
        if (overlapLen <= blockSize) {
            val start = blockSize - overlapLen
            for (i in 0 until overlapLen) {
                overlapBuffer[i] = inputBlock[start + i]
            }
            return
        }

        val shift = overlapLen - blockSize
        for (i in 0 until shift) {
            overlapBuffer[i] = overlapBuffer[blockSize + i]
        }
        for (i in 0 until blockSize) {
            overlapBuffer[shift + i] = inputBlock[i]
        }
    }

    @Keep
    fun reset() {
        blockIndex = 0
        overlapBuffer.fill(0f)
        inputBlock.fill(0f)
        outputQueue.fill(0f)
        outputReadIndex = 0
        outputAvailable = 0
        freqWeights.fill(0f)
        freqWeights[0] = 1f
        extendedBuf.fill(0f)
        inputFftBuf.fill(0f)
        productBuf.fill(0f)
        errorBlockBuf.fill(0f)
        errorFftBuf.fill(0f)
        lastBlockProcessed = false
        fdafLmsUpdateCount = 0L
        fdafProcessCalls = 0L
    }
}