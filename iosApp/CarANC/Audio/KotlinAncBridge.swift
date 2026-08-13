import Foundation
import CarANCShared

/// Bridges AVAudioEngine float blocks ↔ KMP `MultiBandANCProcessor.process(ShortArray)`.
/// This is the **shared Android DSP path** on iOS (not the simplified Swift FxLMS).
final class KotlinAncBridge {
    private let processor: MultiBandANCProcessor
    private let bufferSize: Int
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
    // 1.2.3 AdaptiveNarrowbandBank
    private(set) var tireNotchEnergy: Float = 0
    private(set) var windNotchEnergy: Float = 0
    private(set) var tireNotchF0Hz: Float = 0
    private(set) var windNotchActiveCount: Int = 0
    private(set) var notchMixAnti: Float = 0

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
        guard tier != lastTier else { return }
        lastTier = tier
        processor.updateTier(tier: Self.mapTier(tier))
    }

    func setEstimatedLatencyMs(_ ms: Float) {
        processor.setEstimatedLatencyMs(latencyMs: ms)
        refreshLimits()
    }

    func setVehicleSpeed(kmh: Float, valid: Bool) {
        processor.setVehicleSpeed(speedKmh: kmh, valid: valid)
    }

    func setRumbleAccel(_ mag: Float) {
        processor.setRumbleAccel(mag: mag)
    }

    func registerBlockEnergy(rms: Float) -> Bool {
        processor.registerBlockEnergy(rms: rms)
    }

    func finishLearning() {
        processor.finishLearning()
    }

    /// - Parameter mono: float -1…1
    /// - Returns: anti-noise float -1…1 (same length)
    func process(mono: [Float]) -> [Float] {
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
        // diagnostics
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
        refreshLimits()
        return out
    }

    func release() {
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

    private func refreshLimits() {
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
