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
        // Iter3-4 + S3 Ext #7: fully override-driven maxCancel: if low ov (debug <~130, e.g. 80 for #7) passed (vs real high ~136 AA), use formula that allows 300-380Hz+ for ov=80-120 (higher maxC target).
        // E.g. for ov=120: ~300Hz+; ov=100:~340; ov=80:~387 . Ambitious but realistic for AA 136ms base + override (S3). For no low-ov fall conservative ~180-220.
        // Combines with iter1 30000 base for good rumble range. Guarded in processor by roadMode/speed before full effect.
        val base = 30000f / l
        val isLowOvClaim = l < 130f
        val maxHz = if (isLowOvClaim) {
            (275f + (130f - l) * 2.25f).coerceIn(200f, 410f)
        } else {
            base.coerceIn(150f, 280f)
        }
        return maxHz
    }

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