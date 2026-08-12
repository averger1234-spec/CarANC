import Foundation
import Combine

/// 手機 UI 與 CarPlay 共用的單一控制面（對齊 Android GlobalAncSessionContext + ANCService）。
@MainActor
final class AppController: ObservableObject {
    static let shared = AppController()

    let model: AncAppModel
    let engine: AncAudioEngine
    let routeMonitor: CarAudioRouteMonitor

    private var cancellables = Set<AnyCancellable>()

    private init() {
        let m = AncAppModel()
        self.model = m
        self.engine = AncAudioEngine(model: m)
        self.routeMonitor = CarAudioRouteMonitor()
        self.routeMonitor.start()
        bindRouteToModel()
    }

    private func bindRouteToModel() {
        routeMonitor.$linkType
            .receive(on: DispatchQueue.main)
            .sink { [weak self] type in
                self?.model.aaLinkType = type.rawValue
                self?.model.carPlayConnected = type.isCarPlay
                self?.model.wirelessCarPlaySuspected = type == .carplayWireless
            }
            .store(in: &cancellables)

        routeMonitor.$carPlayConnected
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] connected in
                guard let self else { return }
                if connected {
                    SessionLogger.shared.event("carplay_connected", [
                        "aaLinkType": self.routeMonitor.linkType.rawValue,
                        "port": self.routeMonitor.portName
                    ])
                } else if self.model.wasCarPlayConnected {
                    SessionLogger.shared.event("carplay_disconnected", [
                        "aaLinkType": self.routeMonitor.linkType.rawValue,
                        "action": SessionLogger.shared.guidedTestActive ? "keep_engine_for_log" : "stop_if_running"
                    ])
                    // 對齊 Android AA：斷線時停止降噪；導引腳本中保留 session/log
                    if self.model.isRunning && !SessionLogger.shared.guidedTestActive {
                        self.engine.stop()
                    }
                }
                self.model.wasCarPlayConnected = connected
            }
            .store(in: &cancellables)
    }

    /// CarPlay / 手機共用：啟動降噪（需已同意安全聲明）
    func startAnc() async -> String? {
        guard model.safetyConsentAccepted else {
            model.showSafetyConsent = true
            return "請先在手機 App 接受安全聲明"
        }
        do {
            try await engine.start(preferCarAudio: routeMonitor.linkType.isCarPlay)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func stopAnc() {
        engine.stop()
    }

    func setTier(_ tier: UserTier) {
        model.setTier(tier)
        engine.applyTier(tier)
    }
}
