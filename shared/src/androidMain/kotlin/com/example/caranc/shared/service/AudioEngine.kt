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
import com.example.caranc.shared.model.PlantPathStore
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
import com.example.caranc.shared.latency.AntiNoiseDelayLine
import com.example.caranc.shared.latency.PlantAlignedResidual
import com.example.caranc.shared.BoomPolarityAbTracker
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
    /** 1.2.18: silent USAGE_MEDIA so HU keeps pipe; ANC PCM is on overlay track. */
    private var mediaKeepAliveTrack: AudioTrack? = null
    private var keepAliveSilence = ShortArray(PROCESSING_READ_SIZE)
    private var sendLp1 = 0f
    private var sendLp2 = 0f
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
    /** #8: AUDIOTRACK_AA_SUBMIX | AAUDIO_LIKE_LOCAL_LOW_LATENCY */
    private var lastAudioBackendLabel: String = "unknown"

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
    /** 1.2.16: AA path self-check — brief 50 Hz after Running; PASS/FAIL once. */
    private var pathCheckUntilMs = 0L
    private var pathCheckPeakAbs = 0
    private var pathCheckDone = false
    private var pathCheckAa = false
    private var trackHalSamples = 0

    private var lastVisUpdate = 0L
    private var lastSessionLogUpdate = 0L
    /** In-app multi-band spectrum KPI (replaces missing external m4a for A/B). */
    private var lastSpectrumKpiMs = 0L
    /** Accumulator for anti band KPI (send-path spectrum before AA). */
    private val antiKpiAccum = ShortArray(8192)
    private var antiKpiWrite = 0
    private var diagTonePhase = 0.0
    private var lastDiagToneLogMs = 0L
    private var lastBlockRms = 0f  // for idle telegraph diagnostic in running_snapshot (protocol)
    private var lastBlockRmsVssScale = 1f  // VSS scale passed to processor based on blockRms + pfx variance for dynamic mu
    private var lastBumpLogMs = 0L
    private var bumpLogSuppressed = 0
    private var lastDominant = com.example.caranc.shared.model.DominantNoiseBand.MIXED  // for MUSIC_BROAD force to MUSIC_DOMINANT_RUMBLE even if quality calc stuck (06-30)

    // Closed-loop self-check: anti history for plant-aligned residual band KPI (not external recorder)
    private val antiDelayLine = AntiNoiseDelayLine(16384)
    private val visPlantResidual = ShortArray(PROCESSING_READ_SIZE)
    private var lastRawLowBandDb = -90f
    /** Mic 180–350 Hz band energy for polarity A/B cabin score (1.2.15). */
    private var lastRawMidBandDb = -90f
    private var lastResidualLowBandDb = -90f
    private var lastPlantResidualLowBandDb = -90f
    private var lastPlantResidualReductionDb = 0f
    private var lastBandE60 = -90f
    private var lastBandE80 = -90f
    private var lastBandE100 = -90f
    private var lastBandE120 = -90f
    private var lastOutputPathActive = false

    fun start() {
        if (processingJob?.isActive == true) return

        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // CYCLE3_EXTRA native low-freq proto: report availability once per engine start (JNI load happens in actual ctor)
                val nativeLowAvail = NativeLowBandProcessor.isNativeAvailable()
                Log.i("ANCService", "CYCLE3_EXTRA: NativeLowBandProcessor available=$nativeLowAvail (proto; cmake notes in shared/build.gradle.kts)")
                sessionContext.perfMetrics.nativeLowUsed = nativeLowAvail  // future: set true when hot path switches to it
                var handshakeWaitedMs = 0L
                if (!isAAConnected()) {
                    while (!isAAConnected() && handshakeWaitedMs < AA_HANDSHAKE_WAIT_MS) {
                        delay(AA_HANDSHAKE_POLL_MS)
                        handshakeWaitedMs += AA_HANDSHAKE_POLL_MS
                    }
                }
                delay(if (isAAConnected()) AA_HANDSHAKE_SETTLE_MS else 200L)
                AncSessionLogger.log(
                    phase = "aa_handshake",
                    fields = mapOf(
                        "waitedMs" to handshakeWaitedMs,
                        "aaConnected" to isAAConnected(),
                        "note" to "wait_CarConnection_then_settle_before_route"
                    )
                )

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
                // Real AA only (car USB or Desktop Head Unit on PC). See scripts/start-dhu.ps1.
                val aa = isAAConnected()
                val route = routeManager.resolveRoute(aa)
                currentRoute = route
                val focusGranted = routeManager.prepareRunningAudioMix(aa)
                AncSessionLogger.log(
                    phase = "aa_media_focus",
                    fields = mapOf(
                        "aaConnected" to aa,
                        "granted" to focusGranted,
                        "holding" to routeManager.isHoldingRunningFocus(),
                        "requestResult" to routeManager.lastFocusRequestResult(),
                        "trackUsage" to routeManager.currentTrackUsageLabel(aa),
                        "note" to "1.2.16_hold_USAGE_MEDIA_for_AA_speaker_path"
                    )
                )

                val currentTier = sessionContext.tierManager.currentTier.value
                sessionContext.stateManager.updateState(AncState.Calibrating())

                val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                val sampleRate = sampleRateStr?.toIntOrNull() ?: 44100
                GuidedCabinRecorder.setEngineSampleRate(sampleRate)
                val framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                    ?.toIntOrNull()
                    ?.coerceAtLeast(64) ?: 256

                val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val minTrackBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

                // #9: wired AA preferred; log wireless suspicion (do not hard-crash — warn + tag for analysis)
                routeManager.requireWiredAa = true
                if (aa && route.wirelessAaSuspected) {
                    Log.w(
                        "ANCService",
                        "WIRELESS_AA_WARNING: prefer USB Android Auto. wirelessSuspected=${route.wirelessAaSuspected} " +
                            "wiredAvailable=${route.wiredCarPathAvailable} label=${route.routeLabel}"
                    )
                    AncSessionLogger.log(
                        phase = "wireless_aa_warning",
                        fields = mapOf(
                            "wirelessAaSuspected" to true,
                            "wiredCarPathAvailable" to route.wiredCarPathAvailable,
                            "routeLabel" to route.routeLabel,
                            "requireWiredAa" to true
                        )
                    )
                }

                var audioBackendLabel = "AUDIOTRACK_AA_SUBMIX"
                if (!aa) {
                    // #8: local garage / phone-speaker path — AAudio-like low latency (not used for AA)
                    val local = LocalLowLatencyAudio.plan(
                        audioManager = audioManager,
                        sampleRate = sampleRate,
                        framesPerBuffer = framesPerBuffer,
                        minRecord = minBuffer,
                        minTrack = minTrackBuffer
                    )
                    recordBufferBytes = local.recordBufferBytes
                    trackBufferBytes = local.trackBufferBytes
                    recordHalSamples = recordBufferBytes / 2
                    trackHalSamples = trackBufferBytes / 2
                    audioRecord = LocalLowLatencyAudio.buildRecord(local, sampleRate)
                    audioTrack = LocalLowLatencyAudio.buildTrack(local, sampleRate)
                    audioBackendLabel = local.backendLabel
                    Log.i("ANCService", "AUDIO_BACKEND=$audioBackendLabel (non-AA local validation path)")
                } else {
                    val bufferSize = LOW_LATENCY_BUFFER_SAMPLES.coerceAtLeast(
                        maxOf(minBuffer, minTrackBuffer) / LOW_LATENCY_BUFFER_DIVISOR
                    ).coerceAtMost(1024)

                    recordBufferBytes = computeRecordBufferBytes(minBuffer, bufferSize, sampleRate)
                    trackBufferBytes = computeTrackBufferBytes(minTrackBuffer, framesPerBuffer, sampleRate)

                    // AA remote_submix: force smaller track buffer (16384 often still ~200ms+; try 8192)
                    val isHighLatencyRoute = trackBufferBytes > 12000 || minTrackBuffer > 8192
                    if (isHighLatencyRoute || aa) {
                        val forced = 8192
                        Log.w(
                            "ANCService",
                            "HIGH_LATENCY_AA_DETECTED: trackBuffer was $trackBufferBytes (minTrack=$minTrackBuffer), forcing $forced. aa=$aa"
                        )
                        trackBufferBytes = forced.coerceAtMost(trackBufferBytes.coerceAtLeast(4096))
                        if (trackBufferBytes > 8192) trackBufferBytes = 8192
                    }
                    recordHalSamples = recordBufferBytes / 2
                    trackHalSamples = trackBufferBytes / 2

                    var audioSource = route.audioSource
                    audioRecord = AudioRecord(
                        audioSource, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes
                    )
                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED &&
                        audioSource == MediaRecorder.AudioSource.UNPROCESSED
                    ) {
                        audioRecord?.release()
                        audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                        audioRecord = AudioRecord(
                            audioSource, sampleRate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferBytes
                        )
                    }

                    val trackAttributes = routeManager.buildTrackAudioAttributes(aa)
                    val audioTrackBuilder = AudioTrack.Builder()
                        .setAudioAttributes(trackAttributes)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(trackBufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Still request low-latency mode; AA host may ignore and use submix
                        audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                    audioTrack = audioTrackBuilder.build()
                    // Silent MEDIA keep-alive (music bus open) — ANC is NAV overlay, not MUSIC
                    val keepAliveBuilder = AudioTrack.Builder()
                        .setAudioAttributes(routeManager.buildKeepAliveAudioAttributes())
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(trackBufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        keepAliveBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                    mediaKeepAliveTrack = keepAliveBuilder.build()
                    audioBackendLabel = "AUDIOTRACK_AA_NAV_OVERLAY+MEDIA_KEEPALIVE"
                    Log.i("ANCService", "AUDIO_BACKEND=$audioBackendLabel")
                }
                lastAudioBackendLabel = audioBackendLabel

                val initialRoute = routeManager.applyPreferredDevices(
                    audioRecord = audioRecord!!,
                    audioTrack = audioTrack!!,
                    route = route
                )
                mediaKeepAliveTrack?.let { ka ->
                    try {
                        routeManager.ensureOutputRoute(null, ka, aa)
                    } catch (_: Exception) {
                    }
                }

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
                            "bufferSize" to recordBufferBytes,
                            "recordBufferBytes" to recordBufferBytes,
                            "trackBufferBytes" to trackBufferBytes,
                            "recordHalSamples" to recordHalSamples,
                            "trackHalSamples" to trackHalSamples,
                            "framesPerBuffer" to framesPerBuffer,
                            "readSize" to PROCESSING_READ_SIZE,
                            "minRecordBuffer" to minBuffer,
                            "minTrackBuffer" to minTrackBuffer,
                            "audioSource" to (route.audioSource),
                            "audioBackend" to lastAudioBackendLabel,
                            "wirelessAaSuspected" to route.wirelessAaSuspected,
                            "wiredCarPathAvailable" to route.wiredCarPathAvailable,
                            "requireWiredAa" to routeManager.requireWiredAa,
                            "tier" to currentTier.name,
                            "audioFocusMode" to if (aa) "hold_USAGE_MEDIA_GAIN" else "local_no_hold",
                            "audioFocusGranted" to focusGranted,
                            "holdingMediaFocus" to routeManager.isHoldingRunningFocus(),
                            "aaConnected" to aa,
                            "trackUsage" to routeManager.currentTrackUsageLabel(aa),
                            "aaPreferMediaTrack" to routeManager.aaPreferMediaTrack,
                            "acousticDelaySamples" to 0,
                            "inputDevice" to (activeInput ?: route.inputDeviceName ?: "unknown"),
                            "outputDevice" to (activeOutput ?: route.outputDeviceName ?: "unknown")
                        ) + initialRoute.toLogFields(),
                        latency = latency
                    )
                )

                audioRecord?.startRecording()
                audioTrack?.play()
                try {
                    mediaKeepAliveTrack?.play()
                } catch (_: Exception) {
                }

                val primingSize = sampleRate / 4
                val silence = ShortArray(primingSize)
                audioTrack?.write(silence, 0, silence.size)
                try {
                    mediaKeepAliveTrack?.write(silence, 0, silence.size)
                } catch (_: Exception) {
                }

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
                    bufferSize = recordBufferBytes.coerceAtLeast(PROCESSING_READ_SIZE),
                    initialTier = initialTier
                )
                AncSessionLogger.log(
                    phase = "processor_created",
                    fields = mapOf("initialTier" to initialTier.name)
                )
                ancProcessor?.applyCabinModel(applyMimoTrial(cabinModel))
                currentLatency?.let { applyMeasuredLatencyToProcessor(getEffectiveLatencyForSet(it.totalMs), it) }
                // 1.2.9 P2: load persisted plant electrical delay for this profile+route
                val routeLabel0 = routeAfterPlay.route.routeLabel
                val plantSnap = PlantPathStore.loadBest(appContext, cabinProfileId, routeLabel0)
                if (plantSnap != null && plantSnap.electricalDelaySamples > 64) {
                    val applied = ancProcessor?.refinePlantDelayFromProbe(plantSnap.electricalDelaySamples)
                        ?: plantSnap.electricalDelaySamples
                    ancProcessor?.applyPersistedBoomPolarity(plantSnap.boomPolarity)
                    AncSessionLogger.log(
                        phase = "plant_path_loaded",
                        fields = mapOf(
                            "profileId" to cabinProfileId,
                            "routeLabel" to routeLabel0,
                            "electricalDelaySamples" to plantSnap.electricalDelaySamples,
                            "appliedSamples" to applied,
                            "probeCorrMs" to plantSnap.probeCorrMs,
                            "cabinAcousticDelaySamples" to plantSnap.cabinAcousticDelaySamples,
                            "boomPolarity" to plantSnap.boomPolarity,
                            "updatedEpochMs" to plantSnap.updatedEpochMs
                        )
                    )
                } else {
                    // 1.2.14: no store → cabin-preferred default −1
                    ancProcessor?.applyPersistedBoomPolarity(com.example.caranc.shared.BoomPolarityAbTracker.DEFAULT_POLARITY)
                }
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
                            mediaKeepAliveTrack?.play()
                            mediaKeepAliveTrack?.write(learnSilence, 0, learnSilence.size)
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

                // 1.2.16 path self-check: 1.5s of 50 Hz after Running (AA only); log PASS/FAIL once
                pathCheckAa = aa
                pathCheckDone = false
                pathCheckPeakAbs = 0
                if (aa) {
                    // Ensure focus still held after learning/calib
                    routeManager.requestRunningMediaFocus(true)
                    pathCheckUntilMs = System.currentTimeMillis() + 1500L
                    AncSessionLogger.log(
                        phase = "aa_path_check_start",
                        fields = mapOf(
                            "hz" to 50f,
                            "durationMs" to 1500,
                            "holdingFocus" to routeManager.isHoldingRunningFocus(),
                            "trackUsage" to routeManager.currentTrackUsageLabel(true),
                            "note" to "listen_for_50Hz_bass_music_off_PASS_if_heard"
                        )
                    )
                } else {
                    pathCheckUntilMs = 0L
                }

                AncSessionLogger.log(
                    phase = "running_start",
                    fields = currentLatency?.let { latency ->
                        latencyLogFields(
                            base = mapOf(
                                "acousticDelaySamples" to acousticDelaySamples,
                                "readSize" to readSize,
                                "holdingMediaFocus" to routeManager.isHoldingRunningFocus(),
                                "trackUsage" to routeManager.currentTrackUsageLabel(aa)
                            ),
                            latency = latency
                        )
                    } ?: mapOf(
                        "readSize" to readSize,
                        "holdingMediaFocus" to routeManager.isHoldingRunningFocus()
                    )
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
                        if (GuidedCabinRecorder.isRecording()) {
                            GuidedCabinRecorder.append(input, read)
                        }
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
                            rumbleDominantForThisBlock = true
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
                        val diagHzNow = AncTestPreferences.getDiagToneHz(appContext)
                        // 1.2.18: our own overlay/keepalive is not a "notification" — skip ducking during 50Hz diag
                        if (sonifDet != null && diagHzNow < 40f) {
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
                        } else {
                            ancProcessor?.setSonificationOverride(false, 1f)
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
                            // Rate-limit: 24k bump lines per drive drowned real events & bloated logs
                            val nowBump = System.currentTimeMillis()
                            if (nowBump - lastBumpLogMs >= 2000L) {
                                lastBumpLogMs = nowBump
                                bumpLogSuppressed = 0
                                AncSessionLogger.log(
                                    phase = "bump_detected",
                                    fields = mapOf(
                                        "blockRms" to blockRms,
                                        "frozen" to true,
                                        "freezeRemaining" to freezeRemaining,
                                        "suppressedSinceLastLog" to 0
                                    )
                                )
                            } else {
                                bumpLogSuppressed++
                                if (bumpLogSuppressed == 1 || bumpLogSuppressed % 50 == 0) {
                                    Log.d(
                                        "ANCService",
                                        "bump_detected (throttled x$bumpLogSuppressed) rms=${"%.4f".format(blockRms)}"
                                    )
                                }
                            }
                        }

                        // always expose current freeze state in perf for diagnosis (even if not newly triggered)
                        if (blockCount % 200 == 0L || freezeRemaining > 0) {
                            Log.d("ANCService", "freeze_state: remaining=$freezeRemaining blockRms=${"%.4f".format(blockRms)}")
                        }

                        // 1.2.12: mute + polarity MUST be applied before process() so boom/notch/KPI match send
                        val userGain = AncTestPreferences.getUserAncGain(appContext)
                        val muteAnti = AncTestPreferences.isMuteAntiOutput(appContext) || userGain <= 0.001f
                        ancProcessor?.setAntiOutputMuted(muteAnti)
                        val forcePol = AncTestPreferences.getForceBoomPolarity(appContext)
                        ancProcessor?.setBoomPolarityForced(if (forcePol == 0f) null else forcePol)

                        val processed = ancProcessor?.process(preprocessed) ?: preprocessed

                        (ancProcessor as? MultiBandANCProcessor)?.consumePolarityFlipLog()?.let { (before, after) ->
                            AncSessionLogger.log(
                                phase = "boom_polarity_flip",
                                fields = mapOf(
                                    "from" to before,
                                    "to" to after,
                                    "note" to "1.2.12_auto_flip_neg_corr"
                                )
                            )
                        }

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
                        // IDLE ARTIFACT SUPPRESS (minimal, speed<8 only): auto lower effective gain at idle/low speed to mask any residual telegraph clicks from low-energy LMS (musicLow + high mu).
                        // Full gain at 50+kmh for #6/#7 rumble breakthrough validation (effMid 0.6+). User can still override via TestLogPanel but idle caps it.
                        val speedForGain = vehicleSpeedProvider?.currentSnapshot() ?: VehicleSpeedSnapshot.invalid()
                        val idleGainFactor = if (speedForGain.valid && speedForGain.speedKmh < 8f) 0.65f else 1f
                        // 1.2.8 P4-lite: entertainment loud → freeze-ish attenuate anti (Bose-style)
                        val musicVol = try {
                            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
                            cur / max
                        } catch (_: Exception) {
                            0f
                        }
                        val musicGate = when {
                            musicVol >= 0.85f -> 0.05f  // near mute anti
                            musicVol >= 0.65f -> 0.35f  // attenuate
                            musicVol >= 0.45f -> 0.70f
                            else -> 1f
                        }
                        val finalWriteGain = if (muteAnti) {
                            0f
                        } else {
                            cappedGain * antiArtifactGain * userGain * idleGainFactor * musicGate
                        }
                        // reuse buffer (hot-path opt, similar to push buffer reuse)
                        if (outputBufferReuse.size < read) outputBufferReuse = ShortArray(read)
                        scaleSamplesInto(processed, read, finalWriteGain, outputBufferReuse)
                        val output = outputBufferReuse

                        // 1.2.16 path self-check tone (AA, first 1.5s of Running) — before guided mute/diag
                        val nowPath = System.currentTimeMillis()
                        val pathChecking = pathCheckAa && !pathCheckDone && pathCheckUntilMs > 0L && nowPath < pathCheckUntilMs
                        if (pathChecking && !muteAnti) {
                            injectDiagTone(output, read, 50f, sampleRate = audioRecord?.sampleRate ?: 44100)
                            for (j in 0 until read) {
                                val a = kotlin.math.abs(output[j].toInt())
                                if (a > pathCheckPeakAbs) pathCheckPeakAbs = a
                            }
                        } else if (pathCheckAa && !pathCheckDone && pathCheckUntilMs > 0L && nowPath >= pathCheckUntilMs) {
                            pathCheckDone = true
                            val rm = audioRouteManager
                            val holding = rm?.isRunningMediaPathLive() == true
                            val usage = rm?.currentTrackUsageLabel(true) ?: "?"
                            val routedName = rm?.getActiveOutputDeviceName(audioTrack) ?: "?"
                            val submixOk = routedName.contains("submix", ignoreCase = true)
                            val sinkOk = submixOk ||
                                routedName.contains("usb", ignoreCase = true) ||
                                routedName.contains("bus", ignoreCase = true) ||
                                routedName.contains("car", ignoreCase = true)
                            val sendOk = pathCheckPeakAbs >= 2000 // send-buffer peak only — not HU speakers
                            val usageOk = usage.contains("NAVIGATION", ignoreCase = true) ||
                                usage.contains("MEDIA", ignoreCase = true)
                            // 1.2.18: overlay send+sink is the speaker path; exclusive MEDIA focus is not required
                            val pass = sendOk && sinkOk && usageOk
                            val failReasons = buildList {
                                if (!sendOk) add("send_tone_peak_too_low")
                                if (!sinkOk) add("not_car_or_submix_sink")
                                if (!usageOk) add("trackUsage_not_overlay")
                                if (!holding) add("keepalive_focus_not_live")
                                if (rm?.lastFocusRequestResult() == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                                    add("focus_was_DELAYED")
                                }
                            }
                            AncSessionLogger.log(
                                phase = "aa_path_check",
                                fields = mapOf(
                                    "result" to if (pass) "PASS" else "FAIL",
                                    "holdingMediaFocus" to holding,
                                    "holdingRequested" to (rm?.isHoldingRunningFocus() == true),
                                    "focusRequestResult" to (rm?.lastFocusRequestResult() ?: -1),
                                    "lastFocusChange" to (rm?.lastFocusChange() ?: 0),
                                    "trackUsage" to usage,
                                    "routedOutput" to routedName,
                                    "submixOk" to submixOk,
                                    "sinkOk" to sinkOk,
                                    "pathCheckPeakAbs" to pathCheckPeakAbs,
                                    "sendOk" to sendOk,
                                    "failReasons" to failReasons.joinToString(","),
                                    "note" to if (pass) {
                                        "SEND_AND_SINK_OK_listen_for_50Hz_to_confirm_HU_speakers"
                                    } else {
                                        "FOCUS_SINK_OR_SEND_FAIL_do_not_trust_cancel_KPI"
                                    }
                                )
                            )
                            Log.i(
                                "ANCService",
                                "aa_path_check ${if (pass) "PASS" else "FAIL"} focus=$holding usage=$usage peak=$pathCheckPeakAbs routed=$routedName"
                            )
                        }

                        // 1.2.8/1.2.18: 50Hz overlay (replaces anti). Else extra send LPF vs cabin hiss.
                        val diagHz = if (muteAnti) 0f else AncTestPreferences.getDiagToneHz(appContext)
                        if (!pathChecking && diagHz >= 40f && diagHz <= 100f) {
                            injectDiagTone(output, read, diagHz, sampleRate = audioRecord?.sampleRate ?: 44100)
                            val nowT = System.currentTimeMillis()
                            if (nowT - lastDiagToneLogMs >= 2000L) {
                                lastDiagToneLogMs = nowT
                                AncSessionLogger.log(
                                    phase = "diag_tone_active",
                                    fields = mapOf(
                                        "hz" to diagHz,
                                        "holdingMediaFocus" to (audioRouteManager?.isHoldingRunningFocus() == true),
                                        "pathLive" to (audioRouteManager?.isRunningMediaPathLive() == true),
                                        "trackUsage" to (audioRouteManager?.currentTrackUsageLabel(isAAConnected()) ?: "?"),
                                        "note" to "1.2.18_50Hz_NAV_overlay_not_MUSIC_mix"
                                    )
                                )
                            }
                        } else if (!pathChecking && !muteAnti) {
                            lowPassSendInPlace(output, read, sampleRate = audioRecord?.sampleRate ?: 44100)
                        }

                        // Accumulate anti PCM for send-path band energy KPI (post-mute write buffer)
                        for (j in 0 until read) {
                            antiKpiAccum[antiKpiWrite] = output[j]
                            antiKpiWrite = (antiKpiWrite + 1) % antiKpiAccum.size
                        }

                        // occasional known signal insert (for runtime real latency meas round-trip in ANC loop)
                        // 1.2.12: never probe during mute — contaminates cabin off baseline
                        if (!muteAnti && blockCount % 900L == 123L && blockCount - lastProbeBlock > 650 && diagHz < 40f) {
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
                        val ka = mediaKeepAliveTrack
                        if (ka != null) {
                            if (keepAliveSilence.size < read) keepAliveSilence = ShortArray(read)
                            try {
                                ka.write(keepAliveSilence, 0, read)
                            } catch (_: Exception) {
                            }
                        }

                        // 1.2.12: KPI / plant residual must use **post-mute write** buffer (was pre-mute processed → fake antiNoiseDb≈−8 on mute)
                        updateVisualization(preprocessed, output, read)

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
                                // Probe updates measured e2e; keep plant on measured (not debug ov).
                                applyMeasuredLatencyToProcessor(
                                    getEffectiveLatencyForSet(runtimeMeasuredLatencyMs),
                                    currentLatency
                                )
                                ancProcessor?.setProbeCorrMs(sessionContext.perfMetrics.lastProbeCorrMs)
                                // 1.2.11: reject bogus ~12 ms AA autocorrelation peaks; do not shrink plant D / persist junk
                                val srProbe = audioRecord?.sampleRate ?: 44100
                                val plantSamp = (meas * srProbe / 1000f).toInt().coerceIn(64, 20000)
                                val beforePlant = ancProcessor?.getPlantElectricalDelaySamples() ?: 0
                                val mb = ancProcessor as? com.example.caranc.shared.MultiBandANCProcessor
                                val accepted = mb?.shouldAcceptPlantProbeSamples(plantSamp) ?: (meas >= 40f)
                                val routeLab = currentRoute?.routeLabel ?: "unknown"
                                if (!accepted) {
                                    AncSessionLogger.log(
                                        phase = "probe_rejected",
                                        fields = mapOf(
                                            "measuredMs" to meas,
                                            "plantSamp" to plantSamp,
                                            "keptPlantSamples" to beforePlant,
                                            "halTrackMs" to (currentLatency?.trackBufferMs ?: 0f),
                                            "halFrameworkMs" to (currentLatency?.frameworkMarginMs ?: 35f),
                                            "boomPlantCorr" to (ancProcessor?.getBoomPlantCorr() ?: 0f),
                                            "note" to "1.2.11_reject_false_peak_keep_HAL_plant"
                                        )
                                    )
                                } else {
                                    val appliedPlant = ancProcessor?.refinePlantDelayFromProbe(plantSamp) ?: plantSamp
                                    val polSave = ancProcessor?.getBoomPolarity() ?: 1f
                                    PlantPathStore.save(
                                        appContext,
                                        PlantPathStore.PlantPathSnapshot(
                                            profileId = cabinProfileId,
                                            routeLabel = routeLab,
                                            electricalDelaySamples = appliedPlant,
                                            probeCorrMs = meas,
                                            cabinAcousticDelaySamples = acousticDelaySamples,
                                            updatedEpochMs = System.currentTimeMillis(),
                                            boomPolarity = polSave
                                        )
                                    )
                                    AncSessionLogger.log(
                                        phase = "runtime_latency_correlated",
                                        fields = mapOf(
                                            "measuredMs" to meas,
                                            "smoothedMs" to runtimeMeasuredLatencyMs,
                                            "block" to blockCount,
                                            "corrComputeMs" to corrDtMs,
                                            "plantDelaySamplesApplied" to appliedPlant,
                                            "plantDelayMs" to (appliedPlant * 1000f / srProbe),
                                            "plantPathSaved" to true,
                                            "routeLabel" to routeLab,
                                            "boomPlantCorr" to (ancProcessor?.getBoomPlantCorr() ?: 0f)
                                        )
                                    )
                                    AncSessionLogger.log(
                                        phase = "plant_path_saved",
                                        fields = mapOf(
                                            "profileId" to cabinProfileId,
                                            "routeLabel" to routeLab,
                                            "electricalDelaySamples" to appliedPlant,
                                            "probeCorrMs" to meas,
                                            "cabinAcousticDelaySamples" to acousticDelaySamples,
                                            "boomPlantCorr" to (ancProcessor?.getBoomPlantCorr() ?: 0f),
                                            "boomPolarity" to (ancProcessor?.getBoomPolarity() ?: 1f),
                                            "note" to "P2_sHat_plant_delay_persisted"
                                        )
                                    )
                                }
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
        // 1.2.16: do NOT abandon focus after calib on AA — must keep MEDIA focus for speaker path.
        // Old bug: abandon here → whole road test ran with no lasting focus.
        if (isAAConnected()) {
            routeManager.requestRunningMediaFocus(true)
        } else {
            routeManager.abandonAudioFocus()
        }
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
        val provider = vehicleSpeedProvider
        return mapOf(
            "speedKmh" to speed.speedKmh,
            "speedValid" to speed.valid,
            "speedAccuracyM" to speed.accuracyMeters,
            "speedSource" to speed.source,
            "speedFusion" to (provider?.lastFusionSource ?: speed.source),
            "speedHoldAgeSec" to (provider?.lastHoldAgeSec ?: -1f),
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
        // Same-index residual (legacy viz) — note: for large plant D this is NOT true acoustic residual
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

        // Closed-loop self-check KPI (program-side, no external recorder):
        // push anti → plant-delay mix → low-band dB + fixed tones 60/80/100/120 Hz
        antiDelayLine.push(visOutputSlice, size)
        val plantD = (ancProcessor?.getPlantElectricalDelaySamples() ?: 0).coerceAtLeast(0)
        antiDelayLine.fillPlantMixed(visInputSlice, size, plantD, visPlantResidual)
        val sr = audioRecord?.sampleRate ?: 44100
        val sa = sessionContext.spectrumAnalyzer
        lastRawLowBandDb = sa.computeLowBandRmsDb(visInputSlice, sr, 150f)
        lastResidualLowBandDb = sa.computeLowBandRmsDb(visResidualSpectrum, sr, 150f)
        lastPlantResidualLowBandDb = sa.computeLowBandRmsDb(visPlantResidual, sr, 150f)
        lastPlantResidualReductionDb =
            (lastRawLowBandDb - lastPlantResidualLowBandDb).coerceIn(-30f, 40f)
        lastBandE60 = PlantAlignedResidual.bandEnergyDb(visInputSlice, sr, 60f, 0, size)
        lastBandE80 = PlantAlignedResidual.bandEnergyDb(visInputSlice, sr, 80f, 0, size)
        lastBandE100 = PlantAlignedResidual.bandEnergyDb(visInputSlice, sr, 100f, 0, size)
        lastBandE120 = PlantAlignedResidual.bandEnergyDb(visInputSlice, sr, 120f, 0, size)
        lastOutputPathActive = antiNoiseDb > -55f

        // 07-02 ADD: low-band rumble specific reduction (primary target for driving rumble cancel).
        val lowBandReductionDb = computeLowBandReductionDb(rawSpectrum, cancelledSpectrum, sr)

        val speed = vehicleSpeedProvider?.currentSnapshot() ?: VehicleSpeedSnapshot.invalid()

        // In-app multi-band spectrum KPI every ~2s — primary analysis source (no external m4a required)
        val nowKpi = System.currentTimeMillis()
        if (nowKpi - lastSpectrumKpiMs >= 2000L) {
            lastSpectrumKpiMs = nowKpi
            val mic = visInputSlice
            val plant = visPlantResidual
            val eMicBoom = sa.bandRangeEnergyDb(mic, sr, 40f, 120f)
            val ePlantBoom = sa.bandRangeEnergyDb(plant, sr, 40f, 120f)
            val eMicTire = sa.bandRangeEnergyDb(mic, sr, 180f, 350f)
            val ePlantTire = sa.bandRangeEnergyDb(plant, sr, 180f, 350f)
            // 1.2.15: keep mid band for polarity cabin score (low alone was tied → plant wrongly picked +1)
            lastRawMidBandDb = eMicTire
            lastRawLowBandDb = sa.bandRangeEnergyDb(mic, sr, 40f, 80f)
            val eMicWind = sa.bandRangeEnergyDb(mic, sr, 500f, 2000f)
            val ePlantWind = sa.bandRangeEnergyDb(plant, sr, 500f, 2000f)
            val gs = com.example.caranc.shared.test.GuidedTestController.state.value
            // 1.2.8: anti send-path bands (before AA) — proves App emits correct frequencies
            val antiSlice = ShortArray(antiKpiAccum.size)
            val aw = antiKpiWrite
            for (i in antiKpiAccum.indices) {
                antiSlice[i] = antiKpiAccum[(aw + i) % antiKpiAccum.size]
            }
            val antiE40_80 = sa.bandRangeEnergyDb(antiSlice, sr, 40f, 80f)
            val antiE80_120 = sa.bandRangeEnergyDb(antiSlice, sr, 80f, 120f)
            val antiE200_500 = sa.bandRangeEnergyDb(antiSlice, sr, 200f, 500f)
            val antiE500_2k = sa.bandRangeEnergyDb(antiSlice, sr, 500f, 2000f)
            val lf = antiE40_80
            val hf = antiE500_2k
            val lfDominates = lf > hf + 6f // boom mode should be LF-heavy
            AncSessionLogger.log(
                phase = "spectrum_kpi",
                fields = mapOf(
                    "micE40_120" to eMicBoom,
                    "plantE40_120" to ePlantBoom,
                    "deltaBoomDb" to (eMicBoom - ePlantBoom),
                    "micE180_350" to eMicTire,
                    "plantE180_350" to ePlantTire,
                    "deltaTireDb" to (eMicTire - ePlantTire),
                    "micE500_2000" to eMicWind,
                    "plantE500_2000" to ePlantWind,
                    "deltaWindDb" to (eMicWind - ePlantWind),
                    "micE40_80" to sa.bandRangeEnergyDb(mic, sr, 40f, 80f),
                    "micE80_150" to sa.bandRangeEnergyDb(mic, sr, 80f, 150f),
                    // ★ send-path anti spectrum (App → before AA)
                    "antiE40_80" to antiE40_80,
                    "antiE80_120" to antiE80_120,
                    "antiE200_500" to antiE200_500,
                    "antiE500_2k" to antiE500_2k,
                    "antiLfDominatesHf" to lfDominates,
                    "antiNoiseDb" to antiNoiseDb,
                    "boomPressureOut" to (ancProcessor?.getBoomPressureOut() ?: 0f),
                    "boomPlantCorr" to (ancProcessor?.getBoomPlantCorr() ?: 0f),
                    "plantDelaySamples" to (ancProcessor?.getPlantElectricalDelaySamples() ?: 0),
                    "boomPolarity" to (ancProcessor?.getBoomPolarity() ?: -1f),
                    "openBoom" to ((ancProcessor as? MultiBandANCProcessor)?.isOpenBoomActive() == true),
                    "latencyStrategy" to (ancProcessor?.getLatencyStrategy() ?: ""),
                    "speedKmh" to speed.speedKmh,
                    "nvhFocus" to (ancProcessor?.getNvhFocus() ?: "?"),
                    "forcedNvhFocus" to (com.example.caranc.shared.GuidedNvhOverride.forcedFocusName ?: "auto"),
                    "guidedStep" to (if (gs.active) (gs.currentStep?.id ?: "") else ""),
                    "diagToneHz" to AncTestPreferences.getDiagToneHz(appContext),
                    "muteAnti" to AncTestPreferences.isMuteAntiOutput(appContext),
                    "antiSendMuted" to AncTestPreferences.isMuteAntiOutput(appContext),
                    "userAncGain" to AncTestPreferences.getUserAncGain(appContext),
                    "forceBoomPolarity" to AncTestPreferences.getForceBoomPolarity(appContext),
                    "note" to "1.2.14_open_boom; cabin_polarity; mute_kpi; antiE_*=send"
                )
            )
        }

        // Real-time visibility for "when speakers produce sound" (user: can't know in real-time when ANC anti is played through AA speakers).
        // Prints to logcat when anti output is active (antiDb < -10 means significant speaker power).
        // User can run live on PC: adb -s 57191FDCG002KH logcat -s ANCService | findstr SPEAKER_ANTI
        // This gives timestamped "喇叭出現聲音" with context (speed, accel, red, mode, rumble flag) to correlate with what is heard (note ~0.5s delay from AA + latency).
        if (estimatedRawDb > -90f) {
            sessionContext.stateManager.updateVisualization(rawSpectrum, cancelledSpectrum, estimatedRawDb, residualDb)
        }

        ancProcessor?.setRumbleAccel(speed.linearAccelMagnitude)
        ancProcessor?.setImuAxes(speed.linearAccelX, speed.linearAccelY, speed.linearAccelZ)
        ancProcessor?.setRoadRoughness(speed.roughness)  // #7 speed×roughness pre-learned bank
        // CYCLE3_EXTRA: classify via scoped instance from context (NoiseBandClassifier now class, wired to road ref)
        val classification = sessionContext.noiseBandClassifier.classify(
            spectrum = rawSpectrum,
            sampleRate = audioRecord?.sampleRate ?: 44100,
            speedKmh = speed.speedKmh,
            speedValid = speed.valid,
            isMusicActive = audioManager.isMusicActive,
            isCallActive = audioManager.mode != AudioManager.MODE_NORMAL,
            linearAccelMagnitude = speed.linearAccelMagnitude,  // IMU prior for tire/road vs wind
            estimatedLatencyMs = currentLatency?.totalMs
                ?: ancProcessor?.getMeasuredLatencyMs()
                ?: 150f
        )

        // Real-time visibility for "when speakers produce sound" (user: can't know in real-time when ANC anti is played through AA speakers).
        // Prints to logcat when anti output is active (antiDb < -10 means significant speaker power).
        // User can run live on PC: adb -s 57191FDCG002KH logcat -s ANCService | findstr SPEAKER_ANTI
        // This gives timestamped "喇叭出現聲音" with context (speed, accel, red, mode, rumble flag) to correlate with what is heard (note ~0.5s delay from AA + latency).
        if (antiNoiseDb < -10f) {
            Log.d("ANCService", "SPEAKER_ANTI_ACTIVE: antiDb=${"%.1f".format(antiNoiseDb)} redDb=${"%.3f".format(estimatedRawDb - residualDb)} lowBandRed=${"%.2f".format(lowBandReductionDb)} mode=${processingModeName(ancProcessor!!)} effRumble=${ancProcessor?.isEffectiveRumbleMode() ?: false} speed=${"%.1f".format(speed.speedKmh)} accel=${"%.2f".format(speed.linearAccelMagnitude)} blockRms=${"%.4f".format(lastBlockRms)}")
        }
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
            val guidedStepId = GuidedTestController.state.value.currentStep?.id ?: ""
            // 1.2.15: polarity A/B — cabin low+mid score; discard low speed
            if (!AncTestPreferences.isMuteAntiOutput(appContext)) {
                BoomPolarityAbTracker.sample(
                    stepId = guidedStepId,
                    residualReductionDb = lastPlantResidualReductionDb,
                    cabinLowBandDb = lastRawLowBandDb,
                    speedKmh = speed.speedKmh,
                    cabinMidBandDb = lastRawMidBandDb
                )
            }
            AncSessionLogger.log(
                phase = "running_snapshot",
                fields = latencyLogFields(
                    base = mapOf(
                        "guidedTestStepId" to guidedStepId,
                        "guidedTestActive" to GuidedTestController.state.value.active,
                        "rawDb" to rawDb,
                        "rawDbEstimated" to estimatedRawDb,
                        "cancelledDb" to residualDb,
                        "antiNoiseDb" to antiNoiseDb,
                        // C-fix: honest reduction = mic raw vs residual (mic+anti electrical mix).
                        // Negative means anti made the sum louder (typical of uncorrelated white-noise anti).
                        // Old formula used estimatedRawDb (boosted when anti loud) + coerceAtLeast(0) which
                        // often plateaus ~3 dB and hid hiss. Keep legacy for A/B only.
                        "reductionDb" to (rawDb - residualDb),
                        "reductionDbLegacy" to (estimatedRawDb - residualDb).coerceAtLeast(0f),
                        // Primary KPI for driving rumble (docs C): low-band <250Hz spectral reduction
                        "lowBandRumbleReduction" to lowBandReductionDb,
                        "primaryReductionKpi" to "lowBandRumbleReduction",
                        // Product: 輪噪/路噪/風切 + 5 km/h speed-scheduled anti gains
                        "nvhFocus" to (ancProcessor?.getNvhFocus() ?: "MIXED_CABIN"),
                        "nvhTargetHz" to (ancProcessor?.getNvhTargetHzLabel() ?: ""),
                        "speedNvhBinKmh" to (ancProcessor?.getSpeedNvhBinKmh() ?: 0),
                        "speedNvhLowGain" to (ancProcessor?.getSpeedNvhLowGain() ?: 1f),
                        "speedNvhMidGain" to (ancProcessor?.getSpeedNvhMidGain() ?: 0.25f),
                        "speedNvhTotalAnti" to (ancProcessor?.getSpeedNvhTotalAnti() ?: 1f),
                        "speedNvhTableId" to (ancProcessor?.getSpeedNvhTableId() ?: "none"),
                        // 1.2.3–1.2.5: tire/wind notches + road boom lock KPIs
                        "tireNotchEnergy" to (ancProcessor?.getTireNotchEnergy() ?: 0f),
                        "windNotchEnergy" to (ancProcessor?.getWindNotchEnergy() ?: 0f),
                        "tireNotchF0Hz" to (ancProcessor?.getTireNotchF0Hz() ?: 0f),
                        "windNotchActiveCount" to (ancProcessor?.getWindNotchActiveCount() ?: 0),
                        "notchMixAnti" to (ancProcessor?.getNotchMixAnti() ?: 0f),
                        "roadNotchEnergy" to (ancProcessor?.getRoadNotchEnergy() ?: 0f),
                        "roadBoomWeightEnergy" to (ancProcessor?.getRoadBoomWeightEnergy() ?: 0f),
                        "boomPressureOut" to (ancProcessor?.getBoomPressureOut() ?: 0f),
                        "boomPlantCorr" to (ancProcessor?.getBoomPlantCorr() ?: 0f),
                        "boomPolarity" to (ancProcessor?.getBoomPolarity() ?: -1f),
                        "forceBoomPolarity" to AncTestPreferences.getForceBoomPolarity(appContext),
                        "openBoom" to ((ancProcessor as? MultiBandANCProcessor)?.isOpenBoomActive() == true),
                        "trackUsage" to (audioRouteManager?.currentTrackUsageLabel(isAAConnected()) ?: "?"),
                        "holdingMediaFocus" to (audioRouteManager?.isHoldingRunningFocus() == true),
                        "musicActive" to audioManager.isMusicActive,
                        "plantDelaySamples" to (ancProcessor?.getPlantElectricalDelaySamples() ?: 0),
                        "forcedNvhFocus" to (com.example.caranc.shared.GuidedNvhOverride.forcedFocusName ?: "auto"),
                        // Closed-loop self-check (program band energy — not external phone recorder)
                        "rawLowBandDb" to lastRawLowBandDb,
                        "residualLowBandDb" to lastResidualLowBandDb,
                        "plantResidualLowBandDb" to lastPlantResidualLowBandDb,
                        "plantResidualReductionDb" to lastPlantResidualReductionDb,
                        "antiSendMuted" to AncTestPreferences.isMuteAntiOutput(appContext),
                        "bandE60Db" to lastBandE60,
                        "bandE80Db" to lastBandE80,
                        "bandE100Db" to lastBandE100,
                        "bandE120Db" to lastBandE120,
                        "outputPathActive" to lastOutputPathActive,
                        "plantDelayForResidual" to (ancProcessor?.getPlantElectricalDelaySamples() ?: 0),
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
                        "muteAnti" to AncTestPreferences.isMuteAntiOutput(appContext),
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
                        // P0 AA high-lat strategy (FF_PREVIEW_ONLY when measured lat high + rumble)
                        "latencyStrategy" to (ancProcessor?.getLatencyStrategy() ?: "NORMAL"),
                        "measuredLatencyMs" to (ancProcessor?.getMeasuredLatencyMs() ?: 0f),
                        "plantElectricalDelaySamples" to (ancProcessor?.getPlantElectricalDelaySamples() ?: 0),
                        // P1: predictive FF diagnostics
                        "previewRumble" to (ancProcessor?.getPreviewRumble() ?: 0f),
                        "predictionHorizonMs" to (ancProcessor?.getPredictionHorizonMs() ?: 0f),
                        "previewHistoryAgeMs" to (ancProcessor?.getPreviewHistoryAgeMs() ?: 0f),
                        "previewHistoryCount" to (ancProcessor?.getPreviewHistoryCount() ?: 0),
                        "preLearnedBinCount" to (ancProcessor?.getPreLearnedBinCount() ?: 0),
                        "learnedBinCount" to (ancProcessor?.getLearnedBinCount() ?: 0),
                        "fixedBankOut" to (ancProcessor?.getLastFixedBankOut() ?: 0f),
                        // Literature / patent diagnostics (coherence gate + neural latent bank match)
                        "imuMicCoherence" to (ancProcessor?.getImuMicCoherenceQuality() ?: 0.5f),
                        "bankMatchQuality" to (ancProcessor?.getBankMatchQuality() ?: 0.5f),
                        "bankMatchCosine" to (ancProcessor?.getBankMatchCosine() ?: 0f),
                        "neuralLatentEnabled" to (ancProcessor?.isNeuralLatentEnabled() ?: false),
                        "latent0" to (ancProcessor?.getLatentDim0() ?: 0f),
                        "latent1" to (ancProcessor?.getLatentDim1() ?: 0f),
                        "latent2" to (ancProcessor?.getLatentDim2() ?: 0f),
                        "fdafDelayless" to (ancProcessor?.isFdafDelayless() ?: false),
                        "fdafPartitions" to (ancProcessor?.getFdafPartitionCount() ?: 0),
                        "audioBackend" to lastAudioBackendLabel,
                        "wirelessAaSuspected" to (currentRoute?.wirelessAaSuspected ?: false),
                        "wiredCarPathAvailable" to (currentRoute?.wiredCarPathAvailable ?: false),
                        "aaLinkType" to (currentRoute?.aaLinkType ?: "unknown"),
                        "roadRoughness" to (vehicleSpeedProvider?.currentSnapshot()?.roughness ?: 0f),
                        // debug ov for script A/B only — does NOT drive plant/maxCancel anymore
                        "debugLatencyOverrideMs" to AncTestPreferences.getDebugLatencyOverrideMs(appContext),
                        "usingLatencyOverride" to false,

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
                        // Approximate current band mu scales from latency limiter (MEASURED latency only)
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
        currentLatency?.let { applyMeasuredLatencyToProcessor(getEffectiveLatencyForSet(it.totalMs), it) }
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

    /**
     * P0: plant / maxCancel / FxLMS / FF strategy ALWAYS use measured path latency.
     * Script debugLatencyOverrideMs is for A/B logging only — never fake lower plant delay.
     */
    private fun getEffectiveLatencyForSet(baseMs: Float): Float = baseMs

    private fun applyMeasuredLatencyToProcessor(baseMs: Float, breakdown: LatencyBreakdown? = currentLatency) {
        val proc = ancProcessor ?: return
        proc.setEstimatedLatencyMs(baseMs)
        val sr = audioRecord?.sampleRate ?: 44100
        val bd = breakdown ?: estimateCurrentLatency(sr, acousticDelaySamples)
        proc.setMeasuredLatencyBreakdown(
            recordMs = bd.recordBufferMs,
            trackMs = bd.trackBufferMs,
            blockMs = bd.processingBlockMs,
            acousticMs = bd.acousticDelayMs,
            frameworkMs = bd.frameworkMarginMs
        )
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

    /** 1.2.8/1.2.18 pure sine for AA bass path (overlay mix, slightly louder). */
    private fun injectDiagTone(output: ShortArray, size: Int, hz: Float, sampleRate: Int) {
        if (size <= 0 || sampleRate <= 0) return
        val twoPi = 2.0 * Math.PI
        val amp = 0.38 // overlay bus is quieter than MUSIC; still clip-safe
        for (i in 0 until size) {
            val s = kotlin.math.sin(diagTonePhase) * amp
            diagTonePhase += twoPi * hz / sampleRate
            if (diagTonePhase > twoPi) diagTonePhase -= twoPi
            output[i] = (s * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    /** Extra 70 Hz dual pole on send (not diag tone) — cabin hiss was mid-high leak. */
    private fun lowPassSendInPlace(output: ShortArray, size: Int, sampleRate: Int) {
        val coeff = (2.0 * Math.PI * 70.0 / sampleRate).toFloat().coerceIn(0.003f, 0.08f)
        for (i in 0 until size) {
            val x = output[i] / 32768f
            sendLp1 += coeff * (x - sendLp1)
            sendLp2 += coeff * (sendLp1 - sendLp2)
            output[i] = (sendLp2 * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
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
        if (aa && !routeManager.isHoldingRunningFocus()) {
            val ok = routeManager.requestRunningMediaFocus(true)
            AncSessionLogger.log(
                phase = "aa_media_focus_rerequest",
                fields = mapOf(
                    "granted" to ok,
                    "pathLive" to routeManager.isRunningMediaPathLive(),
                    "requestResult" to routeManager.lastFocusRequestResult(),
                    "lastFocusChange" to routeManager.lastFocusChange()
                )
            )
        }
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
                mediaKeepAliveTrack?.apply {
                    try {
                        if (state == AudioTrack.STATE_INITIALIZED) {
                            pause()
                            flush()
                        }
                    } catch (_: Exception) {
                    }
                    release()
                }
                mediaKeepAliveTrack = null
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
        private const val AA_HANDSHAKE_WAIT_MS = 2000L
        private const val AA_HANDSHAKE_POLL_MS = 200L
        private const val AA_HANDSHAKE_SETTLE_MS = 400L
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
