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

        val dominantBand = when {
            isCallActive -> DominantNoiseBand.MUSIC_BROAD
            // Subagent3 Extended #7 + iter variant: classifier tweak for pure ROAD_MID even with music.
            // If speed>28 + (low+mid energy decent for rumble 200-350 focus) -> force ROAD_MID/LOW even if music=true.
            // Calib to real log weakness (MUSIC_BROAD dominant at low speed/energy). Lowered thresh + or-energy for more pure shift.
            // Guarded by speedValid + energy ratios (no change for low speed <28 or low rumble energy).
            // Prioritizes dominant shift to rumble for deeper mid contrib (effMidMu 0.6+) when strict low-music rough 50+.
            // Only fall to MUSIC_BROAD if high truly dominant (>0.65) or insufficient rumble energy.
            speedValid && speedKmh > 28f && (lowRatio + midRatio) >= 0.30f && (midRatio >= 0.20f || (lowRatio + midRatio) >= 0.48f) ->
                DominantNoiseBand.ROAD_MID
            speedValid && speedKmh > 28f && (lowRatio + midRatio) >= 0.30f && lowRatio >= 0.23f ->
                DominantNoiseBand.ROAD_LOW
            isMusicActive && highRatio > 0.65f -> DominantNoiseBand.MUSIC_BROAD
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
            DominantNoiseBand.MUSIC_BROAD -> BandGains(low = 0.55f, mid = 0.15f, high = 0.03f)
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