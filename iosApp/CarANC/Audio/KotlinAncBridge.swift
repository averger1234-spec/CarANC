import Foundation
import CarANCShared

/// Bridges AVAudioEngine float blocks ↔ KMP `MultiBandANCProcessor.process(ShortArray)`.
/// This is the **shared Android DSP path** on iOS (not the simplified Swift FxLMS).
///
/// **Thread safety**: AVAudioEngine tap 與主執行緒 UI 會同時碰到 processor；
/// 無鎖時 Kotlin/Native 常在「按開始降噪」後立即閃退。
final class KotlinAncBridge {
    private let processor: MultiBandANCProcessor
    private let bufferSize: Int
    private let lock = NSLock()
    private var lastTier: UserTier = .standard

    private(set) var lastNvhFocusRaw: String = "IDLE"
    private(set) var latencyStrategy: String = "NORMAL"
    private(set) var lowLmsUpdates: Int64 = 0
    private(set) var midLmsUpdates: Int64 = 0
    private(set) var maxCancelHz: Float = 150
    private(set) var midEnabled: Bool = false
    private(set) var highEnabled: Bool = false
    private(set) var imuMicCoherence: Float = 0
    private(set) var bankMatchQuality: Float = 0
    private(set) var fixedBankOut: Float = 0
    private(set) var plantResidualReductionDb: Float = 0
    // Android 1.1.0 SpeedScheduledNvhGains (via KMP MultiBand)
    private(set) var speedNvhBinKmh: Int = 0
    private(set) var speedNvhLowGain: Float = 1
    private(set) var speedNvhMidGain: Float = 0.25
    private(set) var speedNvhTotalAnti: Float = 1
    private(set) var speedNvhTableId: String = "none"
    // 1.2.3–1.2.5 AdaptiveNarrowbandBank
    private(set) var tireNotchEnergy: Float = 0
    private(set) var windNotchEnergy: Float = 0
    private(set) var tireNotchF0Hz: Float = 0
    private(set) var windNotchActiveCount: Int = 0
    private(set) var notchMixAnti: Float = 0
    private(set) var roadNotchEnergy: Float = 0
    private(set) var roadBoomWeightEnergy: Float = 0
    private(set) var boomPressureOut: Float = 0
    private(set) var boomPlantCorr: Float = 0
    private(set) var plantElectricalDelaySamples: Int = 0
    /// 1.2.14 default −1（艙錄偏好）
    private(set) var boomPolarity: Float = -1
    private(set) var openBoomActive: Bool = false
    private(set) var effectiveLowMu: Float = 0
    private(set) var effectiveMidMu: Float = 0
    /// Soft mute inside KMP (zeros boom/notch/bank KPIs)
    private(set) var antiOutputMuted: Bool = false

    init(sampleRate: Int, bufferSize: Int, tier: UserTier) {
        self.bufferSize = bufferSize
        let ctx = AncSessionContextIosKt.IosGlobalAncSessionContext
        let kTier = Self.mapTier(tier)
        self.processor = MultiBandANCProcessor(
            sampleRate: Int32(sampleRate),
            bufferSize: Int32(bufferSize),
            initialTier: kTier,
            sessionContext: ctx
        )
        self.lastTier = tier
    }

    func updateTier(_ tier: UserTier) {
        lock.lock()
        defer { lock.unlock() }
        guard tier != lastTier else { return }
        lastTier = tier
        processor.updateTier(tier: Self.mapTier(tier))
    }

    func setEstimatedLatencyMs(_ ms: Float) {
        lock.lock()
        defer { lock.unlock() }
        processor.setEstimatedLatencyMs(latencyMs: ms)
        refreshLimitsUnlocked()
    }

    func setVehicleSpeed(kmh: Float, valid: Bool) {
        lock.lock()
        defer { lock.unlock() }
        processor.setVehicleSpeed(speedKmh: kmh, valid: valid)
    }

    func setRumbleAccel(_ mag: Float) {
        lock.lock()
        defer { lock.unlock() }
        processor.setRumbleAccel(mag: mag)
    }

    /// 1.2.8 P0：三軸 IMU（mu/coherence；1.2.11+ 不再混進 audio ref）
    func setImuAxes(ax: Float, ay: Float, az: Float) {
        lock.lock()
        defer { lock.unlock() }
        processor.setImuAxes(ax: ax, ay: ay, az: az)
    }

    /// 1.2.12：腳本 mute — processor 內歸零 boom/notch/bank
    func setAntiOutputMuted(_ muted: Bool) {
        lock.lock()
        defer { lock.unlock() }
        antiOutputMuted = muted
        processor.setAntiOutputMuted(muted: muted)
    }

