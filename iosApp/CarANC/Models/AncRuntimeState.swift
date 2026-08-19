import Foundation
import Combine

enum AncPhase: String {
    case stopped
    case calibrating
    case learning
    case running
    case driving
    case music
    case paused
    case error

    var message: String {
        switch self {
        case .stopped: return "已停止"
        case .calibrating: return "收音校正中…請保持安靜"
        case .learning: return "學習中…請保持安靜"
        case .running: return "降噪中…"
        case .driving: return "路噪降噪中（行駛中）…"
        case .music: return "底噪降噪中（音樂播放）…"
        case .paused: return "已暫停（通話保護）"
        case .error: return "錯誤"
        }
    }
}

/// 主畫面可觀察狀態（對齊 Android AncStateManager 關鍵欄位）
@MainActor
final class AncAppModel: ObservableObject {
    @Published var phase: AncPhase = .stopped
    @Published var statusDetail: String = ""
    @Published var tier: UserTier = .light
    @Published var plan: SubscriptionPlan = .free
    @Published var safetyConsentAccepted = false
    @Published var showSafetyConsent = false

    @Published var rawDb: Float = -90
    @Published var antiDb: Float = -90
    @Published var residualDb: Float = -90
    @Published var reductionDb: Float = 0
    @Published var lowBandRumbleReduction: Float = 0

    @Published var noiseSpectrum: [Float] = Array(repeating: 0, count: 32)
    @Published var antiSpectrum: [Float] = Array(repeating: 0, count: 32)

    @Published var vehicleSpeedKmh: Float = 0
    @Published var vehicleSpeedValid = false
    /// gps | gps_hold | imu_proxy | none（無車速備用）
    @Published var speedSource: String = "none"
    @Published var speedHoldAgeSec: Float = -1
    @Published var imuProxyKmh: Float = 0
    @Published var speedValidForRoadTest = false
    @Published var rumbleAccel: Float = 0
    // 1.2.3–1.2.5 notch / boom diagnostics
    @Published var tireNotchEnergy: Float = 0
    @Published var windNotchEnergy: Float = 0
    @Published var tireNotchF0Hz: Float = 0
    @Published var windNotchActiveCount: Int = 0
    @Published var notchMixAnti: Float = 0
    @Published var roadNotchEnergy: Float = 0
    @Published var roadBoomWeightEnergy: Float = 0
    /// 1.2.7 plant-delayed LF pressure (0 until KMP framework rebuild includes getter)
    @Published var boomPressureOut: Float = 0
    @Published var boomPlantCorr: Float = 0
    @Published var plantElectricalDelaySamples: Int = 0
    @Published var effectiveLowMu: Float = 0
    @Published var effectiveMidMu: Float = 0
    /// 1.2.6 腳本強制 focus：ROAD_RUMBLE / TIRE_NOISE / WIND_SHEAR / auto
    @Published var forcedNvhFocus: String = "auto"
    /// 1.2.8 診斷 tone（0=關；50=50Hz）
    @Published var diagToneHz: Float = 0
    /// 1.2.10–1.2.12 mute / gain / polarity
    @Published var muteAnti = false
    @Published var userAncGain: Float = 1
    @Published var forceBoomPolarity: Float = 0
    /// 1.2.14 預設 −1
    @Published var boomPolarity: Float = -1
    @Published var openBoomActive = false
    @Published var plantResidualReductionDb: Float = 0
    @Published var estimatedLatencyMs: Float = 0
    @Published var maxCancelHz: Float = 150
    @Published var nvhFocus: NvhFocus = .idle
    @Published var midEnabled = false
    @Published var highEnabled = false
    @Published var isRunning = false
    @Published var lastError: String?

    // CarPlay / 車機路由（對齊 Android aaLinkType / isAAConnected）
    @Published var aaLinkType: String = "local"
    @Published var carPlayConnected = false
    @Published var wirelessCarPlaySuspected = false
    /// 供斷線 edge 偵測
    var wasCarPlayConnected = false

    @Published var showAdvanced = false
    @Published var selectedTab = 0

    static let safetyConsentVersion = 1
    private let defaults = UserDefaults.standard

    init() {
        loadPrefs()
        if !safetyConsentAccepted {
            showSafetyConsent = true
        }
    }

    var statusText: String {
        if phase == .error, let err = lastError {
            return "錯誤：\(err)"
        }
        return statusDetail.isEmpty ? phase.message : "\(phase.message) \(statusDetail)"
    }

    var speedText: String {
        switch speedSource {
        case "gps":
            return String(format: "GPS 車速：%.0f km/h", vehicleSpeedKmh)
        case "gps_hold":
            return String(format: "車速保持：%.0f km/h（掉線 %.0fs）", vehicleSpeedKmh, max(0, speedHoldAgeSec))
        case "imu_proxy":
            return String(format: "IMU 估計車速：%.0f km/h（無 GPS 備用）", vehicleSpeedKmh)
        default:
            if vehicleSpeedValid {
                return String(format: "車速：%.0f km/h", vehicleSpeedKmh)
            }
            return "車速：不可用（開定位或行駛中 IMU 可備用）"
        }
    }

    var latencyText: String {
        if estimatedLatencyMs <= 0 {
            return "延遲：啟動降噪後顯示"
        }
        let mid = midEnabled ? "中" : "中×"
        let high = highEnabled ? "高" : "高×"
        return String(
            format: "延遲 %.0f ms · 可抵消 ≤%.0f Hz · band[%@/%@]",
            estimatedLatencyMs, maxCancelHz, mid, high
        )
    }

    func acceptSafetyConsent(marketingOptIn: Bool = false) {
        safetyConsentAccepted = true
        defaults.set(true, forKey: "safety_consent")
        defaults.set(Self.safetyConsentVersion, forKey: "safety_consent_version")
        defaults.set(marketingOptIn, forKey: "marketing_opt_in")
        showSafetyConsent = false
    }

    func setPlan(_ plan: SubscriptionPlan) {
        self.plan = plan
        defaults.set(plan.rawValue, forKey: "plan")
        // 方案上限夾住等級
        if tier.rawValue.rank > plan.maxTier.rawValue.rank {
            setTier(plan.maxTier)
        }
    }

    func setTier(_ tier: UserTier) {
        let allowed = min(tier, plan.maxTier)
        self.tier = allowed
        defaults.set(allowed.rawValue, forKey: "tier")
    }

    private func loadPrefs() {
        if let p = defaults.string(forKey: "plan"), let plan = SubscriptionPlan(rawValue: p) {
            self.plan = plan
        }
        if let t = defaults.string(forKey: "tier"), let tier = UserTier(rawValue: t) {
            self.tier = tier
        }
        let accepted = defaults.bool(forKey: "safety_consent")
        let ver = defaults.integer(forKey: "safety_consent_version")
        safetyConsentAccepted = accepted && ver >= Self.safetyConsentVersion
    }
}

private extension String {
    var rank: Int {
        switch self {
        case "LIGHT": return 0
        case "STANDARD": return 1
        case "PRO": return 2
        default: return 0
        }
    }
}

private func min(_ a: UserTier, _ b: UserTier) -> UserTier {
    a.rawValue.rank <= b.rawValue.rank ? a : b
}
