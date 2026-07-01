package com.example.caranc.shared.service

import com.example.caranc.shared.*
import com.example.caranc.shared.latency.LatencyBandLimits
import com.example.caranc.shared.model.CabinTransferModel
import com.example.caranc.shared.model.NoiseBandClassification

/**
 * iOS stub implementation of AncProcessorFacade (P2 skeleton).
 *
 * Simple pass-through: process(...) returns silence (zero anti-noise) or could copy input for bypass.
 * All other methods are no-ops / safe defaults.
 *
 * This replaces direct use of MultiBandANCProcessor (common impl) on iOS target.
 * MultiBandANCProcessor lives in commonMain and may be usable, but per-task we provide dedicated iOS facade stub
 * (avoids any potential native target issues with complex DSP or @Keep until iOS port of DSP core).
 *
 * Future: replace this with real iOS port of multi-band ANC (Fdaf, multirate etc) or bridge to native DSP.
 */
class IosAncProcessorFacade(
    private val sampleRate: Int,
    private val bufferSize: Int,
    initialTier: UserTier = UserTier.STANDARD,
    // CYCLE3_P2: accepted for factory parity / future scoping of managers (currently unused in stub).
    sessionContext: AncSessionContext = GlobalAncSessionContext
) : AncProcessorFacade {

    private var processingMode = AncProcessingMode.NORMAL
    private var estimatedLatencyMs = 0f

    override fun process(input: ShortArray): ShortArray {
        // pass-through stub: produce zero anti-noise (no cancellation effect on iOS yet)
        // Alternative for "passthrough": return input.copyOf() to let mic signal through (bypass ANC)
        return ShortArray(input.size) { 0 }
    }

    override fun release() {
        // no-op
    }

    override fun updateSecondaryPath(model: FloatArray) {
        // stub: ignore cabin model update
    }

    override fun setAcousticDelay(samples: Int) {
        // stub
    }

    override fun getAcousticDelaySamples(): Int = 0

    override fun setVehicleSpeed(speedKmh: Float, valid: Boolean) {
        // stub
    }

    override fun setProcessingMode(mode: AncProcessingMode) {
        processingMode = mode
    }

    override fun getProcessingMode(): AncProcessingMode = processingMode

    override fun updateTier(tier: UserTier) {
        // stub: tier ignored
    }

    override fun registerBlockEnergy(rms: Float): Boolean {
        // stub: never detect bump
        return false
    }

    override fun isWeightUpdateFrozen(): Boolean = false

    override fun adjustMu(newMu: Float) {
        // stub
    }

    override fun finishLearning() {
        // stub
    }

    override fun applyCabinModel(model: CabinTransferModel) {
        // stub: no mimo / secondary path apply
    }

    override fun applyClassifierResult(result: NoiseBandClassification) {
        // stub
    }

    override fun setCallActive(active: Boolean) {
        // stub
    }

    override fun setSirenOverride(active: Boolean, gainScale: Float) {
        // stub
    }

    override fun setSonificationOverride(active: Boolean, gainScale: Float) {
        // stub
    }
    override fun isSonificationOverrideActive(): Boolean = false
    override fun getSonificationGainScale(): Float = 1f

    override fun setEngineRpm(rpm: Float, valid: Boolean) {
        // stub
    }

    override fun isSirenOverrideActive(): Boolean = false

    override fun getMimoZoneCount(): Int = 1

    override fun setEstimatedLatencyMs(latencyMs: Float) {
        estimatedLatencyMs = latencyMs
    }

    override fun getLatencyBandLimits(): LatencyBandLimits {
        // return safe default (low freq cancel only); see LatencyAwareBandLimiter for real calc
        return LatencyBandLimits(
            estimatedLatencyMs = estimatedLatencyMs,
            maxCancelFrequencyHz = 100f,
            lowGain = 1f,
            midGain = 0f,
            highGain = 0f,
            lowEnabled = true,
            midEnabled = false,
            highEnabled = false
        )
    }

    // CYCLE3_EXTRA: stub counters for facade (iOS low path not real yet)
    override fun getLowLmsUpdateCount(): Long = 0L
    override fun getLowLmsProcessCalls(): Long = 0L
    override fun getLastLmsPfx(): Float = 0f

    override fun setMusicLowAncEnabled(enabled: Boolean) {
        // stub
    }

    override fun setDebugMuMultiplier(mult: Float) {
        // stub
    }

    override fun setDebugLeakage(alpha: Float) {
        // stub (iOS native path not implemented; leakage handled in common BandFxLms if ported)
    }

    override fun setDebugVssEnergyScale(enabled: Boolean) {
        // stub
    }

    override fun setDebugFreezeConfig(energyRatioThreshold: Float, consecutiveCount: Int, speedFactor: Float) {
        // stub
    }

    override fun setBlockRmsVssScale(scale: Float) {
        // iOS stub
    }

    override fun setUseNativeLowBand(enabled: Boolean) {
        // iOS stub (no native low band)
    }

    override fun setRumbleAccel(mag: Float) {
        // iOS stub for IMU
    }

    override fun setPersonalRumbleBias(bias: Float) {
        // iOS stub (personal acoustic identity follows phone; future full support)
    }

    override fun setMusicSuppressionQuality(quality: Float) {
        // iOS stub (music suppression quality for conservative mode; future)
    }

    override fun setMusicDominantRumbleMode(enabled: Boolean) {
        // iOS stub (MUSIC_DOMINANT_RUMBLE flag; future)
    }

    override fun getVirtualSuppressionQuality(): Float = 0f

    override fun getRumbleEnergyProxy(): Float = 0f
}
