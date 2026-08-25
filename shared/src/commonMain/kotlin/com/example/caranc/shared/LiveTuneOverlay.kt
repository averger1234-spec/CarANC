package com.example.caranc.shared

/**
 * 1.2.32: disk overlay so a host can retune the running DSP without SharedPreferences
 * cache or App restart. Android: `filesDir/anc_live_tune.properties`.
 * iOS: `Documents/anc_live_tune.properties`.
 *
 * Absent key = leave prefs/auto. Present key wins until the file is deleted.
 */
object LiveTuneOverlay {
    const val FILE_NAME = "anc_live_tune.properties"

    @kotlin.concurrent.Volatile
    var forceBoomPolarity: Float? = null
        private set

    @kotlin.concurrent.Volatile
    var forceNvhFocus: String? = null
        private set

    @kotlin.concurrent.Volatile
    var boomOpenScale: Float? = null
        private set

    @kotlin.concurrent.Volatile
    var userAncGain: Float? = null
        private set

    @kotlin.concurrent.Volatile
    var rawText: String = ""
        private set

    fun clear() {
        forceBoomPolarity = null
        forceNvhFocus = null
        boomOpenScale = null
        userAncGain = null
        rawText = ""
    }

    fun parse(text: String) {
        rawText = text
        var pol: Float? = null
        var nvh: String? = null
        var scale: Float? = null
        var gain: Float? = null
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val eq = t.indexOf('=')
            if (eq <= 0) return@forEach
            val k = t.substring(0, eq).trim()
            val v = t.substring(eq + 1).trim()
            when (k) {
                "forceBoomPolarity" -> pol = v.toFloatOrNull()
                "forceNvhFocus" -> nvh = v.ifBlank { null }
                "boomOpenScale" -> scale = v.toFloatOrNull()
                "userAncGain" -> gain = v.toFloatOrNull()
            }
        }
        forceBoomPolarity = pol
        forceNvhFocus = nvh
        boomOpenScale = scale?.coerceIn(0.15f, 1f)
        userAncGain = gain?.coerceIn(0f, 1f)
    }
}
