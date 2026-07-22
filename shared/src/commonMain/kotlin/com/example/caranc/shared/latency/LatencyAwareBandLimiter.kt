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
        //
        // Literature (secondary-path delay ↔ controllable BW, e.g. ~1/(6τ) pure-delay bound
        // for feedback-style systems; ISMA/FxLMS texts). OEM RNC needs τ≪10 ms for ~hundreds Hz.
        // Phone AA remote_submix is 100–250 ms → classical bound collapses to tens of Hz only.
        //
        // Hybrid used here:
        //   litBound  = 1000/(6·T_ms)          // harsh classical
        //   ffBound   = 1000/(2.2·T_ms)         // feedforward RNC slightly more permissive
        //   maxHz    = min(ff, max(lit*3, floor_by_class)) with hard caps
        // so we never claim mid/high cancel under AA while still allowing ~60–140 Hz rumble.
        val tauSec = l / 1000f
        val litBoundHz = (1f / (6f * tauSec)).coerceAtLeast(8f)
        val ffBoundHz = (1f / (2.2f * tauSec)).coerceAtLeast(20f)
        val hybrid = kotlin.math.min(ffBoundHz, kotlin.math.max(litBoundHz * 2.5f, litBoundHz + 40f))
        return when {
            l >= 220f -> hybrid.coerceIn(45f, 95f)    // critical AA: very-low rumble only
            l >= 180f -> hybrid.coerceIn(55f, 110f)   // high-lat AA
            l >= 150f -> hybrid.coerceIn(80f, 150f)
            l >= 100f -> hybrid.coerceIn(110f, 210f)
            else -> hybrid.coerceIn(140f, 380f)
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