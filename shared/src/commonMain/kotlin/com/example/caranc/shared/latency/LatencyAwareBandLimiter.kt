package com.example.caranc.shared.latency

import androidx.annotation.Keep

@Keep
data class LatencyBandLimits(
    val estimatedLatencyMs: Float,
    val maxCancelFrequencyHz: Float,
    val lowGain: Float,
    val midGain: Float,
    val highGain: Float,
    val lowEnabled: Boolean,
    val midEnabled: Boolean,
    val highEnabled: Boolean
)

@Keep
object LatencyAwareBandLimiter {

    fun maxCancelFrequencyHz(latencyMs: Float): Float =
        (1000f / (4f * latencyMs.coerceIn(20f, 400f))).coerceIn(35f, 350f)

    fun limits(latencyMs: Float): LatencyBandLimits {
        val maxHz = maxCancelFrequencyHz(latencyMs)
        val lowGain = 1f
        val midGain = when {
            maxHz < 120f -> 0f
            maxHz < 180f -> 0.25f
            maxHz < 250f -> 0.55f
            else -> 0.85f
        }
        val highGain = when {
            maxHz < 200f -> 0f
            maxHz < 280f -> 0.15f
            else -> 0.4f
        }
        return LatencyBandLimits(
            estimatedLatencyMs = latencyMs,
            maxCancelFrequencyHz = maxHz,
            lowGain = lowGain,
            midGain = midGain,
            highGain = highGain,
            lowEnabled = true,
            midEnabled = midGain > 0.05f,
            highEnabled = highGain > 0.05f
        )
    }

    fun bandMuScale(centerHz: Float, latencyMs: Float): Float {
        val maxHz = maxCancelFrequencyHz(latencyMs)
        if (centerHz <= maxHz * 0.7f) return 1f
        if (centerHz >= maxHz * 1.2f) return 0f
        val ratio = (maxHz * 1.2f - centerHz) / (maxHz * 0.5f)
        return ratio.coerceIn(0f, 1f)
    }
}