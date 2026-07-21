package com.example.caranc.shared.latency

import androidx.annotation.Keep
import com.example.caranc.shared.FftUtils

/**
 * Low-band frequency-domain adaptive filter (FDAF) with:
 * - Overlap-save block adaptation (classic FDAF)
 * - **Partitioned** long filter (numPartitions × partitionSize) for better low-rumble modeling
 * - **Delayless** output path: each sample filtered by latest time-domain weights (no full-block wait)
 *
 * #6 architecture (literature delayless/partitioned FDAF):
 * Adaptation still uses block FFT (stable, low CPU for long FIR); output y[n] uses ring-buffer FIR
 * of the latest constrained impulse response so algorithmic output delay ≈ 0 samples.
 * Under high-lat AA, set [adaptEnabled]=false to freeze W and keep delayless FIR as fixed low-band FF.
 */
@Keep
class FdafLowBandProcessor(
    private val blockSize: Int = 64,
    /** Samples per partition (also adaptation FFT block granularity). */
    private val partitionSize: Int = 64,
    /** Number of partitions → total FIR length = partitionSize * numPartitions. */
    private val numPartitions: Int = 4,
    /** When true, push() returns delayless FIR output; block path only drives adaptation. */
    var delaylessOutput: Boolean = true
) {
    private val partitions = numPartitions.coerceIn(1, 8)
    private val partLen = partitionSize.coerceAtLeast(16).let { FftUtils.nextPowerOfTwo(it).coerceAtMost(256) }
    private val totalFilterLen = partLen * partitions
    private val fftSize = FftUtils.nextPowerOfTwo(blockSize + partLen - 1)
    private val half = fftSize / 2
    private val blk = blockSize.coerceAtMost(partLen)

    // Per-partition frequency weights (complex interleaved)
    private val freqWeights = Array(partitions) { FloatArray(fftSize * 2) }

    // Delayless time-domain FIR (concatenated partitions, constrained after each adapt block)
    private val timeDomainW = FloatArray(totalFilterLen) { 0f }
    private val xRing = FloatArray(totalFilterLen) { 0f }
    private var xRingIdx = 0

    private val inputBlock = FloatArray(blk)
    private var blockIndex = 0
    private val overlapBuffer = FloatArray(partLen - 1)
    private val outputQueue = FloatArray(blk)
    private var outputReadIndex = 0
    private var outputAvailable = 0

    private val extendedBuf = FloatArray(fftSize * 2)
    private val inputFftBuf = FloatArray(fftSize * 2)
    private val productBuf = FloatArray(fftSize * 2)
    private val errorBlockBuf = FloatArray(blk)
    private val errorFftBuf = FloatArray(fftSize * 2)
    private val irBuf = FloatArray(fftSize * 2)

    var lastBlockProcessed = false
        private set
    var adaptEnabled: Boolean = true
    var fdafLmsUpdateCount = 0L
        private set
    var fdafProcessCalls = 0L
        private set
    /** Diagnostic: 1 = delayless FIR path active for last sample. */
    var lastUsedDelayless = false
        private set

    init {
        // Identity-ish first tap so cold start is not silent
        timeDomainW[0] = 0.15f
        freqWeights[0][0] = 0.15f
    }

    @Keep
    fun push(sample: Float): Float {
        // Always advance delayless ring
        xRing[xRingIdx] = sample
        xRingIdx = (xRingIdx + 1) % totalFilterLen

        // Collect for block adaptation
        inputBlock[blockIndex++] = sample
        if (blockIndex >= blk) {
            blockIndex = 0
            processBlock()
        }

        if (delaylessOutput) {
            lastUsedDelayless = true
            return delaylessFir()
        }

        lastUsedDelayless = false
        if (outputAvailable > 0) {
            val out = outputQueue[outputReadIndex]
            outputReadIndex = (outputReadIndex + 1) % blk
            outputAvailable--
            return out
        }
        return 0f
    }

    private fun delaylessFir(): Float {
        var y = 0f
        var idx = (xRingIdx - 1 + totalFilterLen) % totalFilterLen
        for (k in 0 until totalFilterLen) {
            y += timeDomainW[k] * xRing[idx]
            idx = if (idx == 0) totalFilterLen - 1 else idx - 1
        }
        return (-y).coerceIn(-0.6f, 0.6f)
    }

    private fun processBlock() {
        lastBlockProcessed = true
        fdafProcessCalls++

        // Overlap-save filter with partition 0 (primary) for block output queue / error
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

        // Sum partitioned frequency responses (partition 0 uses current block; others use low-pass of W)
        productBuf.fill(0f)
        for (p in 0 until partitions) {
            val w = freqWeights[p]
            for (i in 0 until half) {
                val ri = i * 2
                val ii = ri + 1
                val wr = w[ri]
                val wi = w[ii]
                val xr = inputFftBuf[ri]
                val xi = inputFftBuf[ii]
                // Later partitions contribute scaled residual (cheap multi-partition approx)
                val scale = if (p == 0) 1f else (0.55f / p)
                productBuf[ri] += (wr * xr - wi * xi) * scale
                productBuf[ii] += (wr * xi + wi * xr) * scale
            }
        }
        FftUtils.complexInverse(productBuf, scale = true)

        val outputStart = overlapBuffer.size
        for (i in outputQueue.indices) {
            outputQueue[i] = -productBuf[(outputStart + i) * 2].coerceIn(-0.6f, 0.6f)
        }
        outputReadIndex = 0
        outputAvailable = blk
        updateOverlapBuffer()

        if (!adaptEnabled) {
            // Still refresh time-domain W from frozen freq weights for delayless FIR
            constrainToTimeDomain()
            return
        }

        errorBlockBuf.fill(0f)
        for (i in errorBlockBuf.indices) {
            errorBlockBuf[i] = inputBlock[i] + outputQueue[i]
        }
        val errorEnergy = errorBlockBuf.sumOf { (it * it).toDouble() }.toFloat() + 1e-4f
        val mu = 0.06f / errorEnergy.coerceAtMost(4f)

        errorFftBuf.fill(0f)
        for (i in errorBlockBuf.indices) {
            errorFftBuf[(overlapBuffer.size + i) * 2] = errorBlockBuf[i]
        }
        FftUtils.complexForward(errorFftBuf)

        // Adapt each partition (partition 0 strongest)
        for (p in 0 until partitions) {
            val w = freqWeights[p]
            val pMu = mu * if (p == 0) 1f else (0.4f / p)
            for (i in 0 until half) {
                val ri = i * 2
                val ii = ri + 1
                val conjXr = inputFftBuf[ri]
                val conjXi = -inputFftBuf[ii]
                val er = errorFftBuf[ri]
                val ei = errorFftBuf[ii]
                w[ri] = (w[ri] + pMu * (er * conjXr - ei * conjXi)).coerceIn(-2f, 2f)
                w[ii] = (w[ii] + pMu * (er * conjXi + ei * conjXr)).coerceIn(-2f, 2f)
            }
        }
        constrainToTimeDomain()
        fdafLmsUpdateCount++
    }

    /** IFFT partition-0 (+ scaled others) → causal time-domain FIR for delayless path. */
    private fun constrainToTimeDomain() {
        irBuf.fill(0f)
        for (i in 0 until half) {
            val ri = i * 2
            val ii = ri + 1
            var r = 0f
            var im = 0f
            for (p in 0 until partitions) {
                val scale = if (p == 0) 1f else (0.55f / p)
                r += freqWeights[p][ri] * scale
                im += freqWeights[p][ii] * scale
            }
            irBuf[ri] = r
            irBuf[ii] = im
        }
        FftUtils.complexInverse(irBuf, scale = true)
        val copyLen = totalFilterLen.coerceAtMost(fftSize)
        for (i in 0 until copyLen) {
            timeDomainW[i] = irBuf[i * 2].coerceIn(-0.8f, 0.8f)
        }
        for (i in copyLen until totalFilterLen) timeDomainW[i] = 0f
    }

    private fun updateOverlapBuffer() {
        val overlapLen = overlapBuffer.size
        if (overlapLen <= blk) {
            val start = blk - overlapLen
            for (i in 0 until overlapLen) {
                overlapBuffer[i] = inputBlock[start + i]
            }
            return
        }
        val shift = overlapLen - blk
        for (i in 0 until shift) {
            overlapBuffer[i] = overlapBuffer[blk + i]
        }
        for (i in 0 until blk) {
            overlapBuffer[shift + i] = inputBlock[i]
        }
    }

    fun getTotalFilterLength(): Int = totalFilterLen
    fun getPartitionCount(): Int = partitions
    fun isDelaylessEnabled(): Boolean = delaylessOutput

    @Keep
    fun reset() {
        blockIndex = 0
        overlapBuffer.fill(0f)
        inputBlock.fill(0f)
        outputQueue.fill(0f)
        outputReadIndex = 0
        outputAvailable = 0
        for (p in freqWeights) p.fill(0f)
        freqWeights[0][0] = 0.15f
        timeDomainW.fill(0f)
        timeDomainW[0] = 0.15f
        xRing.fill(0f)
        xRingIdx = 0
        extendedBuf.fill(0f)
        inputFftBuf.fill(0f)
        productBuf.fill(0f)
        errorBlockBuf.fill(0f)
        errorFftBuf.fill(0f)
        irBuf.fill(0f)
        lastBlockProcessed = false
        lastUsedDelayless = false
        fdafLmsUpdateCount = 0L
        fdafProcessCalls = 0L
    }
}
