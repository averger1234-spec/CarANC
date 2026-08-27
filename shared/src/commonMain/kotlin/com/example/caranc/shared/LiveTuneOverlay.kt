package com.example.caranc.shared

/**
 * Disk overlay so a host can retune the **running** DSP without App restart.
 * Android: `filesDir/anc_live_tune.properties` (AudioEngine polls ~40 blocks).
 * iOS: `Documents/anc_live_tune.properties`.
 *
 * Absent key = compiled default. Present key wins until the file is deleted.
 *
 * 1.2.38: send LPF / shelf / high-lat / muteSand / lfSendOnly are hot too —
 * do not require rebuild for sand vs boom path.
 */
object LiveTuneOverlay {
    const val FILE_NAME = "anc_live_tune.properties"
    const val MIN_BOOM_OPEN_SCALE = 0.15f
    const val MAX_BOOM_OPEN_SCALE = 2.0f

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

    /** AA send LPF cutoff Hz. Default compiled 160. */
    @kotlin.concurrent.Volatile
    var sendLpfHz: Float? = null
        private set

    /** AA LF shelf mix (0 = off, 2 ≈ +10 dB). */
    @kotlin.concurrent.Volatile
    var shelfBoost: Float? = null
        private set

    /** ms; estimatedLatency >= this is HIGH_LAT. Compiled default 100. */
    @kotlin.concurrent.Volatile
    var highLatMs: Float? = null
        private set

    /** 1 = force mute Wiener/FDAF/fixed-bank sand. 0 = never mute. */
    @kotlin.concurrent.Volatile
    var muteSand: Float? = null
        private set

    /** 1 = ROAD send through ~90 Hz boom LPF. 0 = skip that LPF. */
    @kotlin.concurrent.Volatile
    var lfSendOnly: Float? = null
        private set

    @kotlin.concurrent.Volatile
    var rawText: String = ""
        private set

    fun clear() {
        forceBoomPolarity = null
        forceNvhFocus = null
        boomOpenScale = null
        userAncGain = null
        sendLpfHz = null
        shelfBoost = null
        highLatMs = null
        muteSand = null
        lfSendOnly = null
        rawText = ""
    }

    fun parse(text: String) {
        rawText = text
        var pol: Float? = null
        var nvh: String? = null
        var scale: Float? = null
        var gain: Float? = null
        var lpf: Float? = null
        var shelf: Float? = null
        var hl: Float? = null
        var sand: Float? = null
        var lfOnly: Float? = null
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
                "sendLpfHz" -> lpf = v.toFloatOrNull()
                "shelfBoost" -> shelf = v.toFloatOrNull()
                "highLatMs" -> hl = v.toFloatOrNull()
                "muteSand" -> sand = parseFlag(v)
                "lfSendOnly" -> lfOnly = parseFlag(v)
            }
        }
        forceBoomPolarity = pol
        forceNvhFocus = nvh
        boomOpenScale = scale?.coerceIn(MIN_BOOM_OPEN_SCALE, MAX_BOOM_OPEN_SCALE)
        userAncGain = gain?.coerceIn(0f, 1f)
        sendLpfHz = lpf?.coerceIn(70f, 400f)
        shelfBoost = shelf?.coerceIn(0f, 4f)
        highLatMs = hl?.coerceIn(60f, 250f)
        muteSand = sand
        lfSendOnly = lfOnly
    }

    private fun parseFlag(v: String): Float? {
        return when (v.lowercase()) {
            "1", "true", "yes", "on" -> 1f
            "0", "false", "no", "off" -> 0f
            else -> v.toFloatOrNull()?.let { if (it >= 0.5f) 1f else 0f }
        }
    }
}
