import Foundation
import AVFoundation
import Combine

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

    init(model: AncAppModel) {
        self.model = model
    }

    /// 執行中切換等級（對齊 AA 車機 ActionStrip）
    @MainActor
    func applyTier(_ tier: UserTier) {
        activeTier = tier
        kmpProcessor?.updateTier(tier)
    }

    @MainActor
    func start(preferCarAudio: Bool = false) async throws {
        guard !isStarted else { return }
        if !model.safetyConsentAccepted {
            model.showSafetyConsent = true
            throw EngineError.needsConsent
        }
        self.preferCarAudio = preferCarAudio

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
        kmpProcessor = proc

        outputRing = [Float](repeating: 0, count: Int(sampleRate))
        outputWrite = 0
        outputRead = 0

        let bufferSize: AVAudioFrameCount = AVAudioFrameCount(bufFrames)
        input.installTap(onBus: 0, bufferSize: bufferSize, format: format) { [weak self] buffer, _ in
            self?.process(buffer: buffer)
        }

        let outFormat = engine.mainMixerNode.outputFormat(forBus: 0)
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
        SessionLogger.shared.startSession(meta: [
            "tier": model.tier.rawValue,
            "sampleRate": String(format: "%.0f", sampleRate),
            "bufferMs": String(format: "%.1f", session.ioBufferDuration * 1000),
            "latencyEstMs": String(format: "%.1f", measuredLatencyMs),
            "dsp": "kmp_MultiBandANCProcessor",
            "aaLinkType": link,
            "preferCarAudio": "\(preferCarAudio)"
        ])
        SessionLogger.shared.event("audio_init", [
            "audioBackend": backend,
            "sampleRate": String(format: "%.0f", sampleRate),
            "channelsIn": "\(format.channelCount)",
            "dsp": "shared.MultiBandANCProcessor",
            "aaLinkType": link,
            "carPlayConnected": "\(model.carPlayConnected)"
        ])
        SessionLogger.shared.event("calibration", ["msg": "learning_window_start"])

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

    func stop() {
        uiTimer?.invalidate()
        uiTimer = nil
        teardownGraph()
        speedProvider.stop()
        imuProvider.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        kmpProcessor?.release()
        kmpProcessor = nil
        isStarted = false
        Task { @MainActor in
            SessionLogger.shared.endSession()
            model.isRunning = false
            model.phase = .stopped
            model.statusDetail = ""
        }
    }

    private func teardownGraph() {
        engine.inputNode.removeTap(onBus: 0)
        if let sourceNode {
            engine.detach(sourceNode)
            self.sourceNode = nil
        }
        if engine.isRunning {
            engine.stop()
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
        // 無 GPS 時仍推進 hold / IMU 代車速（對齊 Android VehicleSpeedFusion）
        speedProvider.tickWithAccel(accel)
        let speed = speedProvider.snapshot
        kmpProcessor?.updateTier(activeTier)
        kmpProcessor?.setVehicleSpeed(kmh: speed.kmh, valid: speed.valid)
        kmpProcessor?.setRumbleAccel(accel)

        let rmsDb = SpectrumAnalyzer.rmsDb(mono)
        let linearRms = pow(10 as Float, rmsDb / 20)
        _ = kmpProcessor?.registerBlockEnergy(rms: linearRms)

        guard let anti = kmpProcessor?.process(mono: mono) else { return }
        pushAnti(anti)

        lastVisInput = mono
        lastVisAnti = anti
        lastRawDb = rmsDb
        lastAntiDb = SpectrumAnalyzer.rmsDb(anti)
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
        let focus = kmpProcessor?.mappedNvhFocus ?? .idle
        let maxHz = kmpProcessor?.maxCancelHz ?? 150
        let mid = kmpProcessor?.midEnabled ?? false
        let high = kmpProcessor?.highEnabled ?? false
        let lowUpdates = kmpProcessor?.lowLmsUpdates ?? lastLowUpdates
        let midUpdates = kmpProcessor?.midLmsUpdates ?? 0
        let lat = measuredLatencyMs
        let strategy = kmpProcessor?.latencyStrategy ?? "NORMAL"
        let blocks = blockCount
        let coh = kmpProcessor?.imuMicCoherence ?? lastCoherence
        let bank = kmpProcessor?.bankMatchQuality ?? lastBankMatch
        let fixedOut = kmpProcessor?.fixedBankOut ?? lastFixedBankOut

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
            model.tireNotchEnergy = self.kmpProcessor?.tireNotchEnergy ?? 0
            model.windNotchEnergy = self.kmpProcessor?.windNotchEnergy ?? 0
            model.tireNotchF0Hz = self.kmpProcessor?.tireNotchF0Hz ?? 0
            model.windNotchActiveCount = self.kmpProcessor?.windNotchActiveCount ?? 0
            model.notchMixAnti = self.kmpProcessor?.notchMixAnti ?? 0
            model.roadNotchEnergy = self.kmpProcessor?.roadNotchEnergy ?? 0
            model.roadBoomWeightEnergy = self.kmpProcessor?.roadBoomWeightEnergy ?? 0
            model.effectiveLowMu = self.kmpProcessor?.effectiveLowMu ?? 0
            model.effectiveMidMu = self.kmpProcessor?.effectiveMidMu ?? 0

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

            // ~2s 一次 running_snapshot（對齊 Android 2s）— 欄位名=Android schema
            self.uiTickCount += 1
            if self.uiTickCount % 10 == 0, model.isRunning {
                let linearRms = pow(10 as Float, raw / 20)
                let bin = self.kmpProcessor?.speedNvhBinKmh ?? 0
                let lowG = self.kmpProcessor?.speedNvhLowGain ?? 1
                let midG = self.kmpProcessor?.speedNvhMidGain ?? 0.25
                let totalA = self.kmpProcessor?.speedNvhTotalAnti ?? 1
                let tableId = self.kmpProcessor?.speedNvhTableId ?? "none"
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
                // Overlay real KMP diagnostics into next event
                SessionLogger.shared.event("kmp_diag", [
                    AndroidSnapshotKeys.imuMicCoherence: String(format: "%.3f", coh),
                    AndroidSnapshotKeys.bankMatchQuality: String(format: "%.3f", bank),
                    AndroidSnapshotKeys.fixedBankOut: String(format: "%.4f", fixedOut),
                    AndroidSnapshotKeys.speedNvhBinKmh: "\(bin)",
                    AndroidSnapshotKeys.speedNvhTableId: tableId,
                    AndroidSnapshotKeys.speedNvhTotalAnti: String(format: "%.2f", totalA),
                    AndroidSnapshotKeys.tireNotchEnergy: String(format: "%.4f", self.kmpProcessor?.tireNotchEnergy ?? 0),
                    AndroidSnapshotKeys.windNotchEnergy: String(format: "%.4f", self.kmpProcessor?.windNotchEnergy ?? 0),
                    AndroidSnapshotKeys.tireNotchF0Hz: String(format: "%.1f", self.kmpProcessor?.tireNotchF0Hz ?? 0),
                    AndroidSnapshotKeys.windNotchActiveCount: "\(self.kmpProcessor?.windNotchActiveCount ?? 0)",
                    AndroidSnapshotKeys.notchMixAnti: String(format: "%.4f", self.kmpProcessor?.notchMixAnti ?? 0),
                    AndroidSnapshotKeys.roadNotchEnergy: String(format: "%.4f", self.kmpProcessor?.roadNotchEnergy ?? 0),
                    AndroidSnapshotKeys.roadBoomWeightEnergy: String(format: "%.4f", self.kmpProcessor?.roadBoomWeightEnergy ?? 0),
                    AndroidSnapshotKeys.effectiveLowMu: String(format: "%.4f", self.kmpProcessor?.effectiveLowMu ?? 0),
                    "dsp": "kmp_MultiBandANCProcessor"
                ])
            }
        }
    }

    enum EngineError: LocalizedError {
        case needsConsent
        case invalidFormat

        var errorDescription: String? {
            switch self {
            case .needsConsent: return "請先同意安全聲明"
            case .invalidFormat: return "無法取得麥克風格式"
            }
        }
    }
}
