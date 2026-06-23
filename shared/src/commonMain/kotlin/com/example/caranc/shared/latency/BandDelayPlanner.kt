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
        val base = baseDelaySamples.coerceIn(0, 256)
        return PerBandDelay(
            lowSamples = base,
            midSamples = (base * 0.55f).toInt().coerceAtLeast(0),
            highSamples = (base * 0.28f).toInt().coerceAtLeast(0)
        )
    }
}