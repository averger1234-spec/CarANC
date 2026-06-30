package com.example.caranc.shared

import androidx.annotation.Keep
import com.example.caranc.shared.model.BandGains
import com.example.caranc.shared.model.CabinMimoProfile
import com.example.caranc.shared.model.CabinResonanceDetector
import com.example.caranc.shared.model.CabinTransferModel
import com.example.caranc.shared.model.CabinZonePath
import com.example.caranc.shared.model.NoiseBandClassification
import com.example.caranc.shared.latency.BandDelayPlanner
import com.example.caranc.shared.latency.EngineCombCanceller
import com.example.caranc.shared.latency.FdafLowBandProcessor
import com.example.caranc.shared.latency.LatencyAwareBandLimiter
import com.example.caranc.shared.latency.LatencyBandLimits
import com.example.caranc.shared.latency.MultirateLowBandFxLms
import com.example.caranc.shared.latency.NativeLowBandProcessor
import com.example.caranc.shared.latency.PreLearnedAncBank
import com.example.caranc.shared.latency.RoadNoiseWienerBank
import com.example.caranc.shared.latency.VirtualSensingModel
import com.example.caranc.shared.signal.VoiceBandProtector
import kotlin.math.PI

// CYCLE3_P2: use via sessionContext (injected) instead of direct object singletons for NoiseBand/Spectrum/Road etc.
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext

/**
 * 2nd-order Linkwitz-Riley (LR2) band splitter for 3-way crossover.
 * Uses cascaded 1-pole stages per crossover point (250 Hz low-mid S3 focus for 300-350 rumble, 800 Hz mid-high)
 * to realize proper 12 dB/oct slopes + flat sum (low + mid + high == input).
 * Replaces the prior crude 1st-order IIR (differences of LPFs, ~6 dB/oct, lost DC/very-low energy).
 * State is per-sample; call split(x) for normalized float input.
 * S3 change guarded by upstream classifier/processor logic using roadMode+speed+energy; mid band gains only active when rumble dominant.
 */
internal data class BandSamples(
    val low: Float,
    val mid: Float,
    val high: Float
)

internal class BandSplitter(sampleRate: Int) {
    private val cLowMid = coeffFor(250f, sampleRate)  // S3 Ext: 300->250 to shift more 250-350 rumble energy into dedicated mid band (with mid center@335, bandEnergies@250/850); focus 300-350Hz
    private val cMidHigh = coeffFor(800f, sampleRate)

    // Low: 2 cascaded LPF stages @ low-mid fc => LR2 lowpass
    private var lowA = 0f
    private var lowB = 0f

    // Mid: 2 cascaded LPF stages on (input - low) @ mid-high fc
    private var midA = 0f
    private var midB = 0f

    fun split(x: Float): BandSamples {
        // 2nd-order lowpass
        lowA += cLowMid * (x - lowA)
        lowB += cLowMid * (lowA - lowB)
        val low = lowB.coerceIn(-1f, 1f)

        // complementary for LR2: highpassed side at fc1
        val hp1 = x - lowB

        // 2nd-order lowpass for mid extraction
        midA += cMidHigh * (hp1 - midA)
        midB += cMidHigh * (midA - midB)
        val mid = midB.coerceIn(-1f, 1f)

        val high = (hp1 - midB).coerceIn(-1f, 1f)
        return BandSamples(low, mid, high)
    }

    private fun coeffFor(freqHz: Float, sampleRate: Int): Float {
        return (2.0 * PI * freqHz / sampleRate).toFloat().coerceIn(0.003f, 0.2f)
    }
}

