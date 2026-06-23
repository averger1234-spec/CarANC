package com.example.caranc.shared

import kotlin.math.pow

/**
 * Reference model for road/idle noise thresholds, blend weights, mu scaling, lowpass cutoffs, energy.
 * Used by ANCProcessor, MultiBandANCProcessor, WienerBank, NoiseBandClassifier, AudioEngine, speed logic.
 *
 * CYCLE3_EXTRA: Refactored object -> class (with ctor) + exposed via AncSessionContext.roadNoiseReferenceModel
 * for scoping / mock support.
 * - Use: RoadNoiseReferenceModel() for direct/test, or context.roadNoiseReferenceModel (injected mock ok).
 * - Consts + pure fns moved to instance; subclass or provide alternative impl to AncSessionContext ctor for fakes.
 * No factory method needed; context provides the (scoped) instance.
 */
class RoadNoiseReferenceModel {

    companion object {
        // Kept as consts for easy access (via instance or RoadNoiseReferenceModel.XXX still works for static-like).
        // (When accessed as roadRef.DRIVING_... inside injected instances.)
        const val IDLE_SPEED_THRESHOLD_KMH = 5f
        const val DRIVING_SPEED_THRESHOLD_KMH = 8f
    }

    fun classify(speedKmh: Float, valid: Boolean): NoiseSourceType {
        if (!valid || speedKmh < IDLE_SPEED_THRESHOLD_KMH) return NoiseSourceType.IDLE
        if (speedKmh >= DRIVING_SPEED_THRESHOLD_KMH) return NoiseSourceType.ROAD
        return NoiseSourceType.MIXED
    }

    /** 路噪參考與麥克風混合權重（0 = 純麥克風） */
    fun roadBlendWeight(speedKmh: Float, valid: Boolean): Float {
        if (!valid || speedKmh < DRIVING_SPEED_THRESHOLD_KMH) return 0f
        return ((speedKmh - DRIVING_SPEED_THRESHOLD_KMH) / 80f).coerceIn(0f, 0.45f)
    }

    fun muScale(speedKmh: Float, valid: Boolean): Float {
        if (!valid) return 1f
        return when {
            speedKmh < IDLE_SPEED_THRESHOLD_KMH -> 1f
            speedKmh < 40f -> 0.85f
            speedKmh < 80f -> 0.65f
            else -> 0.5f
        }
    }

    fun lowPassCutoffHz(speedKmh: Float): Float = when {
        speedKmh < 20f -> 180f
        speedKmh < 60f -> 250f
        speedKmh < 100f -> 320f
        else -> 400f
    }

    fun roadEnergyScale(speedKmh: Float): Float {
        val normalized = (speedKmh / 100f).coerceIn(0f, 1.2f)
        return normalized.pow(1.4f).coerceIn(0f, 1f)
    }
}