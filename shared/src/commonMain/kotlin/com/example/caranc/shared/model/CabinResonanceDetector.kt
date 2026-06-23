package com.example.caranc.shared.model

import androidx.annotation.Keep
import kotlin.math.abs
import kotlin.math.max

@Keep
object CabinResonanceDetector {

    fun detect(
        secondaryPath: FloatArray,
        sampleRate: Int,
        maxPeaks: Int = 5,
        minMagnitude: Float = 0.12f
    ): List<ResonancePeak> {
        if (secondaryPath.isEmpty() || sampleRate <= 0) return emptyList()

        val peaks = mutableListOf<ResonancePeak>()
        for (index in 1 until secondaryPath.lastIndex) {
            val magnitude = abs(secondaryPath[index])
            val isPeak = magnitude >= minMagnitude &&
                magnitude >= abs(secondaryPath[index - 1]) &&
                magnitude > abs(secondaryPath[index + 1])
            if (!isPeak) continue

            peaks += ResonancePeak(
                frequencyHz = index.toFloat() * sampleRate.toFloat() / secondaryPath.size.toFloat(),
                magnitude = magnitude,
                index = index
            )
        }

        return peaks
            .sortedByDescending { it.magnitude }
            .take(maxPeaks)
            .sortedBy { it.frequencyHz }
    }

    fun resonanceMuScale(frequencyHz: Float, peaks: List<ResonancePeak>): Float {
        if (peaks.isEmpty()) return 1f
        var scale = 1f
        for (peak in peaks) {
            val delta = abs(frequencyHz - peak.frequencyHz)
            if (delta < 18f) {
                val proximity = 1f - (delta / 18f)
                scale = minOf(scale, 1f - proximity * 0.55f)
            }
        }
        return scale.coerceIn(0.25f, 1f)
    }
}