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
 * Uses cascaded 1-pole stages per crossover point (300 Hz low-mid, 800 Hz mid-high)
 * to realize proper 12 dB/oct slopes + flat sum (low + mid + high == input).
 * Replaces the prior crude 1st-order IIR (differences of LPFs, ~6 dB/oct, lost DC/very-low energy).
 * State is per-sample; call split(x) for normalized float input.
 */
internal data class BandSamples(
    val low: Float,
    val mid: Float,
    val high: Float
)

internal class BandSplitter(sampleRate: Int) {
    private val cLowMid = coeffFor(300f, sampleRate)
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
    private var bandGains = BandGains(low = 1f, mid = 0.25f, high = 0.05f)
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
        centerHz = 500f,
        baseMuScale = 0.25f
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

    override fun setDebugFreezeConfig(energyRatioThreshold: Float, consecutiveCount: Int, speedFactor: Float) {
        debugFreezeThreshold = energyRatioThreshold.coerceIn(8f, 25f)
        debugFreezeConsec = consecutiveCount.coerceIn(1, 5)
        debugSpeedFreezeFactor = speedFactor.coerceIn(0.3f, 1.2f)
    }

    override fun isSirenOverrideActive(): Boolean = sirenOverride

    override fun getMimoZoneCount(): Int = mimoZoneCount

