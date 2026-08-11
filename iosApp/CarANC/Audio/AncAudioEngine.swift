import Foundation
import AVFoundation
import Combine

/// 本機 duplex 音訊引擎：麥克風 → **KMP MultiBandANCProcessor（共用 Android DSP）** → 喇叭
/// 對齊 Android LocalLowLatencyAudio 路徑（非 CarPlay；CarPlay 為後續整合）
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

    init(model: AncAppModel) {
        self.model = model
    }

    @MainActor
    func start() async throws {
        guard !isStarted else { return }
        if !model.safetyConsentAccepted {
            model.showSafetyConsent = true
            throw EngineError.needsConsent
        }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .measurement,
            options: [.defaultToSpeaker, .allowBluetoothA2DP, .mixWithOthers]
        )
        try session.setPreferredSampleRate(48_000)
        try session.setPreferredIOBufferDuration(0.01)
        try session.setActive(true)

        teardownGraph()

        let input = engine.inputNode
        let format = input.inputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw EngineError.invalidFormat
        }
        sampleRate = format.sampleRate
        measuredLatencyMs = Float(session.ioBufferDuration * 1000.0 * 3.0 + 30.0)

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

        SessionLogger.shared.startSession(meta: [
            "tier": model.tier.rawValue,
            "sampleRate": String(format: "%.0f", sampleRate),
            "bufferMs": String(format: "%.1f", session.ioBufferDuration * 1000),
            "latencyEstMs": String(format: "%.1f", measuredLatencyMs),
            "dsp": "kmp_MultiBandANCProcessor"
        ])
        SessionLogger.shared.event("audio_init", [
            "audioBackend": "AVAudioEngine_local+KMP",
            "sampleRate": String(format: "%.0f", sampleRate),
            "channelsIn": "\(format.channelCount)",
            "dsp": "shared.MultiBandANCProcessor"
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

        let speed = speedProvider.snapshot
        let accel = imuProvider.linearAccelMagnitude
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
            model.rumbleAccel = accel
            model.nvhFocus = focus
            model.maxCancelHz = maxHz
            model.midEnabled = mid
            model.highEnabled = high
            model.estimatedLatencyMs = lat

            if speed.valid && speed.kmh >= 15 {
                if model.phase == .running || model.phase == .driving {
                    model.phase = .driving
                }
            } else if model.phase == .driving {
                model.phase = .running
            }

            if model.showAdvanced {
                model.statusDetail = String(format: "KMP lms=%lld · %@", lowUpdates, focus.rawValue)
            } else {
                model.statusDetail = "KMP · " + focus.displayName
            }

            // ~2s 一次 running_snapshot（對齊 Android 2s）— 欄位名=Android schema
            self.uiTickCount += 1
            if self.uiTickCount % 10 == 0, model.isRunning {
                let linearRms = pow(10 as Float, raw / 20)
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
                    lowBandEnergyAnti: lowAnti
                )
                // Overlay real KMP diagnostics into next event
                SessionLogger.shared.event("kmp_diag", [
                    AndroidSnapshotKeys.imuMicCoherence: String(format: "%.3f", coh),
                    AndroidSnapshotKeys.bankMatchQuality: String(format: "%.3f", bank),
                    AndroidSnapshotKeys.fixedBankOut: String(format: "%.4f", fixedOut),
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
