package com.example.caranc.shared.predictor

import androidx.annotation.Keep
import kotlin.math.max

/**
 * Dedicated RumblePreviewPredictor for high-latency AA (remote-submix ~247ms + buffering, user ~0.5s perceived delay).
 * Uses past IMU samples + speed to linearly extrapolate "future rumble" (vibration leads acoustic rumble).
 * Maintains a tuned delay buffer to output a preview rumble signal that can be used as feedforward anti.
 * 
 * Goal: Shift rumble ANC to mostly predictive/open-loop feedforward for low band (<250Hz) in AA.
 * This bypasses the delayed mic error path that causes poor phase alignment and "white noise" anti output.
 * 
 * Architecture change (per user request 2026-07-02):
 * - In AA submix + effectiveRumbleMode + low band: heavily de-emphasize adaptive LMS.
 * - Rely on this preview + PreLearnedAncBank + RoadNoiseWienerBank.
 * - Dynamically tune horizon using measured latency (probeCorrMs + estimatedLatencyMs).
 */
@Keep
class RumblePreviewPredictor(
    private val maxHistorySize: Int = 32,  // ~ few hundred ms at 64samp blocks
    private val defaultHorizonMs: Double = 250.0  // target 250ms future prediction; tuned by real latency
) {
    private val imuHistory = ArrayDeque<Pair<Double, Float>>()  // (timestampMs, accelMag)
    private val speedHistory = ArrayDeque<Pair<Double, Float>>() // (timestampMs, speedKmh)
    private val previewBuffer = FloatArray(1024) { 0f }  // circular delay buffer for preview rumble signal
    private var bufferIndex = 0
    private var currentHorizonMs = defaultHorizonMs

    private var lastUpdateTs = 0.0
    private var lastPreview = 0f

    /**
     * Update with latest IMU and speed. Call every block (~1.5ms at 64samp/44k, but we downsample history).
     */
    fun update(imuAccelMag: Float, speedKmh: Float, timestampMs: Double) {
        imuHistory.addLast(timestampMs to imuAccelMag)
        speedHistory.addLast(timestampMs to speedKmh)

        while (imuHistory.size > maxHistorySize) imuHistory.removeFirst()
        while (speedHistory.size > maxHistorySize) speedHistory.removeFirst()

        lastUpdateTs = timestampMs
    }

    /**
     * Set the prediction horizon based on measured end-to-end latency.
     * Call from AudioEngine when latency updates (probeCorr + estimatedLatencyMs).
     * horizonMs = latency - IMU_lead_time (IMU leads acoustic by 50-200ms typically in cars).
     */
    fun setPredictionHorizon(latencyMs: Float, probeCorrMs: Float) {
        // Conservative: use max of reported latency and probe, subtract estimated IMU lead (100ms typical)
        val measured = max(latencyMs, probeCorrMs)
        currentHorizonMs = (measured - 100.0).coerceIn(50.0, 400.0)
    }

    /**
     * Get the current rumble preview sample (extrapolated future rumble proxy).
     * This can be scaled and subtracted/added in the low-band ref or anti path.
     * Positive value means expected future rumble -> generate negative anti preview.
     */
    fun getCurrentPreviewRumble(): Float {
        if (imuHistory.size < 3) return lastPreview

        // Linear fit on last few IMU points for slope (rumble change rate)
        val recent = imuHistory.takeLast(5)
        if (recent.size < 2) return lastPreview

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
        val slope = if (denom > 1e-6) (n * sumTA - sumT * sumA) / denom else 0.0

        // Average recent accel as base
        val baseAccel = sumA / n

        // Extrapolate to future (all Double until final Float cast)
        val futureAccel = (baseAccel + slope * currentHorizonMs).coerceIn(0.0, 10.0).toFloat()

        // Simple speed scaling (higher speed -> stronger rumble expectation)
        val avgSpeed = speedHistory.takeLast(3).map { it.second }.average().toFloat().coerceAtLeast(10f)
        val speedScale = (avgSpeed / 60f).coerceIn(0.5f, 2f)

        lastPreview = futureAccel * speedScale * 0.8f  // scale factor tuned for preview strength

        // Write to delay buffer (simulate applying the preview with the horizon delay)
        // In practice, the caller will use this preview value directly as feedforward contribution
        // with its own delay compensation in the band processor.
        previewBuffer[bufferIndex] = lastPreview
        bufferIndex = (bufferIndex + 1) % previewBuffer.size

        return lastPreview
    }

    /**
     * Get a delayed version of the preview (for alignment with the actual acoustic arrival).
     * Useful if we want to subtract the preview at the "right time" relative to delayed mic.
     */
    fun getDelayedPreviewRumble(delaySamples: Int): Float {
        if (delaySamples <= 0) return getCurrentPreviewRumble()
        val idx = (bufferIndex - delaySamples + previewBuffer.size) % previewBuffer.size
        return previewBuffer[idx]
    }

    fun reset() {
        imuHistory.clear()
        speedHistory.clear()
        previewBuffer.fill(0f)
        bufferIndex = 0
        lastPreview = 0f
        currentHorizonMs = defaultHorizonMs
    }
}
