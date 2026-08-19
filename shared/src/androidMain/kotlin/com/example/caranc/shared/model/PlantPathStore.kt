package com.example.caranc.shared.model

import android.content.Context

/**
 * 1.2.9 P2: persist measured plant electrical delay (AA track+framework dominant)
 * separately from short cabin IR ŝ in [CabinProfileStore].
 *
 * 1.2.12: also persist winning boom polarity (+1/−1) from road A/B.
 * 1.2.14: default polarity **−1** (cabin fair A/B preference); cabin proxy beats plant residual.
 *
 * Keyed by profileId + routeLabel so USB AA vs local can differ.
 */
object PlantPathStore {

    private const val PREFS = "plant_path_store"
    private const val KEY_PREFIX = "plant_"

    data class PlantPathSnapshot(
        val profileId: String,
        val routeLabel: String,
        val electricalDelaySamples: Int,
        val probeCorrMs: Float,
        val cabinAcousticDelaySamples: Int,
        val updatedEpochMs: Long,
        /** Boom send polarity: +1 or −1 (1.2.14 default −1). */
        val boomPolarity: Float = -1f
    )

    fun save(context: Context, snap: PlantPathSnapshot) {
        prefs(context).edit()
            .putString(key(snap.profileId, snap.routeLabel), serialize(snap))
            .apply()
    }

    fun load(context: Context, profileId: String, routeLabel: String): PlantPathSnapshot? {
        val raw = prefs(context).getString(key(profileId, routeLabel), null) ?: return null
        return deserialize(raw)
    }

    /** Prefer exact route; fall back to any route for profile. */
    fun loadBest(context: Context, profileId: String, routeLabel: String): PlantPathSnapshot? {
        load(context, profileId, routeLabel)?.let { return it }
        val p = prefs(context)
        val prefix = KEY_PREFIX + profileId + "_"
        return p.all.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (_, v) -> (v as? String)?.let { deserialize(it) } }
            .maxByOrNull { it.updatedEpochMs }
    }

    private fun key(profileId: String, routeLabel: String): String {
        val r = routeLabel.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").take(48)
        return KEY_PREFIX + profileId + "_" + r.ifBlank { "default" }
    }

    private fun serialize(s: PlantPathSnapshot): String =
        listOf(
            s.profileId,
            s.routeLabel,
            s.electricalDelaySamples.toString(),
            s.probeCorrMs.toString(),
            s.cabinAcousticDelaySamples.toString(),
            s.updatedEpochMs.toString(),
            s.boomPolarity.toString()
        ).joinToString("|")

    private fun deserialize(raw: String): PlantPathSnapshot? {
        val p = raw.split("|")
        if (p.size < 6) return null
        return try {
            PlantPathSnapshot(
                profileId = p[0],
                routeLabel = p[1],
                electricalDelaySamples = p[2].toInt(),
                probeCorrMs = p[3].toFloat(),
                cabinAcousticDelaySamples = p[4].toInt(),
                updatedEpochMs = p[5].toLong(),
                boomPolarity = if (p.size >= 7) {
                    val v = p[6].toFloat()
                    if (v >= 0f) 1f else -1f
                } else {
                    -1f // 1.2.14: missing field → cabin-preferred default
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
