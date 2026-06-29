package com.example.caranc.shared

data class VehicleSpeedSnapshot(
    val speedKmh: Float,
    val valid: Boolean,
    val accuracyMeters: Float,
    val source: String,
    // IMU for rumble feedforward + NVH crowdsourced map (Road Preview / predictive ANC moat)
    // linearAccelMagnitude: sqrt(x^2+y^2+z^2) from TYPE_LINEAR_ACCELERATION, as road vibration proxy (immune to acoustic feedback)
    val linearAccelMagnitude: Float = 0f,
    val accelSource: String = "none",
    // Coarse position for dynamic NVH map / crowdsourced road noise + vehicle aging acoustic DB (Waze-like for NVH).
    // Quantized to ~0.001° (~111m) for privacy (road segment level, not exact spot). Enables pre-loading S(z)/VSS for predicted rough segments.
    // Only populated when GPS valid; 0/0 when unavailable. Logs exportable for future anonymous aggregation.
    val coarseLat: Float = 0f,
    val coarseLon: Float = 0f,
    // Roughness proxy for segment (accel or future combined with varEma). Used for predictive boost / profile selection.
    val roughness: Float = 0f
) {
    companion object {
        fun invalid(): VehicleSpeedSnapshot = VehicleSpeedSnapshot(
            speedKmh = 0f,
            valid = false,
            accuracyMeters = 0f,
            source = "none",
            linearAccelMagnitude = 0f,
            accelSource = "none",
            coarseLat = 0f,
            coarseLon = 0f,
            roughness = 0f
        )
    }
}