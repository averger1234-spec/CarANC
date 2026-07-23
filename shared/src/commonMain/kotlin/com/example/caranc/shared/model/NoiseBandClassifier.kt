package com.example.caranc.shared.model

import androidx.annotation.Keep
import com.example.caranc.shared.RoadNoiseReferenceModel

@Keep
enum class DominantNoiseBand {
    IDLE_LOW,
    ROAD_LOW,
    ROAD_MID,
    MUSIC_BROAD,
    MIXED
}

@Keep
data class NoiseBandClassification(
    val dominantBand: DominantNoiseBand,
    val lowEnergyRatio: Float,
    val midEnergyRatio: Float,
    val highEnergyRatio: Float,
    val confidence: Float,
    /** Product: tire / road / wind focus (see CabinNvhFocus). */
    val nvhFocus: NvhFocusClass = NvhFocusClass.MIXED_CABIN,
    val nvhTargetHzLabel: String = "",
    val nvhSuppressHighAnti: Boolean = true
)

/**
 * Classifies dominant noise band (IDLE/ROAD/MUSIC/MIXED) from spectrum + speed/music flags; also maps to band gains.
 * Internally uses RoadNoiseReferenceModel thresholds.
 *
 * CYCLE3_EXTRA: Refactored from object -> class (ctor) to support scoped/mock versions.
 * - Accepts injected roadNoiseRef (default = RoadNoiseReferenceModel()).
 * - Provided via AncSessionContext.noiseBandClassifier (context wires it to use the session's road ref).
 * - Call sites (AudioEngine, MultiBandANCProcessor) updated to go through sessionContext.noiseBandClassifier.
 * - For mock: AncSessionContext(noiseBandClassifier = FakeClassifier()) or subclass.
 * (Uses class+ctor injection pattern rather than separate interface.)
 */
