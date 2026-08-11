import Foundation

/// iOS 端多頻段 ANC（對齊 Android MultiBandANCProcessor 產品策略）
/// - 主力：路噪 40–200 Hz、輪噪 80–350 Hz
/// - 風切 >500 Hz：不追消（high gain → 0）
/// - 依延遲限制 mid/high
final class MultiBandAncProcessor {
    private let sampleRate: Int
    private let splitter: BandSplitter
    private let lowBand: BandFxLms
    private let midBand: BandFxLms
    private let highBand: BandFxLms
    private var tier: UserTier

    private var estimatedLatencyMs: Float = 40
    private var vehicleSpeedKmh: Float = 0
    private var vehicleSpeedValid = false
    private var rumbleAccel: Float = 0
    private var freezeBlocksRemaining = 0
    private var lastBlockEnergy: Float = 0
    private var learningBlocksRemaining = 0

    private(set) var lastNvhFocus: NvhFocus = .idle
    private(set) var maxCancelHz: Float = 150
    private(set) var midEnabled = false
    private(set) var highEnabled = false
    private(set) var latencyStrategy = "NORMAL"

    // 簡易 1 階 lowpass for final anti（減少高頻嘶聲）
    private var lpY: Float = 0
    private var lpCoeff: Float = 0.15

    init(sampleRate: Int = 48_000, tier: UserTier = .light) {
        self.sampleRate = sampleRate
        self.tier = tier
        self.splitter = BandSplitter(sampleRate: sampleRate)
        let fl = tier.filterLength
        let mu = tier.baseMu
        lowBand = BandFxLms(label: "low", centerHz: 120, baseMuScale: 1.0, filterLength: fl, baseMu: mu)
        midBand = BandFxLms(label: "mid", centerHz: 335, baseMuScale: 0.32, filterLength: max(fl / 2, 64), baseMu: mu)
        highBand = BandFxLms(label: "high", centerHz: 1000, baseMuScale: 0.05, filterLength: max(fl / 4, 32), baseMu: mu)
        applyTier(tier)
        // 預設 secondary path ≈ δ
        let identity = [Float](repeating: 0, count: 64)
        var id = identity; id[0] = 1
        lowBand.bindSecondaryPath(id)
        midBand.bindSecondaryPath(id)
        highBand.bindSecondaryPath(id)
        // 最終 anti lowpass ~350 Hz
        lpCoeff = min(max(Float(2 * Double.pi * 350 / Double(sampleRate)), 0.02), 0.5)
        startLearning()
    }

    func applyTier(_ tier: UserTier) {
        self.tier = tier
        let fl = tier.filterLength
        let mu = tier.baseMu
        let leak = tier.leakage
        lowBand.configure(filterLength: fl, baseMu: mu, leakage: leak)
        midBand.configure(filterLength: max(fl / 2, 64), baseMu: mu, leakage: leak)
        highBand.configure(filterLength: max(fl / 4, 32), baseMu: mu, leakage: leak)
    }

    func startLearning(blocks: Int = 80) {
        learningBlocksRemaining = blocks
    }

    func setEstimatedLatencyMs(_ ms: Float) {
        estimatedLatencyMs = min(max(ms, 15), 400)
        updateLatencyLimits()
        // plant electrical ≈ latency * sampleRate / 1000 * 0.35（保守）
        let delay = Int(estimatedLatencyMs * 0.001 * Float(sampleRate) * 0.35)
        lowBand.acousticDelaySamples = delay
        midBand.acousticDelaySamples = delay
        highBand.acousticDelaySamples = delay
    }

    func setVehicleSpeed(kmh: Float, valid: Bool) {
        vehicleSpeedKmh = kmh
        vehicleSpeedValid = valid
    }

    func setRumbleAccel(_ mag: Float) {
        rumbleAccel = max(mag, 0)
    }

    func registerBlockEnergy(rms: Float) -> Bool {
        // bump freeze：能量比突然升高
        if lastBlockEnergy > 1e-6 {
            let ratio = rms / lastBlockEnergy
            if ratio > 15 {
                freezeBlocksRemaining = max(freezeBlocksRemaining, 6)
                lastBlockEnergy = rms
                return true
            }
        }
        lastBlockEnergy = rms * 0.2 + lastBlockEnergy * 0.8
        if freezeBlocksRemaining > 0 { freezeBlocksRemaining -= 1 }
        return false
    }

