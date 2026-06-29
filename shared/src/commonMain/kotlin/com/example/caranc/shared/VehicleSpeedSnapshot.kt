package com.example.caranc.shared

data class VehicleSpeedSnapshot(
    val speedKmh: Float,
    val valid: Boolean,
    val accuracyMeters: Float,
    val source: String,
    // IMU prototype for rumble feedforward (only logging for now, not fed to ANC yet)
    // linearAccelMagnitude: sqrt(x^2+y^2+z^2) from TYPE_LINEAR_ACCELERATION, as road vibration proxy (immune to acoustic feedback)
    val linearAccelMagnitude: Float = 0f,
    val accelSource: String = "none"
) {
    companion object {
        fun invalid(): VehicleSpeedSnapshot = VehicleSpeedSnapshot(
            speedKmh = 0f,
            valid = false,
            accuracyMeters = 0f,
            source = "none",
            linearAccelMagnitude = 0f,
            accelSource = "none"
        )
    }
}