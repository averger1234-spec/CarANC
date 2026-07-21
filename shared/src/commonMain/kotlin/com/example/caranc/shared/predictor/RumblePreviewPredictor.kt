package com.example.caranc.shared.predictor

import androidx.annotation.Keep
import kotlin.math.abs
import kotlin.math.max

/**
 * Dedicated RumblePreviewPredictor for high-latency AA (remote-submix ~247ms + buffering).
 * Uses past IMU samples + speed to extrapolate "future rumble" (vibration leads acoustic rumble).
 *
 * P1 upgrades (after P0 FF_PREVIEW_ONLY strategy switch):
 * - Longer wall-time history (~0.6–0.8s) so linear fit spans useful horizon, not just a few blocks.
 * - EMA-smoothed slope + preview (stable anti under AA, less telegraph).
 * - Diagnostics for log: horizonMs, lastPreview, historyAgeMs, sampleCount.
 * - Delay-buffer read aligned to plant samples for residual path.
 *
 * Goal: under FF_PREVIEW_ONLY, anti is mostly predictive/open-loop for low band; adaptive only residual.
 */
@Keep
class RumblePreviewPredictor(
    private val maxHistorySize: Int = 128,
    private val defaultHorizonMs: Double = 250.0,
    private val emaAlpha: Float = 0.28f
) {
    private val imuHistory = ArrayDeque<Pair<Double, Float>>()  // (timestampMs, accelMag)
    private val speedHistory = ArrayDeque<Pair<Double, Float>>()
    private val previewBuffer = FloatArray(2048) { 0f }
    private var bufferIndex = 0
    private var currentHorizonMs = defaultHorizonMs

    private var lastUpdateTs = 0.0
    private var lastPreview = 0f
    private var previewEma = 0f
    private var slopeEma = 0.0
    private var baseEma = 0f

    /**
     * Update with latest IMU and speed. Call every audio block (~1.5ms at 64@44.1k).
     * History is pruned by count AND by wall-time age (>900ms dropped) so fit spans real horizon.
     */
    fun update(imuAccelMag: Float, speedKmh: Float, timestampMs: Double) {
        val mag = imuAccelMag.coerceAtLeast(0f)
        imuHistory.addLast(timestampMs to mag)
        speedHistory.addLast(timestampMs to speedKmh.coerceAtLeast(0f))

        while (imuHistory.size > maxHistorySize) imuHistory.removeFirst()
        while (speedHistory.size > maxHistorySize) speedHistory.removeFirst()

        // Drop samples older than ~0.9s so linear fit is local (road surface changes)
        val cutoff = timestampMs - 900.0
        while (imuHistory.isNotEmpty() && imuHistory.first().first < cutoff) imuHistory.removeFirst()
        while (speedHistory.isNotEmpty() && speedHistory.first().first < cutoff) speedHistory.removeFirst()

        lastUpdateTs = timestampMs
    }

    /**
     * Horizon ≈ measured path latency − typical IMU lead (structure vibration arrives before cabin air sound).
     */
    fun setPredictionHorizon(latencyMs: Float, probeCorrMs: Float) {
        val measured = max(latencyMs.toDouble(), probeCorrMs.toDouble())
        // IMU lead ~80–120ms in cars; use 100ms default. Keep horizon long enough for AA (~150–280ms).
        currentHorizonMs = (measured - 100.0).coerceIn(50.0, 400.0)
    }

    /**
     * Extrapolated future rumble proxy. Positive → expected rumble → generate negative anti preview.
     */
    fun getCurrentPreviewRumble(): Float {
        if (imuHistory.size < 4) return lastPreview

        // Prefer samples spanning at least ~40ms for a stable slope; take up to last 24 points.
        val recent = imuHistory.takeLast(24)
        if (recent.size < 3) return lastPreview

        val t0 = recent.first().first
        var sumT = 0.0
        var sumA = 0.0
        var sumTA = 0.0
        var sumT2 = 0.0
        recent.forEach { (t, a) ->
            val dt = t - t0
            sumT += dt
            sumA += a
            sumTA += dt * a
            sumT2 += dt * dt
        }
        val n = recent.size.toDouble()
        val denom = n * sumT2 - sumT * sumT
        val rawSlope = if (denom > 1e-3) (n * sumTA - sumT * sumA) / denom else 0.0
        // Slope is d(accel)/d(ms). Cap wild derivatives (potholes).
        val clampedSlope = rawSlope.coerceIn(-0.05, 0.05)
        slopeEma = 0.65 * slopeEma + 0.35 * clampedSlope

        val rawBase = (sumA / n).toFloat()
        baseEma = if (baseEma <= 0f) rawBase else (1f - emaAlpha) * baseEma + emaAlpha * rawBase

        // Extrapolate to future horizon (ms units match slope)
        val futureAccel = (baseEma + (slopeEma * currentHorizonMs).toFloat()).coerceIn(0f, 12f)

        val avgSpeed = speedHistory.takeLast(8).map { it.second }.average().toFloat().coerceAtLeast(8f)
        val speedScale = (avgSpeed / 55f).coerceIn(0.45f, 2.2f)

        val rawPreview = futureAccel * speedScale * 0.85f
        previewEma = if (previewEma == 0f) rawPreview else (1f - emaAlpha) * previewEma + emaAlpha * rawPreview
        lastPreview = previewEma.coerceIn(0f, 14f)

        previewBuffer[bufferIndex] = lastPreview
        bufferIndex = (bufferIndex + 1) % previewBuffer.size

        return lastPreview
    }

    /**
     * Delayed preview for residual / plant-aligned paths.
     * [delaySamples] at process sample rate; mapped into ring (≈ sample-per-call rate, not full audio rate).
     * Callers typically pass plantElectricalDelaySamples / framesPerBlock as coarse lag index.
     */
    fun getDelayedPreviewRumble(delaySamples: Int): Float {
        if (delaySamples <= 0) return getCurrentPreviewRumble()
        // Ring advances once per getCurrentPreviewRumble call (~1 sample/block). Cap lag index.
        val lag = delaySamples.coerceIn(1, previewBuffer.size - 1)
        val idx = (bufferIndex - lag + previewBuffer.size) % previewBuffer.size
        val delayed = previewBuffer[idx]
        return if (abs(delayed) < 1e-6f) getCurrentPreviewRumble() else delayed
    }

    fun getHorizonMs(): Float = currentHorizonMs.toFloat()
    fun getLastPreview(): Float = lastPreview
    fun getHistorySampleCount(): Int = imuHistory.size
    fun getHistoryAgeMs(): Float {
        if (imuHistory.size < 2) return 0f
        return (imuHistory.last().first - imuHistory.first().first).toFloat().coerceAtLeast(0f)
    }

    fun reset() {
        imuHistory.clear()
        speedHistory.clear()
        previewBuffer.fill(0f)
        bufferIndex = 0
        lastPreview = 0f
        previewEma = 0f
        slopeEma = 0.0
        baseEma = 0f
        currentHorizonMs = defaultHorizonMs
    }
}