    private func updateLatencyLimits() {
        // 相位可追上限 ≈ 0.15 / latency_sec 粗估
        let latSec = max(estimatedLatencyMs / 1000, 0.02)
        maxCancelHz = min(max(0.15 / latSec, 60), 350)
        midEnabled = maxCancelHz >= 200
        highEnabled = false // 風切策略：high 永遠關 adaptive
        if estimatedLatencyMs > 180 {
            latencyStrategy = "HIGH_LAT_CONSERVATIVE"
            midEnabled = false
            maxCancelHz = min(maxCancelHz, 120)
        } else {
            latencyStrategy = "NORMAL"
        }
    }

    private func classifyNvh(lowE: Float, midE: Float, highE: Float) -> NvhFocus {
        let total = lowE + midE + highE + 1e-9
        let lowR = lowE / total
        let midR = midE / total
        let highR = highE / total
        let spd = vehicleSpeedValid ? vehicleSpeedKmh : 0

        if !vehicleSpeedValid || spd < 12 {
            return .idle
        }
        if highR > 0.45 && spd > 60 {
            return .windShear
        }
        if midR > 0.35 || (spd > 40 && midR > 0.25) {
            return .tireNoise
        }
        if lowR > 0.4 || rumbleAccel > 0.8 {
            return .roadRumble
        }
        return .mixedCabin
    }

    /// 處理一整個 block（正規化 float -1…1）
    /// - Returns: anti-noise samples（應播到喇叭，符號已取負：speaker = anti）
    func process(input: [Float]) -> [Float] {
        updateLatencyLimits()
        var out = [Float](repeating: 0, count: input.count)
        var lowE: Float = 0
        var midE: Float = 0
        var highE: Float = 0

        let freeze = freezeBlocksRemaining > 0 || learningBlocksRemaining > 20
        let learning = learningBlocksRemaining > 0
        if learningBlocksRemaining > 0 { learningBlocksRemaining -= 1 }

        // 速度 / 延遲 對 mu 的尺度
        let speedScale: Float = {
            guard vehicleSpeedValid else { return 0.35 }
            if vehicleSpeedKmh < 12 { return 0.3 }
            if vehicleSpeedKmh < 40 { return 0.7 }
            return 1.0
        }()

        let highLatDamp: Float = estimatedLatencyMs > 180 ? 0.45 : 1.0
        let rumbleBoost = min(1 + rumbleAccel * 0.15, 1.6)

        for i in 0..<input.count {
            let x = input[i]
            let bands = splitter.split(x)
            lowE += bands.low * bands.low
            midE += bands.mid * bands.mid
            highE += bands.high * bands.high

            let focus = lastNvhFocus
            var lowGain: Float = 1
            var midGain: Float = midEnabled ? 0.6 : 0
            var highGain: Float = 0

            switch focus {
            case .idle:
                lowGain = 0.25; midGain = 0; highGain = 0
            case .roadRumble:
                lowGain = 1.0 * rumbleBoost; midGain = midEnabled ? 0.25 : 0; highGain = 0
            case .tireNoise:
                lowGain = 0.7; midGain = midEnabled ? 0.85 : 0.2; highGain = 0
            case .windShear:
                // 不追消風切
                lowGain = 0.5; midGain = 0; highGain = 0
            case .mixedCabin:
                lowGain = 0.8; midGain = midEnabled ? 0.4 : 0; highGain = 0
            }

            if learning {
                // learning 期較小輸出
                lowGain *= 0.15
                midGain *= 0.1
            }

            let lowMu = lowBand.baseMuScale * lowGain * speedScale * highLatDamp
            let midMu = midBand.baseMuScale * midGain * speedScale * highLatDamp
            let highMu: Float = 0 // never chase HF

            let yLow = lowBand.processSample(
                sample: bands.low, muScale: lowMu, freezeUpdates: freeze, errorSample: bands.low
            )
            let yMid = midBand.processSample(
                sample: bands.mid, muScale: midMu, freezeUpdates: freeze, errorSample: bands.mid
            )
            let yHigh = highBand.processSample(
                sample: bands.high, muScale: highMu, freezeUpdates: true, errorSample: bands.high
            )

            // anti = -(y)；再 lowpass 去嘶聲
            var anti = -(yLow * lowGain + yMid * midGain + yHigh * highGain)
            // 最終 ~350 Hz lowpass
            lpY += lpCoeff * (anti - lpY)
            anti = lpY
            // 輸出限幅
            out[i] = min(max(anti * 0.85, -0.95), 0.95)
        }

        lastNvhFocus = classifyNvh(lowE: lowE, midE: midE, highE: highE)
        return out
    }

    var lowLmsUpdates: Int64 { lowBand.lmsUpdateCount }
    var midLmsUpdates: Int64 { midBand.lmsUpdateCount }
}
