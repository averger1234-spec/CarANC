import Foundation
import AVFoundation
import Combine
import CarANCShared

/// 本機 / CarPlay duplex 音訊引擎：麥克風 → **KMP MultiBandANCProcessor** → 輸出
/// - 本機：對齊 Android `LocalLowLatencyAudio`
/// - CarPlay：對齊 Android AA `AUDIOTRACK_AA_SUBMIX` 高延遲路徑（DSP SpeedScheduled 吸收）
final class AncAudioEngine: ObservableObject {
    private let model: AncAppModel
    private let engine = AVAudioEngine()
    /// Shared DSP from Kotlin Multiplatform (`CarANC.framework`)
    private var kmpProcessor: KotlinAncBridge?
    private let spectrum = SpectrumAnalyzer(barCount: 32, fftSize: 512)
    private let speedProvider = SpeedProvider()
    private let imuProvider = ImuProvider()

    private var isStarted = false
    private var sampleRate: Double = 48_000
    private var uiTimer: Timer?
    private var lastVisInput: [Float] = []
    private var lastVisAnti: [Float] = []
    private var blockCount: Int64 = 0
    private var sourceNode: AVAudioSourceNode?

    private var outputRing: [Float] = []
    private var outputWrite = 0
    private var outputRead = 0
    private let ringLock = NSLock()

    private var lastRawDb: Float = -90
    private var lastAntiDb: Float = -90
    private var measuredLatencyMs: Float = 40
    private var uiTickCount = 0
    private var lastLowUpdates: Int64 = 0
    private var lastMidUpdates: Int64 = 0
    private var lastCoherence: Float = 0
    private var lastBankMatch: Float = 0
    private var lastFixedBankOut: Float = 0
    /// Captured on main at start/tier change — safe for audio thread
    private var activeTier: UserTier = .light
    private var preferCarAudio = false
    /// 1.2.8 診斷 tone（音訊執行緒讀取）
    private var diagToneHz: Float = 0
    private var diagTonePhase: Double = 0
    /// 1.2.10–1.2.12 mute / gain / polarity（音訊執行緒讀取）
    private var muteAnti = false
    private var userAncGain: Float = 1
    private var forceBoomPolarity: Float = 0
    private var lastPlantResidualReductionDb: Float = 0
    /// 通話／中斷：音訊執行緒讀；1=正常，0=靜音（恢復後漸升，避免車機音樂暴衝）
    private var outputGain: Float = 1
    private var interrupted = false
    private var interruptionObserver: NSObjectProtocol?
    private var secondarySilenceObserver: NSObjectProtocol?
    private var resumeRampTimer: Timer?
    /// 1.2.16：對齊 Android `aa_path_check`（啟動後短播 50Hz 測路徑）
    private var pathCheckActive = false
    private var pathCheckPeak: Float = 0
    private var pathCheckTimer: Timer?
    /// Never call `removeTap` unless we actually installed one (first-start crash).
    private var tapInstalled = false

    init(model: AncAppModel) {
        self.model = model
        installInterruptionObservers()
    }

    deinit {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
        if let secondarySilenceObserver {
            NotificationCenter.default.removeObserver(secondarySilenceObserver)
        }
        resumeRampTimer?.invalidate()
    }

