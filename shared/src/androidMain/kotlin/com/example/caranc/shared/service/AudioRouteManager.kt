package com.example.caranc.shared.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

data class AudioRouteInfo(
    val routeLabel: String,
    val inputDeviceName: String?,
    val outputDeviceName: String?,
    val preferredOutput: AudioDeviceInfo?,
    val preferredInput: AudioDeviceInfo?,
    val audioSource: Int,
    val availableOutputs: List<String> = emptyList(),
    val availableInputs: List<String> = emptyList(),
    /**
     * #9 / A-fix: only true for likely **Bluetooth** wireless AA / A2DP.
     * Phone USB AA often exposes only remote_submix — that is **projection_submix**, not wireless.
     */
    val wirelessAaSuspected: Boolean = false,
    /** True when USB/BUS car sink is enumerated. */
    val wiredCarPathAvailable: Boolean = false,
    /**
     * A-fix link class for logs:
     * local | wired_usb | projection_submix | wireless_bt | aa_unknown
     */
    val aaLinkType: String = "local"
)

data class RouteApplyResult(
    val route: AudioRouteInfo,
    val outputPreferredApplied: Boolean,
    val inputPreferredApplied: Boolean,
    val routedOutputType: Int?,
    val routedOutputName: String?,
    val routedInputType: Int?,
    val routedInputName: String?,
    val carSinkRouted: Boolean
) {
    fun toLogFields(): Map<String, Any?> = mapOf(
        "routeLabel" to route.routeLabel,
        "preferredOutput" to route.outputDeviceName,
        "preferredInput" to route.inputDeviceName,
        "outputPreferredApplied" to outputPreferredApplied,
        "inputPreferredApplied" to inputPreferredApplied,
        "routedOutput" to routedOutputName,
        "routedOutputType" to routedOutputType,
        "routedInput" to routedInputName,
        "routedInputType" to routedInputType,
        "carSinkRouted" to carSinkRouted,
        "availableOutputs" to route.availableOutputs,
        "availableInputs" to route.availableInputs,
        "wirelessAaSuspected" to route.wirelessAaSuspected,
        "wiredCarPathAvailable" to route.wiredCarPathAvailable,
        "aaLinkType" to route.aaLinkType
    )
}

class AudioRouteManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var lastFocusChangeCode = 0
    private var lastFocusRequestResultCode = 0
    private var deviceCallback: AudioDeviceCallback? = null
    private var routeChangeListener: ((RouteApplyResult) -> Unit)? = null

    // 1.2.20: ANC PCM prefers **car A2DP** (cabin 50Hz LINE on Octavia).
    // USB AA remote_submix still only hiss — HU mix HP. Do not send anti there if BT exists.
    private var aaSonificationRoutingFailed = false
    /** AA ANC always uses the MUSIC bus (1.2.19). Flag kept for log compatibility. */
    @Volatile
    var aaPreferMediaTrack: Boolean = true
    private var savedStreamMusicVolume: Int? = null
    @Volatile
    private var bassToneExclusive = false

    fun resetAaSonificationFallback() {
        aaSonificationRoutingFailed = false
        Log.i(TAG, "AA overlay fallback reset")
    }

    fun isAaSonificationFallbackActive(): Boolean = aaSonificationRoutingFailed

    /** Actual send usage (A2DP and AA both use MUSIC). */
    fun currentTrackUsageLabel(isAaConnected: Boolean): String = "USAGE_MEDIA"

    fun isA2dpRoute(route: AudioRouteInfo): Boolean {
        val t = route.preferredOutput?.type
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || route.routeLabel == "bluetooth_a2dp"
    }

    @Volatile
    var ancOutputGain: Float = 1f
        private set

    /**
     * #9: Prefer wired USB/BUS Android Auto only. When true (default), wireless AA
     * (BT A2DP projection / no USB car sink) is deprioritized and flagged.
     * Garage local mode (isAaConnected=false) is unaffected.
     */
    @Volatile
    var requireWiredAa: Boolean = true

    fun setRouteChangeListener(listener: ((RouteApplyResult) -> Unit)?) {
        routeChangeListener = listener
    }

    fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || deviceCallback != null) return
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                Log.i(TAG, "Audio devices added: ${addedDevices.joinToString { describeDevice(it) }}")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                Log.i(TAG, "Audio devices removed: ${removedDevices.joinToString { describeDevice(it) }}")
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback!!, mainHandler)
    }

    fun unregisterDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        routeChangeListener = null
    }

    fun resolveRoute(isAaConnected: Boolean): AudioRouteInfo {
        val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } else {
            emptyArray()
        }
        val inputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } else {
            emptyArray()
        }

        val preferredOutput = pickPreferredOutput(outputs, isAaConnected)
        val preferredInput = pickPreferredInput(inputs, isAaConnected)
        val routeLabel = routeLabelFor(preferredOutput, isAaConnected)

        val sinks = outputs.filter { it.isSink }
        val wiredAvailable = sinks.any { isWiredCarSink(it) }
        val outType = preferredOutput?.type
        val isBt = outType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            routeLabel.contains("bluetooth", ignoreCase = true)
        val isSubmix = outType == AudioDeviceInfo.TYPE_REMOTE_SUBMIX ||
            routeLabel == "remote_submix" ||
            routeLabel.contains("submix", ignoreCase = true)

        // A-fix: remote_submix + AA + not BT = normal phone→car USB/Wi‑Fi projection path, NOT "wireless suspected".
        // Only flag wireless when BT A2DP (or explicit bluetooth label) is the chosen sink.
        val wirelessSuspected = isAaConnected && isBt
        val aaLinkType = when {
            !isAaConnected -> "local"
            wiredAvailable -> "wired_usb"
            isBt -> "wireless_bt"
            isSubmix || routeLabel.startsWith("android_auto") -> "projection_submix"
            else -> "aa_unknown"
        }

        if (routeLabel == "bluetooth_a2dp") {
            Log.i(TAG, "ANC_BASS_SINK=A2DP (50Hz path). label=$routeLabel aa=$isAaConnected")
        } else if (isAaConnected && aaLinkType == "projection_submix") {
            Log.i(
                TAG,
                "AA_PROJECTION_SUBMIX: typical phone USB/Wi‑Fi AA path (not flagged wireless). " +
                    "label=$routeLabel wiredUsbEnum=$wiredAvailable"
            )
        }

        val audioSource = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                android.media.MediaRecorder.AudioSource.UNPROCESSED
            else ->
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        return AudioRouteInfo(
            routeLabel = routeLabel,
            inputDeviceName = preferredInput?.productName?.toString()
                ?: inputs.firstOrNull()?.productName?.toString(),
            outputDeviceName = preferredOutput?.productName?.toString()
                ?: outputs.firstOrNull { it.isSink }?.productName?.toString(),
            preferredOutput = preferredOutput,
            preferredInput = preferredInput,
            audioSource = audioSource,
            availableOutputs = describeDevices(outputs.filter { it.isSink }),
            availableInputs = describeDevices(inputs.filter { it.isSource }),
            wirelessAaSuspected = wirelessSuspected,
            wiredCarPathAvailable = wiredAvailable,
            aaLinkType = aaLinkType
        )
    }

    fun isWiredCarSink(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUS -> true
            else -> {
                val name = device.productName?.toString().orEmpty().lowercase()
                "usb" in name && device.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        }
    }

    fun buildTrackAudioAttributes(isAaConnected: Boolean): AudioAttributes = buildMediaAudioAttributes()

    /** AA music-bus attributes (ANC PCM + focus). */
    fun buildMediaAudioAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
        @Suppress("DEPRECATION")
        builder.setLegacyStreamType(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "ANC track attrs=USAGE_MEDIA CONTENT_TYPE_MUSIC STREAM_MUSIC")
        return builder.build()
    }

    @Deprecated("Use buildMediaAudioAttributes", ReplaceWith("buildMediaAudioAttributes()"))
    fun buildKeepAliveAudioAttributes(): AudioAttributes = buildMediaAudioAttributes()

    /** Hold MEDIA focus for AA *or* A2DP bass (car music DAC). */
    fun prepareRunningAudioMix(holdMediaFocus: Boolean): Boolean {
        ancOutputGain = 1f
        if (holdMediaFocus) {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        return if (holdMediaFocus) {
            requestRunningMediaFocus(isAaConnected = true)
        } else {
            true
        }
    }

    /** True while ANC holds continuous MEDIA focus (AA path keep-alive). */
    fun isHoldingRunningFocus(): Boolean = hasAudioFocus

    /**
     * Path self-check: DELAYED is not a live speaker path, and LOSS is dead
     * even if a previous request was GRANTED.
     */
    fun isRunningMediaPathLive(): Boolean {
        if (!hasAudioFocus) return false
        if (lastFocusChangeCode == AudioManager.AUDIOFOCUS_LOSS) return false
        if (lastFocusRequestResultCode == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            return lastFocusChangeCode == AudioManager.AUDIOFOCUS_GAIN ||
                lastFocusChangeCode == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ||
                lastFocusChangeCode == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        return lastFocusRequestResultCode == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun lastFocusChange(): Int = lastFocusChangeCode

    fun lastFocusRequestResult(): Int = lastFocusRequestResultCode

    /**
     * Hold USAGE_MEDIA AudioFocus for the whole ANC session (AA).
     * GAIN: we *are* the media source (path test needs the bass bus at full level).
     * 5s engine refresh re-requests on LOSS (AA may flap).
     */
    fun requestRunningMediaFocus(isAaConnected: Boolean): Boolean {
        if (hasAudioFocus && focusRequest != null) return true

        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            lastFocusChangeCode = change
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasAudioFocus = false
                    ancOutputGain = 0.05f
                    Log.w(TAG, "AA running MEDIA focus LOSS — path likely dead until re-request")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    ancOutputGain = 0.15f
                    Log.w(TAG, "AA running MEDIA focus LOSS_TRANSIENT")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ancOutputGain = 0.45f
                    Log.i(TAG, "AA running MEDIA focus ducked")
                }
                AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                    hasAudioFocus = true
                    ancOutputGain = 1f
                    Log.i(TAG, "AA running MEDIA focus GAIN")
                }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mediaAttrs = buildMediaAudioAttributes()
            // Normal ANC: MAY_DUCK (keep user music). 50Hz path tone: GAIN so HU uses music DAC.
            val focusType = if (bassToneExclusive) {
                AudioManager.AUDIOFOCUS_GAIN
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }
            val request = AudioFocusRequest.Builder(focusType)
                .setAudioAttributes(mediaAttrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .build()
            focusRequest = request
            val result = audioManager.requestAudioFocus(request)
            lastFocusRequestResultCode = result
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(
                TAG,
                "requestRunningMediaFocus result=$result granted=$hasAudioFocus " +
                    "exclusiveBassTone=$bassToneExclusive"
            )
            hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                if (bassToneExclusive) AudioManager.AUDIOFOCUS_GAIN
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            lastFocusRequestResultCode = result
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasAudioFocus
        }
    }

    /**
     * During 50/80/120/200 path tones only: exclusive MUSIC so HU uses the music DAC.
     * Normal ANC stays MAY_DUCK so user music is not killed.
     */
    fun setBassToneExclusive(enable: Boolean) {
        if (bassToneExclusive == enable) return
        bassToneExclusive = enable
        Log.i(TAG, "setBassToneExclusive=$enable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
        focusRequest = null
        requestRunningMediaFocus(true)
    }

    /** Calibration chirp: reuse running focus if held; else short transient. */
    fun requestCalibrationAudioFocus(isAaConnected: Boolean = true): Boolean {
        if (hasAudioFocus) return true
        if (isAaConnected) return requestRunningMediaFocus(true)

        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            lastFocusChangeCode = change
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> ancOutputGain = 0.15f
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ancOutputGain = 0.35f
                AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> ancOutputGain = 1f
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = buildTrackAudioAttributes(isAaConnected = false)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(listener, mainHandler)
                .build()
            focusRequest = request
            val result = audioManager.requestAudioFocus(request)
            lastFocusRequestResultCode = result
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            lastFocusRequestResultCode = result
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasAudioFocus
        }
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
        focusRequest = null
        ancOutputGain = 1f
        Log.i(TAG, "abandonAudioFocus (running MEDIA focus released)")
    }

    /**
     * Phone STREAM_MUSIC scales USAGE_MEDIA PCM *before* AA encodes.
     * 1.2.18 logs showed 6/25 — 50Hz became inaudible even if HU passed bass.
     * Car knob is still the cabin volume; this only un-attenuates the send.
     */
    fun boostStreamMusicForAaSend() {
        if (savedStreamMusicVolume != null) return
        try {
            if (audioManager.isVolumeFixed) {
                Log.i(TAG, "STREAM_MUSIC volume fixed by policy; cannot boost")
                return
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedStreamMusicVolume = cur
            if (max > 0 && cur < max) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                Log.i(TAG, "STREAM_MUSIC boost $cur -> $max (restore on stop)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "STREAM_MUSIC boost failed: ${e.message}")
        }
    }

    fun restoreStreamMusicVolume() {
        val saved = savedStreamMusicVolume ?: return
        savedStreamMusicVolume = null
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            Log.i(TAG, "STREAM_MUSIC restored to $saved")
        } catch (e: Exception) {
            Log.w(TAG, "STREAM_MUSIC restore failed: ${e.message}")
        }
    }

    fun streamMusicVolumePair(): Pair<Int, Int> {
        return try {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) to
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            0 to 0
        }
    }

    fun describeOutputDevices(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return describeDevices(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink })
    }

    fun applyPreferredDevices(
        audioRecord: AudioRecord,
        audioTrack: AudioTrack,
        route: AudioRouteInfo
    ): RouteApplyResult = applyRoute(audioRecord, audioTrack, route)

    fun ensureOutputRoute(
        audioRecord: AudioRecord?,
        audioTrack: AudioTrack,
        isAaConnected: Boolean
    ): RouteApplyResult {
        val route = resolveRoute(isAaConnected)
        val result = if (audioRecord != null) {
            applyRoute(audioRecord, audioTrack, route)
        } else {
            applyTrackRoute(audioTrack, route)
        }

        // AA routing 保險機制偵測
        if (isAaConnected && !result.carSinkRouted && !aaSonificationRoutingFailed) {
            // 07-02 log 教訓：#7 期間及結束時多次 route_refresh_warning + carSinkRouted=false，導致 rumble ref 不穩 (playbackRefActive false)。AA routing 保險機制偵測
            // 嘗試判斷是否是因為 SONIFICATION 導致 routing 失敗
            // (carSinkRouted false 且 routed 到非車機 sink)
            val routedName = result.routedOutputName ?: ""
            if (!routedName.contains("car", ignoreCase = true) &&
                !routedName.contains("submix", ignoreCase = true) &&
                !routedName.contains("bus", ignoreCase = true)) {
                aaSonificationRoutingFailed = true
                Log.w(TAG, "AA SONIFICATION routing 未成功到達 car sink (routed=$routedName)，啟用 MEDIA fallback 保險。")
                Log.w(TAG, "下次 rebuild track 或重啟 App 將使用 USAGE_MEDIA 以確保 AA 路由。")
            }
        }

        return result
    }

    fun isCarSinkRouted(audioTrack: AudioTrack?, isAaConnected: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioTrack == null) return false
        val routed = audioTrack.routedDevice ?: return false
        if (isCarOutputType(routed.type)) return true
        if (!isAaConnected) return false
        return isAaOutputHeuristic(routed)
    }

    fun getActiveInputDeviceName(audioRecord: AudioRecord?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioRecord == null) return null
        return audioRecord.routedDevice?.let { describeDevice(it) }
    }

    fun getActiveOutputDeviceName(audioTrack: AudioTrack?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioTrack == null) return null
        return audioTrack.routedDevice?.let { describeDevice(it) }
    }

    fun getActiveOutputDeviceType(audioTrack: AudioTrack?): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioTrack == null) return -1
        return audioTrack.routedDevice?.type ?: -1
    }

    private fun applyRoute(
        audioRecord: AudioRecord,
        audioTrack: AudioTrack,
        route: AudioRouteInfo
    ): RouteApplyResult {
        val outputApplied = applyTrackPreferredDevice(audioTrack, route.preferredOutput)
        val inputApplied = applyRecordPreferredDevice(audioRecord, route.preferredInput)
        return buildRouteResult(route, outputApplied, inputApplied, audioRecord, audioTrack)
    }

    private fun applyTrackRoute(audioTrack: AudioTrack, route: AudioRouteInfo): RouteApplyResult {
        val outputApplied = applyTrackPreferredDevice(audioTrack, route.preferredOutput)
        return buildRouteResult(route, outputApplied, inputPreferredApplied = false, audioRecord = null, audioTrack)
    }

    private fun buildRouteResult(
        route: AudioRouteInfo,
        outputApplied: Boolean,
        inputPreferredApplied: Boolean,
        audioRecord: AudioRecord?,
        audioTrack: AudioTrack
    ): RouteApplyResult {
        val routedOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioTrack.routedDevice else null
        val routedInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioRecord?.routedDevice else null
        // 1.2.16: remote_submix / projection_submix ARE the phone USB AA path — was excluded → false carSinkRouted
        val isAaConnected = route.aaLinkType != "local" ||
            route.routeLabel.startsWith("android_auto") ||
            route.routeLabel.startsWith("usb_") ||
            route.routeLabel == "bluetooth_a2dp" ||
            route.routeLabel == "remote_submix" ||
            route.routeLabel.contains("submix", ignoreCase = true)
        return RouteApplyResult(
            route = route,
            outputPreferredApplied = outputApplied,
            inputPreferredApplied = inputPreferredApplied,
            routedOutputType = routedOutput?.type,
            routedOutputName = routedOutput?.let { describeDevice(it) },
            routedInputType = routedInput?.type,
            routedInputName = routedInput?.let { describeDevice(it) },
            carSinkRouted = isCarSinkRouted(audioTrack, isAaConnected)
        )
    }

    private fun applyTrackPreferredDevice(audioTrack: AudioTrack, device: AudioDeviceInfo?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || device == null) return false
        val applied = audioTrack.setPreferredDevice(device)
        Log.i(TAG, "AudioTrack preferred device: ${describeDevice(device)} applied=$applied")
        return applied
    }

    private fun applyRecordPreferredDevice(audioRecord: AudioRecord, device: AudioDeviceInfo?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || device == null) return false
        val applied = audioRecord.setPreferredDevice(device)
        Log.i(TAG, "AudioRecord preferred device: ${describeDevice(device)} applied=$applied")
        return applied
    }

    private fun pickPreferredOutput(
        outputs: Array<AudioDeviceInfo>,
        isAaConnected: Boolean
    ): AudioDeviceInfo? {
        val sinks = outputs.filter { it.isSink }
        if (sinks.isEmpty()) return null

        if (isAaConnected) {
            val scored = sinks
                .filterNot { isPhoneLocalOutput(it) }
                .map { device -> device to scoreOutputDevice(device, isAaConnected) }
                .sortedByDescending { it.second }
            scored.firstOrNull { it.second > 0 }?.let { (device, score) ->
                Log.i(
                    TAG,
                    "AA_SUBMIX_PICK score=$score ${describeDevice(device)} " +
                        "(prefer mixp:0 48kHz music over mixp:1 16kHz voice)"
                )
                return device
            }

            // AA 已連線但沒有外接 sink 時，不 fallback 到手機喇叭，改走媒體預設路由。
            Log.w(TAG, "AA connected but no external sink found; available=${describeDevices(sinks)}")
            return null
        }

        val priority = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        for (type in priority) {
            sinks.firstOrNull { it.type == type }?.let { return it }
        }
        return sinks.firstOrNull()
    }

    private fun pickPreferredInput(
        inputs: Array<AudioDeviceInfo>,
        isAaConnected: Boolean
    ): AudioDeviceInfo? {
        val sources = inputs.filter { it.isSource }
        if (sources.isEmpty()) return null

        val priority = if (isAaConnected) {
            listOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET
            )
        } else {
            listOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )
        }
        for (type in priority) {
            sources.firstOrNull { it.type == type }?.let { return it }
        }
        return sources.firstOrNull()
    }

    private fun scoreOutputDevice(device: AudioDeviceInfo, isAaConnected: Boolean): Int {
        var score = 0
        // #9: wired USB/BUS first; BT A2DP (wireless AA / phone A2DP) heavily penalized when requireWiredAa
        score += when (device.type) {
            // USB AA connected: must use remote_submix (HU AA mix). A2DP cannot run beside USB AA.
            // No AA: A2DP is the car music DAC (idle 50Hz LINE on Octavia).
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> if (isAaConnected) 900 else 200
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> if (isAaConnected) 80 else 1100
            AudioDeviceInfo.TYPE_BUS -> 1000
            AudioDeviceInfo.TYPE_USB_DEVICE -> 980
            AudioDeviceInfo.TYPE_USB_HEADSET -> 950
            AudioDeviceInfo.TYPE_HDMI -> 800
            AudioDeviceInfo.TYPE_HDMI_ARC -> 780
            AudioDeviceInfo.TYPE_LINE_ANALOG -> 500
            AudioDeviceInfo.TYPE_AUX_LINE -> 500
            else -> 0
        }
        if (isAaConnected && isPhoneLocalOutput(device)) {
            score -= 10_000
        }
        // 1.2.20: do not penalize A2DP — it is the bass sink.
        score += nameHintScore(device)
        // 1.2.26: AA opens mixp:0 @ 48 kHz (music) and mixp:1 @ 16 kHz (voice).
        // 16 kHz voice HP kills 50 Hz. Prefer mixp:0 / 48 kHz.
        if (isAaConnected && device.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
            score += aaMediaSubmixBias(device)
        }
        return score
    }

    /** Positive = music bus; negative = voice/nav 16 kHz bus. */
    private fun aaMediaSubmixBias(device: AudioDeviceInfo): Int {
        val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address.orEmpty().lowercase()
        } else {
            ""
        }
        var bias = 0
        if ("mixp:0" in addr) bias += 400
        if ("mixp:1" in addr) bias -= 500
        val rates = try {
            device.sampleRates ?: intArrayOf()
        } catch (_: Exception) {
            intArrayOf()
        }
        if (rates.contains(48000) || rates.contains(44100)) bias += 200
        if (rates.isNotEmpty() && rates.maxOrNull() ?: 0 <= 16000) bias -= 400
        Log.i(
            TAG,
            "AA_SUBMIX_CANDIDATE id=${device.id} addr=$addr rates=${rates.joinToString()} bias=$bias"
        )
        return bias
    }

    private fun nameHintScore(device: AudioDeviceInfo): Int {
        val text = buildString {
            append(device.productName?.toString().orEmpty().lowercase())
            append(' ')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                append(device.address.orEmpty().lowercase())
            }
        }
        var score = 0
        if ("usb" in text) score += 80
        if ("car" in text || "auto" in text || "automotive" in text) score += 60
        if ("headunit" in text || "head unit" in text) score += 60
        if ("a2dp" in text) score += 40
        if ("speaker" in text && "built" !in text) score += 20
        return score
    }

    private fun isPhoneLocalOutput(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            device.type == AudioDeviceInfo.TYPE_TELEPHONY
    }

    private fun isCarOutputType(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> true // phone USB AA projection sink
            else -> false
        }
    }

    private fun isAaOutputHeuristic(device: AudioDeviceInfo): Boolean {
        if (isPhoneLocalOutput(device)) return false
        val text = device.productName?.toString().orEmpty().lowercase()
        return device.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX ||
            "usb" in text ||
            "car" in text ||
            "auto" in text
    }

    private fun routeLabelFor(device: AudioDeviceInfo?, isAaConnected: Boolean): String {
        if (device == null) {
            return if (isAaConnected) "android_auto_media_default" else "phone_local"
        }
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUS -> "android_auto_bus"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_car"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_car"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
            else -> if (isAaConnected) "android_auto_scored" else "phone_local"
        }
    }

    private fun describeDevices(devices: List<AudioDeviceInfo>): List<String> =
        devices.map { describeDevice(it) }

    private fun describeDevice(device: AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address.orEmpty()
        } else {
            ""
        }
        return "${deviceTypeName(device.type)}:${device.productName}:$address:id=${device.id}"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "SUBMIX"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE"
        else -> type.toString()
    }

    companion object {
        private const val TAG = "AudioRouteManager"
    }
}