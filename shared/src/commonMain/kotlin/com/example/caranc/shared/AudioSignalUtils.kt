package com.example.caranc.shared

import androidx.annotation.Keep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

@Keep
data class ImpulseResponseEstimate(
    val model: FloatArray,
    val acousticDelaySamples: Int,
    val peakMagnitude: Float
)

@Keep
object AudioSignalUtils {

    const val CALIBRATION_CHIRP_REPEATS = 2
    private const val CHIRP_AMPLITUDE = 0.18f
    private const val FADE_SAMPLES = 256

    fun generateLogChirp(
        size: Int,
        sampleRate: Int,
        f0: Float = 100f,
        f1: Float = minOf(8_000f, sampleRate / 2f - 100f),
        amplitude: Float = CHIRP_AMPLITUDE
    ): ShortArray {
        require(size > 0 && sampleRate > 0)
        val output = ShortArray(size)
        val duration = size.toFloat() / sampleRate
        val k = ln(f1 / f0) / duration
        val fadeSamples = FADE_SAMPLES.coerceAtMost(size / 4)

        for (i in 0 until size) {
            val t = i.toFloat() / sampleRate
            val phase = 2.0 * PI * f0 * (exp(k * t) - 1.0) / k
            val envelope = chirpEnvelope(i, size, fadeSamples)
            val sample = (sin(phase) * amplitude * envelope * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = sample.toShort()
        }
        return output
    }

    private fun chirpEnvelope(index: Int, size: Int, fadeSamples: Int): Float {
        if (fadeSamples <= 0) return 1f
        val fadeIn = (index.toFloat() / fadeSamples).coerceIn(0f, 1f)
        val fadeOut = ((size - 1 - index).toFloat() / fadeSamples).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut)
    }

    fun estimateImpulseResponse(
        excitation: ShortArray,
        recorded: ShortArray,
        maxLength: Int
    ): ImpulseResponseEstimate {
        val usableLength = minOf(excitation.size, recorded.size)
        if (usableLength <= 0) return fallbackEstimate(maxLength)

        val fftSize = FftUtils.nextPowerOfTwo(usableLength * 2)
        val excitationFft = toNormalizedFloat(excitation, usableLength, fftSize)
        val recordedFft = toNormalizedFloat(recorded, usableLength, fftSize)

        FftUtils.complexForward(excitationFft)
        FftUtils.complexForward(recordedFft)

        val correlation = FloatArray(fftSize * 2)
        val half = fftSize / 2
        for (i in 0 until half) {
            val realIndex = i * 2
            val imagIndex = realIndex + 1

            val excReal = excitationFft[realIndex]
            val excImag = excitationFft[imagIndex]
            val recReal = recordedFft[realIndex]
            val recImag = recordedFft[imagIndex]

            correlation[realIndex] = recReal * excReal + recImag * excImag
            correlation[imagIndex] = recImag * excReal - recReal * excImag
        }

        FftUtils.complexInverse(correlation, scale = true)

        val impulseLength = maxLength.coerceAtMost(usableLength)
        val impulse = FloatArray(impulseLength)
        var peakIndex = 0
        var peakValue = 0f

        for (i in 0 until impulseLength) {
            val value = abs(correlation[i * 2])
            impulse[i] = correlation[i * 2]
            if (value > peakValue) {
                peakValue = value
                peakIndex = i
            }
        }

        if (peakValue < 1e-4f) {
            return fallbackEstimate(maxLength)
        }

        val aligned = FloatArray(impulseLength)
        val tailLength = impulseLength - peakIndex
        for (i in 0 until tailLength) {
            aligned[i] = impulse[peakIndex + i]
        }

        val maxAbs = aligned.maxOf { abs(it) }.coerceAtLeast(1e-4f)
        val model = FloatArray(impulseLength) { aligned[it] / maxAbs }
        return ImpulseResponseEstimate(
            model = model,
            acousticDelaySamples = peakIndex,
            peakMagnitude = peakValue
        )
    }

    fun averageAbsoluteEnergy(samples: ShortArray, length: Int = samples.size): Double {
        if (length <= 0) return 0.0
        var energy = 0.0
        for (i in 0 until length.coerceAtMost(samples.size)) {
            energy += abs(samples[i].toInt())
        }
        return energy / length
    }

    private fun fallbackEstimate(maxLength: Int): ImpulseResponseEstimate {
        return ImpulseResponseEstimate(
            model = FloatArray(maxLength) { index -> if (index == 0) 1f else 0f },
            acousticDelaySamples = 0,
            peakMagnitude = 1f
        )
    }

    private fun toNormalizedFloat(source: ShortArray, usableLength: Int, fftSize: Int): FloatArray {
        val data = FloatArray(fftSize * 2)
        for (i in 0 until usableLength) {
            data[i * 2] = source[i] / 32768f
        }
        return data
    }
}