@Keep
class MultiBandANCProcessor(
    private val sampleRate: Int,
    private val bufferSize: Int,
    initialTier: UserTier = UserTier.STANDARD,
    // CYCLE3_P2 + EXTRA: sessionContext provides the (scoped/mockable) managers (state/road/spectrum/noiseBandClassifier).
    // Defaults to Global for direct test ctors / compat; factory passes the real session one.
    private val sessionContext: AncSessionContext = GlobalAncSessionContext
) : AncProcessorFacade {

    private var filterLength = tierLength(initialTier)
    private var baseMu = tierMu(initialTier)

    private val sHatLength = 64
    private val sHat = FloatArray(sHatLength) { 0f }.apply { this[0] = 1.0f }

    private var acousticDelaySamples = 0
    private var processingMode = AncProcessingMode.NORMAL
    private var vehicleSpeedKmh = 0f
    private var vehicleSpeedValid = false
    private var rumbleAccelMag = 0f  // IMU linear accel mag as rumble vibration proxy (integrated into low band for boost)
    private var blockRmsVssScale = 1f  // blockRms variance based scale from AudioEngine for enhanced VSS in BandFxLms
    private var useNativeLowBand = false  // switch to native low band when available (stub now, real impl when NDK active)
    private var rumbleBoostFactor = 0.05f  // per-tier strength for IMU rumble boost (set by tier in updateTier)
    private var personalRumbleBias = 1.0f  // personal acoustic identity bias (follows user/phone, not car). Applied to rumbleVibBoost.
    private var musicSuppressionQuality = 1f  // P1: from pipeline. Low = music dominant & poorly subtracted -> conservative to avoid ruining music.
    private var musicDominantRumbleMode = false  // direction C flag for MUSIC_DOMINANT_RUMBLE mode
    private var lastRumbleVibBoost = 1f
    private var lastEffectiveLowMu = 0f
    private var bandGains = BandGains(low = 1f, mid = 0.25f, high = 0.05f)
    private var lastDominant = com.example.caranc.shared.model.DominantNoiseBand.MIXED
    private var resonancePeaks = emptyList<com.example.caranc.shared.model.ResonancePeak>()
    private var mimoProfile: CabinMimoProfile? = null
    private var mimoZoneCount = 1

    private val voiceProtector = VoiceBandProtector(sampleRate)
    private var callActive = false
    private var sirenOverride = false
    private var sirenGainScale = 1f
    private val engineComb = EngineCombCanceller(sampleRate)
    // CYCLE3: use road ref from sessionContext so scoped RoadNoiseReferenceModel (e.g. test thresholds) is honored.
    private val roadWiener = RoadNoiseWienerBank(sampleRate, sessionContext.roadNoiseReferenceModel)
    private val virtualSensing = VirtualSensingModel(sHat)
    private val multirateLow = MultirateLowBandFxLms(decimation = 4)
    private val fdafLow = FdafLowBandProcessor(blockSize = 64, filterLength = 64)
    // CYCLE3_EXTRA: native low-freq path prototype (expect/actual + JNI/C++ LMS).
    // Not yet switched into hot path (would replace multirateLow + lowBand.processSample for rumble).
    // Use: if (NativeLowBandProcessor.isNativeAvailable()) nativeLow.processLowBand(...) else ...
    // See NativeLowBandProcessor.kt (comments + cpp skeleton for overlap-save NLMS or time LMS).
    // Processor counters now better exposed for timing comparison (native vs kotlin low path).
    private val nativeLow = NativeLowBandProcessor()
    private val preLearnedBank = PreLearnedAncBank(filterLength = tierLength(initialTier))
    private var latencyBandLimits = LatencyAwareBandLimiter.limits(150f)
    private var estimatedLatencyMs = 150f
    private var learningCaptured = false

    private var musicLowAncEnabled = true

    // Debug tuning (for "PID-like" LMS adaptation learning via TestLogPanel)
    private var debugMuMultiplier = 1f
    private var debugFreezeThreshold = 15f
    private var debugFreezeConsec = 3
    private var debugSpeedFreezeFactor = 0.6f

    // hot-path opt (P1 #6+7): push buffer reuse to processor layer (fdafLow + multirateLow buffers conceptually
    // owned/reused at this MultiBandANCProcessor layer for low-band; see fdaf internal reuse too)
    // NOTE for native low-freq: fdafLow, multirateLow, roadWiener, lowBand LMS are critical hot for rumble<250Hz;
    // CYCLE3_EXTRA: native impl (NativeLowBandProcessor JNI/C++ LMS prototype) added. See latency/NativeLowBandProcessor.kt + cpp/.
    // Build notes + CMake in shared/build.gradle.kts. Not hot-switched yet.

    private val lowBand = BandFxLms(
        label = "low",
        centerHz = 190f,
        baseMuScale = 1f
    )
    private val midBand = BandFxLms(
        label = "mid",
        centerHz = 335f,  // Subagent3 Extended #7: 320->335 for tighter 300-350Hz Skoda rumble focus (with bandEnergies/classifier tweak)
        baseMuScale = 0.32f
    )
    private val highBand = BandFxLms(
        label = "high",
        centerHz = 1200f,
        baseMuScale = 0.05f
    )

    private val splitter = BandSplitter(sampleRate)
    private var blockEnergyEma = 0f
    private var freezeWeightUpdates = 0
    private var bumpDetectedFlag = false
    private var consecutiveHighEnergyRatio = 0  // for consecutive high-ratio requirement (less sensitive to single spikes)
    private var consecutiveBumpCount = 0  // P2 enhancement: track consecutive bumps to extend freeze if clustered impulses (from 20260630 log 41k bumps)

    init {
        updateTier(initialTier)
    }

    private val lpCoeff = (2.0 * PI * 250.0 / sampleRate).toFloat().coerceIn(0.005f, 0.12f)
    private var lpInputState = 0f
    private var lpOutputState = 0f
    private var roadLpCoeff = lpCoeff
    private var roadLpInputState = 0f
    private var roadLpOutputState = 0f

    @Keep
    override fun applyCabinModel(model: CabinTransferModel) {
        mimoProfile = model.mimoProfile
        mimoZoneCount = model.zoneCount
        updateSecondaryPath(model.effectiveSecondaryPath())
        setAcousticDelay(model.acousticDelaySamples)
        resonancePeaks = model.resonancePeaks
        virtualSensing.bindPath(model.effectiveSecondaryPath())
        bindMimoZones(model.mimoProfile)
        fdafLow.reset()
    }

    @Keep
    override fun setEstimatedLatencyMs(latencyMs: Float) {
        estimatedLatencyMs = latencyMs.coerceIn(15f, 400f)
        latencyBandLimits = LatencyAwareBandLimiter.limits(estimatedLatencyMs)
    }

    override fun getLatencyBandLimits(): LatencyBandLimits = latencyBandLimits

    override fun setCallActive(active: Boolean) {
        callActive = active
    }

    override fun setSirenOverride(active: Boolean, gainScale: Float) {
        sirenOverride = active
        sirenGainScale = gainScale.coerceIn(0.02f, 1f)
    }

    override fun setEngineRpm(rpm: Float, valid: Boolean) {
        engineComb.setRpm(rpm, valid)
    }

    override fun setMusicLowAncEnabled(enabled: Boolean) {
        musicLowAncEnabled = enabled
    }

    override fun setDebugMuMultiplier(mult: Float) {
        debugMuMultiplier = mult.coerceIn(0.1f, 3.0f)
    }

    // For Leaky LMS tuning (stability with high mu)
    override fun setDebugLeakage(alpha: Float) {
        lowBand.setLeakage(alpha)
        midBand.setLeakage(alpha)
        highBand.setLeakage(alpha)
    }

    override fun setDebugVssEnergyScale(enabled: Boolean) {
        // Placeholder: in future, AudioEngine can pass blockRms-based scale into effectiveMuScale or per-band.
        // Currently VSS logic inside BandFxLms already uses pfx (input energy proxy) for dynamic factor.
    }

    override fun setDebugFreezeConfig(energyRatioThreshold: Float, consecutiveCount: Int, speedFactor: Float) {
        debugFreezeThreshold = energyRatioThreshold.coerceIn(8f, 25f)
        debugFreezeConsec = consecutiveCount.coerceIn(1, 5)
        debugSpeedFreezeFactor = speedFactor.coerceIn(0.3f, 1.2f)
    }

    override fun setRumbleAccel(mag: Float) {
        rumbleAccelMag = mag.coerceAtLeast(0f)
    }

    override fun setPersonalRumbleBias(bias: Float) {
        personalRumbleBias = bias.coerceIn(0.7f, 1.3f)
    }

    override fun setMusicSuppressionQuality(quality: Float) {
        musicSuppressionQuality = quality.coerceIn(0f, 1f)
    }

    override fun setMusicDominantRumbleMode(enabled: Boolean) {
        musicDominantRumbleMode = enabled
    }

    override fun setBlockRmsVssScale(scale: Float) {
        blockRmsVssScale = scale.coerceIn(0.1f, 2f)
    }

    override fun setUseNativeLowBand(enabled: Boolean) {
        useNativeLowBand = enabled
    }

    override fun isSirenOverrideActive(): Boolean = sirenOverride

    override fun getMimoZoneCount(): Int = mimoZoneCount

    override fun applyClassifierResult(result: NoiseBandClassification) {
        // CYCLE3_EXTRA: use via context (supports injected/mock classifier).
        bandGains = sessionContext.noiseBandClassifier.bandGains(result)
        lastDominant = result.dominantBand
    }

    override fun updateSecondaryPath(model: FloatArray) {
        for (i in 0 until sHatLength.coerceAtMost(model.size)) {
            sHat[i] = model[i]
        }
        lowBand.bindSecondaryPath(sHat)
        midBand.bindSecondaryPath(sHat)
        highBand.bindSecondaryPath(sHat)
    }

    override fun setAcousticDelay(samples: Int) {
        acousticDelaySamples = samples.coerceIn(0, 256)
        val perBand = BandDelayPlanner.plan(acousticDelaySamples)
        lowBand.acousticDelaySamples = perBand.lowSamples
        midBand.acousticDelaySamples = perBand.midSamples
        highBand.acousticDelaySamples = perBand.highSamples
    }

    override fun getAcousticDelaySamples(): Int = acousticDelaySamples

    @Keep
    override fun setVehicleSpeed(speedKmh: Float, valid: Boolean) {
        vehicleSpeedValid = valid
        vehicleSpeedKmh = if (valid) speedKmh.coerceAtLeast(0f) else 0f
        roadWiener.setSpeed(vehicleSpeedKmh, vehicleSpeedValid)
        if (valid) {
            // CYCLE3: use scoped road ref from sessionContext
            val cutoff = sessionContext.roadNoiseReferenceModel.lowPassCutoffHz(vehicleSpeedKmh)
            roadLpCoeff = (2.0 * PI * cutoff / sampleRate).toFloat().coerceIn(0.005f, 0.15f)
            preLearnedBank.blendedWeights(vehicleSpeedKmh)?.let { bias ->
                lowBand.applyWeightBias(bias, blend = 0.25f)
            }
        }
    }

    override fun setProcessingMode(mode: AncProcessingMode) {
        processingMode = mode
    }

    override fun getProcessingMode(): AncProcessingMode = processingMode

    @Keep
    override fun updateTier(newTier: UserTier) {
        filterLength = tierLength(newTier)
        baseMu = tierMu(newTier)
        lowBand.filterLength = filterLength
        midBand.filterLength = (filterLength / 2).coerceAtLeast(64)
        highBand.filterLength = (filterLength / 4).coerceAtLeast(32)
        lowBand.baseMu = baseMu
        midBand.baseMu = baseMu
        highBand.baseMu = baseMu

        // Auto-apply advanced tunings based on tier only (no manual switches for user in future)
        // User only chooses LIGHT/STANDARD/PRO (light/medium/heavy); everything else (leakage, VSS=blockRmsVssScale, IMU rumbleBoostFactor, native) auto-configured via tier* funcs.
        // Values tuned by sim_iter.ps1 (LIGHT/STANDARD/PRO under normal/strict +/-IMU/pothole/native); balance stab (low pfxVarEma, no pop on impulses) + perf (high effMidMu, red 200-350Hz rumble, lms updates).
        // PRO aggressive (low leak/high vss/boost/native), LIGHT conservative. See sim output table for predicted metrics.
        lowBand.setLeakage(tierLeakage(newTier))
        midBand.setLeakage(tierLeakage(newTier))
        highBand.setLeakage(tierLeakage(newTier))

        blockRmsVssScale = tierVssScale(newTier)

        // Rumble boost strength per tier (used in rumbleVibBoost calc)
        // (The actual boost uses this * accel; defined in process for low band)
        // For simplicity, we can store and use.
        // (We'll adjust the boost calc to use tier strength)

        useNativeLowBand = tierNativeEnabled(newTier) && NativeLowBandProcessor.isNativeAvailable()
        rumbleBoostFactor = tierRumbleBoostStrength(newTier)

        // Log applied for debug (AudioEngine snapshots will include effective values)
    }

    override fun registerBlockEnergy(rms: Float): Boolean {
        if (blockEnergyEma < 1e-6f) {
            blockEnergyEma = rms
            consecutiveHighEnergyRatio = 0
            return false
        }
        val ratio = rms / blockEnergyEma.coerceAtLeast(1e-6f)
        blockEnergyEma = 0.95f * blockEnergyEma + 0.05f * rms

        // Dynamic threshold: higher (less freeze) at highway speeds for steady tire/wind rumble
        // In music dominant (from log analysis 06-30: frequent freeze + artifact even in normal mode), use higher threshold to avoid over-freezing rumble learning.
        val speed = vehicleSpeedKmh
        var threshold = if (speed > 50f) debugFreezeThreshold.coerceAtLeast(12f) else debugFreezeThreshold
        if (musicDominantRumbleMode) {
            threshold = (threshold * 1.5f).coerceAtLeast(15f)  // less sensitive in music dom to prevent telegraph on residual while allowing rumble LMS
        }
        val minRms = if (speed > 50f) 0.015f else 0.02f

        if (ratio > threshold && rms > minRms) {
            consecutiveHighEnergyRatio++
            if (consecutiveHighEnergyRatio >= debugFreezeConsec) {  // require consecutive high ratios to reduce single-spike freezes
                // P2 enhancement (20260630 log analysis + review): 
                // - dynamic based on severity
                // - consecutiveBumpCount: if clustered bumps, extend freeze to protect LMS
                // - incorporate lastLmsPfxVarEma if high (high variance = higher risk, longer freeze)
                val baseFreeze = (sampleRate / bufferSize.coerceAtLeast(256)).coerceIn(2, 12)
                val severity = (ratio / threshold).coerceAtMost(3f)
                var freezeDur = (baseFreeze * severity).toInt().coerceIn(2, 12)
                consecutiveBumpCount++
                if (consecutiveBumpCount >= 3) freezeDur += 4  // clustered bumps -> extra protection
                val varEma = sessionContext.perfMetrics.lastLmsPfxVarEma
                if (varEma > 20f) freezeDur += 3  // high varEma (from log) -> conservative
                if (speed > 50f) freezeDur = (freezeDur * debugSpeedFreezeFactor).toInt().coerceAtLeast(2)  // steady rumble allow more LMS
                freezeWeightUpdates = freezeDur.coerceAtMost(15)
                bumpDetectedFlag = true
                consecutiveHighEnergyRatio = 0
                return true
            }
        } else {
            consecutiveHighEnergyRatio = 0
            consecutiveBumpCount = 0  // reset on no bump
        }
        // IDLE TELEGRAPH SUPPRESS (minimal safe, <10kmh only; drive rumble unaffected for #6/#7 eff 0.6+ goal):
        // At low speed + low/steady rms (no strong rumble excitation, ratio near 1 < high thresh), short-freeze LMS to halt over-adaptation on residuals/bleed/elec.
        // Complements speed-dependent minRms/thresh; productive rumble at 50+ never triggers this (high rms or varying).
        val isIdleLowSpeed = speed < 10f
        if (isIdleLowSpeed && rms < 0.025f && ratio < 1.6f) {
            // steady low energy: freeze briefly (4 blocks ~16ms @64samp) to prevent pulsed weights from high mu on low pfx
            val shortFreeze = 4
            if (freezeWeightUpdates < shortFreeze) freezeWeightUpdates = shortFreeze
            return true
        }
        return false
    }

    override fun isWeightUpdateFrozen(): Boolean = freezeWeightUpdates > 0

    override fun getCurrentFreezeBlocksRemaining(): Int = freezeWeightUpdates  // for debug/perf log of bump freeze state

    override fun adjustMu(newMu: Float) {
        baseMu = newMu.coerceAtMost(0.01f)
        lowBand.baseMu = baseMu
        midBand.baseMu = baseMu
        highBand.baseMu = baseMu
    }

    override fun finishLearning() {
        baseMu = 0.004f
        lowBand.baseMu = baseMu
        midBand.baseMu = baseMu
        highBand.baseMu = baseMu
        if (!learningCaptured) {
            preLearnedBank.capture(vehicleSpeedKmh, lowBand.captureWeights())
            learningCaptured = true
        }
    }

    @Keep
    override fun process(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        val floorMode = processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC ||
            processingMode == AncProcessingMode.FLOOR_NOISE_CALL ||
            processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD ||
            processingMode == AncProcessingMode.MUSIC_DOMINANT_RUMBLE
        val roadMode = processingMode == AncProcessingMode.ROAD_NOISE_GPS ||
            processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD
        val callFloorMode = processingMode == AncProcessingMode.FLOOR_NOISE_CALL
        val musicDominantRumbleMode = processingMode == AncProcessingMode.MUSIC_DOMINANT_RUMBLE
        val sirenScale = if (sirenOverride) sirenGainScale else 1f

        val latencyLimits = latencyBandLimits
        val freeze = freezeWeightUpdates > 0 || sirenOverride

        for (i in input.indices) {
            val xRaw = input[i] / 32768.0f
            val virtualError = virtualSensing.update(xRaw)
            voiceProtector.update(callActive, xRaw)
            val bands = splitter.split(xRaw)
            val virtualBands = splitter.split(virtualError)
            val reference = buildReference(xRaw, bands.low, floorMode, roadMode)
            val lowSample = if (floorMode || roadMode) reference.low else bands.low
            val idleMode = !floorMode && !roadMode && !callActive

            val lowMu = effectiveMuScale(lowBand, floorMode, roadMode) *
                sirenScale *
                latencyLimits.lowGain *
                LatencyAwareBandLimiter.bandMuScale(lowBand.centerHz, estimatedLatencyMs, roadRumble = roadMode)

            // P3: Direct IMU integration into ANC: use rumbleAccelMag (vibration proxy from phone IMU) to boost low band when high road rumble vibration detected.
            // Strengthened per review (0.09 insufficient; now PRO 0.15, max boost ~1.8x before bias, allow higher accel input) for better rumble prediction/mix into low band.
            // This provides a true structural feedforward (immune to cabin acoustic feedback) complementing the speed-based road ref and mic error.
            // + personalRumbleBias (acoustic identity follows *user* via phone prefs; >1.0 for rumble-sensitive users). Tier auto + sim-driven.
            // Boost only in roadMode; for "personal mobile quiet cabin" across any car (AA).
            val baseRumbleBoost = if (roadMode) (1f + rumbleAccelMag.coerceAtMost(5f) * rumbleBoostFactor).coerceAtMost(1.8f) else 1f
            var rumbleVibBoost = (baseRumbleBoost * personalRumbleBias).coerceIn(0.8f, 2.0f)
            // First-principles: in MUSIC_DOMINANT_RUMBLE, make IMU rumble boost even stronger (vibration precursor is the cleanest rumble ref, unaffected by music/latency)
            // Dynamic: low suppressionQuality (<0.4) -> extra aggressive (1.3-1.5x more)
            if (musicDominantRumbleMode) {
                var extra = 2.0f  // per 06-30 log feedback: raise base extra boost in music dom so IMU becomes even more dominant ref (vibration precursor immune to music/latency)
                if (musicSuppressionQuality < 0.4f) {
                    extra *= (1.0f + (0.4f - musicSuppressionQuality) * 1.25f).coerceIn(1.0f, 1.5f)
                }
                rumbleVibBoost *= extra
                rumbleVibBoost = rumbleVibBoost.coerceAtMost(3.0f)
            }
            // IMU coupling quality: if accelMag too low (poor phone placement/coupling), dampen boost to avoid over-relying on weak signal.
            val couplingQuality = (rumbleAccelMag / 0.3f).coerceIn(0f, 1f)  // baseline ~0.1-0.3 from logs; adjust if needed
            if (couplingQuality < 0.5f && musicDominantRumbleMode) {
                rumbleVibBoost *= (0.5f + couplingQuality)  // reduce if poor coupling
            }
            val effectiveLowMu = lowMu * rumbleVibBoost
            lastRumbleVibBoost = rumbleVibBoost
            lastEffectiveLowMu = effectiveLowMu

            val lowOut = multirateLow.processSample(lowSample) { decimated ->
                // Aggressive per-"quiet zone" rumble focus: in musicLow, amplify low errorSample to drive stronger LMS adaptation for tire/wind (simulate seat-specific quiet zones)
                // IDLE ARTIFACT FIX (minimal, speed guard only): skip lowErr boost at very low speed (<12kmh) to suppress telegraph on low-rms residuals/bleed without rumble excitation.
                // At drive speed 50+ (for #6/#7) fully active. Preserves effMidMu 0.6+ goal.
                val useLowErrBoost = musicLowAncEnabled && vehicleSpeedKmh > 12f
                // Safe "amplify road noise" (post-subtractor): only boost error (for stronger rumble LMS) when suppression good.
                // Implements user's idea with guard: high suppression + rumble context -> more aggressive low band learning without amplifying music artifact.
                val suppressionBoost = 1.0f + (musicSuppressionQuality * 0.8f).coerceAtMost(0.8f)  // up to ~1.8x when perfect suppression
                val lowError = if (useLowErrBoost) virtualBands.low * suppressionBoost else virtualBands.low
                lowBand.processSample(
                    sample = decimated,
                    muScale = effectiveLowMu,
                    freezeUpdates = freeze,
                    errorSample = lowError
                )
            }
            // profiling counters active in BandFxLms.processSample above; fdaf push buffer reuse at this layer
            val fdafOut = fdafLow.push(lowSample)

            // CYCLE3_EXTRA integration point: exercise native low proto (switchable via setUseNativeLowBand)
            // Now enabled stub switching point: if flag + available, use native (even if currently stub returns 0, real impl when NDK active will contribute low band rumble cancel)
            // DIRECT INTEGRATION: nativeLowOut now added to low contribution (for when real native provides rumble cancel).
            val nativeLowOut = if (useNativeLowBand) {
                // Switch point open: even if !isNativeAvailable() (stub returns 0), the integration is active.
                // When real native impl (NDK active, isNativeAvailable true), this will contribute real low rumble cancel.
                nativeLow.processLowBand(lowSample, effectiveLowMu, freeze, lowSample)
            } else {
                0f
            }

            // Iter2-4 + Subagent3 Extended #7: roadMode + musicLow specific boost to midMu for 200-350Hz rumble in high-lat AA + Skoda.
            // Relax midEnabled to allow mid contrib when road rumble even if latency limits conservative.
            // S3: stronger mid boost (2.15x) + direct mid error boost *1.28 (like low's *1.3) (even if music) when road+rumble+speed+energy.
            // min 0.58f , + *1.75 if ROAD dominant. Guarded by roadMode+speed>28+energy to avoid artifact in pure music/idle (C2 risk refine).
            // mid center now 335Hz (tuned for 300-350 rumble peak focus). Also log effective via lastMuScale. minimal safe changes.
            val midEnabledEff = latencyLimits.midEnabled ||
                (roadMode && musicLowAncEnabled)  // relax for rumble road cases
            val rawMidMu = if (midEnabledEff) {
                effectiveMuScale(midBand, floorMode, roadMode) *
                    bandGains.mid *
                    voiceProtector.midBandMuScale(callFloorMode || callActive) *
                    sirenScale *
                    latencyLimits.midGain *
                    LatencyAwareBandLimiter.bandMuScale(midBand.centerHz, estimatedLatencyMs, roadRumble = (roadMode && musicLowAncEnabled))
            } else {
                0f
            }
            val roadMusicMidBoost = if (roadMode && musicLowAncEnabled && vehicleSpeedKmh > 28f) 2.15f else 1.0f
            var midMu = rawMidMu * roadMusicMidBoost
            if (roadMode && musicLowAncEnabled && vehicleSpeedKmh > 28f && midMu < 0.58f) {
                midMu = 0.58f  // ensure minimum mid contrib for rumble dominant even at high AA lat (S3 ext ambitious)
            }
            // Iter3-4 + S3 + breakthrough: if dominant ROAD or (road + speed + musicLow context) -> extra mid boost + stronger error drive.
            // This ensures rumble mid contrib (200-350Hz) even if high music energy keeps dominant=MUSIC_BROAD in AA mic spectrum (real max low+mid~0.07).
            // Guard remains roadMode+speed+musicLow; dominant shift is bonus (via relaxed classifier).
            val hasRumbleContext = roadMode && vehicleSpeedKmh > 28f && musicLowAncEnabled
            if ((lastDominant == com.example.caranc.shared.model.DominantNoiseBand.ROAD_MID ||
                lastDominant == com.example.caranc.shared.model.DominantNoiseBand.ROAD_LOW || hasRumbleContext) && roadMode && vehicleSpeedKmh > 25f) {
                midMu *= 1.75f
            }
            // S3 Ext: direct mid error boost *1.28 ... broadened to rumble context (not only dom)
            val midEnergyGuard = (lastDominant == com.example.caranc.shared.model.DominantNoiseBand.ROAD_MID || lastDominant == com.example.caranc.shared.model.DominantNoiseBand.ROAD_LOW || hasRumbleContext)
            val midError = if (roadMode && musicLowAncEnabled && vehicleSpeedKmh > 28f && midEnergyGuard) virtualBands.mid * 1.28f else virtualBands.mid
            val midOut = if (midMu > 0f) {
                midBand.processSample(
                    sample = bands.mid,
                    muScale = midMu,
                    freezeUpdates = freeze,
                    errorSample = midError
                )
            } else {
                0f
            }

            val highMu = if (latencyLimits.highEnabled) {
                effectiveMuScale(highBand, floorMode, roadMode) *
                    bandGains.high *
                    voiceProtector.highBandMuScale(callFloorMode || callActive) *
                    sirenScale *
                    latencyLimits.highGain *
                    LatencyAwareBandLimiter.bandMuScale(highBand.centerHz, estimatedLatencyMs, roadRumble = roadMode)
            } else {
                0f
            }
            val highOut = if (highMu > 0f) {
                highBand.processSample(
                    sample = bands.high,
                    muScale = highMu,
                    freezeUpdates = freeze,
                    errorSample = virtualBands.high
                )
            } else {
                0f
            }

            val engineFf = engineComb.feedforwardSample(lowSample) * engineComb.blendGain(idleMode)
            val roadFf = if (roadMode) {
                roadWiener.feedforwardSample(lowSample) * roadWiener.blendGain()
            } else {
                0f
            }

            val adaptiveCombined = (lowOut + nativeLowOut) * bandGains.low * latencyLimits.lowGain +
                midOut + highOut + fdafOut * 0.45f

            val combined = when {
                floorMode && (processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC || processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD || processingMode == AncProcessingMode.MUSIC_DOMINANT_RUMBLE) && musicLowAncEnabled -> {
                    // low freq full ANC + mid/high protected (lowpassed)
                    // extra boost to low band anti for stronger tire/wind rumble cancellation even in music (user request for more noticeable low-freq effect)
                    // More aggressive dynamic boost for low band in musicLowAnc (user: perceived reduction still insensitive, make rumble cancellation stronger)
                    val lowRumbleEnergy = kotlin.math.abs(lowOut) * bandGains.low
                    val speedBoost = (vehicleSpeedKmh / 100f).coerceIn(0f, 0.6f)
                    // IDLE ARTIFACT FIX: at low speed no dynamic boost (prevents over-anti on quiet residuals); full at rumble speeds for #7 goal.
                    val dynamicLowBoost = if (vehicleSpeedKmh < 12f) 1.0f else 1.6f + (lowRumbleEnergy * 0.7f).coerceAtMost(1.0f) + speedBoost  // even more aggressive for perceived rumble reduction (user: still insensitive)
                    // Conservative anti output scale: when suppression poor (music not well removed), reduce lowAnti to avoid pushing anti that cancels music residual (artifacts).
                    // Complements the mu conservative scale. High suppression -> full anti strength.
                    val conservativeAntiScale = if (musicLowAncEnabled) musicSuppressionQuality.coerceAtLeast(0.5f) else 1f
                    val lowAnti = (lowOut * bandGains.low * latencyLimits.lowGain + fdafOut * 0.45f) * dynamicLowBoost * conservativeAntiScale
                    val higherAnti = midOut + highOut
                    // Even higher road_wiener in musicLow for tire/wind (aggressive feedforward)
                    val roadMusicWeight = if (roadMode) roadWiener.blendGain() * 2.0f else 0f
                    val roadFfInMusicLow = if (roadMode) roadWiener.feedforwardSample(lowSample) * roadMusicWeight else 0f

                    // Per Skoda Octavia 2019 + user spectrum analysis (200-350 Hz dominant rumble):
                    // When road noise dominant (roadMode), relax higher-band lowpass protection so mid (200-350Hz) anti can contribute.
                    // S3 Ext: even more permissive (0.52f) for road+rumble+speed>28 to let bigger mid contrib even with music (ov=80 maxC focus).
                    // Guarded by roadMode + speed + energy (minimal safe). Addresses main energy above 150Hz limit.
                    val protectedHigher = if (roadMode && vehicleSpeedKmh > 28f) higherAnti * 0.52f else lowPassOutput(higherAnti)
                    - (lowAnti + protectedHigher) + engineFf + roadFfInMusicLow
                }
                floorMode -> -lowPassOutput(adaptiveCombined) + engineFf
                roadMode -> -roadLowPassOutput(adaptiveCombined) + roadFf + engineFf * 0.3f
                else -> -adaptiveCombined + engineFf
            }

            output[i] = (combined * sirenScale * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()

            if (freezeWeightUpdates > 0) {
                freezeWeightUpdates--
            }
        }
        return output
    }

    private fun bindMimoZones(profile: CabinMimoProfile?) {
        val zones = profile?.activeZones.orEmpty()
        if (zones.isEmpty()) {
            lowBand.bindZonePaths(listOf(sHat))
            midBand.bindZonePaths(listOf(sHat))
            highBand.bindZonePaths(listOf(sHat))
            return
        }
        val zonePaths = zones.map { it.secondaryPath }
        lowBand.bindZonePaths(zonePaths, zones)
        midBand.bindZonePaths(zonePaths, zones)
        highBand.bindZonePaths(zonePaths, zones)
    }

    @Keep
    override fun release() {}

    private fun buildReference(
        xRaw: Float,
        lowSample: Float,
        floorMode: Boolean,
        roadMode: Boolean
    ): BandSamples {
        if (!floorMode && !roadMode) return splitter.split(xRaw)

        var roadWeight = when (processingMode) {
            AncProcessingMode.ROAD_NOISE_GPS,
            AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD -> sessionContext.roadNoiseReferenceModel.roadBlendWeight(
                vehicleSpeedKmh,
                vehicleSpeedValid
            )
            AncProcessingMode.NORMAL -> sessionContext.roadNoiseReferenceModel.roadBlendWeight(
                vehicleSpeedKmh,
                vehicleSpeedValid
            ) * 0.5f
            else -> 0f
        }
        // First-principles: in MUSIC_DOMINANT_RUMBLE, boost road/IMU ref weight (cleaner than mic which has music mix), reduce mic reliance for low rumble.
        // Dynamic with suppression and coupling.
        if (musicDominantRumbleMode) {
            var extra = 1.5f
            if (musicSuppressionQuality < 0.4f) {
                extra *= (1.0f + (0.4f - musicSuppressionQuality) * 1.25f).coerceIn(1.0f, 1.5f)
            }
            roadWeight = (roadWeight * extra).coerceAtMost(1f)
            val couplingQuality = (rumbleAccelMag / 0.3f).coerceIn(0f, 1f)
            if (couplingQuality < 0.5f) {
                roadWeight *= (0.5f + couplingQuality)
            }
        }
        if (roadWeight <= 0f) {
            val micLow = if (floorMode) lowPassInput(xRaw) else lowSample
            return BandSamples(micLow, 0f, 0f)
        }

        val roadComponent = roadLowPassInput(xRaw)
        // CYCLE3: scoped via context
        val energyScale = sessionContext.roadNoiseReferenceModel.roadEnergyScale(vehicleSpeedKmh)
        val scaledRoad = roadComponent * (0.55f + 0.45f * energyScale)
        val micLow = if (floorMode) lowPassInput(xRaw) else lowSample
        // First-principles explicit: in MUSIC_DOMINANT_RUMBLE, further lower mic residue weight in low band (reduce reliance on high-latency mic signal which carries music bleed).
        val micFactor = if (musicDominantRumbleMode) 0.3f else 1f  // per 06-30 log feedback: even lower mic weight in music dom to rely more on IMU (vibration immune to music bleed)
        val blendedLow = (1f - roadWeight) * micLow * micFactor + roadWeight * scaledRoad
        return BandSamples(blendedLow, 0f, 0f)
    }

    private fun effectiveMuScale(
        band: BandFxLms,
        floorMode: Boolean,
        roadMode: Boolean
    ): Float {
        val modeScale = when (processingMode) {
            AncProcessingMode.NORMAL -> 1f
            AncProcessingMode.FLOOR_NOISE_MUSIC -> if (band.label == "low" && musicLowAncEnabled) 1f else 0.38f
            AncProcessingMode.FLOOR_NOISE_CALL -> 0.08f
            AncProcessingMode.ROAD_NOISE_GPS -> 0.75f
            AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD -> if (band.label == "low" && musicLowAncEnabled) 1f else if (roadMode) 0.75f else 0.55f
            AncProcessingMode.MUSIC_DOMINANT_RUMBLE -> if (band.label == "low") 1.2f * musicSuppressionQuality.coerceAtLeast(0.5f) else 0.5f * musicSuppressionQuality.coerceAtLeast(0.7f)  // direction C: rumble focus, conservative on music
        }
        // P1 conservative mode: when music dominates and suppressionQuality low (subtractor not removing music well),
        // scale down low/mid mu to avoid treating music residual as noise -> artifacts that make users disable ANC.
        // High suppression + rumble context allows normal (or enhanced) processing.
        val musicConservativeScale = if (floorMode && musicLowAncEnabled) {
            // when suppression poor, be conservative (protect music); when good, full strength
            (0.4f + 0.6f * musicSuppressionQuality).coerceIn(0.3f, 1f)
        } else 1f
        val resonanceScale = CabinResonanceDetector.resonanceMuScale(band.centerHz, resonancePeaks)
        // IDLE ARTIFACT FIX (minimal, speed<8 only; #7 at 50-70 untouched): lower muScale at pure idle to reduce over-adapt on low-energy residuals while musicLow/debug high mu active.
        // roadMode at low spd rare (thresholds); rumble drive unaffected. Helps telegraph w/o touching effMidMu 0.6+ at speed.
        val idleMuScale = if (vehicleSpeedKmh < 8f && !roadMode) 0.32f else 1f
        return band.baseMuScale * modeScale * speedMuScale() * resonanceScale * debugMuMultiplier * idleMuScale * musicConservativeScale
    }

    private fun speedMuScale(): Float =
        // CYCLE3_P2: via scoped from context (mockable)
        sessionContext.roadNoiseReferenceModel.muScale(vehicleSpeedKmh, vehicleSpeedValid)

    private fun lowPassInput(x: Float): Float {
        lpInputState += lpCoeff * (x - lpInputState)
        return lpInputState
    }

    private fun lowPassOutput(x: Float): Float {
        lpOutputState += lpCoeff * (x - lpOutputState)
        return lpOutputState
    }

    private fun roadLowPassInput(x: Float): Float {
        roadLpInputState += roadLpCoeff * (x - roadLpInputState)
        return roadLpInputState
    }

    private fun roadLowPassOutput(x: Float): Float {
        roadLpOutputState += roadLpCoeff * (x - roadLpOutputState)
        return roadLpOutputState
    }

    private fun tierLength(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 128
        UserTier.STANDARD -> 256
        UserTier.PRO -> 512
    }

    private fun tierMu(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 0.005f
        UserTier.STANDARD -> 0.01f
        UserTier.PRO -> 0.02f
    }

    private fun tierLeakage(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 0.9999f   // conservative, minimal leak (higher alpha) for stability in simple/music-bleed cases. From sim_iter.ps1 per-tier runs (LIGHT always STABLE even idle/pothole)
        UserTier.STANDARD -> 0.9998f
        UserTier.PRO -> 0.9995f     // more aggressive (lower alpha) for high-mu stability with VSS+clip on rough rumble. sims: lowest varEma even on pothole impulses
    }

    private fun tierVssScale(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 0.65f   // conservative; lower scale safer on variable/low energy (music/idle). sim rec from stability/perf balance
        UserTier.STANDARD -> 0.85f
        UserTier.PRO -> 1.0f      // full scale for max adaptation on high rumble excitation (rough high-spd)
    }

    private fun tierRumbleBoostStrength(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 0.015f  // low IMU boost for conservative; sims show sufficient for LIGHT
        UserTier.STANDARD -> 0.045f
        UserTier.PRO -> 0.15f     // P3: increased from 0.09 per review (log showed insufficient rumble boost in rough); allows stronger 1+4*0.15~1.6x for better low band rumble prediction/mix
    }

    private fun tierNativeEnabled(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> false
        UserTier.STANDARD -> false
        UserTier.PRO -> true  // enable for PRO (2x lms save simulated in sim_iter.ps1); real when NDK active
    }

    private inner class BandFxLms(
        val label: String,
        val centerHz: Float,
        var baseMuScale: Float
    ) {
        private val maxFilterLength = 512
        private val bufferMask = 1023
        var filterLength = tierLength(UserTier.STANDARD)
        var baseMu = 0.01f
        var acousticDelaySamples = 0

        private val w = FloatArray(maxFilterLength) { 0f }
        private val xBuffer = FloatArray(1024) { 0f }
        private val filteredXBuffer = FloatArray(1024) { 0f }
        private var bufferIndex = 0
        // Leaky LMS: leakage factor α (0.999 ~ 0.9999 typical). Prevents weight drift/divergence when excitation is absent or low (e.g. steady rumble or idle).
        // Formula used: w(j) = α * w(j) + muNorm * error * x(j)
        // Current default very mild (0.9998) to allow convergence; can be made more aggressive (smaller α) for stability with high mu=2.0.
        private var leakage = 0.9998f
        private val sHatLocal = FloatArray(sHatLength) { 0f }.apply { this[0] = 1f }
        private val zonePaths = mutableListOf<FloatArray>()
        private val zoneWeights = mutableListOf<Float>()

        // LMS profiling counters (P1 #6+7 hot-path opt): track calls vs actual weight updates for profiling LMS load
        var lmsProcessCalls = 0L
            private set
        var lmsUpdateCount = 0L
            private set
        var lastLmsPfx = 0f
            private set

        // Iter2+: last effective muScale applied to this band (post all scales including road/music boosts; for mid contrib logging)
        var lastMuScale = 0f
            private set

        fun setLeakage(alpha: Float) {
            leakage = alpha.coerceIn(0.99f, 0.99999f)
        }

        fun bindSecondaryPath(model: FloatArray) {
            for (i in 0 until sHatLength.coerceAtMost(model.size)) {
                sHatLocal[i] = model[i]
            }
            zonePaths.clear()
            zoneWeights.clear()
            zonePaths.add(sHatLocal.copyOf())
            zoneWeights.add(1f)
        }

        fun bindZonePaths(paths: List<FloatArray>, zones: List<CabinZonePath> = emptyList()) {
            zonePaths.clear()
            zoneWeights.clear()
            if (paths.isEmpty()) {
                zonePaths.add(sHatLocal.copyOf())
                zoneWeights.add(1f)
                return
            }
            paths.forEachIndexed { index, path ->
                val local = FloatArray(sHatLength) { 0f }
                for (i in 0 until sHatLength.coerceAtMost(path.size)) {
                    local[i] = path[i]
                }
                zonePaths.add(local)
                val weight = zones.getOrNull(index)?.blendWeight ?: 1f
                zoneWeights.add(weight)
            }
        }

        fun captureWeights(): FloatArray {
            val copy = FloatArray(filterLength)
            for (i in 0 until filterLength) copy[i] = w[i]
            return copy
        }

        fun applyWeightBias(bias: FloatArray, blend: Float) {
            val b = blend.coerceIn(0f, 0.8f)
            val len = filterLength.coerceAtMost(bias.size)
            for (i in 0 until len) {
                w[i] = w[i] * (1f - b) + bias[i] * b
            }
        }

        fun processSample(
            sample: Float,
            muScale: Float,
            freezeUpdates: Boolean,
            errorSample: Float = sample
        ): Float {
            xBuffer[bufferIndex] = sample
            lmsProcessCalls++

            var y = 0f
            for (j in 0 until filterLength) {
                val idx = (bufferIndex - acousticDelaySamples - j) and bufferMask
                y += w[j] * xBuffer[idx]
            }

            var filteredX = 0f
            var weightSum = 0f
            if (zonePaths.isEmpty()) {
                for (j in 0 until sHatLength) {
                    val idx = (bufferIndex - j) and bufferMask
                    filteredX += sHatLocal[j] * xBuffer[idx]
                }
                weightSum = 1f
            } else {
                for (z in zonePaths.indices) {
                    val zoneSHat = zonePaths[z]
                    val weight = zoneWeights.getOrElse(z) { 1f }
                    var zoneFiltered = 0f
                    for (j in 0 until sHatLength) {
                        val idx = (bufferIndex - j) and bufferMask
                        zoneFiltered += zoneSHat[j] * xBuffer[idx]
                    }
                    filteredX += zoneFiltered * weight
                    weightSum += weight
                }
                if (weightSum > 0f) filteredX /= weightSum
            }
            filteredXBuffer[bufferIndex] = filteredX

            if (!freezeUpdates && muScale > 0f) {
                lastMuScale = muScale
                val vssFromBlockRms = this@MultiBandANCProcessor.blockRmsVssScale  // use outer blockRms variance for enhanced VSS
                val currentMu = baseMu * muScale * vssFromBlockRms
                val error = errorSample

                var pfx = 0f
                for (j in 0 until filterLength) {
                    val idx = (bufferIndex - j) and bufferMask
                    pfx += filteredXBuffer[idx] * filteredXBuffer[idx]
                }

                // VSS (Variable Step-Size) + Leaky + clipping for stability with aggressive mu=2.0 + freeze=10.
                // Base is NLMS-style normalization. Additional:
                // - pfx-based VSS: high stable energy -> full mu; low/variable -> reduced (prevents divergence on impulses like potholes/expansion joints).
                // - Energy factor: simple VSS using pfx (can extend with ema variance of blockRms from AudioEngine).
                // - Gradient clipping: limit per-tap update to avoid popping on sudden high error.
                // - Leaky applied in update (see leakage field + doc above).
                val baseNorm = currentMu / (pfx + 1e-3f)
                val energyFactor = when {
                    pfx > 30f -> 1.0f                  // strong rumble excitation, allow full aggressive step
                    pfx > 10f -> 0.85f
                    pfx < 2.0f -> 0.22f                // existing low energy / idle guard
                    else -> 0.6f
                }
                val muNorm = baseNorm * energyFactor

                for (j in 0 until filterLength) {
                    val idx = (bufferIndex - j) and bufferMask
                    val rawUpdate = muNorm * error * filteredXBuffer[idx]
                    // Gradient clipping / saturation to tame impulses (potholes etc.)
                    val clippedUpdate = rawUpdate.coerceIn(-0.05f, 0.05f)  // per-tap limit; tune based on real logs
                    w[j] = (w[j] * leakage + clippedUpdate).coerceIn(-0.8f, 0.8f)
                }
                lmsUpdateCount++
                lastLmsPfx = pfx
            }

            bufferIndex = (bufferIndex + 1) and bufferMask
            return y
        }
    }

    // CYCLE3_EXTRA: better exposure of profiling counters (P1 counters were private to BandFxLms inner).
    // Now on public facade surface + direct access for AudioEngine timing extension.
    // Only lowBand is the primary "low freq" (fed by multirate + fdaf); mid/high separate.
    // Also surface multirate/fdaf for completeness (future: they can grow counters too).
    override fun getLowLmsUpdateCount(): Long = lowBand.lmsUpdateCount
    override fun getLowLmsProcessCalls(): Long = lowBand.lmsProcessCalls
    override fun getLastLmsPfx(): Float = lowBand.lastLmsPfx

    fun getLowBandLmsUpdateCount(): Long = lowBand.lmsUpdateCount  // direct (non-facade) for internal
    fun getLowBandLmsProcessCalls(): Long = lowBand.lmsProcessCalls

    // Also surface the dedicated low processors' counters (CYCLE3_EXTRA native prep)
    // These override the default impls in AncProcessorFacade (added for AudioEngine perfMetrics).
    override fun getFdafLmsUpdateCount(): Long = fdafLow.fdafLmsUpdateCount
    override fun getMultirateDecimUpdateCount(): Long = multirateLow.multirateDecimUpdates

    // Iter2: effective mid mu (post boost/relax) for AudioEngine logging of mid band rumble contrib
    override fun getLastEffectiveMidMu(): Float = midBand.lastMuScale

    // 06-30 verification: expose internal flag (set by force on MUSIC_BROAD even if quality=0) and IMU rumble boost values.
    // Allows confirming in logs: flag true in #7 MUSIC_BROAD, rumbleVibBoost raised (2+), effectiveLowMu higher than base.
    override fun isMusicDominantRumbleMode(): Boolean = musicDominantRumbleMode
    override fun getLastRumbleVibBoost(): Float = lastRumbleVibBoost
    override fun getLastEffectiveLowMu(): Float = lastEffectiveLowMu
}