    /// 來電／Siri 等中斷：暫停 anti；結束後重套 default session + 增益漸升
    private func installInterruptionObservers() {
        let nc = NotificationCenter.default
        interruptionObserver = nc.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            self?.handleInterruption(note)
        }
        secondarySilenceObserver = nc.addObserver(
            forName: AVAudioSession.silenceSecondaryAudioHintNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] note in
            guard let self,
                  let typeVal = note.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
                  let hint = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: typeVal) else { return }
            // 系統媒體成為主音訊時，暫時壓低我們的 anti，減少與車機搶增益
            if hint == .begin {
                self.outputGain = min(self.outputGain, 0.15)
                Task { @MainActor in
                    SessionLogger.shared.event("secondary_audio_hint", ["hint": "begin", "outputGain": "0.15"])
                }
            } else if !self.interrupted, self.isStarted {
                self.beginOutputGainRamp(from: max(self.outputGain, 0.15), duration: 1.0)
                Task { @MainActor in
                    SessionLogger.shared.event("secondary_audio_hint", ["hint": "end"])
                }
            }
        }
    }

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeVal) else { return }

        switch type {
        case .began:
            interrupted = true
            outputGain = 0
            resumeRampTimer?.invalidate()
            resumeRampTimer = nil
            // 立刻清輸出 ring，避免通話中還在灌 anti
            ringLock.lock()
            if !outputRing.isEmpty {
                for i in 0..<outputRing.count { outputRing[i] = 0 }
                outputWrite = 0
                outputRead = 0
            }
            ringLock.unlock()
            if engine.isRunning {
                engine.pause()
            }
            Task { @MainActor in
                if model.isRunning {
                    model.phase = .paused
                    model.statusDetail = "通話／中斷保護中"
                }
                SessionLogger.shared.event("audio_interruption", [
                    "type": "began",
                    "preferCar": "\(preferCarAudio)",
                    "note": "mute_anti_pause_engine"
                ])
            }

        case .ended:
            let optsVal = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let opts = AVAudioSession.InterruptionOptions(rawValue: optsVal)
            let shouldResume = opts.contains(.shouldResume)
            Task { @MainActor in
                SessionLogger.shared.event("audio_interruption", [
                    "type": "ended",
                    "shouldResume": "\(shouldResume)",
                    "wasStarted": "\(isStarted)",
                    "preferCar": "\(preferCarAudio)"
                ])
            }
            guard isStarted, shouldResume else {
                interrupted = false
                return
            }
            resumeAfterInterruption()

        @unknown default:
            break
        }
    }

    private func resumeAfterInterruption() {
        // 關鍵：離開 voice/call 增益路徑，再輕柔恢復 anti（避免車機音樂忽然很大）
        do {
            try CarAudioRouteMonitor.restoreAfterCallInterruption(preferCar: preferCarAudio)
        } catch {
            Task { @MainActor in
                SessionLogger.shared.event("audio_interruption_restore_error", [
                    "error": error.localizedDescription
                ])
            }
        }
        interrupted = false
        outputGain = 0
        if !engine.isRunning {
            do {
                try engine.start()
            } catch {
                Task { @MainActor in
                    SessionLogger.shared.event("audio_interruption_engine_start_error", [
                        "error": error.localizedDescription
                    ])
                }
            }
        }
        beginOutputGainRamp(from: 0, duration: 1.6)
        Task { @MainActor in
            if model.isRunning {
                model.phase = preferCarAudio ? .driving : .running
                model.statusDetail = "通話結束・音量漸升恢復"
            }
            SessionLogger.shared.event("audio_interruption_resumed", [
                "rampSec": "1.6",
                "sessionMode": "default",
                "note": "avoid_carplay_music_volume_blast"
            ])
        }
    }

    private func beginOutputGainRamp(from start: Float, duration: TimeInterval) {
        resumeRampTimer?.invalidate()
        outputGain = start
        let steps = max(Int(duration / 0.05), 1)
        var step = 0
        let timer = Timer(timeInterval: 0.05, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            step += 1
            let g = min(1, start + (1 - start) * Float(step) / Float(steps))
            self.outputGain = g
            if step >= steps {
                self.outputGain = 1
                t.invalidate()
                self.resumeRampTimer = nil
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        resumeRampTimer = timer
    }

    /// 執行中切換等級（對齊 AA 車機 ActionStrip）
    @MainActor
    func applyTier(_ tier: UserTier) {
        activeTier = tier
        kmpProcessor?.updateTier(tier)
    }

    /// 1.2.6 腳本強制 NVH（`GuidedNvhOverride` + processor）
    @MainActor
    func setForcedNvhFocus(_ name: String?) {
        kmpProcessor?.setForcedNvhFocus(name)
        model.forcedNvhFocus = (name?.isEmpty == false) ? name! : "auto"
    }

    /// 1.2.8 腳本 diag 50Hz tone（經喇叭／CarPlay 輸出）
    @MainActor
    func setDiagToneHz(_ hz: Float) {
        model.diagToneHz = hz
        diagToneHz = muteAnti ? 0 : hz
        if hz > 0 && !muteAnti {
            SessionLogger.shared.event("diag_tone_active", [
                "hz": String(format: "%.0f", hz),
                "platform": "ios"
            ])
        }
    }

    /// 1.2.10：真 mute anti（喇叭硬歸零 + KMP setAntiOutputMuted）
    @MainActor
    func setMuteAnti(_ muted: Bool) {
        muteAnti = muted
        model.muteAnti = muted
        if muted {
            userAncGain = 0
            model.userAncGain = 0
            diagToneHz = 0
            model.diagToneHz = 0
        }
        kmpProcessor?.setAntiOutputMuted(muted || userAncGain <= 0.001)
        SessionLogger.shared.event("mute_anti", [
            "muteAnti": "\(muted)",
            "userAncGain": String(format: "%.2f", userAncGain)
        ])
    }

    @MainActor
    func setUserAncGain(_ gain: Float) {
        userAncGain = max(0, min(1, gain))
        model.userAncGain = userAncGain
        let muted = muteAnti || userAncGain <= 0.001
        kmpProcessor?.setAntiOutputMuted(muted)
    }

    /// 1.2.12：forceBoomPolarity +1/−1；0=auto
    @MainActor
    func setForceBoomPolarity(_ polarity: Float) {
        forceBoomPolarity = polarity
        model.forceBoomPolarity = polarity
        if abs(polarity) < 0.01 {
            kmpProcessor?.setBoomPolarityForced(nil)
        } else {
            kmpProcessor?.setBoomPolarityForced(polarity)
        }
        SessionLogger.shared.event("force_boom_polarity", [
            "forceBoomPolarity": String(format: "%.0f", polarity)
        ])
    }

    var currentSampleRate: Double { sampleRate }

    @MainActor
    func start(preferCarAudio: Bool = false) async throws {
        guard !isStarted else { return }
        if !model.safetyConsentAccepted {
            model.showSafetyConsent = true
            throw EngineError.needsConsent
        }
        // 麥克風權限：未授權時 AVAudioEngine 常直接崩（非 Swift throw）
        let micOk = await Self.requestMicPermission()
        guard micOk else {
            model.lastError = "需要麥克風權限才能降噪"
            throw EngineError.needsMicPermission
        }
        self.preferCarAudio = preferCarAudio

        do {
            try await startUnlocked(preferCarAudio: preferCarAudio)
        } catch {
            // 失敗時清乾淨，避免半開狀態下次再崩
            stop()
            model.lastError = error.localizedDescription
            SessionLogger.shared.event("audio_start_error", [
                "error": error.localizedDescription,
                "preferCar": "\(preferCarAudio)"
            ])
            throw error
        }
    }

    @MainActor
    private func startUnlocked(preferCarAudio: Bool) async throws {
        // 路由：本機 speaker vs CarPlay 車機（對齊 AA resolveRoute）
        try CarAudioRouteMonitor.configureSessionForCarIfNeeded(preferCar: preferCarAudio)
        AppController.shared.routeMonitor.refresh()
        let session = AVAudioSession.sharedInstance()

        teardownGraph()

        let input = engine.inputNode
        let format = input.inputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw EngineError.invalidFormat
        }
        sampleRate = format.sampleRate
        // CarPlay / AA 路徑延遲通常 100–250ms+；本機較低
        let base = Float(session.ioBufferDuration * 1000.0 * 3.0 + 30.0)
        measuredLatencyMs = preferCarAudio ? max(base, 140) : base

        let bufFrames = 512
        activeTier = model.tier
        let proc = KotlinAncBridge(sampleRate: Int(sampleRate), bufferSize: bufFrames, tier: activeTier)
        proc.setEstimatedLatencyMs(measuredLatencyMs)
        let ioMs = Float(session.ioBufferDuration * 1000.0)
        let blockMs = Float(bufFrames) / Float(sampleRate) * 1000.0
        if preferCarAudio {
            proc.setMeasuredLatencyBreakdown(
                recordMs: max(ioMs, 15),
                trackMs: 110,
                blockMs: blockMs,
                acousticMs: 2,
                frameworkMs: 40
            )
        } else {
            proc.setMeasuredLatencyBreakdown(
                recordMs: max(ioMs, 8),
                trackMs: max(ioMs, 8),
                blockMs: blockMs,
                acousticMs: 2,
                frameworkMs: 8
            )
        }
        let profileId = "ios_default"
        let routeLink = AppController.shared.routeMonitor.linkType
        let route = PlantPathStore.routeLabel(
            carPlay: preferCarAudio || routeLink.isCarPlay,
            wireless: routeLink.wirelessSuspected
        )
        if let snap = PlantPathStore.loadBest(profileId: profileId, routeLabel: route) {
            proc.applyPersistedBoomPolarity(snap.boomPolarity)
            var applied = snap.electricalDelaySamples
            if snap.electricalDelaySamples > 64 {
                applied = proc.refinePlantDelayFromProbe(snap.electricalDelaySamples)
            }
            SessionLogger.shared.event("plant_path_loaded", [
                "profileId": snap.profileId,
                "routeLabel": snap.routeLabel,
                "electricalDelaySamples": "\(snap.electricalDelaySamples)",
                "appliedSamples": "\(applied)",
                "boomPolarity": String(format: "%.0f", snap.boomPolarity)
            ])
        } else {
            proc.applyPersistedBoomPolarity(PlantPathStore.defaultPolarity)
            SessionLogger.shared.event("plant_path_default_polarity", [
                "boomPolarity": String(format: "%.0f", PlantPathStore.defaultPolarity),
                "note": "1.2.14_cabin_pref_default_neg1"
            ])
        }
        proc.setAntiOutputMuted(muteAnti || userAncGain <= 0.001)
        if abs(forceBoomPolarity) > 0.01 {
            proc.setBoomPolarityForced(forceBoomPolarity)
        }
        kmpProcessor = proc
        interrupted = false
        outputGain = 1
        resumeRampTimer?.invalidate()
        resumeRampTimer = nil
        pathCheckTimer?.invalidate()
        pathCheckTimer = nil
        pathCheckActive = false
        pathCheckPeak = 0

        outputRing = [Float](repeating: 0, count: max(Int(sampleRate), 48000))
        outputWrite = 0
        outputRead = 0

        let bufferSize: AVAudioFrameCount = AVAudioFrameCount(bufFrames)
        input.installTap(onBus: 0, bufferSize: bufferSize, format: format) { [weak self] buffer, _ in
            self?.process(buffer: buffer)
        }
        tapInstalled = true

        var outFormat = engine.mainMixerNode.outputFormat(forBus: 0)
        if outFormat.sampleRate <= 0 || outFormat.channelCount == 0 {
            outFormat = format
        }
        let source = AVAudioSourceNode { [weak self] _, _, frameCount, audioBufferList -> OSStatus in
            self?.fillOutput(
                abl: UnsafeMutableAudioBufferListPointer(audioBufferList),
                frames: Int(frameCount)
            )
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: outFormat)
        sourceNode = source
        engine.mainMixerNode.outputVolume = 1.0

        try engine.start()
        isStarted = true
        blockCount = 0
        uiTickCount = 0

        speedProvider.start()
        imuProvider.start()

        model.isRunning = true
        model.phase = .calibrating
        model.lastError = nil
        model.estimatedLatencyMs = measuredLatencyMs
        model.maxCancelHz = proc.maxCancelHz
        model.midEnabled = proc.midEnabled
        model.highEnabled = proc.highEnabled

        let link = model.aaLinkType
        let backend = preferCarAudio ? "AVAudioEngine_carplay+KMP" : "AVAudioEngine_local+KMP"
        let outputs = session.currentRoute.outputs.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: ",")
        SessionLogger.shared.startSession(meta: [
            "tier": model.tier.rawValue,
            "sampleRate": String(format: "%.0f", sampleRate),
            "bufferMs": String(format: "%.1f", session.ioBufferDuration * 1000),
            "latencyEstMs": String(format: "%.1f", measuredLatencyMs),
            "dsp": "kmp_MultiBandANCProcessor",
            "aaLinkType": link,
            "preferCarAudio": "\(preferCarAudio)",
            "routeOutputs": outputs
        ])
        SessionLogger.shared.event("audio_init", [
            "audioBackend": backend,
            "sampleRate": String(format: "%.0f", sampleRate),
            "channelsIn": "\(format.channelCount)",
            "dsp": "shared.MultiBandANCProcessor",
            "aaLinkType": link,
            "carPlayConnected": "\(model.carPlayConnected)",
            "routeOutputs": outputs
        ])
        SessionLogger.shared.event("calibration", ["msg": "learning_window_start"])

        // 1.2.16：對齊 Android aa_path_check — 開 ANC 後短播 50Hz，看喇叭／CarPlay 是否真有輸出
        schedulePathCheck(preferCar: preferCarAudio, routeOutputs: outputs)

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self, self.isStarted else { return }
            Task { @MainActor in
                self.model.phase = .running
                SessionLogger.shared.event("calibration_done", ["msg": "enter_running", "dsp": "kmp"])
            }
            self.kmpProcessor?.finishLearning()
        }

        uiTimer?.invalidate()
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in
            self?.pushUI()
        }
        RunLoop.main.add(timer, forMode: .common)
        uiTimer = timer
    }

    /// 對齊 Android `aa_path_check`：1.5s 50Hz；依 anti 峰值 + 路由判 PASS/FAIL
    @MainActor
    private func schedulePathCheck(preferCar: Bool, routeOutputs: String) {
        pathCheckPeak = 0
        pathCheckActive = true
        setDiagToneHz(50)
        SessionLogger.shared.event("path_check_start", [
            "hz": "50",
            "durationSec": "1.5",
            "preferCar": "\(preferCar)",
            "routeOutputs": routeOutputs,
            "note": "ios_carplay_or_local_path_self_test"
        ])
        pathCheckTimer?.invalidate()
        let t = Timer(timeInterval: 1.5, repeats: false) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                self.finishPathCheck(preferCar: preferCar, routeOutputs: routeOutputs)
            }
        }
        RunLoop.main.add(t, forMode: .common)
        pathCheckTimer = t
    }

    @MainActor
    private func finishPathCheck(preferCar: Bool, routeOutputs: String) {
        pathCheckActive = false
        setDiagToneHz(0)
        pathCheckTimer = nil
        let peak = pathCheckPeak
        AppController.shared.routeMonitor.refresh()
        let session = AVAudioSession.sharedInstance()
        let outputsNow = session.currentRoute.outputs
        let hasCarAudio = outputsNow.contains { $0.portType == .carAudio }
        let routeNow = outputsNow.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: ",")
        let route = AppController.shared.routeMonitor.linkType
        let sendOk = peak >= 0.02
        let routeOk = !preferCar || hasCarAudio
        let pass = sendOk && routeOk
        let result = pass ? "PASS" : "FAIL"
        let failReasons: [String] = {
            var r: [String] = []
            if !sendOk { r.append("send_tone_peak_too_low") }
            if preferCar && !hasCarAudio { r.append("not_on_carAudio") }
            return r
        }()
        SessionLogger.shared.event("carplay_path_check", [
            "result": result,
            "aa_path_check": result,
            "sendPeak": String(format: "%.4f", peak),
            "preferCar": "\(preferCar)",
            "hasCarAudio": "\(hasCarAudio)",
            "carPlayConnected": "\(model.carPlayConnected)",
            "aaLinkType": route.rawValue,
            "routeOutputs": routeNow.isEmpty ? routeOutputs : routeNow,
            "failReasons": failReasons.joined(separator: ","),
            "note": pass
                ? "SEND_AND_ROUTE_OK_listen_for_50Hz_to_confirm_speakers"
                : "SEND_OR_CARAUDIO_FAIL_do_not_trust_ANC_KPI"
        ])
        model.statusDetail = pass
            ? "路徑自檢 PASS（應聽到短 50Hz）"
            : "路徑自檢 FAIL — 喇叭/CarPlay 可能無輸出"
    }

    private static func requestMicPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioSession.sharedInstance().requestRecordPermission { ok in
                cont.resume(returning: ok)
            }
        }
    }

    @MainActor
    func stop() {
        let wasStarted = isStarted
        isStarted = false
        uiTimer?.invalidate()
        uiTimer = nil
        resumeRampTimer?.invalidate()
        resumeRampTimer = nil
        pathCheckTimer?.invalidate()
        pathCheckTimer = nil
        pathCheckActive = false
        interrupted = false
        outputGain = 1
        diagToneHz = 0
        teardownGraph()
        speedProvider.stop()
        imuProvider.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        kmpProcessor?.release()
        kmpProcessor = nil
        if wasStarted {
            SessionLogger.shared.endSession()
        }
        model.isRunning = false
        model.phase = .stopped
        model.statusDetail = ""
    }

    private func teardownGraph() {
        if engine.isRunning {
            engine.stop()
        }
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if let sourceNode {
            engine.detach(sourceNode)
            self.sourceNode = nil
        }
        engine.reset()
    }

    private func pushAnti(_ samples: [Float]) {
        ringLock.lock()
        defer { ringLock.unlock() }
        guard !outputRing.isEmpty else { return }
        let n = outputRing.count
        for s in samples {
            outputRing[outputWrite % n] = s
            outputWrite += 1
            if outputWrite - outputRead > n - 1024 {
                outputRead = outputWrite - (n / 2)
            }
        }
    }

    private func fillOutput(abl: UnsafeMutableAudioBufferListPointer, frames: Int) {
        ringLock.lock()
        defer { ringLock.unlock() }
        let n = max(outputRing.count, 1)
        for buf in abl {
            guard let ptr = buf.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let capacity = Int(buf.mDataByteSize) / MemoryLayout<Float>.size
            let count = min(frames, capacity)
            for i in 0..<count {
                if outputWrite > outputRead {
                    ptr[i] = outputRing[outputRead % n]
                    outputRead += 1
                } else {
                    ptr[i] = 0
                }
            }
        }
    }

    private func process(buffer: AVAudioPCMBuffer) {
        guard isStarted, kmpProcessor != nil else { return }
        guard let channelData = buffer.floatChannelData else { return }
        let frameCount = Int(buffer.frameLength)
        guard frameCount > 0 else { return }

        var mono = [Float](repeating: 0, count: frameCount)
        let ch = Int(buffer.format.channelCount)
        if ch == 1 {
            for i in 0..<frameCount { mono[i] = channelData[0][i] }
        } else {
            for i in 0..<frameCount {
                mono[i] = 0.5 * (channelData[0][i] + channelData[1][i])
            }
        }

        let accel = imuProvider.linearAccelMagnitude
        let axes = imuProvider.axes
        // 無 GPS 時仍推進 hold / IMU 代車速（對齊 Android VehicleSpeedFusion）
        speedProvider.tickWithAccel(accel)
        let speed = speedProvider.snapshot
        kmpProcessor?.updateTier(activeTier)
        kmpProcessor?.setVehicleSpeed(kmh: speed.kmh, valid: speed.valid)
        kmpProcessor?.setRumbleAccel(accel)
        kmpProcessor?.setImuAxes(ax: axes.0, ay: axes.1, az: axes.2)

        let rmsDb = SpectrumAnalyzer.rmsDb(mono)
        let linearRms = pow(10 as Float, rmsDb / 20)
        _ = kmpProcessor?.registerBlockEnergy(rms: linearRms)

        guard var anti = kmpProcessor?.process(mono: mono) else { return }
        let muted = muteAnti || userAncGain <= 0.001
        // 1.2.8 diag tone（mute 時關閉）
        let toneHz = muted ? 0 : diagToneHz
        if toneHz > 0, sampleRate > 0 {
            let twoPi = 2.0 * Double.pi
            for i in 0..<anti.count {
                let s = sin(diagTonePhase)
                anti[i] = max(-1, min(1, Float(s) * 0.72))
                diagTonePhase += twoPi * Double(toneHz) / sampleRate
                if diagTonePhase > twoPi { diagTonePhase -= twoPi }
            }
        }
        // 1.2.10/1.2.12：post-mute write（KPI 與喇叭一致）
        if muted || interrupted {
            for i in 0..<anti.count { anti[i] = 0 }
        } else {
            var g = userAncGain
            if g > 0.999 { g = 1 }
            let og = outputGain
            if g < 0.999 || og < 0.999 {
                let m = g * og
                for i in 0..<anti.count { anti[i] *= m }
            }
        }
        pushAnti(anti)
        if pathCheckActive {
            var peak: Float = 0
            for s in anti { peak = max(peak, abs(s)) }
            if peak > pathCheckPeak { pathCheckPeak = peak }
        }
        if GuidedCabinRecorder.shared.isRecording {
            GuidedCabinRecorder.shared.append(mono: mono)
        }

        lastVisInput = mono
        lastVisAnti = anti
        lastRawDb = rmsDb
        lastAntiDb = SpectrumAnalyzer.rmsDb(anti) // post-mute
        if let k = kmpProcessor {
            lastLowUpdates = k.lowLmsUpdates
            lastCoherence = k.imuMicCoherence
            lastBankMatch = k.bankMatchQuality
            lastFixedBankOut = k.fixedBankOut
        }
        blockCount += 1
    }

    private func pushUI() {
        let raw = lastRawDb
        let anti = lastAntiDb
        // KPI proxy：anti 相對 mic 的「有輸出且低於輸入雜訊時」偏正（路測看行駛段趨勢，非絕對 dB）
        let reduction: Float = {
            if anti < -85 { return 0 } // 幾乎無 anti 輸出
            return max(-6, min(12, (raw - anti) * 0.15))
        }()
        let noiseBars = spectrum.analyze(lastVisInput)
        let antiBars = spectrum.analyze(lastVisAnti)
        // 低頻條（前 ~1/3 頻譜）能量差作 low-band 代理
        let lowN = max(1, noiseBars.count / 3)
        let lowIn = noiseBars.prefix(lowN).reduce(0, +) / Float(lowN)
        let lowAnti = antiBars.prefix(lowN).reduce(0, +) / Float(lowN)
        let lowBandKpi = max(-6, min(12, (lowIn - lowAnti) * 8 + reduction * 0.5))
        speedProvider.tickWithAccel(imuProvider.linearAccelMagnitude)
        let speed = speedProvider.snapshot
        let accel = imuProvider.linearAccelMagnitude
        let snap = kmpProcessor?.snapshot()
        let focus: NvhFocus = {
            switch (snap?.lastNvhFocusRaw ?? "").uppercased() {
            case "ROAD_RUMBLE": return .roadRumble
            case "TIRE_NOISE": return .tireNoise
            case "WIND_SHEAR": return .windShear
            case "IDLE": return .idle
            case "": return .idle
            default: return .mixedCabin
            }
        }()
        let maxHz = snap?.maxCancelHz ?? 150
        let mid = snap?.midEnabled ?? false
        let high = snap?.highEnabled ?? false
        let lowUpdates = snap?.lowLmsUpdates ?? lastLowUpdates
        let midUpdates = snap?.midLmsUpdates ?? 0
        let lat = measuredLatencyMs
        let strategy = snap?.latencyStrategy ?? "NORMAL"
        let blocks = blockCount
        let coh = snap?.imuMicCoherence ?? lastCoherence
        let bank = snap?.bankMatchQuality ?? lastBankMatch
        let fixedOut = snap?.fixedBankOut ?? lastFixedBankOut

        Task { @MainActor in
            model.rawDb = raw
            model.antiDb = anti
            model.residualDb = raw
            model.reductionDb = reduction
            model.lowBandRumbleReduction = lowBandKpi
            model.noiseSpectrum = noiseBars
            model.antiSpectrum = antiBars
            model.vehicleSpeedKmh = speed.kmh
            model.vehicleSpeedValid = speed.valid
            model.speedSource = speed.source
            model.speedHoldAgeSec = speed.holdAgeSec
            model.imuProxyKmh = speed.imuProxyKmh
            model.speedValidForRoadTest = speed.validForRoadTest
            model.rumbleAccel = accel
            model.nvhFocus = focus
            model.maxCancelHz = maxHz
            model.midEnabled = mid
            model.highEnabled = high
            model.estimatedLatencyMs = lat
            model.tireNotchEnergy = snap?.tireNotchEnergy ?? 0
            model.windNotchEnergy = snap?.windNotchEnergy ?? 0
            model.tireNotchF0Hz = snap?.tireNotchF0Hz ?? 0
            model.windNotchActiveCount = snap?.windNotchActiveCount ?? 0
            model.notchMixAnti = snap?.notchMixAnti ?? 0
            model.roadNotchEnergy = snap?.roadNotchEnergy ?? 0
            model.roadBoomWeightEnergy = snap?.roadBoomWeightEnergy ?? 0
            model.boomPressureOut = snap?.boomPressureOut ?? 0
            model.boomPlantCorr = snap?.boomPlantCorr ?? 0
            model.plantElectricalDelaySamples = snap?.plantElectricalDelaySamples ?? 0
            model.boomPolarity = snap?.boomPolarity ?? PlantPathStore.defaultPolarity
            model.openBoomActive = snap?.openBoomActive ?? false
            model.muteAnti = self.muteAnti
            model.userAncGain = self.userAncGain
            model.forceBoomPolarity = self.forceBoomPolarity
            model.effectiveLowMu = snap?.effectiveLowMu ?? 0
            model.effectiveMidMu = snap?.effectiveMidMu ?? 0

            if speed.valid && speed.kmh >= 15 {
                if model.phase == .running || model.phase == .driving {
                    model.phase = .driving
                }
            } else if model.phase == .driving {
                model.phase = .running
            }

            if model.showAdvanced {
                model.statusDetail = String(
                    format: "KMP lms=%lld · %@ · speed=%@",
                    lowUpdates, focus.rawValue, speed.source
                )
            } else {
                model.statusDetail = "KMP · " + focus.displayName
            }

            // ~2s 一次 running_snapshot + spectrum_kpi（對齊 Android 1.2.6）
            self.uiTickCount += 1
            if self.uiTickCount % 10 == 0, model.isRunning {
                let linearRms = pow(10 as Float, raw / 20)
                let bin = snap?.speedNvhBinKmh ?? 0
                let lowG = snap?.speedNvhLowGain ?? 1
                let midG = snap?.speedNvhMidGain ?? 0.25
                let totalA = snap?.speedNvhTotalAnti ?? 1
                let tableId = snap?.speedNvhTableId ?? "none"
                SessionLogger.shared.runningSnapshot(
                    model: model,
                    antiDb: anti,
                    lowBandKpi: lowBandKpi,
                    reductionDb: reduction,
                    lmsLow: lowUpdates,
                    lmsMid: midUpdates,
                    latencyStrategy: strategy,
                    blockCount: blocks,
                    blockRms: linearRms,
                    lowBandEnergyIn: lowIn,
                    lowBandEnergyAnti: lowAnti,
                    speedNvhBinKmh: bin,
                    speedNvhLowGain: lowG,
                    speedNvhMidGain: midG,
                    speedNvhTotalAnti: totalA,
                    speedNvhTableId: tableId
                )
                // spectrum_kpi：mic vs residual proxy（iOS 無完整 plant delay 時用 input+anti）
                let mic = self.lastVisInput
                var plant = mic
                let antiVis = self.lastVisAnti
                let n = min(mic.count, antiVis.count)
                if n > 0 {
                    plant = (0..<n).map { mic[$0] + antiVis[$0] }
                }
                let sr = self.sampleRate
                let eMicBoom = SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 40, fHi: 120)
                let ePlantBoom = SpectrumAnalyzer.bandRangeEnergyDb(plant, sampleRate: sr, fLo: 40, fHi: 120)
                let eMicTire = SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 180, fHi: 350)
                let ePlantTire = SpectrumAnalyzer.bandRangeEnergyDb(plant, sampleRate: sr, fLo: 180, fHi: 350)
                let eMicWind = SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 500, fHi: 2000)
                let ePlantWind = SpectrumAnalyzer.bandRangeEnergyDb(plant, sampleRate: sr, fLo: 500, fHi: 2000)
                // 1.2.8 antiE*：送出前 anti PCM 分帶
                let antiE4080 = SpectrumAnalyzer.bandRangeEnergyDb(antiVis, sampleRate: sr, fLo: 40, fHi: 80)
                let antiE80120 = SpectrumAnalyzer.bandRangeEnergyDb(antiVis, sampleRate: sr, fLo: 80, fHi: 120)
                let antiE200500 = SpectrumAnalyzer.bandRangeEnergyDb(antiVis, sampleRate: sr, fLo: 200, fHi: 500)
                let antiE5002k = SpectrumAnalyzer.bandRangeEnergyDb(antiVis, sampleRate: sr, fLo: 500, fHi: 2000)
                let antiLf = max(antiE4080, antiE80120)
                let antiLfDom = antiLf > antiE5002k + 3
                SessionLogger.shared.event("spectrum_kpi", [
                    "micE40_120": String(format: "%.2f", eMicBoom),
                    "plantE40_120": String(format: "%.2f", ePlantBoom),
                    "deltaBoomDb": String(format: "%.2f", eMicBoom - ePlantBoom),
                    "micE180_350": String(format: "%.2f", eMicTire),
                    "plantE180_350": String(format: "%.2f", ePlantTire),
                    "deltaTireDb": String(format: "%.2f", eMicTire - ePlantTire),
                    "micE500_2000": String(format: "%.2f", eMicWind),
                    "plantE500_2000": String(format: "%.2f", ePlantWind),
                    "deltaWindDb": String(format: "%.2f", eMicWind - ePlantWind),
                    "micE40_80": String(format: "%.2f", SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 40, fHi: 80)),
                    "micE80_150": String(format: "%.2f", SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 80, fHi: 150)),
                    "antiE40_80": String(format: "%.2f", antiE4080),
                    "antiE80_120": String(format: "%.2f", antiE80120),
                    "antiE200_500": String(format: "%.2f", antiE200500),
                    "antiE500_2k": String(format: "%.2f", antiE5002k),
                    "antiLfDominatesHf": "\(antiLfDom)",
                    "speedKmh": String(format: "%.1f", model.vehicleSpeedKmh),
                    "nvhFocus": model.nvhFocus.rawValue,
                    "forcedNvhFocus": model.forcedNvhFocus,
                    "boomPressureOut": String(format: "%.4f", model.boomPressureOut),
                    "boomPlantCorr": String(format: "%.3f", model.boomPlantCorr),
                    "plantDelaySamples": "\(model.plantElectricalDelaySamples)",
                    "boomPolarity": String(format: "%.0f", model.boomPolarity),
                    "openBoom": "\(model.openBoomActive)",
                    "muteAnti": "\(self.muteAnti)",
                    "userAncGain": String(format: "%.2f", self.userAncGain),
                    "forceBoomPolarity": String(format: "%.0f", self.forceBoomPolarity),
                    "latencyStrategy": strategy,
                    "antiNoiseDb": String(format: "%.1f", anti),
                    "guidedStep": SessionLogger.shared.guidedTestStepId,
                    "note": "ios_spectrum_kpi_input_plus_anti_proxy"
                ])
                // 1.2.15：cabin score = low + 0.8×mid；plant 次之；丟棄 <45 km/h
                let plantRed = eMicBoom - ePlantBoom
                let cabinLow = SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 40, fHi: 120)
                let cabinMid = SpectrumAnalyzer.bandRangeEnergyDb(mic, sampleRate: sr, fLo: 180, fHi: 350)
                self.lastPlantResidualReductionDb = plantRed
                model.plantResidualReductionDb = plantRed
                BoomPolarityAbTracker.shared.sample(
                    stepId: SessionLogger.shared.guidedTestStepId,
                    residualReductionDb: plantRed,
                    cabinLowBandDb: KotlinFloat(float: cabinLow),
                    speedKmh: model.vehicleSpeedKmh,
                    cabinMidBandDb: KotlinFloat(float: cabinMid)
                )
                // Overlay real KMP diagnostics into next event
                SessionLogger.shared.event("kmp_diag", [
                    AndroidSnapshotKeys.imuMicCoherence: String(format: "%.3f", coh),
                    AndroidSnapshotKeys.bankMatchQuality: String(format: "%.3f", bank),
                    AndroidSnapshotKeys.fixedBankOut: String(format: "%.4f", fixedOut),
                    AndroidSnapshotKeys.speedNvhBinKmh: "\(bin)",
                    AndroidSnapshotKeys.speedNvhTableId: tableId,
                    AndroidSnapshotKeys.speedNvhTotalAnti: String(format: "%.2f", totalA),
                    AndroidSnapshotKeys.tireNotchEnergy: String(format: "%.4f", snap?.tireNotchEnergy ?? 0),
                    AndroidSnapshotKeys.windNotchEnergy: String(format: "%.4f", snap?.windNotchEnergy ?? 0),
                    AndroidSnapshotKeys.tireNotchF0Hz: String(format: "%.1f", snap?.tireNotchF0Hz ?? 0),
                    AndroidSnapshotKeys.windNotchActiveCount: "\(snap?.windNotchActiveCount ?? 0)",
                    AndroidSnapshotKeys.notchMixAnti: String(format: "%.4f", snap?.notchMixAnti ?? 0),
                    AndroidSnapshotKeys.roadNotchEnergy: String(format: "%.4f", snap?.roadNotchEnergy ?? 0),
                    AndroidSnapshotKeys.roadBoomWeightEnergy: String(format: "%.4f", snap?.roadBoomWeightEnergy ?? 0),
                    AndroidSnapshotKeys.boomPressureOut: String(format: "%.4f", snap?.boomPressureOut ?? 0),
                    AndroidSnapshotKeys.boomPlantCorr: String(format: "%.3f", snap?.boomPlantCorr ?? 0),
                    AndroidSnapshotKeys.plantElectricalDelaySamples: "\(snap?.plantElectricalDelaySamples ?? 0)",
                    AndroidSnapshotKeys.effectiveLowMu: String(format: "%.4f", snap?.effectiveLowMu ?? 0),
                    "dsp": "kmp_MultiBandANCProcessor"
                ])
            }
        }
    }

    enum EngineError: LocalizedError {
        case needsConsent
        case needsMicPermission
        case invalidFormat

        var errorDescription: String? {
            switch self {
            case .needsConsent: return "請先同意安全聲明"
            case .needsMicPermission: return "需要麥克風權限才能降噪"
            case .invalidFormat: return "無法取得麥克風格式（請確認未佔用／已連 CarPlay 再試）"
            }
        }
    }
}
