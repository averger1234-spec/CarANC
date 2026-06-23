package com.example.caranc.shared.model

import androidx.annotation.Keep
import com.example.caranc.shared.ImpulseResponseEstimate

@Keep
data class ResonancePeak(
    val frequencyHz: Float,
    val magnitude: Float,
    val index: Int
)

@Keep
data class CabinTransferModel(
    val profileId: String,
    val sampleRate: Int,
    val secondaryPath: FloatArray,
    val acousticDelaySamples: Int,
    val resonancePeaks: List<ResonancePeak>,
    val calibratedAtEpochMs: Long,
    val avgCalibrationEnergy: Double,
    val mimoProfile: CabinMimoProfile? = null
) {
    val resonancePeaksHz: List<Float> = resonancePeaks.map { it.frequencyHz }
    val mimoEnabled: Boolean = mimoProfile?.mimoEnabled == true
    val zoneCount: Int = mimoProfile?.activeZones?.size ?: 1

    fun isCompatibleWith(sampleRate: Int): Boolean = this.sampleRate == sampleRate

    fun effectiveSecondaryPath(): FloatArray =
        mimoProfile?.blendedSecondaryPath() ?: secondaryPath

    @Keep
    companion object {
        const val DEFAULT_PROFILE_ID = "skoda_octavia"

        fun fromImpulseEstimate(
            profileId: String,
            sampleRate: Int,
            estimate: ImpulseResponseEstimate,
            avgEnergy: Double,
            resonancePeaks: List<ResonancePeak>
        ): CabinTransferModel {
            val secondaryPath = estimate.model.copyOf()
            return CabinTransferModel(
                profileId = profileId,
                sampleRate = sampleRate,
                secondaryPath = secondaryPath,
                acousticDelaySamples = estimate.acousticDelaySamples,
                resonancePeaks = resonancePeaks,
                calibratedAtEpochMs = currentEpochMs(),
                avgCalibrationEnergy = avgEnergy,
                mimoProfile = CabinMimoProfile.fromSinglePath(
                    secondaryPath = secondaryPath,
                    acousticDelaySamples = estimate.acousticDelaySamples,
                    driverFocused = true
                )
            )
        }

        fun fallback(profileId: String, sampleRate: Int): CabinTransferModel {
            val secondaryPath = FloatArray(64) { index -> if (index == 0) 1f else 0f }
            return CabinTransferModel(
                profileId = profileId,
                sampleRate = sampleRate,
                secondaryPath = secondaryPath,
                acousticDelaySamples = 0,
                resonancePeaks = emptyList(),
                calibratedAtEpochMs = currentEpochMs(),
                avgCalibrationEnergy = 0.0,
                mimoProfile = CabinMimoProfile.fromSinglePath(
                    secondaryPath = secondaryPath,
                    acousticDelaySamples = 0,
                    driverFocused = true
                )
            )
        }

        fun withMimoProfile(
            base: CabinTransferModel,
            mimoProfile: CabinMimoProfile
        ): CabinTransferModel = base.copy(mimoProfile = mimoProfile)
    }
}

internal expect fun currentEpochMs(): Long