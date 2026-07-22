package com.example.caranc.shared.latency

import androidx.annotation.Keep
import kotlin.math.abs

/**
 * Predictive / delay-compensated reference for high secondary-path delay (AA ~100–250 ms).
 *
 * Literature: secondary-path delay is the main bandwidth killer; Predictive FxLMS and
 * delay-compensated FF use *future-aligned* reference (or predicted ref) so the controller
 * y is timed for plant arrival — not for same-sample residual.
 *
 * Practical phone path (no under-mirror accel array):
 * - Keep a ring of low-band *audio* reference (mic/road blend, bipolar).
 * - At plant delay D samples, read x(n−D) for adaptation-aligned paths when needed.
 * - Linear 1-step extrapolation from recent slope approximates short look-ahead when
 *   IMU lead is shorter than full AA delay (classic predictive FF idea).
 *
 * IMPORTANT: never inject non-negative IMU *magnitude* as audio; this class only stores
 * bipolar audio samples.
 */
@Keep
class PredictiveReferenceAligner(
    private val capacity: Int = 16384
) {
    private val ring = FloatArray(capacity.coerceAtLeast(1024))
    private val mask = ring.size - 1 // only valid if power of 2; we use modulo instead
    private var write = 0
    private var count = 0

    private var prev = 0f
    private var slopeEma = 0f

    /** Push one bipolar low-band reference sample. */
    @Keep
    fun push(sample: Float) {
        val x = sample.coerceIn(-1.5f, 1.5f)
        ring[write % ring.size] = x
        // Local slope for short predictive extrapolate (per-sample, heavily smoothed)
        val rawSlope = x - prev
        slopeEma = 0.92f * slopeEma + 0.08f * rawSlope
        prev = x
        write++
        if (count < ring.size) count++
    }

    /** Reference delayed by [delaySamples] (pure plant delay read). */
    @Keep
    fun delayed(delaySamples: Int): Float {
        if (count < 2) return prev
        val d = delaySamples.coerceIn(0, count - 1)
        val idx = (write - 1 - d).mod(ring.size)
        return ring[idx]
    }

    /**
     * Predictive reference: extrapolate [lookAheadSamples] beyond current using slope EMA.
     * Caps look-ahead so we don't invent high-frequency content under huge AA delays.
     * For lookAhead=0 returns latest sample.
     */
    @Keep
    fun predict(lookAheadSamples: Int): Float {
        if (count < 2) return prev
        val la = lookAheadSamples.coerceIn(0, 2048)
        if (la == 0) return delayed(0)
        // Soft extrapolate: only a fraction of slope×la (stable under non-stationary road)
        val pred = prev + slopeEma * la * 0.35f
        return pred.coerceIn(-1.2f, 1.2f)
    }

    /**
     * Blend delayed + mild predict for high-lat FF:
     * - Mostly plant-delayed ref (causal FF with known D)
     * - Small predictive tip from slope (IMU-lead style short horizon)
     */
    @Keep
    fun plantAlignedReference(plantDelaySamples: Int, predictFraction: Float = 0.2f): Float {
        val d = delayed(plantDelaySamples.coerceAtLeast(0))
        val p = predict((plantDelaySamples * 0.08f).toInt().coerceIn(0, 512))
        val f = predictFraction.coerceIn(0f, 0.45f)
        return (d * (1f - f) + p * f).coerceIn(-1.2f, 1.2f)
    }

    @Keep
    fun latest(): Float = if (count == 0) 0f else delayed(0)

    @Keep
    fun reset() {
        ring.fill(0f)
        write = 0
        count = 0
        prev = 0f
        slopeEma = 0f
    }
}
