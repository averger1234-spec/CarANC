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
    val confidence: Float
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
        isCallActive: Boolean
    ): NoiseBandClassification {
        if (spectrum.isEmpty() || sampleRate <= 0) {
            return NoiseBandClassification(
                dominantBand = DominantNoiseBand.MIXED,
                lowEnergyRatio = 0.33f,
                midEnergyRatio = 0.33f,
                highEnergyRatio = 0.34f,
                confidence = 0f
            )
        }

        val (lowEnergy, midEnergy, highEnergy) = bandEnergies(spectrum, sampleRate)
        val total = (lowEnergy + midEnergy + highEnergy).coerceAtLeast(1e-6f)
        val lowRatio = lowEnergy / total
        val midRatio = midEnergy / total
        val highRatio = highEnergy / total
        val lowMidRatio = lowRatio + midRatio

        // BREAKTHROUGH for AA music + Skoda rumble (2026-06-29 real log data):
        // In cabin AA playback, music energy swamps total power (highRatio~0.999, low+mid max observed 0.071 even @90kmh rough).
        // Original 0.30 thresh never triggers force-ROAD even with driving. 
        // Lowered to 0.06 + relaxed subconds so "some rumble presence" + speed + musicLow intent can force ROAD_MID for mid boost.
        // Rumble presence now wins over pure high-dominant for adaptation goal (protection still handled in gains/mode).
        val hasDecentRumbleForMid = lowMidRatio >= 0.06f

        val dominantBand = when {
            isCallActive -> DominantNoiseBand.MUSIC_BROAD
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
            confidence = confidence
        )
    }

    fun bandGains(classification: NoiseBandClassification): BandGains {
        return when (classification.dominantBand) {
            DominantNoiseBand.IDLE_LOW -> BandGains(low = 1f, mid = 0.2f, high = 0.05f)
            DominantNoiseBand.ROAD_LOW -> BandGains(low = 1f, mid = 0.28f, high = 0.08f)
            DominantNoiseBand.ROAD_MID -> BandGains(low = 0.7f, mid = 0.55f, high = 0.12f)  // iter4+S3: higher mid for 300-350 rumble focus (pure ROAD_MID)
            DominantNoiseBand.MUSIC_BROAD -> BandGains(low = 0.55f, mid = 0.28f, high = 0.03f)  // raised 0.15->0.28 as safer fallback for rumble-under-music (AA cabin); still < ROAD_MID 0.55
            DominantNoiseBand.MIXED -> BandGains(low = 0.85f, mid = 0.25f, high = 0.06f)
        }
    }

    private fun bandEnergies(spectrum: FloatArray, sampleRate: Int): Triple<Float, Float, Float> {
        val nyquist = sampleRate / 2f
        var low = 0f
        var mid = 0f
        var high = 0f

        spectrum.forEachIndexed { index, magnitude ->
            val freq = (index + 0.5f) * nyquist / spectrum.size
            when {
                // Subagent3: adjusted 300->250 / 800->850 for better 250-350Hz (rumble peak) capture into mid band; center focus 300-350
                freq < 250f -> low += magnitude
                freq < 850f -> mid += magnitude
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