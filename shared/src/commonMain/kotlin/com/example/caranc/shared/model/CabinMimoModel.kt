package com.example.caranc.shared.model

import androidx.annotation.Keep

/**
 * AMBEO-lite 試作：以多區域 secondary path 加權混合，模擬 MIMO 車廂聲場。
 * 單喇叭輸出時以 driver 為主、其他區域為衰減副本。
 */
@Keep
enum class CabinZoneId(val label: String, val defaultWeight: Float) {
    DRIVER("driver", 1.0f),
    PASSENGER_FRONT("passenger_front", 0.65f),
    REAR_LEFT("rear_left", 0.45f),
    REAR_RIGHT("rear_right", 0.45f);

    companion object {
        val calibrationOrder = listOf(DRIVER, PASSENGER_FRONT, REAR_LEFT, REAR_RIGHT)
    }
}

@Keep
data class CabinZonePath(
    val zoneId: CabinZoneId,
    val secondaryPath: FloatArray,
    val acousticDelaySamples: Int,
    val blendWeight: Float = zoneId.defaultWeight
) {
    fun effectiveWeight(mimoEnabled: Boolean): Float =
        if (mimoEnabled) blendWeight else if (zoneId == CabinZoneId.DRIVER) 1f else 0f
}

@Keep
data class CabinMimoProfile(
    val zones: List<CabinZonePath>,
    val mimoEnabled: Boolean = true,
    val driverFocused: Boolean = true
) {
    val activeZones: List<CabinZonePath> = zones.filter { it.effectiveWeight(mimoEnabled) > 0f }

    fun blendedSecondaryPath(): FloatArray {
        if (zones.isEmpty()) return FloatArray(64) { if (it == 0) 1f else 0f }
        val length = zones.maxOf { it.secondaryPath.size }
        val blended = FloatArray(length) { 0f }
        var weightSum = 0f
        for (zone in zones) {
            val w = zone.effectiveWeight(mimoEnabled)
            if (w <= 0f) continue
            weightSum += w
            for (i in zone.secondaryPath.indices) {
                blended[i] += zone.secondaryPath[i] * w
            }
        }
        if (weightSum > 0f) {
            for (i in blended.indices) blended[i] /= weightSum
        }
        return blended
    }

    @Keep
    companion object {
        fun fromSinglePath(
            secondaryPath: FloatArray,
            acousticDelaySamples: Int,
            driverFocused: Boolean = true
        ): CabinMimoProfile {
            val zones = CabinZoneId.calibrationOrder.map { zoneId ->
                val attenuation = when (zoneId) {
                    CabinZoneId.DRIVER -> 1.0f
                    CabinZoneId.PASSENGER_FRONT -> 0.82f
                    CabinZoneId.REAR_LEFT -> 0.58f
                    CabinZoneId.REAR_RIGHT -> 0.58f
                }
                val delayOffset = when (zoneId) {
                    CabinZoneId.DRIVER -> 0
                    CabinZoneId.PASSENGER_FRONT -> 2
                    CabinZoneId.REAR_LEFT -> 5
                    CabinZoneId.REAR_RIGHT -> 5
                }
                val zonePath = FloatArray(secondaryPath.size) { index ->
                    val src = (index - delayOffset).coerceAtLeast(0)
                    secondaryPath[src] * attenuation
                }
                CabinZonePath(
                    zoneId = zoneId,
                    secondaryPath = zonePath,
                    acousticDelaySamples = acousticDelaySamples + delayOffset,
                    blendWeight = zoneId.defaultWeight
                )
            }
            return CabinMimoProfile(
                zones = zones,
                mimoEnabled = !driverFocused,
                driverFocused = driverFocused
            )
        }

        fun deriveVirtualZone(
            basePath: FloatArray,
            baseDelay: Int,
            zoneId: CabinZoneId
        ): CabinZonePath {
            val profile = fromSinglePath(basePath, baseDelay, driverFocused = zoneId == CabinZoneId.DRIVER)
            return profile.zones.first { it.zoneId == zoneId }
        }
    }
}