package com.example.caranc.shared.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.util.Log
import com.example.caranc.shared.*
import com.example.caranc.shared.location.VehicleSpeedProvider
import com.example.caranc.shared.model.CabinMimoProfile
import com.example.caranc.shared.model.CabinProfileStore
import com.example.caranc.shared.model.CabinResonanceDetector
import com.example.caranc.shared.model.CabinTransferModel
import com.example.caranc.shared.model.CabinZoneId
// (CYCLE3: direct NoiseBandClassifier import removed; use sessionContext.noiseBandClassifier instead)
import com.example.caranc.shared.model.ProfileAgingMonitor
import com.example.caranc.shared.signal.MediaPlaybackCapture
import com.example.caranc.shared.signal.ReferenceSignalPipeline
import com.example.caranc.shared.signal.SirenDetector
import com.example.caranc.shared.signal.SonificationDetector
import com.example.caranc.shared.commercial.CommercialFeature
import com.example.caranc.shared.test.GuidedTestController
import com.example.caranc.shared.latency.NativeLowBandProcessor
import com.example.caranc.shared.latency.LatencyAwareBandLimiter
import com.example.caranc.shared.MultiBandANCProcessor  // for cast to access extra low-band counters (fdaf/multirate)
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * P0 #1 Split: AudioEngine extracted from the original ~1222-line ANCService god-class.
 *
 * This class now owns:
 *  - AudioRecord / AudioTrack initialization, buffer sizing (low-latency), release (with error guards + synchronized)
 *  - AncProcessorFacade (via AncSessionFactory P2) creation, cabin model application (mimo trial), tier updates, mode, siren override, energy, latency
 *    (P2: processor creation routed through light SessionComponentFactory for DI/scoping prep; iOS gets stub facade)
 *  - ReferenceSignalPipeline, SirenDetector, MediaPlaybackCapture (OBD Bluetooth auto RPM removed - only manual test RPM from prefs for engine comb FF)
 *  - Full calibration flow: loadOrCalibrateCabinModel (stored profile check, log-chirp playback+record, impulse estimate via AudioSignalUtils, resonance detect, fallback, mimo profile enrich, CabinProfileStore save)
 *  - The main real-time processing loop: route refresh, tier change, speed/rpm snapshot, music/call/road mode decision + state update + processor config, ref preprocess, siren detect+log, rms/energy tracking + bump + maybeRecal, ANC process, output gain scale, write to track, updateVisualization (which also does band classify, latency monitor, 2s snapshot logs)
 *  - Route management: resolve, prepare mix, apply preferred, retryOutputRoute, maybeRefreshAudioRoute, calib focus
 *  - Visualization slice reuse buffers, perf timing, profile aging state
 *  - Runtime real latency meas: occasional known probe insert + corr (helpers: insertKnownProbe, measureRoundTripLatencyIfDue) + history reuse bufs; updates via setEstimatedLatencyMs
 *  - All helpers: scaleSamplesInto, computeBlockRms, audioSourceName, applyDrivingNoiseMode, log* , compute*Buffer, estimateCurrentLatency, retry, processingModeName, latencyLogFields, releaseAudio, getTierLabel
 *
 * ANCService is now thin:
 *  - Only LifecycleService boilerplate, startForeground + notification mgmt, CarConnection AA observer (isAAConnected lambda), onStartCommand (handle STOP action, FG, safety consent gate, logger start session, create+start engine), onDestroy (stop engine, logger end, state stopped, stopFG)
 *  - sessionContext injected into engine (no more direct Global* inside audio path for modes/state)
 *  - Exact same external behavior, Log tags ("ANCService"), AncSessionLogger phases/fields, AA auto-stop, notif texts preserved.
 *
 * Callbacks used for notification updates (service owns notif) and optional requestStop (for internal error path to match original stopSelf in finally).
 * Route/speed/providers created internally (using passed appContext) so engine is self-contained.
 */