    override fun applyClassifierResult(result: NoiseBandClassification) {
        // CYCLE3_EXTRA: use via context (supports injected/mock classifier).
        bandGains = sessionContext.noiseBandClassifier.bandGains(result)
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
        val speed = vehicleSpeedKmh
        val threshold = if (speed > 50f) debugFreezeThreshold.coerceAtLeast(12f) else debugFreezeThreshold
        val minRms = if (speed > 50f) 0.015f else 0.02f

        if (ratio > threshold && rms > minRms) {
            consecutiveHighEnergyRatio++
            if (consecutiveHighEnergyRatio >= debugFreezeConsec) {  // require consecutive high ratios to reduce single-spike freezes
                // Shorter freeze at high speed (steady rumble should not pause LMS as much)
                val baseFreeze = (sampleRate / bufferSize.coerceAtLeast(256)).coerceIn(3, 10)
                val freezeDur = if (speed > 50f) (baseFreeze * debugSpeedFreezeFactor).toInt().coerceAtLeast(2) else baseFreeze
                freezeWeightUpdates = freezeDur
                bumpDetectedFlag = true
                consecutiveHighEnergyRatio = 0
                return true
            }
        } else {
            consecutiveHighEnergyRatio = 0
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
            processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD
        val roadMode = processingMode == AncProcessingMode.ROAD_NOISE_GPS ||
            processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD
        val callFloorMode = processingMode == AncProcessingMode.FLOOR_NOISE_CALL
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
                LatencyAwareBandLimiter.bandMuScale(lowBand.centerHz, estimatedLatencyMs)

            val lowOut = multirateLow.processSample(lowSample) { decimated ->
                // Aggressive per-"quiet zone" rumble focus: in musicLow, amplify low errorSample to drive stronger LMS adaptation for tire/wind (simulate seat-specific quiet zones)
                val lowError = if (musicLowAncEnabled) virtualBands.low * 1.3f else virtualBands.low
                lowBand.processSample(
                    sample = decimated,
                    muScale = lowMu,
                    freezeUpdates = freeze,
                    errorSample = lowError
                )
            }
            // profiling counters active in BandFxLms.processSample above; fdaf push buffer reuse at this layer
            val fdafOut = fdafLow.push(lowSample)

            // CYCLE3_EXTRA integration point: exercise native low proto (currently 0 contrib, but loads JNI + can collect internal counters)
            // Future: val nativeLowOut = if (NativeLowBandProcessor.isNativeAvailable()) nativeLow.processLowBand(decimated, lowMu, freeze, ...) else 0f
            // Then combine with lowOut / fdafOut.
            nativeLow.processLowBand(lowSample, 0f, true)  // force freeze, zero mu to keep pure no-op for now; proto only

            val midMu = if (latencyLimits.midEnabled) {
                effectiveMuScale(midBand, floorMode, roadMode) *
                    bandGains.mid *
                    voiceProtector.midBandMuScale(callFloorMode || callActive) *
                    sirenScale *
                    latencyLimits.midGain *
                    LatencyAwareBandLimiter.bandMuScale(midBand.centerHz, estimatedLatencyMs)
            } else {
                0f
            }
            val midOut = if (midMu > 0f) {
                midBand.processSample(
                    sample = bands.mid,
                    muScale = midMu,
                    freezeUpdates = freeze,
                    errorSample = virtualBands.mid
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
                    LatencyAwareBandLimiter.bandMuScale(highBand.centerHz, estimatedLatencyMs)
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

            val adaptiveCombined = lowOut * bandGains.low * latencyLimits.lowGain +
                midOut + highOut + fdafOut * 0.45f

            val combined = when {
                floorMode && (processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC || processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD) && musicLowAncEnabled -> {
                    // low freq full ANC + mid/high protected (lowpassed)
                    // extra boost to low band anti for stronger tire/wind rumble cancellation even in music (user request for more noticeable low-freq effect)
                    // More aggressive dynamic boost for low band in musicLowAnc (user: perceived reduction still insensitive, make rumble cancellation stronger)
                    val lowRumbleEnergy = kotlin.math.abs(lowOut) * bandGains.low
                    val speedBoost = (vehicleSpeedKmh / 100f).coerceIn(0f, 0.6f)
                    val dynamicLowBoost = 1.6f + (lowRumbleEnergy * 0.7f).coerceAtMost(1.0f) + speedBoost  // even more aggressive for perceived rumble reduction (user: still insensitive)
                    val lowAnti = (lowOut * bandGains.low * latencyLimits.lowGain + fdafOut * 0.45f) * dynamicLowBoost
                    val higherAnti = midOut + highOut
                    // Even higher road_wiener in musicLow for tire/wind (aggressive feedforward)
                    val roadMusicWeight = if (roadMode) roadWiener.blendGain() * 2.0f else 0f
                    val roadFfInMusicLow = if (roadMode) roadWiener.feedforwardSample(lowSample) * roadMusicWeight else 0f

                    // Per Skoda Octavia 2019 + user spectrum analysis (200-350 Hz dominant rumble):
                    // When road noise dominant (roadMode), relax higher-band lowpass protection so mid (200-350Hz) anti can contribute.
                    // This addresses the case where main energy is above 150Hz limit.
                    val protectedHigher = if (roadMode) higherAnti * 0.65f else lowPassOutput(higherAnti)
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

        val roadWeight = when (processingMode) {
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
        if (roadWeight <= 0f) {
            val micLow = if (floorMode) lowPassInput(xRaw) else lowSample
            return BandSamples(micLow, 0f, 0f)
        }

        val roadComponent = roadLowPassInput(xRaw)
        // CYCLE3: scoped via context
        val energyScale = sessionContext.roadNoiseReferenceModel.roadEnergyScale(vehicleSpeedKmh)
        val scaledRoad = roadComponent * (0.55f + 0.45f * energyScale)
        val micLow = if (floorMode) lowPassInput(xRaw) else lowSample
        val blendedLow = (1f - roadWeight) * micLow + roadWeight * scaledRoad
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
        }
        val resonanceScale = CabinResonanceDetector.resonanceMuScale(band.centerHz, resonancePeaks)
        return band.baseMuScale * modeScale * speedMuScale() * resonanceScale * debugMuMultiplier
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
        private val leakage = 0.9998f
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
                val currentMu = baseMu * muScale
                val error = errorSample

                var pfx = 0f
                for (j in 0 until filterLength) {
                    val idx = (bufferIndex - j) and bufferMask
                    pfx += filteredXBuffer[idx] * filteredXBuffer[idx]
                }

                val muNorm = if (pfx > 20f) {
                    currentMu / (pfx * 2f)
                } else {
                    currentMu / (pfx + 1e-3f)
                }

                for (j in 0 until filterLength) {
                    val idx = (bufferIndex - j) and bufferMask
                    w[j] = (w[j] * leakage + muNorm * error * filteredXBuffer[idx]).coerceIn(-0.8f, 0.8f)
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
}