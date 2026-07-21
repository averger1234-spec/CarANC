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

    // For short transient events like system notifications, ringtones, sonification beeps (USAGE_ASSISTANCE_SONIFICATION etc).
    // When active, heavily duck ANC anti-noise output gain (to prevent echo on the important short sound) and freeze adaptation (prevent LMS learning artifact on transient).
    // This is the highest priority fix for choppy AA audio + notification echo under high-latency AA remote_submix.
    // Separate from siren (narrowband sweeps) but shares similar gain+freeze effect.
    fun setSonificationOverride(active: Boolean, gainScale: Float = 0.08f)
    fun isSonificationOverrideActive(): Boolean
    fun getSonificationGainScale(): Float = 1f
    fun getMimoZoneCount(): Int
    fun setEstimatedLatencyMs(latencyMs: Float)
    fun getLatencyBandLimits(): LatencyBandLimits

    /**
     * P0 (AA high-latency): pass measured breakdown so plant delay + strategy use REAL path latency.
     * Debug latencyOverride must NOT replace these for maxCancel / FxLMS plant.
     */
    fun setMeasuredLatencyBreakdown(
        recordMs: Float,
        trackMs: Float,
        blockMs: Float,
        acousticMs: Float,
        frameworkMs: Float
    ) {}

    /** e.g. NORMAL | FF_PREVIEW_ONLY — for log diagnosis of AA high-lat strategy. */
    fun getLatencyStrategy(): String = "NORMAL"
    fun getPlantElectricalDelaySamples(): Int = 0
    fun getMeasuredLatencyMs(): Float = 0f

    // Dynamic real end-to-end latency from probe (used to tune RumblePreviewPredictor horizon and feedforward weights).
    fun setProbeCorrMs(probeCorrMs: Float) {}

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
    fun setDebugLeakage(alpha: Float)  // Legacy for A/B (still callable); primary now tier auto (updateTier sets per LIGHT/STANDARD/PRO from sims; see effectiveLeakageFromTier in snapshots). Connected to prefs/TestLogPanel for transition.
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

    // Personal acoustic identity follows the *person* (phone) not car. Bias on IMU rumble feedforward / low mu.
    // >1.0 for users more sensitive to tire/wind rumble; enables "your quiet cabin" across any vehicle via AA.
    // Applied in processor rumbleVibBoost path (on top of tier auto + sim-driven).
    fun setPersonalRumbleBias(bias: Float) {}

    // P1: from ReferencePipeline after subtractor. Low quality means music not well suppressed -> conservative mode to protect music.
    // Processor can use it to temporarily scale down low/mid gains or mu in music modes.
    fun setMusicSuppressionQuality(quality: Float) {}

    // direction C: flag to enter MUSIC_DOMINANT_RUMBLE mode (conservative music protect + aggressive rumble via IMU/selective)
    fun setMusicDominantRumbleMode(enabled: Boolean) {}

    // For log verification of 06-30 feedback: confirm force entry on MUSIC_BROAD, and IMU boost actually raised (rumbleVibBoost >~2x, effectiveLowMu higher).
    fun isMusicDominantRumbleMode(): Boolean = false
    fun getLastRumbleVibBoost(): Float = 1f
    fun getLastEffectiveLowMu(): Float = 0f

    // 07-02: expose whether effectiveRumble (music dom OR pure driving rumble IMU) is active; used for ref boost, low mu, sonif relax etc.
    fun isEffectiveRumbleMode(): Boolean = false

    // virtualSuppressionQuality: 混合 media quality + IMU rumble energy proxy。用來在 quality 卡 0 時仍能依 rumble 能量給較 aggressive 處理。
    fun getVirtualSuppressionQuality(): Float = 0f

    // Raw rumble energy proxy (accel/5) for log diagnosis of IMU vs music conflict and virtual quality calc.
    fun getRumbleEnergyProxy(): Float = 0f

    // Iter2+: expose effective mid band mu (after road/musicLow boosts + bandMuScale) for logging mid contrib to 200-350Hz rumble.
    // 0 means no mid adaptation (pre-iter1 case for 136ms AA).
    fun getLastEffectiveMidMu(): Float = 0f

    // P1: RumblePreviewPredictor diagnostics for AA high-lat FF path validation in logs.
    fun getPreviewRumble(): Float = 0f
    fun getPredictionHorizonMs(): Float = 0f
    fun getPreviewHistoryAgeMs(): Float = 0f
    fun getPreviewHistoryCount(): Int = 0
    fun getPreLearnedBinCount(): Int = 0
}