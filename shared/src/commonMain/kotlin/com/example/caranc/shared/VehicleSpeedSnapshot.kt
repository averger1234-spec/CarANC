package com.example.caranc.shared

data class VehicleSpeedSnapshot(
    val speedKmh: Float,
    val valid: Boolean,
    val accuracyMeters: Float,
    val source: String
) {
    companion object {
        fun invalid(): VehicleSpeedSnapshot = VehicleSpeedSnapshot(
            speedKmh = 0f,
            valid = false,
            accuracyMeters = 0f,
            source = "none"
        )
    }
}