    /// 1.2.12：強制 boom 極性（+1 / −1）；nil 或 0 = auto
    func setBoomPolarityForced(_ polarity: Float?) {
        lock.lock()
        defer { lock.unlock() }
        if let polarity, abs(polarity) > 0.01 {
            let p: Float = polarity >= 0 ? 1 : -1
            processor.setBoomPolarityForced(polarity: KotlinFloat(float: p))
        } else {
            processor.setBoomPolarityForced(polarity: nil)
        }
    }

    func applyPersistedBoomPolarity(_ polarity: Float) {
        lock.lock()
        defer { lock.unlock() }
        processor.applyPersistedBoomPolarity(polarity: polarity)
    }

    /// 1.2.6：腳本強制 ROAD/TIRE/WIND
    func setForcedNvhFocus(_ name: String?) {
        lock.lock()
        defer { lock.unlock() }
        if let name, !name.isEmpty, name.uppercased() != "AUTO", name.uppercased() != "NONE" {
            GuidedNvhOverride.shared.set(name: name)
            processor.setForcedNvhFocus(focusName: name)
        } else {
            GuidedNvhOverride.shared.clear()
            processor.setForcedNvhFocus(focusName: nil)
        }
    }

    func registerBlockEnergy(rms: Float) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return processor.registerBlockEnergy(rms: rms)
    }

    func finishLearning() {
        lock.lock()
        defer { lock.unlock() }
        processor.finishLearning()
    }

    /// - Parameter mono: float -1…1
    /// - Returns: anti-noise float -1…1 (same length)
    func process(mono: [Float]) -> [Float] {
        lock.lock()
        defer { lock.unlock() }
        let n = mono.count
        let input = KotlinShortArray(size: Int32(n))
        for i in 0..<n {
            let s = max(-1, min(1, mono[i]))
            let v = Int16(clamping: Int(s * 32767))
            input.set(index: Int32(i), value: v)
        }
        let outShort = processor.process(input: input)
        var out = [Float](repeating: 0, count: n)
        let sz = Int(outShort.size)
        for i in 0..<min(n, sz) {
            out[i] = Float(outShort.get(index: Int32(i))) / 32768.0
        }
        // diagnostics（同鎖內更新，供主執行緒讀 snapshot）
        lowLmsUpdates = processor.getLowLmsUpdateCount()
        latencyStrategy = processor.getLatencyStrategy()
        lastNvhFocusRaw = processor.getNvhFocus()
        imuMicCoherence = processor.getImuMicCoherenceQuality()
        bankMatchQuality = processor.getBankMatchQuality()
        fixedBankOut = processor.getLastFixedBankOut()
        speedNvhBinKmh = Int(processor.getSpeedNvhBinKmh())
        speedNvhLowGain = processor.getSpeedNvhLowGain()
        speedNvhMidGain = processor.getSpeedNvhMidGain()
        speedNvhTotalAnti = processor.getSpeedNvhTotalAnti()
        speedNvhTableId = processor.getSpeedNvhTableId()
        tireNotchEnergy = processor.getTireNotchEnergy()
        windNotchEnergy = processor.getWindNotchEnergy()
        tireNotchF0Hz = processor.getTireNotchF0Hz()
        windNotchActiveCount = Int(processor.getWindNotchActiveCount())
        notchMixAnti = processor.getNotchMixAnti()
        roadNotchEnergy = processor.getRoadNotchEnergy()
        roadBoomWeightEnergy = processor.getRoadBoomWeightEnergy()
        boomPressureOut = processor.getBoomPressureOut()
        boomPlantCorr = processor.getBoomPlantCorr()
        plantElectricalDelaySamples = Int(processor.getPlantElectricalDelaySamples())
        boomPolarity = processor.getBoomPolarity()
        openBoomActive = processor.isOpenBoomActive()
        effectiveLowMu = processor.getLastEffectiveLowMu()
        effectiveMidMu = processor.getLastEffectiveMidMu()
        refreshLimitsUnlocked()
        return out
    }

    func release() {
        lock.lock()
        defer { lock.unlock() }
        processor.release()
    }

    var mappedNvhFocus: NvhFocus {
        switch lastNvhFocusRaw.uppercased() {
        case "ROAD_RUMBLE": return .roadRumble
        case "TIRE_NOISE": return .tireNoise
        case "WIND_SHEAR": return .windShear
        case "IDLE": return .idle
        default: return .mixedCabin
        }
    }

    private func refreshLimitsUnlocked() {
        let lim = processor.getLatencyBandLimits()
        maxCancelHz = lim.maxCancelFrequencyHz
        midEnabled = lim.midEnabled
        highEnabled = lim.highEnabled
    }

    private static func mapTier(_ tier: UserTier) -> CarANCShared.UserTier {
        switch tier {
        case .light: return CarANCShared.UserTier.light
        case .standard: return CarANCShared.UserTier.standard
        case .pro: return CarANCShared.UserTier.pro
        }
    }
}
