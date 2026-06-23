package com.example.caranc.shared.model

import androidx.annotation.Keep

@Keep
object ProfileAgingMonitor {
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    const val ENERGY_DRIFT_RATIO = 5.0
    const val MIN_ENERGY_FOR_DRIFT = 1.5
    /** Normalized RMS -> mean |short| scale used during chirp calibration. */
    private const val RMS_TO_CALIBRATION_ENERGY = 32768.0

    @Keep
    data class AgingAssessment(
        val shouldRecalibrate: Boolean,
        val ageMs: Long,
        val ageStale: Boolean,
        val energyDrift: Boolean,
        val driftRatio: Double,
        val reason: String
    )

    fun assess(
        model: CabinTransferModel,
        currentBlockEnergy: Double,
        nowEpochMs: Long = currentEpochMs()
    ): AgingAssessment {
        val ageMs = (nowEpochMs - model.calibratedAtEpochMs).coerceAtLeast(0L)
        val ageStale = ageMs > MAX_AGE_MS

        val baseline = model.avgCalibrationEnergy.coerceAtLeast(MIN_ENERGY_FOR_DRIFT)
        val comparableEnergy = currentBlockEnergy * RMS_TO_CALIBRATION_ENERGY
        val driftRatio = if (comparableEnergy > 0.0) {
            comparableEnergy / baseline
        } else {
            1.0
        }
        val energyDrift = driftRatio > ENERGY_DRIFT_RATIO

        val shouldRecalibrate = ageStale || (energyDrift && model.avgCalibrationEnergy > MIN_ENERGY_FOR_DRIFT)
        val reason = when {
            ageStale && energyDrift -> "age_and_energy_drift"
            ageStale -> "profile_age_stale"
            energyDrift -> "energy_drift"
            else -> "ok"
        }

        return AgingAssessment(
            shouldRecalibrate = shouldRecalibrate,
            ageMs = ageMs,
            ageStale = ageStale,
            energyDrift = energyDrift,
            driftRatio = driftRatio,
            reason = reason
        )
    }
}