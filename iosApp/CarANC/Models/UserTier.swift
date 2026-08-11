import Foundation

/// 對齊 Android `UserTier`：免費輕度 / 標準中度 / 專業重度
enum UserTier: String, CaseIterable, Identifiable {
    case light = "LIGHT"
    case standard = "STANDARD"
    case pro = "PRO"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .light: return "輕度（免費）"
        case .standard: return "中度（標準）"
        case .pro: return "重度（專業）"
        }
    }

    var shortLabel: String {
        switch self {
        case .light: return "輕"
        case .standard: return "中"
        case .pro: return "重"
        }
    }

    /// 對齊 MultiBandANCProcessor.tierLength
    var filterLength: Int {
        switch self {
        case .light: return 128
        case .standard: return 256
        case .pro: return 512
        }
    }

    /// 對齊 MultiBandANCProcessor.tierMu
    var baseMu: Float {
        switch self {
        case .light: return 0.005
        case .standard: return 0.01
        case .pro: return 0.02
        }
    }

    /// 對齊 MultiBandANCProcessor.tierLeakage
    var leakage: Float {
        switch self {
        case .light: return 0.9999
        case .standard: return 0.9998
        case .pro: return 0.9995
        }
    }
}

enum SubscriptionPlan: String, CaseIterable {
    case free = "free"
    case standard = "standard"
    case pro = "pro"

    var displayName: String {
        switch self {
        case .free: return "免費"
        case .standard: return "標準方案"
        case .pro: return "專業方案"
        }
    }

    var maxTier: UserTier {
        switch self {
        case .free: return .light
        case .standard: return .standard
        case .pro: return .pro
        }
    }
}

enum NvhFocus: String {
    case roadRumble = "ROAD_RUMBLE"
    case tireNoise = "TIRE_NOISE"
    case windShear = "WIND_SHEAR"
    case mixedCabin = "MIXED_CABIN"
    case idle = "IDLE"

    var displayName: String {
        switch self {
        case .roadRumble: return "路噪 40–200 Hz"
        case .tireNoise: return "輪噪 80–350 Hz"
        case .windShear: return "風切 >500 Hz（不追消）"
        case .mixedCabin: return "混合車廂"
        case .idle: return "怠速 / 靜止"
        }
    }
}
