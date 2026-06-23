package com.example.caranc.shared.model

import android.content.Context
import com.example.caranc.shared.AncTestPreferences

object CabinProfileStore {

    private const val PREFS_NAME = "cabin_profile_store"
    private const val KEY_PREFIX = "profile_"

    fun resolveProfileId(context: Context): String {
        val vehicle = AncTestPreferences.getEnvironment(context).vehicleModel
            .trim()
            .lowercase()
            .replace(" ", "_")
        return vehicle.ifBlank { CabinTransferModel.DEFAULT_PROFILE_ID }
    }

    fun save(context: Context, model: CabinTransferModel) {
        prefs(context).edit()
            .putString(key(model.profileId), model.serialize())
            .apply()
    }

    fun load(context: Context, profileId: String): CabinTransferModel? {
        val raw = prefs(context).getString(key(profileId), null) ?: return null
        return deserializeCabinProfile(raw)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(profileId: String) = KEY_PREFIX + profileId
}

private fun CabinTransferModel.serialize(): String {
    val peaks = resonancePeaks.joinToString(";") { peak ->
        "${peak.frequencyHz},${peak.magnitude},${peak.index}"
    }
    val sHat = secondaryPath.joinToString(",")
    val mimo = serializeMimoProfile(mimoProfile)
    return listOf(
        profileId,
        sampleRate.toString(),
        acousticDelaySamples.toString(),
        calibratedAtEpochMs.toString(),
        avgCalibrationEnergy.toString(),
        peaks,
        sHat,
        mimo
    ).joinToString("|")
}

private fun serializeMimoProfile(profile: CabinMimoProfile?): String {
    if (profile == null) return ""
    val zones = profile.zones.joinToString(";") { zone ->
        val path = zone.secondaryPath.joinToString(",")
        "${zone.zoneId.name},${zone.acousticDelaySamples},${zone.blendWeight},$path"
    }
    return "${profile.mimoEnabled},${profile.driverFocused},$zones"
}

private fun deserializeMimoProfile(raw: String, fallbackPath: FloatArray, fallbackDelay: Int): CabinMimoProfile? {
    if (raw.isBlank()) return null
    val segments = raw.split(",", limit = 3)
    if (segments.size < 3) return null
    return try {
        val mimoEnabled = segments[0].toBooleanStrictOrNull() ?: false
        val driverFocused = segments[1].toBooleanStrictOrNull() ?: true
        val zoneEntries = segments[2].split(";").filter { it.isNotBlank() }
        val zones = zoneEntries.mapNotNull { entry ->
            val zoneParts = entry.split(",", limit = 4)
            if (zoneParts.size < 4) return@mapNotNull null
            val zoneId = runCatching { CabinZoneId.valueOf(zoneParts[0]) }.getOrNull() ?: return@mapNotNull null
            CabinZonePath(
                zoneId = zoneId,
                acousticDelaySamples = zoneParts[1].toInt(),
                blendWeight = zoneParts[2].toFloat(),
                secondaryPath = zoneParts[3].split(",").map { it.toFloat() }.toFloatArray()
            )
        }
        if (zones.isEmpty()) {
            CabinMimoProfile.fromSinglePath(fallbackPath, fallbackDelay, driverFocused)
        } else {
            CabinMimoProfile(zones = zones, mimoEnabled = mimoEnabled, driverFocused = driverFocused)
        }
    } catch (_: Exception) {
        null
    }
}

private fun deserializeCabinProfile(raw: String): CabinTransferModel? {
    val parts = raw.split("|")
    if (parts.size < 7) return null

    return try {
        val peaks = if (parts[5].isBlank()) {
            emptyList()
        } else {
            parts[5].split(";").mapNotNull { entry ->
                val values = entry.split(",")
                if (values.size < 3) return@mapNotNull null
                ResonancePeak(
                    frequencyHz = values[0].toFloat(),
                    magnitude = values[1].toFloat(),
                    index = values[2].toInt()
                )
            }
        }

        val secondaryPath = parts[6].split(",").map { it.toFloat() }.toFloatArray()
        val acousticDelay = parts[2].toInt()
        val mimoProfile = if (parts.size >= 8) {
            deserializeMimoProfile(parts[7], secondaryPath, acousticDelay)
        } else {
            null
        }

        CabinTransferModel(
            profileId = parts[0],
            sampleRate = parts[1].toInt(),
            acousticDelaySamples = acousticDelay,
            resonancePeaks = peaks,
            calibratedAtEpochMs = parts[3].toLong(),
            avgCalibrationEnergy = parts[4].toDouble(),
            secondaryPath = secondaryPath,
            mimoProfile = mimoProfile ?: CabinMimoProfile.fromSinglePath(
                secondaryPath = secondaryPath,
                acousticDelaySamples = acousticDelay,
                driverFocused = true
            )
        )
    } catch (_: Exception) {
        null
    }
}