@Keep
class NoiseBandClassifier(
    private val roadNoiseRef: RoadNoiseReferenceModel = RoadNoiseReferenceModel()
) {

    fun classify(
        spectrum: FloatArray,
        sampleRate: Int,
        speedKmh: Float,
        speedValid: Boolean,
        isMusicActive: Boolean,
        isCallActive: Boolean,
        linearAccelMagnitude: Float = 0f,  // NEW: IMU for driving rumble bias (when speed high + accel, force ROAD even if MUSIC_BROAD)
        estimatedLatencyMs: Float = 150f
    ): NoiseBandClassification {
        if (spectrum.isEmpty() || sampleRate <= 0) {
            return NoiseBandClassification(
                dominantBand = DominantNoiseBand.MIXED,
                lowEnergyRatio = 0.33f,
                midEnergyRatio = 0.33f,
                highEnergyRatio = 0.34f,
                confidence = 0f,
                nvhFocus = NvhFocusClass.MIXED_CABIN,
                nvhTargetHzLabel = "unknown",
                nvhSuppressHighAnti = true
            )
        }

        val (lowEnergy, midEnergy, highEnergy) = bandEnergies(spectrum, sampleRate)
        val total = (lowEnergy + midEnergy + highEnergy).coerceAtLeast(1e-6f)
        val lowRatio = lowEnergy / total
        val midRatio = midEnergy / total
        val highRatio = highEnergy / total
        val lowMidRatio = lowRatio + midRatio

        val nvh = CabinNvhFocus.classify(
            speedKmh = speedKmh,
            speedValid = speedValid,
            lowRatio = lowRatio,
            midRatio = midRatio,
            highRatio = highRatio,
            linearAccelMagnitude = linearAccelMagnitude,
            estimatedLatencyMs = estimatedLatencyMs
        )

        // BREAKTHROUGH for AA music + Skoda rumble (2026-06-29 real log data):
        // In cabin AA playback, music energy swamps total power (highRatio~0.999, low+mid max observed 0.071 even @90kmh rough).
        // Original 0.30 thresh never triggers force-ROAD even with driving. 
        // Lowered to 0.06 + relaxed subconds so "some rumble presence" + speed + musicLow intent can force ROAD_MID for mid boost.
        // Rumble presence now wins over pure high-dominant for adaptation goal (protection still handled in gains/mode).
        val hasDecentRumbleForMid = lowMidRatio >= 0.06f

        // NEW 07-02: Driving rumble bias from IMU (user feedback: even music off, still MUSIC_BROAD in driving; high boost/mu but no red in driving).
        // When speed high + accel (rumble proxy), force ROAD rumble mode to enable full IMU dominant low/mid processing (ignore MUSIC_BROAD for low band).
        // This addresses: classifier failing to see rumble when driving (perhaps wind/tire high freq swamping), so use IMU as prior.
        // Matches log: when accel high, boost high internally but reduction low -> need to force the mode to let IMU ref dominate.
        val rumbleProxy = (linearAccelMagnitude / 5f).coerceIn(0f, 1f)
        val isDrivingRumble = speedValid && speedKmh > 40f && linearAccelMagnitude > 0.5f && rumbleProxy > 0.15f

        val dominantBand = when {
            isCallActive -> DominantNoiseBand.MUSIC_BROAD
            // 07-02 STRENGTHENED (user: "我沒看到你在修改APP?", analysis of 125731.log: music=false 100%, but MUSIC_BROAD 537x, ROAD_MID only 1x; internal rumbleVibBoost max 11.25/effLowMu 14.7/virtualQ 0.75 but driving reductionDb~0 (only idle some); placement not core issue).
            // IMU structural prior (vibration) takes precedence over mic spectrum when driving rumble detected.
            // Bypass hasDecentRumbleForMid (lowMidRatio>=0.06) and highRatio MUSIC gate entirely: mic often shows high dominant in motion (wind/hiss/tire) even with strong road rumble.
            // This forces bandGains to ROAD (higher mid/low), enables rumble paths in processor, and improves log dominant for diagnosis.
            // isDrivingRumble already requires speed>40 + accel>0.5 + proxy>0.15; safe to force rumble band.
            isDrivingRumble ->
                if (lowRatio >= midRatio) DominantNoiseBand.ROAD_LOW else DominantNoiseBand.ROAD_MID
            // Subagent3 Extended #7 + iter variant: classifier tweak for pure ROAD_MID even with music.
            // Data-driven: speed>28 + decent rumble energy (low+mid>=~0.06, observed max~0.071 in real AA mic under music) -> force ROAD_MID/LOW.
            // This enables effectiveMidMu 0.5-0.8, higher mid contrib to 200-350Hz Skoda rumble despite music=true.
            // Guarded by speedValid. See car_road_tuning_v1 #6/#7 steps + strict low-music test protocol.
            speedValid && speedKmh > 28f && hasDecentRumbleForMid && (midRatio >= 0.04f || lowMidRatio >= 0.08f) ->
                DominantNoiseBand.ROAD_MID
            speedValid && speedKmh > 28f && hasDecentRumbleForMid && lowRatio >= 0.05f ->
                DominantNoiseBand.ROAD_LOW
            isMusicActive && highRatio > 0.65f && !(speedValid && speedKmh > 28f && hasDecentRumbleForMid) -> DominantNoiseBand.MUSIC_BROAD
            speedValid && speedKmh >= RoadNoiseReferenceModel.DRIVING_SPEED_THRESHOLD_KMH && lowRatio >= 0.42f ->
                DominantNoiseBand.ROAD_LOW
            speedValid && speedKmh >= RoadNoiseReferenceModel.DRIVING_SPEED_THRESHOLD_KMH && midRatio >= 0.38f ->
                DominantNoiseBand.ROAD_MID
            !speedValid || speedKmh < RoadNoiseReferenceModel.IDLE_SPEED_THRESHOLD_KMH ->
                DominantNoiseBand.IDLE_LOW
            highRatio >= 0.42f -> DominantNoiseBand.MUSIC_BROAD
            else -> DominantNoiseBand.MIXED
        }

        val confidence = when (dominantBand) {
            DominantNoiseBand.ROAD_LOW -> (lowRatio - midRatio).coerceIn(0f, 1f)
            DominantNoiseBand.ROAD_MID -> (midRatio - lowRatio).coerceIn(0f, 1f)
            DominantNoiseBand.IDLE_LOW -> (lowRatio - highRatio).coerceIn(0f, 1f)
            DominantNoiseBand.MUSIC_BROAD -> (highRatio - lowRatio).coerceIn(0f, 1f)
            DominantNoiseBand.MIXED -> 0.35f
        }

        return NoiseBandClassification(
            dominantBand = dominantBand,
            lowEnergyRatio = lowRatio,
            midEnergyRatio = midRatio,
            highEnergyRatio = highRatio,
            confidence = confidence,
            nvhFocus = nvh.focus,
            nvhTargetHzLabel = nvh.targetHzLabel,
            nvhSuppressHighAnti = nvh.suppressHighAnti
        )
    }

    fun bandGains(classification: NoiseBandClassification): BandGains {
        val base = when (classification.dominantBand) {
            DominantNoiseBand.IDLE_LOW -> BandGains(low = 1f, mid = 0.15f, high = 0f)
            // 路噪：全力 low；輪噪 mid 次之；風切 high 永不開（產品政策）
            DominantNoiseBand.ROAD_LOW -> BandGains(low = 1f, mid = 0.35f, high = 0f)
            DominantNoiseBand.ROAD_MID -> BandGains(low = 0.85f, mid = 0.65f, high = 0f)  // tire 200-350 class
            DominantNoiseBand.MUSIC_BROAD -> BandGains(low = 0.7f, mid = 0.3f, high = 0f)
            DominantNoiseBand.MIXED -> BandGains(low = 0.9f, mid = 0.3f, high = 0f)
        }
        val focus = NvhFocusResult(
            focus = classification.nvhFocus,
            confidence = classification.confidence,
            targetHzLabel = classification.nvhTargetHzLabel,
            lowPriority = when (classification.nvhFocus) {
                NvhFocusClass.ROAD_RUMBLE -> 1f
                NvhFocusClass.TIRE_NOISE -> 1f
                NvhFocusClass.WIND_SHEAR -> 0.35f
                NvhFocusClass.MIXED_CABIN -> 0.85f
                NvhFocusClass.IDLE -> 0.2f
            },
            midPriority = when (classification.nvhFocus) {
                NvhFocusClass.TIRE_NOISE -> 0.55f
                NvhFocusClass.ROAD_RUMBLE -> 0.3f
                NvhFocusClass.WIND_SHEAR -> 0.05f
                NvhFocusClass.MIXED_CABIN -> 0.25f
                NvhFocusClass.IDLE -> 0.05f
            },
            highPriority = 0f,
            suppressHighAnti = true,
            preferStructuralFf = true
        )
        // Product rule: high always 0 — never chase wind/hiss with adaptive ANC
        return CabinNvhFocus.applyToBandGains(base, focus).copy(high = 0f)
    }

    private fun bandEnergies(spectrum: FloatArray, sampleRate: Int): Triple<Float, Float, Float> {
        val nyquist = sampleRate / 2f
        var low = 0f
        var mid = 0f
        var high = 0f

        spectrum.forEachIndexed { index, magnitude ->
            val freq = (index + 0.5f) * nyquist / spectrum.size
            when {
                // Product split: road rumble / deep structure
                freq < CabinNvhFocus.ROAD_HI_HZ -> low += magnitude
                // Tire tread + cabin mid-rumble (200–500 includes Skoda 200–350)
                freq < CabinNvhFocus.WIND_LO_HZ -> mid += magnitude
                // Wind shear / aero hiss / music treble — do not treat as cancel target
                else -> high += magnitude
            }
        }

        return Triple(low, mid, high)
    }
}

@Keep
data class BandGains(
    val low: Float,
    val mid: Float,
    val high: Float
)