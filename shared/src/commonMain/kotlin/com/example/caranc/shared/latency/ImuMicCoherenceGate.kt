package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multiple-coherence *proxy* for single-phone IMU vs cabin low-band mic.
 *
 * Literature: multi-accelerometer RNC selects channels by coherence with cabin error;
 * incoherent refs corrupt FxLMS. We only have one phone IMU magnitude envelope + mic low,
 * so we track a cheap normalized correlation of |lowMic| energy vs rumbleAccel over blocks.
 *
 * Output [quality] in 0..1 multiplies IMU boost / bank confidence — never used as speaker audio.
 */
@Keep
class ImuMicCoherenceGate {
    private var micEma = 1e-4f
    private var imuEma = 1e-4f
    private var crossEma = 0f
    private var qualityEma = 0.5f

    /** Call once per sample or block with |lowBand| and IMU mag. */
    @Keep
    fun update(absLowMic: Float, imuMag: Float): Float {
        val m = abs(absLowMic).coerceIn(0f, 1.5f)
        val u = imuMag.coerceAtLeast(0f).coerceAtMost(12f)
        micEma = 0.995f * micEma + 0.005f * (m * m)
        imuEma = 0.995f * imuEma + 0.005f * (u * u)
        crossEma = 0.995f * crossEma + 0.005f * (m * u)
        val denom = sqrt((micEma * imuEma).coerceAtLeast(1e-10f))
        // Normalized co-energy (not full Pearson); maps to soft quality
        val raw = (crossEma / denom).coerceIn(0f, 3f) / 3f
        // If IMU is quiet, don't kill audio-only cancel — hold mid quality
        val q = when {
            u < 0.15f -> 0.55f
            // Vibration without cabin low energy → weak structural coupling (bad ref)
            m < 0.05f && u > 0.8f -> (0.12f + 0.2f * raw).coerceIn(0.12f, 0.4f)
            else -> (0.3f + 0.7f * raw).coerceIn(0.2f, 1f)
        }
        qualityEma = 0.9f * qualityEma + 0.1f * q
        return qualityEma
    }

    @Keep
    fun quality(): Float = qualityEma

    @Keep
    fun reset() {
        micEma = 1e-4f
        imuEma = 1e-4f
        crossEma = 0f
        qualityEma = 0.5f
    }
}