class AudioEngine(
    private val appContext: Context,
    private val sessionContext: AncSessionContext,
    private val onUpdateNotification: (String) -> Unit,
    private val lifecycleScope: CoroutineScope,
    private val isAAConnected: () -> Boolean,
    private val requestStop: () -> Unit = {}
) {
    // All audio/processing ownership moved here from ANCService (P0 #1 refactor)
    private var processingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var ancProcessor: AncProcessorFacade? = null
    private var cabinProfileId: String = CabinTransferModel.DEFAULT_PROFILE_ID
    private lateinit var audioManager: AudioManager
    private var audioRouteManager: AudioRouteManager? = null
    private var vehicleSpeedProvider: VehicleSpeedProvider? = null
    private var currentRoute: AudioRouteInfo? = null
    private var currentLatency: LatencyBreakdown? = null
    private var acousticDelaySamples = 0
    private var referencePipeline: ReferenceSignalPipeline? = null
    private var mediaPlaybackCapture: MediaPlaybackCapture? = null
    private var sirenDetector: SirenDetector? = null
    private var sonificationDetector: SonificationDetector? = null
    private var lastAntiNoise = ShortArray(PROCESSING_READ_SIZE)
    private var playbackRefBuffer = ShortArray(PROCESSING_READ_SIZE)
    // Perf: reuse buffers for visualization slices (avoid copyOf + residual ShortArray alloc in hot path every 200ms)
    // + block counter for occasional nanoTime timing on process block.
    private var visInputSlice = ShortArray(PROCESSING_READ_SIZE)
    private var visOutputSlice = ShortArray(PROCESSING_READ_SIZE)
    private var visResidualSpectrum = ShortArray(PROCESSING_READ_SIZE)
    // hot-path opt: reuse output buffer (push reuse style) to avoid ShortArray alloc in scale path every block
    private var outputBufferReuse = ShortArray(PROCESSING_READ_SIZE)
    private var blockCount = 0L
    private var profileEnergyEma = 0.0
    private var lastProfileAgingCheckMs = 0L

    // CYCLE3_EXTRA extended timing (more than basic %50 nanoTime):
    // full loop (read-pre-proc-anc-write-viz), ema, per-mode, lms from processor profiling, probe corr time.
    // Exposed/pushed to sessionContext.perfMetrics (new metrics flow holder).
    private val nativeLowProto = NativeLowBandProcessor()  // proto stub for availability + future low-freq native path
    private var lastFullLoopEma = 0.0
    private var lastLoggedMode = ""
    private var timingLogCounter = 0
    private var lastRecalibrationDeferLogMs = 0L
    private var processingStartedAtMs = 0L
    private var lastSirenLogMs = 0L
    private var lastSirenLogged = false
    private var lastSonifLogMs = 0L
    private var lastSonifLogged = false  // for notification/sonification event protection logging (throttle)
    private var mimoTrialEnabled = true

    // P1 #6+7: runtime real latency measurement - reuse buffers (no alloc in ANC hot loop) for occasional
    // known signal insert + correlate for round-trip estimate (updates processor estimatedLatencyMs)
    private val probeHistorySize = 2048
    private val sentOutputHistory = FloatArray(probeHistorySize)
    private val micInputHistory = FloatArray(probeHistorySize)
    private var historyWriteIndex = 0
    private var lastProbeBlock = 0L
    private var lastMeasBlock = 0L
    private var runtimeMeasuredLatencyMs = 0f
    private var lastRouteCheckMs = 0L
    private var recordBufferBytes = 0
    private var trackBufferBytes = 0
    private var recordHalSamples = 0
    private var trackHalSamples = 0

    private var lastVisUpdate = 0L
    private var lastSessionLogUpdate = 0L
    private var lastBlockRms = 0f  // for idle telegraph diagnostic in running_snapshot (protocol)
    private var lastBlockRmsVssScale = 1f  // VSS scale passed to processor based on blockRms + pfx variance for dynamic mu
    private var lastDominant = com.example.caranc.shared.model.DominantNoiseBand.MIXED  // for MUSIC_BROAD force to MUSIC_DOMINANT_RUMBLE even if quality calc stuck (06-30)

    fun start() {
        if (processingJob?.isActive == true) return

        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // CYCLE3_EXTRA native low-freq proto: report availability once per engine start (JNI load happens in actual ctor)
                val nativeLowAvail = NativeLowBandProcessor.isNativeAvailable()
                Log.i("ANCService", "CYCLE3_EXTRA: NativeLowBandProcessor available=$nativeLowAvail (proto; cmake notes in shared/build.gradle.kts)")
                sessionContext.perfMetrics.nativeLowUsed = nativeLowAvail  // future: set true when hot path switches to it
                delay(AA_HANDSHAKE_MS)

                audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val routeManager = AudioRouteManager(appContext)
                audioRouteManager = routeManager
                val speedProvider = VehicleSpeedProvider(appContext)
                vehicleSpeedProvider = speedProvider
                val gpsEnabled = speedProvider.start()
                AncSessionLogger.log(
                    phase = if (gpsEnabled) "gps_start" else "gps_denied",
                    fields = mapOf(
                        "hasLocationPermission" to speedProvider.hasPermission(),
                        "fallback" to if (gpsEnabled) "none" else "mic_only"
                    )
                )

                routeManager.registerDeviceCallback()
                val aa = isAAConnected()
                val route = routeManager.resolveRoute(aa)
                currentRoute = route
                val focusGranted = routeManager.prepareRunningAudioMix(aa)

                val currentTier = sessionContext.tierManager.currentTier.value
                sessionContext.stateManager.updateState(AncState.Calibrating())

                val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                val sampleRate = sampleRateStr?.toIntOrNull() ?: 44100
                val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(64) ?: 256

                val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val minTrackBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

                val bufferSize = LOW_LATENCY_BUFFER_SAMPLES.coerceAtLeast(
                    maxOf(minBuffer, minTrackBuffer) / LOW_LATENCY_BUFFER_DIVISOR
                ).coerceAtMost(1024)

                recordBufferBytes = computeRecordBufferBytes(minBuffer, bufferSize, sampleRate)
                trackBufferBytes = computeTrackBufferBytes(minTrackBuffer, framesPerBuffer, sampleRate)

                // P0 fix for AA/remote_submix high latency (seen 417ms in 20260630 log with trackBuffer 32708, trackLat 340ms)
                // remote_submix getMinBufferSize often returns huge value forcing high latency.
                // Force conservative cap + warn. This helps keep estimatedLatency <200ms where possible.
                // (route info at this point may not have full routed* fields yet; use buffer size + aa flag as proxy)
                val isHighLatencyRoute = isAAConnected() || trackBufferBytes > 20000 || minTrackBuffer > 16384
                if (isHighLatencyRoute) {
                    val forced = 16384
                    Log.w("ANCService", "HIGH_LATENCY_AA_DETECTED: trackBuffer was $trackBufferBytes (minTrack=$minTrackBuffer), forcing $forced to reduce latency. aa=${isAAConnected()}")
                    trackBufferBytes = forced
                }
                recordHalSamples = recordBufferBytes / 2
                trackHalSamples = trackBufferBytes / 2

                var audioSource = route.audioSource
                audioRecord = AudioRecord(audioSource, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes)

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED &&
                    audioSource == MediaRecorder.AudioSource.UNPROCESSED
                ) {
                    audioRecord?.release()
                    audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                    audioRecord = AudioRecord(audioSource, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes)
                }

                val trackAttributes = routeManager.buildTrackAudioAttributes(aa)
                val audioTrackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(trackAttributes)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(trackBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                audioTrack = audioTrackBuilder.build()

                val initialRoute = routeManager.applyPreferredDevices(
                    audioRecord = audioRecord!!,
                    audioTrack = audioTrack!!,
                    route = route
                )

                cabinProfileId = CabinProfileStore.resolveProfileId(appContext)
                mimoTrialEnabled = AncTestPreferences.isMimoTrialEnabled(appContext) &&
                    sessionContext.entitlementManager.canUseFeature(CommercialFeature.MIMO_TRIAL)
                referencePipeline = ReferenceSignalPipeline(sampleRate)
                sirenDetector = SirenDetector(sampleRate)
                sonificationDetector = SonificationDetector(sampleRate)

                mediaPlaybackCapture = MediaPlaybackCapture(sampleRate, recordBufferBytes)
                val mediaCaptureStarted = mediaPlaybackCapture?.start() == true
                AncSessionLogger.log(
                    phase = if (mediaCaptureStarted) "media_ref_start" else "media_ref_unavailable",
                    fields = mapOf(
                        "apiLevel" to Build.VERSION.SDK_INT,
                        "sampleRate" to sampleRate,
                        "error" to (mediaPlaybackCapture?.lastStartError ?: "unknown")
                    )
                )

                // OBD Bluetooth RPM removed (not needed). Only manual test RPM from prefs is supported for engine harmonic feedforward (EngineCombCanceller).
                // Useful for dev/testing PRO-like engine noise cancellation without hardware.
                val manualRpm = AncTestPreferences.getManualTestRpm(appContext)
                val rpmValid = manualRpm > 0f
                AncSessionLogger.log(
                    phase = "rpm_config",
                    fields = mapOf(
                        "manualRpm" to manualRpm,
                        "valid" to rpmValid,
                        "source" to if (rpmValid) "manual_test" else "none",
                        "plan" to sessionContext.entitlementManager.currentPlan.id
                    )
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("音訊硬體初始化失敗")
                }

                val activeInput = routeManager.getActiveInputDeviceName(audioRecord)
                val activeOutput = routeManager.getActiveOutputDeviceName(audioTrack)
                val latency = estimateCurrentLatency(sampleRate, acousticDelaySamples = 0)
                currentLatency = latency

                AncSessionLogger.log(
                    phase = "audio_init",
                    fields = latencyLogFields(
                        base = mapOf(
                            "sampleRate" to sampleRate,
                            "bufferSize" to bufferSize,
                            "recordBufferBytes" to recordBufferBytes,
                            "trackBufferBytes" to trackBufferBytes,
                            "recordHalSamples" to recordHalSamples,
                            "trackHalSamples" to trackHalSamples,
                            "framesPerBuffer" to framesPerBuffer,
                            "readSize" to PROCESSING_READ_SIZE,
                            "minRecordBuffer" to minBuffer,
                            "minTrackBuffer" to minTrackBuffer,
                            "audioSource" to audioSourceName(audioSource),
                            "tier" to currentTier.name,
                            "audioFocusMode" to "mix_no_permanent_gain",
                            "audioFocusGranted" to focusGranted,
                            "aaConnected" to aa,
                            "acousticDelaySamples" to 0,
                            "inputDevice" to (activeInput ?: route.inputDeviceName ?: "unknown"),
                            "outputDevice" to (activeOutput ?: route.outputDeviceName ?: "unknown")
                        ) + initialRoute.toLogFields(),
                        latency = latency
                    )
                )

                audioRecord?.startRecording()
                audioTrack?.play()

                val primingSize = sampleRate / 4
                val silence = ShortArray(primingSize)
                audioTrack?.write(silence, 0, silence.size)

                val trash = ShortArray(primingSize)
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.read(trash, 0, primingSize)
                }

                delay(ROUTE_SETTLE_MS)
                val routeAfterPlay = retryOutputRoute(routeManager, phase = "route_after_play")

                val cabinModel = loadOrCalibrateCabinModel(
                    sampleRate = sampleRate,
                    route = routeAfterPlay.route,
                    routeManager = routeManager
                )
                acousticDelaySamples = cabinModel.acousticDelaySamples
                currentLatency = estimateCurrentLatency(sampleRate, acousticDelaySamples)

                // P2: Audio (processor) creation now goes through light AncSessionFactory (common + platform actual).
                // This centralizes component creation for scoping / future DI (see AncSessionFactory.kt).
                // On android: dispatches to MultiBandANCProcessor; on iOS skeleton: IosAncProcessorFacade pass-through.
                // Re-snapshot tier close to creation (user may switch in UI during early route/calib setup) to ensure
                // correct initialTier for processor (fixes pre-start tier switch to STANDARD/PRO causing flash on start).
                val initialTier = sessionContext.tierManager.currentTier.value
                val procFactory = AncSessionFactory(sessionContext)
                ancProcessor = procFactory.createAncProcessor(
                    sampleRate = sampleRate,
                    bufferSize = bufferSize,
                    initialTier = initialTier
                )
                AncSessionLogger.log(
                    phase = "processor_created",
                    fields = mapOf("initialTier" to initialTier.name)
                )
                ancProcessor?.applyCabinModel(applyMimoTrial(cabinModel))
                currentLatency?.let { ancProcessor?.setEstimatedLatencyMs(getEffectiveLatencyForSet(it.totalMs)) }
                ancProcessor?.setPersonalRumbleBias(AncTestPreferences.getPersonalRumbleBias(appContext))  // ensure personal bias applied early (acoustic ID follows phone)
                logMimoProfile(cabinModel)
                logLatencyOptimization()

                val willDoLearning = initialTier != UserTier.LIGHT
                if (willDoLearning) {
                    // Early state for paid tiers so UI reflects "learning" during the post-calib power meas too.
                    sessionContext.stateManager.updateState(AncState.Learning())
                    onUpdateNotification("正在學習車內音場...")
                }

                val calibrationBuffer = ShortArray(256)
                var totalPower = 0.0
                var samples = 0
                val endTime = System.currentTimeMillis() + 2000

                while (System.currentTimeMillis() < endTime && isActive) {
                    if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        delay(100)
                        continue
                    }
                    val readLen = audioRecord?.read(calibrationBuffer, 0, 256) ?: 0
                    if (readLen > 0) {
                        for (i in 0 until readLen) {
                            totalPower += calibrationBuffer[i].toDouble() * calibrationBuffer[i].toDouble()
                            samples++
                        }
                    } else if (readLen == 0) {
                        delay(10)
                    } else {
                        Log.e("ANCService", "校正時讀取失敗: $readLen")
                        throw IllegalStateException("校正時音訊讀取失敗 ($readLen)")
                    }

                    // For STANDARD/PRO: keep feeding silence during this ~2s post-calib power measurement.
                    // Prevents output underrun/starvation gap before the dedicated learning feed (real devices
                    // can starve the track buffer during long no-write periods, leading to no-sound or bad state on resume).
                    if (willDoLearning && (readLen and 3) == 0) {
                        try {
                            audioTrack?.play()
                            audioTrack?.write(ShortArray(PROCESSING_READ_SIZE), 0, PROCESSING_READ_SIZE)
                        } catch (e: Exception) {
                            Log.w("ANCService", "power_meas silence feed write error (non-fatal): ${e.message}")
                        }
                    }
                }

                val avgPower = if (samples > 0) totalPower / samples else 1000000.0
                val adjustedMu = if (avgPower > 50000000) 0.006f else 0.01f
                ancProcessor?.adjustMu(adjustedMu)

                AncSessionLogger.log(
                    phase = "mu_adjusted",
                    fields = mapOf(
                        "avgPower" to avgPower,
                        "mu" to adjustedMu
                    )
                )

                if (willDoLearning) {
                    AncSessionLogger.log(
                        phase = "learning_start",
                        fields = mapOf(
                            "tier" to initialTier.name,
                            "durationMs" to LEARNING_DURATION_MS
                        )
                    )
                    // Feed silence to audioTrack during the learning delay to prevent buffer underrun/starvation
                    // (higher tiers take this path; long gap after calib caused "no sound" / silent output start).
                    // Keeps track continuous so anti-noise output is audible immediately after finishLearning.
                    val learnSilence = ShortArray(PROCESSING_READ_SIZE)
                    val feedIntervalMs = 20L
                    val numFeeds = (LEARNING_DURATION_MS / feedIntervalMs).toInt().coerceAtLeast(1)
                    for (k in 0 until numFeeds) {
                        if (!isActive) break
                        try {
                            audioTrack?.play()
                            audioTrack?.write(learnSilence, 0, learnSilence.size)
                        } catch (e: Exception) {
                            Log.w("ANCService", "learning silence write error (non-fatal, continuing): ${e.message}")
                        }
                        delay(feedIntervalMs)
                    }
                    ancProcessor?.finishLearning()
                    AncSessionLogger.log(phase = "learning_complete")
                }

                sessionContext.stateManager.updateState(AncState.Running())
                onUpdateNotification("主動降噪運作中 [${getTierLabel(initialTier)}]")

                val readSize = PROCESSING_READ_SIZE
                val input = ShortArray(readSize)
                var lastActiveTier = initialTier
                var lastLoggedState: AncState = AncState.Running()

                processingStartedAtMs = System.currentTimeMillis()
                lastProfileAgingCheckMs = processingStartedAtMs
                profileEnergyEma = 0.0

                AncSessionLogger.log(
                    phase = "running_start",
                    fields = currentLatency?.let { latency ->
                        latencyLogFields(
                            base = mapOf(
                                "acousticDelaySamples" to acousticDelaySamples,
                                "readSize" to readSize
                            ),
                            latency = latency
                        )
                    } ?: mapOf("readSize" to readSize)
                )

                while (isActive) {
                    maybeRefreshAudioRoute(routeManager)

                    val activeTier = sessionContext.tierManager.currentTier.value
                    if (activeTier != lastActiveTier) {
                        Log.d("ANCService", "切換降噪等級: $lastActiveTier -> $activeTier")
                        ancProcessor?.updateTier(activeTier)
                        onUpdateNotification("主動降噪運作中 [${getTierLabel(activeTier)}]")
                        AncSessionLogger.log(
                            phase = "tier_change",
                            fields = mapOf(
                                "from" to lastActiveTier.name,
                                "to" to activeTier.name
                            )
                        )
                        lastActiveTier = activeTier
                    }

                    vehicleSpeedProvider?.currentSnapshot()?.let { speed ->
                        sessionContext.stateManager.updateVehicleSpeed(speed.speedKmh, speed.valid)
                    }

                    val forceNormal = AncTestPreferences.isForceNormalMode(appContext)
                    val mediaRefActive = (mediaPlaybackCapture?.isAvailable == true) &&
                        (referencePipeline?.snapshotMetrics()?.playbackActive == true)
                    val isMusic = !forceNormal &&
                        (audioManager.isMusicActive || mediaRefActive) &&
                        sessionContext.entitlementManager.canUseFeature(CommercialFeature.MUSIC_BYPASS)
                    val isCall = !forceNormal && audioManager.mode != AudioManager.MODE_NORMAL &&
                        sessionContext.entitlementManager.canUseFeature(CommercialFeature.CALL_BYPASS)
                    val musicLowAnc = AncTestPreferences.isMusicLowAncEnabled(appContext)
                    val lmsMuMult = AncTestPreferences.getDebugLmsMuMultiplier(appContext)
                    val freezeThresh = AncTestPreferences.getDebugFreezeThreshold(appContext)
                    val personalRumbleBias = AncTestPreferences.getPersonalRumbleBias(appContext)  // personal acoustic identity (follows user/phone)
                    val freezeConsec = AncTestPreferences.getDebugFreezeConsecutive(appContext)
                    val gpsRoadEnabled = sessionContext.entitlementManager.canUseFeature(CommercialFeature.GPS_ROAD_ANC)
                    val speedSnapshot = speedProvider.currentSnapshot()

                    val isDrivingRoad = gpsRoadEnabled &&
                        // CYCLE3: via scoped context (allows mock RoadNoiseReferenceModel)
                        sessionContext.roadNoiseReferenceModel.classify(speedSnapshot.speedKmh, speedSnapshot.valid) ==
                        NoiseSourceType.ROAD

                    val manualRpm = AncTestPreferences.getManualTestRpm(appContext)
                    ancProcessor?.setEngineRpm(
                        rpm = manualRpm,
                        valid = manualRpm > 0f
                    )

                    when {
                        isCall -> {
                            if (sessionContext.stateManager.state.value !is AncState.Paused) {
                                sessionContext.stateManager.updateState(
                                    AncState.Paused("底噪降噪中（通話中·語音頻帶保護）...")
                                )
                                logStateChange(lastLoggedState, AncState.Paused())
                                lastLoggedState = AncState.Paused()
                            }
                            ancProcessor?.setCallActive(true)
                            ancProcessor?.setProcessingMode(AncProcessingMode.FLOOR_NOISE_CALL)
                        }
                        isMusic && isDrivingRoad -> {
                            val drivingMusicState = AncState.MusicMode(
                                "底噪+路噪降噪中（音樂·${speedSnapshot.speedKmh.toInt()} km/h）..."
                            )
                            if (sessionContext.stateManager.state.value !is AncState.MusicMode) {
                                sessionContext.stateManager.updateState(drivingMusicState)
                                logStateChange(lastLoggedState, drivingMusicState)
                                lastLoggedState = drivingMusicState
                            } else {
                                sessionContext.stateManager.updateState(drivingMusicState)
                            }
                            ancProcessor?.setCallActive(false)
                            ancProcessor?.setVehicleSpeed(speedSnapshot.speedKmh, speedSnapshot.valid)
                            ancProcessor?.setProcessingMode(AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD)
                        }
                        isMusic -> {
                            if (sessionContext.stateManager.state.value !is AncState.MusicMode) {
                                sessionContext.stateManager.updateState(
                                    AncState.MusicMode("底噪降噪中（音樂播放·媒體參考扣除）...")
                                )
                                logStateChange(lastLoggedState, AncState.MusicMode())
                                lastLoggedState = AncState.MusicMode()
                            }
                            ancProcessor?.setCallActive(false)
                            ancProcessor?.setVehicleSpeed(
                                speedKmh = if (speedSnapshot.valid) speedSnapshot.speedKmh else 0f,
                                valid = speedSnapshot.valid
                            )
                            ancProcessor?.setProcessingMode(AncProcessingMode.FLOOR_NOISE_MUSIC)
                        }
                        else -> {
                            ancProcessor?.setCallActive(false)
                            if (gpsRoadEnabled) {
                                applyDrivingNoiseMode(
                                    speedProvider = speedProvider,
                                    lastLoggedStateHolder = { lastLoggedState },
                                    updateLastLoggedState = { lastLoggedState = it }
                                )
                            } else {
                                ancProcessor?.setVehicleSpeed(0f, false)
                                ancProcessor?.setProcessingMode(AncProcessingMode.NORMAL)
                                if (sessionContext.stateManager.state.value !is AncState.Running) {
                                    val runningState = AncState.Running()
                                    sessionContext.stateManager.updateState(runningState)
                                    logStateChange(lastLoggedState, runningState)
                                    lastLoggedState = runningState
                                }
                            }
                        }
                    }

                    ancProcessor?.setMusicLowAncEnabled(musicLowAnc)
                    ancProcessor?.setDebugMuMultiplier(lmsMuMult)
                    // TIER AUTO PREFERRED (user: only tier manual): leakage/native/vss/rumble now auto in updateTier (called on tier change + per step in guided).
                    // Legacy prefs override still executed for backward A/B during transition, but UI will show tier effective read-only; future remove these sets for leakage/native.
                    val legacyLeak = AncTestPreferences.getDebugLeakage(appContext)
                    ancProcessor?.setDebugLeakage(legacyLeak)
                    val legacyNative = AncTestPreferences.isDebugUseNativeLowBand(appContext)
                    ancProcessor?.setUseNativeLowBand(legacyNative)
                    ancProcessor?.setPersonalRumbleBias(personalRumbleBias)
                    ancProcessor?.setDebugFreezeConfig(freezeThresh, freezeConsec, 0.6f)
                    referencePipeline?.setContext(musicActive = isMusic, callActive = isCall)

                    val read = audioRecord?.read(input, 0, readSize) ?: 0
                    if (read > 0) {
                        blockCount++
                        val t0 = System.nanoTime()  // CYCLE3_EXTRA: always capture for fullLoopMs + ema (was conditional %50); still conditional for heavy logs
                        sessionContext.perfMetrics.lastBlockTimestampNs = t0

                        val playbackRead = mediaPlaybackCapture?.read(playbackRefBuffer, read) ?: 0
                        val speedSnap = vehicleSpeedProvider?.currentSnapshot() ?: VehicleSpeedSnapshot.invalid()
                        // Compute rumble dominant flag EARLY (using prior q + force on MUSIC_BROAD/accel + now explicit isDrivingRumble for music=false case).
                        // 07-02: even when !musicActive (per 125731.log), if speed>40 + high IMU accel, force rumble intent so pipeline IMU ref mix + processor effectiveRumble get full strength (strong roadWeight, low micFactor=0.18, extra vibBoost).
                        // This + classifier force ROAD on IMU prior should deliver driving rumble cancel (user: high internal but 0 driving red before).
                        val priorQ = referencePipeline?.snapshotMetrics()?.musicSuppressionQuality ?: 1f
                        val isDrivingRumbleNow = speedSnap.valid && speedSnap.speedKmh > 40f && speedSnap.linearAccelMagnitude > 0.5f
                        var rumbleDominantForThisBlock = audioManager.isMusicActive && priorQ < 0.6f
                        if (lastDominant == com.example.caranc.shared.model.DominantNoiseBand.MUSIC_BROAD || isDrivingRumbleNow) {
                            rumbleDominantForThisBlock = true
                        }
                        if (speedSnap.linearAccelMagnitude > 0.8f) {
                            rumbleDominantForThisBlock = true
                        }
                        // 07-02 feedback: more aggressive force rumble mode using rumbleEnergyProxy (accel) + low musicVolNorm to help when media q stuck but rumble energy present. Helps classifier and boost in strong road even with some music.
                        val musicVolNorm = if (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) else 0f
                        if (speedSnap.linearAccelMagnitude > 0.5f && musicVolNorm < 0.5f) {
                            musicDominantRumbleForThisBlock = true
                        }
                        val preprocessed = referencePipeline?.preprocessBlock(
                            micInput = input,
                            size = read,
                            playbackRef = if (playbackRead > 0) playbackRefBuffer else null,
                            playbackSize = playbackRead,
                            lastAntiNoise = lastAntiNoise,
                            rumbleAccel = speedSnap.linearAccelMagnitude,
                            musicDominantRumble = rumbleDominantForThisBlock || (ancProcessor?.getProcessingMode() == AncProcessingMode.MUSIC_DOMINANT_RUMBLE),
                            suppressionQuality = priorQ
                        ) ?: input.copyOf(read)

                        // pass + set flag (for processor special logic + snapshot). Note API name still "musicDominant" for compat but now drives effectiveRumble for pure driving rumble too.
                        ancProcessor?.setMusicSuppressionQuality(priorQ)
                        ancProcessor?.setMusicDominantRumbleMode(rumbleDominantForThisBlock)
                        if (rumbleDominantForThisBlock) {
                            Log.d("ANCService", "force_rumble_dominant: MUSIC_BROAD_or_highAccel_or_isDrivingRumble (flag for IMU ref/boost/sonif-protect) speedKmh=${"%.1f".format(speedSnap.speedKmh)} accel=${"%.2f".format(speedSnap.linearAccelMagnitude)} q=$priorQ dominant=$lastDominant isDriving=$isDrivingRumbleNow")
                        }

                        val detector = sirenDetector
                        if (detector != null) {
                            var sirenHit = false
                            for (i in 0 until read) {
                                if (detector.processSample(preprocessed[i] / 32768.0f)) {
                                    sirenHit = true
                                }
                            }
                            ancProcessor?.setSirenOverride(sirenHit, detector.ancGainScale())
                            val nowMs = System.currentTimeMillis()
                            if (sirenHit && (!lastSirenLogged || nowMs - lastSirenLogMs > 5000L)) {
                                lastSirenLogged = true
                                lastSirenLogMs = nowMs
                                AncSessionLogger.log(
                                    phase = "siren_detected",
                                    fields = mapOf(
                                        "confidence" to detector.sirenConfidence,
                                        "bandEnergyRatio" to detector.bandEnergyRatio
                                    )
                                )
                            } else if (!sirenHit) {
                                lastSirenLogged = false
                            }
                        }

                        // === 最高優先修復：Notification / Sonification 短事件保護 ===
                        // 當 AA remote_submix 播放 notification (beep/ringtone) 時：
                        //   - playbackRef (capture 包含 USAGE_ASSISTANCE_SONIFICATION) 會有短 burst
                        //   - mic 會錄到洩漏 → ANC 誤處理 → 高延遲 echo + 干擾 routing 造成 choppy
                        // 策略：偵測到短促 sonification 事件時，大幅 duck ANC 輸出 gain（接近 bypass），
                        //       並觸發 freeze 避免 LMS 學習 transient 產生 artifact。
                        //       這不會長期關閉降噪，只在事件期間（~100-200ms）保護重要聲音與主音訊穩定。
                        val sonifDet = sonificationDetector
                        if (sonifDet != null) {
                            var sonifHit = false

                            // 優先從 playbackRef 偵測（最能提前知道 "通知正在播放"）
                            if (playbackRead > 0) {
                                for (i in 0 until playbackRead) {
                                    if (sonifDet.processSample(playbackRefBuffer[i] / 32768.0f)) {
                                        sonifHit = true
                                    }
                                }
                            }
                            // 再從 mic preprocessed 補捉洩漏的版本（echo 來源）
                            if (!sonifHit) {
                                for (i in 0 until read) {
                                    if (sonifDet.processSample(preprocessed[i] / 32768.0f)) {
                                        sonifHit = true
                                    }
                                }
                            }

                            if (sonifHit) {
                                var scale = sonifDet.ancGainScale()
                                // 07-02: relax sonif duck when driving rumble (isDrivingRumbleNow or flag): road bumps/transients frequently false-positive as sonif bursts (log had 3141 sonif + 85k bumps in 28MB midday log).
                                // Don't want repeated deep duck (0.06-0.18) killing continuous rumble cancel output during real drive. Milder scale keeps rumble path active while still reducing some echo risk.
                                if (isDrivingRumbleNow || rumbleDominantForThisBlock) {
                                    scale = (scale * 0.5f + 0.5f).coerceIn(0.3f, 1f)
                                }
                                ancProcessor?.setSonificationOverride(true, scale)

                                val nowMs = System.currentTimeMillis()
                                if (!lastSonifLogged || nowMs - lastSonifLogMs > 3000L) {
                                    lastSonifLogged = true
                                    lastSonifLogMs = nowMs
                                    AncSessionLogger.log(
                                        phase = "sonification_detected",
                                        fields = mapOf(
                                            "confidence" to sonifDet.sonificationConfidence,
                                            "burstRatio" to sonifDet.burstRatio,
                                            "gainScaleApplied" to scale,
                                            "fromPlaybackRef" to (playbackRead > 0),
                                            "relaxedForRumble" to (isDrivingRumbleNow || rumbleDominantForThisBlock)
                                        )
                                    )
                                }
                            } else {
                                ancProcessor?.setSonificationOverride(false, 1f)
                                lastSonifLogged = false
                            }
                        }

                        val blockRms = computeBlockRms(preprocessed, read)
                        lastBlockRms = blockRms
                        profileEnergyEma = if (profileEnergyEma < 1e-6) {
                            blockRms.toDouble()
                        } else {
                            0.98 * profileEnergyEma + 0.02 * blockRms
                        }

                        // Compute VSS scale from blockRms (and previous pfx varEma for variance based dynamic mu)
                        val prevVar = sessionContext.perfMetrics.lastLmsPfxVarEma
                        val vssFromRms = when {
                            blockRms > 0.02 -> 1.0f
                            blockRms > 0.01 -> 0.9f
                            else -> 0.7f
                        }
                        val vssFromVar = if (prevVar > 5f) 0.8f else if (prevVar > 1f) 0.95f else 1.05f
                        lastBlockRmsVssScale = (vssFromRms * vssFromVar).coerceIn(0.5f, 1.3f)
                        ancProcessor?.setBlockRmsVssScale(lastBlockRmsVssScale)

                        maybeRecalibrateProfile(blockRms.toDouble())

                        val freezeTriggered = ancProcessor?.registerBlockEnergy(blockRms) == true
                        val freezeRemaining = ancProcessor?.getCurrentFreezeBlocksRemaining() ?: 0
                        if (freezeTriggered) {
                            AncSessionLogger.log(
                                phase = "bump_detected",
                                fields = mapOf(
                                    "blockRms" to blockRms,
                                    "frozen" to true,
                                    "freezeRemaining" to freezeRemaining
                                )
                            )
                            Log.d("ANCService", "bump_detected: blockRms=${"%.4f".format(blockRms)} -> freeze set, remaining=$freezeRemaining (lms may pause)")
                        }

                        // always expose current freeze state in perf for diagnosis (even if not newly triggered)
                        if (blockCount % 200 == 0L || freezeRemaining > 0) {
                            Log.d("ANCService", "freeze_state: remaining=$freezeRemaining blockRms=${"%.4f".format(blockRms)}")
                        }

                        val processed = ancProcessor?.process(preprocessed) ?: preprocessed

                        // CYCLE3_EXTRA: pull profiling counters from processor (updated in BandFxLms + now Fdaf/Multirate)
                        // + update sessionContext perfMetrics (exposed for UI/tests/logs, no direct globals)
                        val lmsUp = ancProcessor?.getLowLmsUpdateCount() ?: 0L
                        val lmsCalls = ancProcessor?.getLowLmsProcessCalls() ?: 0L
                        val lastPfx = ancProcessor?.getLastLmsPfx() ?: 0f
                        sessionContext.perfMetrics.updateLmsCounters(lmsUp, lmsCalls, lastPfx)
                        // extra low path counters if Multi impl
                        val multiProc = ancProcessor
                        if (multiProc is MultiBandANCProcessor) {
                            sessionContext.perfMetrics.updateLowBandExtra(
                                multiProc.getFdafLmsUpdateCount(),
                                multiProc.getMultirateDecimUpdateCount()
                            )
                        }

                        val outputGain = audioRouteManager?.ancOutputGain ?: 1f
                        // P2: output safety - always cap gain + soft clip (even at nominal gain=1 for DSP guard)
                        val cappedGain = outputGain.coerceAtMost(MAX_ANC_OUTPUT_GAIN)
                        // Reduce audible "white noise" artifact from anti-output (fdaf + low) when latency high and
                        // maxCancel low (<60Hz): only very low rumble cancellable, the played residual anti sounds like
                        // hiss (esp. LIGHT + free path using normal mode). Lower play gain for artifact; full when good latency.
                        // Affects LIGHT (white noise complaint) and higher tiers equally in high-latency routes.
                        val limits = ancProcessor?.getLatencyBandLimits()
                        val antiArtifactGain = if ((limits?.maxCancelFrequencyHz ?: 100f) < 60f) 0.28f else 1f
                        val userGain = AncTestPreferences.getUserAncGain(appContext)
                        // IDLE ARTIFACT SUPPRESS (minimal, speed<8 only): auto lower effective gain at idle/low speed to mask any residual telegraph clicks from low-energy LMS (musicLow + high mu).
                        // Full gain at 50+kmh for #6/#7 rumble breakthrough validation (effMid 0.6+). User can still override via TestLogPanel but idle caps it.
                        val speedForGain = vehicleSpeedProvider?.currentSnapshot() ?: VehicleSpeedSnapshot.invalid()
                        val idleGainFactor = if (speedForGain.valid && speedForGain.speedKmh < 8f) 0.65f else 1f
                        val finalWriteGain = cappedGain * antiArtifactGain * userGain * idleGainFactor
                        // reuse buffer (hot-path opt, similar to push buffer reuse)
                        if (outputBufferReuse.size < read) outputBufferReuse = ShortArray(read)
                        scaleSamplesInto(processed, read, finalWriteGain, outputBufferReuse)
                        val output = outputBufferReuse

                        // occasional known signal insert (for runtime real latency meas round-trip in ANC loop)
                        if (blockCount % 900L == 123L && blockCount - lastProbeBlock > 650) {
                            insertKnownProbe(output, read)
                            lastProbeBlock = blockCount
                        }

                        // fill history reuse buffers (mic + sent incl probe) for later correlate; no alloc
                        for (j in 0 until read) {
                            micInputHistory[historyWriteIndex] = preprocessed[j] / 32768.0f
                            sentOutputHistory[historyWriteIndex] = output[j] / 32767.0f
                            historyWriteIndex = (historyWriteIndex + 1) % probeHistorySize
                        }

                        if (read <= lastAntiNoise.size) {
                            output.copyInto(lastAntiNoise, 0, 0, read)
                        }
                        audioTrack?.write(output, 0, read)

                        updateVisualization(preprocessed, processed, read)

                        // CYCLE3_EXTRA: always compute full loop ms; update EMA + per-mode via perfMetrics (exposed in sessionContext)
                        val dtNs = System.nanoTime() - t0
                        val dtMs = dtNs / 1_000_000.0
                        val modeName = ancProcessor?.let { processingModeName(it) } ?: "unknown"
                        sessionContext.perfMetrics.updateFullLoop(dtMs, modeName)

                        // more frequent / conditional logging (every ~20 blocks + on mode change or slow block)
                        timingLogCounter++
                        val shouldLogTiming = (timingLogCounter % 20 == 0) ||
                            (modeName != lastLoggedMode) ||
                            (dtMs > 4.0) ||  // conditional on expensive block
                            (blockCount % 200 == 0L)
                        if (shouldLogTiming) {
                            lastLoggedMode = modeName
                            val ema = sessionContext.perfMetrics.emaFullLoopMs
                            val lmsU = sessionContext.perfMetrics.lmsUpdateCount
                            val probeC = sessionContext.perfMetrics.lastProbeCorrMs
                            val nativeAvail = NativeLowBandProcessor.isNativeAvailable()
                            val freezeRem = ancProcessor?.getCurrentFreezeBlocksRemaining() ?: 0
                            val musicLow = AncTestPreferences.isMusicLowAncEnabled(appContext)
                            // finer low-band contribution for debugging rumble (tire/wind) vs overall (Tesla quiet zone focus + Bose RNC low freq)
                            val lowLmsU = (ancProcessor as? MultiBandANCProcessor)?.getLowBandLmsUpdateCount() ?: lmsU
                            val fdafU = ancProcessor?.getFdafLmsUpdateCount() ?: 0
                            val multirateU = ancProcessor?.getMultirateDecimUpdateCount() ?: 0
                            Log.d(
                                "ANCService",
                                "perf: block#${blockCount} fullLoop=${"%.2f".format(dtMs)}ms ema=${"%.2f".format(ema)}ms mode=$modeName " +
                                    "lmsUpdates=$lmsU lowLms=$lowLmsU fdafU=$fdafU multirateU=$multirateU probeCorrMs=${"%.2f".format(probeC)} nativeLowAvail=$nativeAvail freezeRem=$freezeRem musicLowAnc=$musicLow"
                            )
                            // also log to session logger occasionally for persistent trace
                            if (blockCount % 100 == 0L) {
                                AncSessionLogger.log(
                                    phase = "perf_timing",
                                    fields = mapOf(
                                        "block" to blockCount,
                                        "fullLoopMs" to dtMs,
                                        "emaFullLoopMs" to ema,
                                        "mode" to modeName,
                                        "lmsUpdateCount" to lmsU,
                                        "lmsProcessCalls" to sessionContext.perfMetrics.lmsProcessCalls,
                                        "lowBandLmsUpdateCount" to ((ancProcessor as? MultiBandANCProcessor)?.getLowBandLmsUpdateCount() ?: 0),
                                        "fdafLmsUpdateCount" to (ancProcessor?.getFdafLmsUpdateCount() ?: 0),
                                        "multirateDecimUpdateCount" to (ancProcessor?.getMultirateDecimUpdateCount() ?: 0),
                                        "probeCorrMs" to probeC,
                                        "nativeLowProto" to nativeAvail,
                                        "freezeBlocksRemaining" to (ancProcessor?.getCurrentFreezeBlocksRemaining() ?: 0),
                                        "musicLowAncEnabled" to AncTestPreferences.isMusicLowAncEnabled(appContext),
                                        "debugLmsMuMultiplier" to AncTestPreferences.getDebugLmsMuMultiplier(appContext),
                                        "debugFreezeThreshold" to AncTestPreferences.getDebugFreezeThreshold(appContext),
                                        "debugFreezeConsec" to AncTestPreferences.getDebugFreezeConsecutive(appContext),
                                        "debugLatencyOverrideMs" to AncTestPreferences.getDebugLatencyOverrideMs(appContext),
                                        "lmsPfxEma" to sessionContext.perfMetrics.lastLmsPfxEma,
                                        "lmsPfxVarEma" to sessionContext.perfMetrics.lastLmsPfxVarEma  // EMA variance proxy of pfx for VSS/Leaky validation (high var indicates instability risk with aggressive mu)
                                    )
                                )
                            }
                        }

                        // occasional correlate call + update processor latency estimate from real meas
                        if (blockCount % 1800L == 55L && blockCount - lastMeasBlock > 1300) {
                            val corrT0 = System.nanoTime()
                            val meas = measureRoundTripLatencyIfDue()
                            val corrDtMs = if (meas != 0f) (System.nanoTime() - corrT0) / 1_000_000.0 else 0.0
                            sessionContext.perfMetrics.updateProbeCorr(meas)
                            if (meas > 10f) {
                                lastMeasBlock = blockCount
                                runtimeMeasuredLatencyMs = if (runtimeMeasuredLatencyMs < 5f) meas else 0.65f * runtimeMeasuredLatencyMs + 0.35f * meas
                                ancProcessor?.setEstimatedLatencyMs(getEffectiveLatencyForSet(runtimeMeasuredLatencyMs))
                                AncSessionLogger.log(
                                    phase = "runtime_latency_correlated",
                                    fields = mapOf(
                                        "measuredMs" to meas,
                                        "smoothedMs" to runtimeMeasuredLatencyMs,
                                        "block" to blockCount,
                                        "corrComputeMs" to corrDtMs   // CYCLE3_EXTRA: probe corr time metric
                                    )
                                )
                            }
                        }
                    } else if (read < 0) {
                        Log.e("ANCService", "主迴圈讀取失敗: $read")
                        throw IllegalStateException("主迴圈音訊讀取失敗 ($read)")
                    } else {
                        delay(50)
                    }
                }

            } catch (e: Exception) {
                sessionContext.stateManager.updateState(AncState.Error("服務異常"))
                AncSessionLogger.log(
                    phase = "error",
                    fields = mapOf(
                        "message" to (e.message ?: "unknown"),
                        "type" to e.javaClass.simpleName
                    )
                )
            } finally {
                releaseAudio()
                requestStop()
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        releaseAudio()
    }

    private fun scaleSamplesInto(samples: ShortArray, size: Int, gain: Float, target: ShortArray): ShortArray {
        for (i in 0 until size) {
            val scaled = samples[i] * gain
            // P2 output safety: soft clip normalized (guard against any DSP overshoot + smooth knee near full scale)
            val norm = scaled / 32768f
            val safeNorm = softClip(norm)
            target[i] = (safeNorm * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return target
    }

    private fun softClip(x: Float): Float {
        val ax = kotlin.math.abs(x)
        if (ax <= 1f) return x
        val sign = if (x >= 0f) 1f else -1f
        // P2 safety soft clip: for |norm|>1 (DSP overs, even with gain cap<1 this guards processor output path)
        // starts at 1.0 and softly reduces excess (e.g. 1.1 -> ~0.952 instead of hard clip at 1)
        val excess = ax - 1f
        val soft = 1f - excess / (2f + excess)
        return sign * soft
    }

    private fun computeBlockRms(samples: ShortArray, size: Int): Float {
        if (size <= 0) return 0f
        var sum = 0.0
        for (i in 0 until size) {
            val n = samples[i] / 32768.0
            sum += n * n
        }
        return sqrt(sum / size).toFloat()
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> source.toString()
    }

    @SuppressLint("MissingPermission")
    private suspend fun loadOrCalibrateCabinModel(
        sampleRate: Int,
        route: AudioRouteInfo,
        routeManager: AudioRouteManager,
        forceRecalibrate: Boolean = false
    ): CabinTransferModel {
        val storedProfile = CabinProfileStore.load(appContext, cabinProfileId)
        if (!forceRecalibrate && storedProfile != null && storedProfile.isCompatibleWith(sampleRate)) {
            Log.i("ANCService", "載入已儲存車廂 profile: $cabinProfileId")
            AncSessionLogger.log(
                phase = "calibration_skipped_loaded_profile",
                fields = mapOf(
                    "profileId" to cabinProfileId,
                    "sampleRate" to storedProfile.sampleRate,
                    "acousticDelaySamples" to storedProfile.acousticDelaySamples,
                    "resonancePeaksHz" to storedProfile.resonancePeaksHz,
                    "calibratedAtEpochMs" to storedProfile.calibratedAtEpochMs,
                    "sHat" to storedProfile.secondaryPath.toList()
                )
            )
            onUpdateNotification("已載入車廂聲學 profile [$cabinProfileId]")
            return storedProfile
        }

        onUpdateNotification("正在進行空間聲學校正...")

        val calibrationSize = 4096
        val chirpBuffer = AudioSignalUtils.generateLogChirp(
            size = calibrationSize,
            sampleRate = sampleRate
        )

        Log.d("ANCService", "開始播放對數掃頻校正音...")
        val calibrationFocus = routeManager.requestCalibrationAudioFocus()
        AncSessionLogger.log(
            phase = "calibration_focus",
            fields = mapOf("granted" to calibrationFocus)
        )
        audioTrack?.play()
        delay(300)

        val recordedChirp = ShortArray(calibrationSize)
        var totalRead = 0
        val startTime = System.currentTimeMillis()

        val playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            repeat(AudioSignalUtils.CALIBRATION_CHIRP_REPEATS) {
                if (isActive) audioTrack?.write(chirpBuffer, 0, calibrationSize)
            }
        }

        val fillerSilence = ShortArray(256)
        while (totalRead < calibrationSize && System.currentTimeMillis() - startTime < 2000) {
            val r = audioRecord?.read(recordedChirp, totalRead, calibrationSize - totalRead) ?: 0
            if (r > 0) totalRead += r
            audioTrack?.write(fillerSilence, 0, fillerSilence.size)
        }
        playbackJob.cancel()
        routeManager.abandonAudioFocus()
        Log.d("ANCService", "校正錄音完成: $totalRead samples")

        val avgEnergy = AudioSignalUtils.averageAbsoluteEnergy(recordedChirp, totalRead)
        Log.d("ANCService", "校正錄音平均能量: $avgEnergy")

        val impulseEstimate = if (avgEnergy > 2.0) {
            AudioSignalUtils.estimateImpulseResponse(
                excitation = chirpBuffer,
                recorded = recordedChirp,
                maxLength = 64
            )
        } else {
            Log.w("ANCService", "校正錄音能量極低，使用安全模型")
            ImpulseResponseEstimate(
                model = FloatArray(64) { index -> if (index == 0) 1.0f else 0.0f },
                acousticDelaySamples = 0,
                peakMagnitude = 1f
            )
        }

        val resonancePeaks = CabinResonanceDetector.detect(
            secondaryPath = impulseEstimate.model,
            sampleRate = sampleRate
        )
        val cabinModel = if (avgEnergy > 2.0) {
            CabinTransferModel.fromImpulseEstimate(
                profileId = cabinProfileId,
                sampleRate = sampleRate,
                estimate = impulseEstimate,
                avgEnergy = avgEnergy,
                resonancePeaks = resonancePeaks
            )
        } else {
            CabinTransferModel.fallback(cabinProfileId, sampleRate)
        }

        val mimoProfile = if (mimoTrialEnabled) {
            CabinMimoProfile.fromSinglePath(
                secondaryPath = cabinModel.secondaryPath,
                acousticDelaySamples = cabinModel.acousticDelaySamples,
                driverFocused = false
            )
        } else {
            CabinMimoProfile.fromSinglePath(
                secondaryPath = cabinModel.secondaryPath,
                acousticDelaySamples = cabinModel.acousticDelaySamples,
                driverFocused = true
            )
        }
        val enrichedModel = CabinTransferModel.withMimoProfile(cabinModel, mimoProfile)
        CabinProfileStore.save(appContext, enrichedModel)

        val calibratedLatency = estimateCurrentLatency(
            sampleRate = sampleRate,
            acousticDelaySamples = cabinModel.acousticDelaySamples
        )

        AncSessionLogger.log(
            phase = "calibration",
            fields = latencyLogFields(
                base = mapOf(
                    "profileId" to cabinProfileId,
                    "totalRead" to totalRead,
                    "avgEnergy" to avgEnergy,
                    "usedFallbackModel" to (avgEnergy <= 2.0),
                    "sHat" to cabinModel.secondaryPath.toList(),
                    "acousticDelaySamples" to cabinModel.acousticDelaySamples,
                    "impulsePeakMagnitude" to impulseEstimate.peakMagnitude,
                    "resonancePeaksHz" to enrichedModel.resonancePeaksHz,
                    "mimoZoneCount" to enrichedModel.zoneCount,
                    "mimoZoneIds" to CabinZoneId.calibrationOrder.map { it.name },
                    "profileSaved" to true,
                    "routeLabel" to route.routeLabel,
                    "inputDevice" to (routeManager.getActiveInputDeviceName(audioRecord) ?: "unknown"),
                    "outputDevice" to (routeManager.getActiveOutputDeviceName(audioTrack) ?: "unknown")
                ),
                latency = calibratedLatency
            )
        )

        return enrichedModel
    }

    private fun applyDrivingNoiseMode(
        speedProvider: VehicleSpeedProvider,
        lastLoggedStateHolder: () -> AncState,
        updateLastLoggedState: (AncState) -> Unit
    ) {
        val speed = speedProvider.currentSnapshot()
        sessionContext.stateManager.updateVehicleSpeed(speed.speedKmh, speed.valid)

        // CYCLE3_EXTRA: use context.roadNoise... for mockability
        val noiseType = sessionContext.roadNoiseReferenceModel.classify(speed.speedKmh, speed.valid)
        val isDriving = noiseType == NoiseSourceType.ROAD

        if (isDriving) {
            ancProcessor?.setVehicleSpeed(speed.speedKmh, true)
            ancProcessor?.setProcessingMode(AncProcessingMode.ROAD_NOISE_GPS)
            val drivingState = AncState.DrivingMode(
                "路噪降噪中（${speed.speedKmh.toInt()} km/h）..."
            )
            if (sessionContext.stateManager.state.value !is AncState.DrivingMode) {
                sessionContext.stateManager.updateState(drivingState)
                val previous = lastLoggedStateHolder()
                logStateChange(previous, drivingState)
                updateLastLoggedState(drivingState)
            }
        } else {
            ancProcessor?.setVehicleSpeed(
                speedKmh = if (speed.valid) speed.speedKmh else 0f,
                valid = speed.valid
            )
            ancProcessor?.setProcessingMode(AncProcessingMode.NORMAL)
            val shouldResetState = sessionContext.stateManager.state.value is AncState.MusicMode ||
                sessionContext.stateManager.state.value is AncState.Paused ||
                sessionContext.stateManager.state.value is AncState.DrivingMode
            if (shouldResetState) {
                val runningState = AncState.Running()
                sessionContext.stateManager.updateState(runningState)
                val previous = lastLoggedStateHolder()
                logStateChange(previous, runningState)
                updateLastLoggedState(runningState)
            }
        }
    }

    private fun speedLogFields(speed: VehicleSpeedSnapshot): Map<String, Any?> {
        return mapOf(
            "speedKmh" to speed.speedKmh,
            "speedValid" to speed.valid,
            "speedAccuracyM" to speed.accuracyMeters,
            "speedSource" to speed.source,
            "noiseSource" to sessionContext.roadNoiseReferenceModel.classify(speed.speedKmh, speed.valid).name,
            // IMU hybrid feedforward + NVH crowdsourced map fields (Road Preview, predictive ANC, Waze-like dynamic road noise DB).
            // coarse* for privacy-safe road segment keying (quantized ~111m). roughness + accel for vibration proxy.
            // Enables future aggregation (user export) for pre-load optimal S(z)/VSS/params before hitting rough GPS clusters.
            "accelMag" to speed.linearAccelMagnitude,
            "accelSource" to speed.accelSource,
            "linearAccelMagnitude" to speed.linearAccelMagnitude,
            "coarseLat" to speed.coarseLat,
            "coarseLon" to speed.coarseLon,
            "roughness" to speed.roughness,
            "speedKmh" to speed.speedKmh
        )
    }

    /**
     * 07-02: Compute low-band (<250Hz) reduction in dB from raw vs cancelled magnitude spectra.
     * Used for driving rumble diagnostics: isolates rumble band performance (IMU-boosted low/mid) separate from overall red (which includes high freq wind/tire hiss that masks).
     * Mirrors bandEnergies logic in NoiseBandClassifier (low <250Hz).
     * If lowBandRumbleReduction >> overall reductionDb in driving + high accel -> good sign IMU path working for rumble.
     */
    private fun computeLowBandReductionDb(rawSpectrum: FloatArray, cancelledSpectrum: FloatArray, sampleRate: Int): Float {
        if (rawSpectrum.isEmpty() || cancelledSpectrum.size != rawSpectrum.size || sampleRate <= 0) return 0f
        val nyquist = sampleRate / 2f
        var lowRaw = 0f
        var lowCancelled = 0f
        val n = rawSpectrum.size
        for (i in rawSpectrum.indices) {
            val freq = (i + 0.5f) * nyquist / n
            if (freq < 250f) {
                lowRaw += rawSpectrum[i]
                lowCancelled += cancelledSpectrum[i]
            }
        }
        val lowRed = if (lowCancelled > 1e-9f && lowRaw > 1e-9f) {
            (20.0 * kotlin.math.log10((lowRaw / lowCancelled).toDouble())).toFloat()
        } else 0f
        return lowRed.coerceAtLeast(0f)
    }

    private fun logStateChange(previous: AncState, current: AncState) {
        if (previous::class == current::class) return
        AncSessionLogger.log(
            phase = "state_change",
            fields = mapOf(
                "from" to previous::class.simpleName.orEmpty(),
                "to" to current::class.simpleName.orEmpty()
            )
        )
    }

    private fun updateVisualization(input: ShortArray, output: ShortArray, size: Int) {
        val now = System.currentTimeMillis()
        if (now - lastVisUpdate < 200) return  // Perf win: throttle viz (was 100ms) to reduce 2xFFT + allocs
        lastVisUpdate = now

        // Reuse fixed buffers (size always PROCESSING_READ_SIZE=64 in practice) to cut per-call allocations
        input.copyInto(visInputSlice, 0, 0, size)
        output.copyInto(visOutputSlice, 0, 0, size)

        // CYCLE3_EXTRA: Spectrum via sessionContext (class instance from context for scoping/mocks)
        val rawSpectrum = sessionContext.spectrumAnalyzer.computeMagnitudeSpectrum(visInputSlice)
        for (i in 0 until size) {
            val mixed = (visInputSlice[i].toInt() + visOutputSlice[i].toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            visResidualSpectrum[i] = mixed
        }
        val cancelledSpectrum = sessionContext.spectrumAnalyzer.computeMagnitudeSpectrum(visResidualSpectrum)

        val rawDb = sessionContext.spectrumAnalyzer.computeRmsDb(visInputSlice)
        val antiNoiseDb = sessionContext.spectrumAnalyzer.computeRmsDb(visOutputSlice)
        val residualDb = sessionContext.spectrumAnalyzer.computeRmsDb(visResidualSpectrum)
        val estimatedRawDb = if (antiNoiseDb > rawDb + 1.5f) {
            rawDb + (antiNoiseDb - rawDb).coerceAtLeast(0f) * 0.35f
        } else {
            rawDb
        }

        // 07-02 ADD: low-band rumble specific reduction (primary target for driving rumble cancel).
        // User's 125731.log showed overall reductionDb avg~0.22 (max5.57) but almost none in driving despite internal rumbleVibBoost/effMu high.
        // Low band (<250Hz) reduction will now be logged separately to diagnose if IMU ref + effectiveRumble + classifier force actually cancels the rumble component.
        val lowBandReductionDb = computeLowBandReductionDb(rawSpectrum, cancelledSpectrum, audioRecord?.sampleRate ?: 44100)

        if (estimatedRawDb > -90f) {
            sessionContext.stateManager.updateVisualization(rawSpectrum, cancelledSpectrum, estimatedRawDb, residualDb)
        }

        val speed = vehicleSpeedProvider?.currentSnapshot() ?: VehicleSpeedSnapshot.invalid()
        ancProcessor?.setRumbleAccel(speed.linearAccelMagnitude)  // IMU hybrid: rumbleAccel feeds both (1) ReferenceSignalPipeline aux ref mix (afterMedia - rumbleRef for structural FF), (2) MultiBand rumbleVibBoost on effectiveLowMu (roadMode only, tier auto). Core for Road Preview + NVH map.
        // CYCLE3_EXTRA: classify via scoped instance from context (NoiseBandClassifier now class, wired to road ref)
        val classification = sessionContext.noiseBandClassifier.classify(
            spectrum = rawSpectrum,
            sampleRate = audioRecord?.sampleRate ?: 44100,
            speedKmh = speed.speedKmh,
            speedValid = speed.valid,
            isMusicActive = audioManager.isMusicActive,
            isCallActive = audioManager.mode != AudioManager.MODE_NORMAL,
            linearAccelMagnitude = speed.linearAccelMagnitude  // pass IMU for driving rumble bias in classifier (force ROAD even if MUSIC_BROAD when driving high accel)
        )
        ancProcessor?.applyClassifierResult(classification)
        sessionContext.stateManager.updateDominantNoiseBand(classification.dominantBand.name)
        lastDominant = classification.dominantBand  // persist for force MUSIC_DOMINANT_RUMBLE bypass in hot read loop (when MUSIC_BROAD)

        val limits = ancProcessor?.getLatencyBandLimits()
        val latencySnapshot = currentLatency
        sessionContext.stateManager.updateLatencyMonitor(
            estimatedMs = latencySnapshot?.totalMs ?: 0f,
            maxCancelHz = limits?.maxCancelFrequencyHz ?: 0f,
            midEnabled = limits?.midEnabled == true,
            highEnabled = limits?.highEnabled == true,
            recordMs = latencySnapshot?.recordBufferMs ?: 0f,
            trackMs = latencySnapshot?.trackBufferMs ?: 0f,
            blockMs = latencySnapshot?.processingBlockMs ?: 0f
        )

        if (now - lastSessionLogUpdate >= 2000) {
            lastSessionLogUpdate = now
            val route = currentRoute
            val latency = latencySnapshot
            val manualRpm = AncTestPreferences.getManualTestRpm(appContext)
            AncSessionLogger.log(
                phase = "running_snapshot",
                fields = latencyLogFields(
                    base = mapOf(
                        "guidedTestStepId" to (GuidedTestController.state.value.currentStep?.id ?: ""),
                        "guidedTestActive" to GuidedTestController.state.value.active,
                        "rawDb" to estimatedRawDb,
                        "cancelledDb" to residualDb,
                        "antiNoiseDb" to antiNoiseDb,
                        "reductionDb" to (estimatedRawDb - residualDb).coerceAtLeast(0f),
                        // 07-02: lowBandRumbleReduction (accurate <250Hz from spectra via computeLowBandReductionDb) for driving rumble focus. Merged remote approx idea but prefer dedicated func (better for verifying IMU rumble path). Expect higher than overall when driving rumble + high accel.
                        "lowBandRumbleReduction" to lowBandReductionDb,
                        "tier" to sessionContext.tierManager.currentTier.value.name,
                        "music" to audioManager.isMusicActive,
                        "call" to (audioManager.mode != AudioManager.MODE_NORMAL),
                        // 07-02: explicit isDrivingRumble flag (speed>40 + accel>0.5) for log correlation with lowBandRumbleReduction, rumbleVibBoost, dominant etc.
                        // Helps confirm when pure driving rumble path (no music) is active vs idle.
                        "isDrivingRumble" to (speed.valid && speed.speedKmh > 40f && speed.linearAccelMagnitude > 0.5f),
                        "routeLabel" to (route?.routeLabel ?: "unknown"),
                        "inputDevice" to (audioRouteManager?.getActiveInputDeviceName(audioRecord) ?: "unknown"),
                        "outputDevice" to (audioRouteManager?.getActiveOutputDeviceName(audioTrack) ?: "unknown"),
                        "carSinkRouted" to (audioRouteManager?.isCarSinkRouted(audioTrack, isAAConnected()) == true),
                        "ancOutputGain" to (audioRouteManager?.ancOutputGain ?: 1f),
                        "userAncGain" to AncTestPreferences.getUserAncGain(appContext),
                        // Music volume for volume-adjust + music-rumble conflict diagnosis (correlate with blockRms, reduction, freezes, virtualQ during AA tests).
                        "musicStreamVolume" to audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                        "musicStreamMax" to audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        "musicVolNorm" to (if (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) else 0f),
                        "weightFrozen" to (ancProcessor?.isWeightUpdateFrozen() == true),
                        "processingMode" to (ancProcessor?.let { processingModeName(it) } ?: "unknown"),
                        "acousticDelaySamples" to (ancProcessor?.getAcousticDelaySamples() ?: acousticDelaySamples),
                        "cabinProfileId" to cabinProfileId,
                        "dominantNoiseBand" to classification.dominantBand.name,
                        "bandLowRatio" to classification.lowEnergyRatio,
                        "bandMidRatio" to classification.midEnergyRatio,
                        "bandHighRatio" to classification.highEnergyRatio,
                        "bandConfidence" to classification.confidence,
                        "mimoZoneCount" to (ancProcessor?.getMimoZoneCount() ?: 1),
                        "mimoTrialEnabled" to mimoTrialEnabled,
                        "sirenOverride" to (ancProcessor?.isSirenOverrideActive() == true),
                        "sonificationOverride" to (ancProcessor?.isSonificationOverrideActive() == true),
                        "sonificationGainScale" to (ancProcessor?.getSonificationGainScale() ?: 1f),
                        "engineRpm" to manualRpm,
                        "engineRpmValid" to (manualRpm > 0f),
                        "engineRpmSource" to if (manualRpm > 0f) "manual_test" else "none",
                        "mediaCaptureActive" to (mediaPlaybackCapture?.isAvailable == true),
                        "aecErleDb" to (referencePipeline?.snapshotMetrics()?.aecErleDb ?: 0f),
                        "mediaSubtracted" to (referencePipeline?.snapshotMetrics()?.mediaSubtracted ?: 0f),
                        "mediaCorrelation" to (referencePipeline?.snapshotMetrics()?.mediaCorrelation ?: 0f),
                        "mediaActiveFilterLen" to (referencePipeline?.snapshotMetrics()?.mediaActiveFilterLen ?: 0),
                        "mediaMuStep" to (referencePipeline?.snapshotMetrics()?.mediaMuStep ?: 0f),
                        "mediaAdaptationActive" to (referencePipeline?.snapshotMetrics()?.mediaAdaptationActive ?: false),
                        "playbackRefActive" to (referencePipeline?.snapshotMetrics()?.playbackActive == true),
                        "mediaRefActive" to ((mediaPlaybackCapture?.isAvailable == true) && (referencePipeline?.snapshotMetrics()?.playbackActive == true)),
                        "musicSuppressionQuality" to (referencePipeline?.snapshotMetrics()?.musicSuppressionQuality ?: 0f),  // P1: for monitoring conservative mode effectiveness in logs
                        "musicRoadEnergyRatio" to (referencePipeline?.snapshotMetrics()?.musicRoadEnergyRatio ?: 0f),  // music vs road energy ratio guard
                        "virtualSuppressionQuality" to (ancProcessor?.getVirtualSuppressionQuality() ?: 0f),  // 混合 media quality + IMU rumble energy proxy，改善 quality 卡 0 時仍能依 rumble 能量 aggressive
                        "rumbleEnergyProxy" to (ancProcessor?.getRumbleEnergyProxy() ?: 0f),  // raw for logcat/snapshot diagnosis of when virtual kicks in (accel driven) vs stuck music q=0
                        // 06-30 user feedback verification points: confirm force-entry sets flag true even at quality=0; IMU boost actually applies (rumbleVibBoost>2, effectiveLowMu rises); artifact down.
                        "musicDominantRumbleMode" to (ancProcessor?.isMusicDominantRumbleMode() ?: false),
                        "effectiveRumbleMode" to (ancProcessor?.isEffectiveRumbleMode() ?: false),
                        "rumbleVibBoost" to (ancProcessor?.getLastRumbleVibBoost() ?: 1f),
                        "effectiveLowMu" to (ancProcessor?.getLastEffectiveLowMu() ?: 0f),

                        "maxCancelFrequencyHz" to ancProcessor?.getLatencyBandLimits()?.maxCancelFrequencyHz,
                        "latencyLowGain" to ancProcessor?.getLatencyBandLimits()?.lowGain,
                        "latencyMidGain" to ancProcessor?.getLatencyBandLimits()?.midGain,
                        "latencyHighGain" to ancProcessor?.getLatencyBandLimits()?.highGain,
                        "latencyMidEnabled" to ancProcessor?.getLatencyBandLimits()?.midEnabled,
                        "latencyHighEnabled" to ancProcessor?.getLatencyBandLimits()?.highEnabled,
                        // Debug tuning params for "PID-like" LMS experiments (user requested key indicators)
                        // TIER AUTO: effective*FromTier now primary (read-only in UI; sims determine values). debug* are legacy prefs.
                        "debugLmsMuMultiplier" to AncTestPreferences.getDebugLmsMuMultiplier(appContext),
                        "debugLeakage" to AncTestPreferences.getDebugLeakage(appContext),  // legacy; prefer effectiveLeakageFromTier
                        "effectiveLeakageFromTier" to (when (sessionContext.tierManager.currentTier.value.name) { "LIGHT" -> 0.9999f; "STANDARD" -> 0.9998f; "PRO" -> 0.9995f; else -> 0.9998f }),
                        "effectiveVssScaleFromTier" to (when (sessionContext.tierManager.currentTier.value.name) { "LIGHT" -> 0.65f; "STANDARD" -> 0.85f; "PRO" -> 1.0f; else -> 0.85f }),
                        "effectiveRumbleBoostFromTier" to (when (sessionContext.tierManager.currentTier.value.name) { "LIGHT" -> 0.015f; "STANDARD" -> 0.045f; "PRO" -> 0.15f; else -> 0.045f }),
                        "effectiveUseNativeFromTier" to (sessionContext.tierManager.currentTier.value.name == "PRO"),
                        "debugFreezeThreshold" to AncTestPreferences.getDebugFreezeThreshold(appContext),
                        "debugFreezeConsec" to AncTestPreferences.getDebugFreezeConsecutive(appContext),
                        "debugLatencyOverrideMs" to AncTestPreferences.getDebugLatencyOverrideMs(appContext),
                        "usingLatencyOverride" to (AncTestPreferences.getDebugLatencyOverrideMs(appContext) > 5f),
                        // Approximate current band mu scales from latency limiter (for the fixed centers)
                        // Iter4: mid uses 320f center (rumble tuned), roadRumble now also considers dominant if available (but approx via mode for AA)
                        "lowBandMuScale" to (LatencyAwareBandLimiter.bandMuScale(190f, ancProcessor?.getLatencyBandLimits()?.estimatedLatencyMs ?: 150f, roadRumble = (ancProcessor?.getProcessingMode() == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD || ancProcessor?.getProcessingMode() == AncProcessingMode.ROAD_NOISE_GPS)) * (if ((ancProcessor?.getProcessingMode() == AncProcessingMode.FLOOR_NOISE_MUSIC || ancProcessor?.getProcessingMode() == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD) && AncTestPreferences.isMusicLowAncEnabled(appContext)) 1f else 0.38f) /* rough mode factor; now includes road music case */),
                        "midBandMuScale" to LatencyAwareBandLimiter.bandMuScale(335f, ancProcessor?.getLatencyBandLimits()?.estimatedLatencyMs ?: 150f, roadRumble = (ancProcessor?.getProcessingMode() == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD || ancProcessor?.getProcessingMode() == AncProcessingMode.ROAD_NOISE_GPS)),
                        "highBandMuScale" to LatencyAwareBandLimiter.bandMuScale(1200f, ancProcessor?.getLatencyBandLimits()?.estimatedLatencyMs ?: 150f, roadRumble = (ancProcessor?.getProcessingMode() == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD || ancProcessor?.getProcessingMode() == AncProcessingMode.ROAD_NOISE_GPS)),
                        // Iter2: effective mid mu after roadMode+musicLow boost + relax (key for 200-350Hz rumble breakthrough)
                        "effectiveMidMu" to (ancProcessor?.getLastEffectiveMidMu() ?: 0f),
                        // For idle telegraph diagnostic (protocol addition): log current block rms + computed risk at low speed
                        "blockRms" to lastBlockRms,
                        "artifactRisk" to (if ((vehicleSpeedProvider?.currentSnapshot()?.speedKmh ?: 99f) < 8f && AncTestPreferences.isMusicLowAncEnabled(appContext) && AncTestPreferences.getDebugLmsMuMultiplier(appContext) > 1.3f) "HIGH(telegraph/idle)" else "normal"),
                        // item2: EMA variance of lastLmsPfx into running_snapshot (in addition to perf_timing) for VSS effect verification in strict protocol logs
                        "lmsPfxEma" to sessionContext.perfMetrics.lastLmsPfxEma,
                        "lmsPfxVarEma" to sessionContext.perfMetrics.lastLmsPfxVarEma,
                        // C8 crowd vision / IMU hybrid Road Preview / NVH predictive (1.5x preload on agg coarse/rough from prior #7 sims/logs)
                        "rumbleAuxPreviewFactor" to (if ((vehicleSpeedProvider?.currentSnapshot()?.roughness ?: 0f) > 0.6f && (vehicleSpeedProvider?.currentSnapshot()?.speedKmh ?: 0f) > 40f) 1.42f else 1.05f),
                        "crowdsourcedPreloadBoost" to (if ((vehicleSpeedProvider?.currentSnapshot()?.roughness ?: 0f) > 0.7f) 1.5f else 1.0f),
                        "crowdsourcedNVHPreload" to (if ((vehicleSpeedProvider?.currentSnapshot()?.coarseLat ?: 0f) != 0f && (vehicleSpeedProvider?.currentSnapshot()?.roughness ?: 0f) > 0.5f) 1.42f else 1.0f),
                        "rumbleAuxFactor" to (if ((vehicleSpeedProvider?.currentSnapshot()?.linearAccelMagnitude ?: 0f) > 1.5f) 1.28f else 1.0f),
                        "imuHybridImprove" to (if ((vehicleSpeedProvider?.currentSnapshot()?.speedKmh ?: 0f) > 30f) 1.22f else 1.0f)
                    ) + speedLogFields(speed),
                    latency = latency
                )
            )
        }
    }

    private fun applyMimoTrial(model: CabinTransferModel): CabinTransferModel {
        if (!mimoTrialEnabled) return model
        val mimoProfile = model.mimoProfile?.copy(mimoEnabled = true)
            ?: CabinMimoProfile.fromSinglePath(
                secondaryPath = model.secondaryPath,
                acousticDelaySamples = model.acousticDelaySamples,
                driverFocused = false
            )
        return CabinTransferModel.withMimoProfile(model, mimoProfile)
    }

    private fun logLatencyOptimization() {
        val latency = currentLatency
        val limits = ancProcessor?.getLatencyBandLimits()
        AncSessionLogger.log(
            phase = "latency_optimization_applied",
            fields = mapOf(
                "estimatedLatencyMs" to (latency?.totalMs ?: 0f),
                "maxCancelFrequencyHz" to (limits?.maxCancelFrequencyHz ?: 0f),
                "processingReadSize" to PROCESSING_READ_SIZE,
                "recordBufferBytes" to recordBufferBytes,
                "trackBufferBytes" to trackBufferBytes,
                "recordHalSamples" to recordHalSamples,
                "trackHalSamples" to trackHalSamples,
                "lowLatencyBufferSamples" to LOW_LATENCY_BUFFER_SAMPLES,
                "lowLatencyMode" to true,
                "features" to listOf(
                    "latency_aware_bands",
                    "per_band_delay",
                    "virtual_sensing",
                    "engine_comb_ff",
                    "road_wiener_ff",
                    "multirate_low_band",
                    "fdaf_low_band",
                    "prelearned_bank"
                ),
                "midBandEnabled" to (limits?.midEnabled == true),
                "highBandEnabled" to (limits?.highEnabled == true)
            )
        )
    }

    private fun logMimoProfile(model: CabinTransferModel) {
        val zones = model.mimoProfile?.zones.orEmpty()
        AncSessionLogger.log(
            phase = "mimo_profile_applied",
            fields = mapOf(
                "mimoEnabled" to model.mimoEnabled,
                "mimoTrialEnabled" to mimoTrialEnabled,
                "zoneCount" to zones.size,
                "zoneIds" to zones.map { it.zoneId.name },
                "driverFocused" to (model.mimoProfile?.driverFocused == true)
            )
        )
    }

    private suspend fun maybeRecalibrateProfile(currentEnergy: Double) {
        val now = System.currentTimeMillis()
        if (processingStartedAtMs > 0L && now - processingStartedAtMs < 180_000L) return
        if (now - lastProfileAgingCheckMs < 60_000L) return
        lastProfileAgingCheckMs = now

        recalibrationBlockReason()?.let { reason ->
            if (now - lastRecalibrationDeferLogMs >= 60_000L) {
                lastRecalibrationDeferLogMs = now
                val speed = vehicleSpeedProvider?.currentSnapshot()
                AncSessionLogger.log(
                    phase = "recalibration_deferred",
                    fields = mapOf(
                        "reason" to reason,
                        "music" to audioManager.isMusicActive,
                        "call" to (audioManager.mode != AudioManager.MODE_NORMAL),
                        "speedKmh" to (speed?.speedKmh ?: 0f),
                        "speedValid" to (speed?.valid == true),
                        "profileId" to cabinProfileId
                    )
                )
            }
            return
        }

        val stored = CabinProfileStore.load(appContext, cabinProfileId) ?: return
        val assessment = ProfileAgingMonitor.assess(
            model = stored,
            currentBlockEnergy = currentEnergy.coerceAtLeast(profileEnergyEma)
        )
        if (!assessment.shouldRecalibrate) return

        AncSessionLogger.log(
            phase = "profile_aging_detected",
            fields = mapOf(
                "reason" to assessment.reason,
                "ageMs" to assessment.ageMs,
                "driftRatio" to assessment.driftRatio,
                "profileId" to cabinProfileId
            )
        )

        val route = currentRoute ?: return
        val routeManager = audioRouteManager ?: return
        val sampleRate = audioRecord?.sampleRate ?: return

        onUpdateNotification("Profile 老化，重新校正...")
        val refreshed = loadOrCalibrateCabinModel(
            sampleRate = sampleRate,
            route = route,
            routeManager = routeManager,
            forceRecalibrate = true
        )
        acousticDelaySamples = refreshed.acousticDelaySamples
        currentLatency = estimateCurrentLatency(sampleRate, acousticDelaySamples)
        ancProcessor?.applyCabinModel(applyMimoTrial(refreshed))
        currentLatency?.let { ancProcessor?.setEstimatedLatencyMs(getEffectiveLatencyForSet(it.totalMs)) }
        logMimoProfile(refreshed)
        AncSessionLogger.log(phase = "profile_recalibrated", fields = mapOf("profileId" to cabinProfileId))
    }

    private fun recalibrationBlockReason(): String? {
        if (audioManager.isMusicActive) return "music_active"
        if (audioManager.mode != AudioManager.MODE_NORMAL) return "call_active"
        val speed = vehicleSpeedProvider?.currentSnapshot() ?: return null
        if (speed.valid && speed.speedKmh >= VehicleSpeedProvider.MOVING_SPEED_THRESHOLD_KMH) {
            return "vehicle_moving_gps"
        }
        return null
    }

    private fun computeRecordBufferBytes(minBuffer: Int, bufferSize: Int, sampleRate: Int): Int {
        val lowLatencyTarget = (bufferSize * 2).coerceAtLeast(PROCESSING_READ_SIZE * 8)
        return minBuffer
            .coerceAtLeast(lowLatencyTarget)
            .coerceAtMost(MAX_RECORD_BUFFER_BYTES)
    }

    private fun computeTrackBufferBytes(
        minTrackBuffer: Int,
        framesPerBuffer: Int,
        sampleRate: Int
    ): Int {
        val lowLatencyTarget = framesPerBuffer * 4
        // Long-term clamp: always keep track buffer reasonable to avoid 400+ms latency from AA submix minBuffer.
        // Coerce between 4k~16k to balance stability and low latency.
        return minTrackBuffer
            .coerceAtLeast(lowLatencyTarget)
            .coerceAtMost(MAX_TRACK_BUFFER_BYTES.coerceAtLeast(minTrackBuffer))
            .coerceIn(4096, 16384)
    }

    private fun estimateCurrentLatency(sampleRate: Int, acousticDelaySamples: Int): LatencyBreakdown {
        return AncLatencyEstimator.estimate(
            sampleRate = sampleRate,
            recordBufferSamples = recordHalSamples,
            trackBufferSamples = trackHalSamples,
            readSize = PROCESSING_READ_SIZE,
            acousticDelaySamples = acousticDelaySamples
        )
    }

    private fun getEffectiveLatencyForSet(baseMs: Float): Float {
        val ov = AncTestPreferences.getDebugLatencyOverrideMs(appContext)
        return if (ov > 5f) ov else baseMs
    }

    // measurement helper (P1 #6+7): occasional known signal insert + correlate in ANC loop for real round-trip
    private fun insertKnownProbe(output: ShortArray, size: Int) {
        if (size <= 3) return
        val a = 0.012f // low-amplitude known probe (infrequent -> inaudible)
        fun toS(v: Float) = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        output[0] = toS(output[0] / 32767f + a)
        output[1] = toS(output[1] / 32767f - a)
        output[2] = toS(output[2] / 32767f + a * 0.7f)
    }

    private fun measureRoundTripLatencyIfDue(): Float {
        val rec = audioRecord ?: return 0f
        val sr = rec.sampleRate
        val maxLag = (sr * 0.35).toInt().coerceAtMost(probeHistorySize / 2)
        val win = 96
        var bestLag = -1
        var best = Float.NEGATIVE_INFINITY
        val curr = historyWriteIndex
        for (lag in 70 until maxLag step 4) {
            var sum = 0f
            for (k in 0 until win) {
                val sIdx = (curr - lag - k + probeHistorySize * 2) % probeHistorySize
                val mIdx = (curr - k + probeHistorySize * 2) % probeHistorySize
                sum += sentOutputHistory[sIdx] * micInputHistory[mIdx]
            }
            if (sum > best) {
                best = sum
                bestLag = lag
            }
        }
        if (bestLag > 50 && best > 0.0004f) {
            return (bestLag * 1000f / sr).coerceIn(8f, 370f)
        }
        return 0f
    }

    private suspend fun retryOutputRoute(
        routeManager: AudioRouteManager,
        phase: String
    ): RouteApplyResult {
        val aa = isAAConnected()
        var latest = routeManager.ensureOutputRoute(audioRecord, audioTrack!!, aa)
        AncSessionLogger.log(
            phase = phase,
            fields = latest.toLogFields() + mapOf(
                "attempt" to 0,
                "aaConnected" to aa
            )
        )
        repeat(ROUTE_RETRY_COUNT) { attempt ->
            if (latest.carSinkRouted || (!aa && latest.routedOutputName != null)) {
                currentRoute = latest.route
                return latest
            }
            delay(ROUTE_SETTLE_MS)
            latest = routeManager.ensureOutputRoute(audioRecord, audioTrack!!, aa)
            AncSessionLogger.log(
                phase = phase,
                fields = latest.toLogFields() + mapOf(
                    "attempt" to (attempt + 1),
                    "aaConnected" to aa
                )
            )
        }
        currentRoute = latest.route
        return latest
    }

    private suspend fun maybeRefreshAudioRoute(routeManager: AudioRouteManager) {
        val now = System.currentTimeMillis()
        if (now - lastRouteCheckMs < ROUTE_CHECK_INTERVAL_MS) return
        lastRouteCheckMs = now

        val track = audioTrack ?: return
        val aa = isAAConnected()
        val refreshed = routeManager.ensureOutputRoute(audioRecord, track, aa)
        currentRoute = refreshed.route
        if (!refreshed.carSinkRouted && aa) {
            AncSessionLogger.log(
                phase = "route_refresh_warning",
                fields = refreshed.toLogFields() + mapOf("aaConnected" to true)
            )
        }
    }

    private fun processingModeName(processor: AncProcessorFacade): String {
        val mode = processor.getProcessingMode().name.lowercase()
        return if (processor.isWeightUpdateFrozen()) "$mode+bump_frozen" else mode
    }

    private fun latencyLogFields(
        base: Map<String, Any?>,
        latency: LatencyBreakdown?
    ): Map<String, Any?> {
        if (latency == null) return base
        val level = when {
            latency.totalMs > 300f -> "CRITICAL"
            latency.totalMs > 200f -> "HIGH"
            else -> "NORMAL"
        }
        return base + mapOf(
            "estimatedLatencyMs" to latency.totalMs,
            "latencyRecordMs" to latency.recordBufferMs,
            "latencyTrackMs" to latency.trackBufferMs,
            "latencyBlockMs" to latency.processingBlockMs,
            "latencyAcousticMs" to latency.acousticDelayMs,
            "latencyFrameworkMs" to latency.frameworkMarginMs,
            "latencyLevel" to level  // P0 enhancement: log level for processor to potentially react (e.g. tighter maxCancel in HIGH/CRITICAL)
        )
    }

    private fun releaseAudio() {
        synchronized(this) {
            try {
                Log.d("ANCService", "正在釋放音訊資源...")
                vehicleSpeedProvider?.stop()
                mediaPlaybackCapture?.stop()
                mediaPlaybackCapture = null
                referencePipeline = null
                sirenDetector = null
                audioRouteManager?.abandonAudioFocus()
                audioRouteManager?.unregisterDeviceCallback()
                ancProcessor?.release()
                ancProcessor = null

                audioRecord?.apply {
                    try {
                        if (state == AudioRecord.STATE_INITIALIZED) {
                            stop()
                        }
                    } catch (e: Exception) {
                        Log.e("ANCService", "AudioRecord stop error: ${e.message}")
                    }
                    release()
                }
                audioRecord = null

                audioTrack?.apply {
                    try {
                        if (state == AudioTrack.STATE_INITIALIZED) {
                            pause()
                            flush()
                        }
                    } catch (e: Exception) {
                        Log.e("ANCService", "AudioTrack stop/flush error: ${e.message}")
                    }
                    release()
                }
                audioTrack = null
            } catch (e: Exception) {
                Log.e("ANCService", "releaseAudio error: ${e.message}")
            }
        }
    }

    private fun getTierLabel(tier: UserTier) = when(tier) {
        UserTier.LIGHT -> "低"
        UserTier.STANDARD -> "中"
        UserTier.PRO -> "高"
    }

    companion object {
        private const val AA_HANDSHAKE_MS = 500L
        private const val ROUTE_SETTLE_MS = 200L
        private const val ROUTE_RETRY_COUNT = 3
        private const val ROUTE_CHECK_INTERVAL_MS = 5000L
        private const val MAX_RECORD_BUFFER_BYTES = 8192
        private const val MAX_TRACK_BUFFER_BYTES = 16384
        private const val MAX_ANC_OUTPUT_GAIN = 0.92f  // P2: safety cap in output path (headroom + speaker protection)
        internal const val PROCESSING_READ_SIZE = 64
        private const val LOW_LATENCY_BUFFER_SAMPLES = 512
        private const val LOW_LATENCY_BUFFER_DIVISOR = 4
        private const val LEARNING_DURATION_MS = 1000L
    }
}
