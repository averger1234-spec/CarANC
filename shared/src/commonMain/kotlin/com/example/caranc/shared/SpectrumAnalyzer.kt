package com.example.caranc.shared

import androidx.annotation.Keep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

private const val MIN_FFT_SIZE = 256
private const val MAX_FFT_SIZE = 2048

@Keep
/**
 * DSP utility for magnitude spectrum (FFT + downsample bins) and RMS dB computation.
 *
 * CYCLE3_EXTRA: Refactored from object singleton -> regular class (ctor for scoping).
 * - Direct use in tests: SpectrumAnalyzer().computeMagnitudeSpectrum(...)
 * - Via context for prod/scoped: sessionContext.spectrumAnalyzer.compute... (enables test doubles by passing custom impl to AncSessionContext)
 * - No state, pure; class allows subclass override or replacement for mocks without global side effects.
 * Factory not needed; default() via context suffices.
 */
class SpectrumAnalyzer {

    fun computeMagnitudeSpectrum(samples: ShortArray, binCount: Int = 64): FloatArray {
        if (samples.isEmpty() || binCount <= 0) return FloatArray(binCount)

        val fftSize = FftUtils.nextPowerOfTwo(samples.size.coerceIn(MIN_FFT_SIZE, MAX_FFT_SIZE))
        val fftInput = FloatArray(fftSize * 2)
        for (i in samples.indices) {
            if (i >= fftSize) break
            val window = 0.5f * (1f - cos(2f * PI.toFloat() * i / (samples.size - 1).coerceAtLeast(1)))
            fftInput[i * 2] = (samples[i] / 32768f) * window
        }

        FftUtils.complexForward(fftInput)

        val halfBins = fftSize / 2
        val magnitudes = FloatArray(halfBins)
        for (i in 0 until halfBins) {
            val real = fftInput[i * 2]
            val imag = fftInput[i * 2 + 1]
            magnitudes[i] = sqrt(real * real + imag * imag)
        }

        return downsampleToBins(magnitudes, binCount)
    }

    fun computeRmsDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -90f
        var sumSq = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sumSq += normalized * normalized
        }
        val rms = sqrt(sumSq / samples.size)
        return (20 * ln(rms + 1e-10) / ln(10.0)).toFloat()
    }

    private fun downsampleToBins(magnitudes: FloatArray, binCount: Int): FloatArray {
        val result = FloatArray(binCount)
        if (magnitudes.isEmpty()) return result

        val binsPerBucket = magnitudes.size.toFloat() / binCount
        for (bucket in 0 until binCount) {
            val start = (bucket * binsPerBucket).toInt()
            val end = ((bucket + 1) * binsPerBucket).toInt().coerceAtMost(magnitudes.size)
            var sum = 0f
            var count = 0
            for (i in start until end) {
                sum += magnitudes[i]
                count++
            }
            result[bucket] = if (count > 0) sum / count else 0f
        }
        return result
    }
}