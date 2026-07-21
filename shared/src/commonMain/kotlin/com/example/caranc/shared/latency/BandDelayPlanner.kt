package com.example.caranc.shared.latency

import androidx.annotation.Keep

@Keep
data class PerBandDelay(
    val lowSamples: Int,
    val midSamples: Int,
    val highSamples: Int
)

@Keep
object BandDelayPlanner {

    fun plan(baseDelaySamples: Int): PerBandDelay {
        // P0: allow larger bases for mid/high planning; low band uses full plant delay separately.
        val base = baseDelaySamples.coerceIn(0, 4096)
        return PerBandDelay(
            lowSamples = base,
            midSamples = (base * 0.55f).toInt().coerceIn(0, 512),
            highSamples = (base * 0.28f).toInt().coerceIn(0, 256)
        )
    }
}