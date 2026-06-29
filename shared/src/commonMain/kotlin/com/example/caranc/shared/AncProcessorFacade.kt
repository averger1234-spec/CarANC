package com.example.caranc.shared

import androidx.annotation.Keep
import com.example.caranc.shared.latency.LatencyBandLimits
import com.example.caranc.shared.model.CabinTransferModel
import com.example.caranc.shared.model.NoiseBandClassification

@Keep
interface AncProcessorFacade : AudioProcessor {
    fun updateSecondaryPath(model: FloatArray)
    fun setAcousticDelay(samples: Int)
    fun getAcousticDelaySamples(): Int
    fun setVehicleSpeed(speedKmh: Float, valid: Boolean)
    fun setProcessingMode(mode: AncProcessingMode)
    fun getProcessingMode(): AncProcessingMode
    fun updateTier(tier: UserTier)
    fun registerBlockEnergy(rms: Float): Boolean
    fun isWeightUpdateFrozen(): Boolean
    fun getCurrentFreezeBlocksRemaining(): Int = 0  // debug: remaining blocks of LMS freeze due to bump detection
    fun adjustMu(newMu: Float)
    fun finishLearning()
    fun applyCabinModel(model: CabinTransferModel)
    fun applyClassifierResult(result: NoiseBandClassification)
    fun setCallActive(active: Boolean)
    fun setSirenOverride(active: Boolean, gainScale: Float = 0.05f)
    fun setEngineRpm(rpm: Float, valid: Boolean)
    fun isSirenOverrideActive(): Boolean
    fun getMimoZoneCount(): Int
    fun setEstimatedLatencyMs(latencyMs: Float)
    fun getLatencyBandLimits(): LatencyBandLimits

    // CYCLE3_EXTRA: expose low-band LMS profiling counters (from inner BandFxLms) for AudioEngine extended timing/monitoring.
    // lmsUpdateCount: actual weight updates performed (not just calls).
    // lmsProcessCalls: total invocations into low LMS process path.
    // Used to feed perfMetrics via sessionContext + conditional logs.
    fun getLowLmsUpdateCount(): Long
    fun getLowLmsProcessCalls(): Long
    fun getLastLmsPfx(): Float   // last power norm used in mu calc (debug/profiling)

    // CYCLE3_EXTRA for low band extra (Fdaf/Multirate)
    fun getFdafLmsUpdateCount(): Long = 0L
    fun getMultirateDecimUpdateCount(): Long = 0L

    // For music mode low freq ANC option
    fun setMusicLowAncEnabled(enabled: Boolean)

    // Debug: LMS "PID-like" tuning for experimentation (mu = learning rate / adaptation speed)
    // High mu -> faster convergence (like higher P/I gain) but risk instability on high-latency or sudden changes; freeze protects.
    fun setDebugMuMultiplier(mult: Float)
    fun setDebugLeakage(alpha: Float)  // Leaky LMS for stability under aggressive mu + impulses (potholes etc.). Connected to AncTestPreferences + TestLogPanel + tuning presets for A/B 0.9998 vs 0.9995.
    fun setDebugVssEnergyScale(enabled: Boolean) // future hook for full VSS using real-time block energy
    // energyRatioThreshold: higher = less sensitive to "bumps" (e.g. 12-18); consec: require N consecutive high ratio before freeze; speedFactor scales duration at >50kmh.
    fun setDebugFreezeConfig(energyRatioThreshold: Float, consecutiveCount: Int, speedFactor: Float)

    // IMU prototype integration: pass linear accel magnitude (from VehicleSpeedProvider) as rumble vibration proxy.
    // Used inside low-band processing to boost roadMode gains / low mu when high vibration detected (complements speed-based road ref).
    // Currently only logging in snapshots; now directly fed into ANC for feedforward boost.
    fun setRumbleAccel(mag: Float) {}

    // Pass blockRms variance based VSS scale (computed in AudioEngine from perfMetrics) into processor.
    // Allows using full block energy variance (not just per-sample pfx) for dynamic mu in VSS logic.
    fun setBlockRmsVssScale(scale: Float) {}

    // Enable native low band switching point (for when NDK/native impl is active; currently falls back to no-op)
    fun setUseNativeLowBand(enabled: Boolean) {}

    // Iter2+: expose effective mid band mu (after road/musicLow boosts + bandMuScale) for logging mid contrib to 200-350Hz rumble.
    // 0 means no mid adaptation (pre-iter1 case for 136ms AA).
    fun getLastEffectiveMidMu(): Float = 0f
}