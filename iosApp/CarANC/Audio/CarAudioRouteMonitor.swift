import Foundation
import AVFoundation
import Combine

/// 對齊 Android `AudioRouteManager.aaLinkType` 的 iOS 車機路由分類。
enum CarLinkType: String {
    case local = "local"
    /// USB / 有線 CarPlay（優先，對齊 AA wired_usb / projection_submix）
    case carplayWired = "carplay_wired"
    /// 無線 CarPlay（對齊 AA wireless_bt 風險路徑）
    case carplayWireless = "carplay_wireless"
    /// 偵測到 CarPlay 但無法分有線/無線
    case carplayUnknown = "carplay_unknown"
    /// 其他車載音訊口
    case carAudio = "car_audio"

    var isCarPlay: Bool {
        switch self {
        case .carplayWired, .carplayWireless, .carplayUnknown, .carAudio:
            return true
        case .local:
            return false
        }
    }

    /// Log 用：對齊 Android `wirelessAaSuspected`
    var wirelessSuspected: Bool { self == .carplayWireless }
}

/// 監聽 `AVAudioSession` 路由，對齊 Android `CarConnection` + `aaLinkType`。
final class CarAudioRouteMonitor: ObservableObject {
    @Published private(set) var linkType: CarLinkType = .local
    @Published private(set) var carPlayConnected = false
    @Published private(set) var portName: String = "none"
    @Published private(set) var portTypeRaw: String = "none"

    private var observers: [NSObjectProtocol] = []

    func start() {
        refresh()
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refresh()
        })
        // 路由刷新即可；通話中斷的 pause/resume 由 AncAudioEngine 處理（避免只 refresh 卻繼續播 anti）
        observers.append(nc.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refresh()
        })
    }

    func stop() {
        let nc = NotificationCenter.default
        observers.forEach { nc.removeObserver($0) }
        observers.removeAll()
    }

    func refresh() {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs
        var best: CarLinkType = .local
        var name = "phone_local"
        var typeRaw = "builtin"

        for port in outputs {
            let t = port.portType
            typeRaw = t.rawValue
            name = port.portName

            // CarPlay / car audio ports
            if t == .carAudio {
                // 依 port 名稱粗分有線/無線（Apple 未保證 enum 區分）
                let n = port.portName.lowercased()
                if n.contains("wireless") || n.contains("wifi") || n.contains("wi-fi") {
                    best = .carplayWireless
                } else if n.contains("carplay") || n.contains("usb") || n.contains("cable") {
                    best = .carplayWired
                } else {
                    // 預設視為投影車機（對齊 AA projection_submix 常見路徑）
                    best = .carplayUnknown
                }
                break
            }
            // 部分系統以藍牙呈現無線 CarPlay 相關
            if t == .bluetoothA2DP || t == .bluetoothHFP || t == .bluetoothLE {
                // 僅當名稱暗示 CarPlay 才升級；否則仍算 local/BT 耳機
                let n = port.portName.lowercased()
                if n.contains("carplay") || n.contains("car") {
                    best = .carplayWireless
                    break
                }
            }
            if t == .airPlay {
                let n = port.portName.lowercased()
                if n.contains("car") {
                    best = .carplayWireless
                    break
                }
            }
        }

        // 多輸出時若任一為 car，優先 car
        if best == .local {
            for port in outputs where port.portType == .carAudio {
                best = .carplayUnknown
                name = port.portName
                typeRaw = port.portType.rawValue
                break
            }
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.linkType = best
            self.carPlayConnected = best.isCarPlay
            self.portName = name
            self.portTypeRaw = typeRaw
        }
    }

    /// 啟動 ANC 前呼叫，讓 session 偏好車機輸出（對齊 AA 不 fallback 手機喇叭的意圖）。
    ///
    /// **勿用 `.voiceChat`**：會開語音處理／AGC，通話結束後 CarPlay 音樂常變「電話聲道」或忽然很大聲。
    static func configureSessionForCarIfNeeded(preferCar: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        if preferCar {
            // 不強制 defaultToSpeaker，讓系統把輸出送到 CarPlay；與音樂 mix，不搶通話聲道
            // 對齊 Android 1.2.16：盡量走「媒體」可聽路徑（勿 voiceChat）
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.allowBluetoothA2DP, .mixWithOthers, .allowAirPlay, .allowBluetoothHFP]
            )
        } else {
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.defaultToSpeaker, .allowBluetoothA2DP, .mixWithOthers]
            )
        }
        try session.setPreferredSampleRate(48_000)
        try session.setPreferredIOBufferDuration(preferCar ? 0.02 : 0.01)
        try session.setActive(true, options: [])
        let outs = session.currentRoute.outputs.map { "\($0.portType.rawValue):\($0.portName)" }.joined(separator: "|")
        let cat = session.category.rawValue
        let mode = session.mode.rawValue
        Task { @MainActor in
            SessionLogger.shared.event("audio_session_configured", [
                "preferCar": "\(preferCar)",
                "category": cat,
                "mode": mode,
                "routeOutputs": outs.isEmpty ? "none" : outs
            ])
        }
    }

    /// 通話結束後重新套用「非語音」session，避免卡在 call audio 增益路徑。
    static func restoreAfterCallInterruption(preferCar: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        // 先讓出，再以 default mode 搶回 — 有助 CarPlay 音樂回到正常媒體音量
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        try configureSessionForCarIfNeeded(preferCar: preferCar)
    }
}
