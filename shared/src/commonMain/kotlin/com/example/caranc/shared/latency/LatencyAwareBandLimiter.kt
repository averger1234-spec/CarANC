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

    fun maxCancelFrequencyHz(latencyMs: Float): Float {
        val l = latencyMs.coerceIn(20f, 400f)
        // P0: always driven by MEASURED latency (no debug override fake-low).
        // Classical 1/(4T) is harsh for pure feedback; feedforward RNC can go higher, but AA ~250ms
        // must stay conservative so adaptive doesn't chase un-cancelable mid/high.
        // Formula ~ 1000/(2.5*T) with hard caps by latency class.
        val base = 1000f / (2.5f * l)
        return when {
            l >= 200f -> base.coerceIn(60f, 120f)   // AA high-lat: low-rumble focus only
            l >= 150f -> base.coerceIn(90f, 160f)
            l >= 100f -> base.coerceIn(120f, 220f)
            else -> base.coerceIn(150f, 350f)
        }
    }

    fun limits(latencyMs: Float): LatencyBandLimits {
        val maxHz = maxCancelFrequencyHz(latencyMs)
        val lowGain = 1f
        // P0: at high measured latency, mid/high adaptive disabled (FF handles low rumble).
        val midGain = when {
            latencyMs >= 180f -> 0f
            maxHz < 120f -> 0f
            maxHz < 180f -> 0.25f
            maxHz < 250f -> 0.55f
            else -> 0.85f
        }
        val highGain = when {
            latencyMs >= 150f -> 0f
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

    fun bandMuScale(centerHz: Float, latencyMs: Float, roadRumble: Boolean = false): Float {
        val maxHz = maxCancelFrequencyHz(latencyMs)
        if (centerHz <= maxHz * 0.68f) return 1f  // S3: more permissive start 0.65->0.68 for rumble centers ~250-335 (ov80 focus 300-350)
        // Iter1 + Iter3-4 + S3 Ext: roadRumble aware (from processor roadMode+musicLow or dominant). extra permissive for 300-350 centers.
        // Allows higher muScale for rumble freqs (e.g. 300-350Hz @ mid center 335) even under AA 136ms + ov=80 (for #7_ext).
        // Cycle2 refined: only super permissive if maxHz high enough + guarded upstream by roadMode+speed+energy (avoid artifact on low ov pure music).
        val cutoffMul = if (roadRumble && maxHz > 170f) 5.2f else if (roadRumble) 4.1f else 3.5f
        val ratioDenomMul = if (roadRumble && maxHz > 170f) 2.9f else if (roadRumble) 2.4f else 2.0f
        if (centerHz >= maxHz * cutoffMul) return 0f
        val ratio = (maxHz * cutoffMul - centerHz) / (maxHz * ratioDenomMul)
        return ratio.coerceIn(0f, 1f)
    }
}