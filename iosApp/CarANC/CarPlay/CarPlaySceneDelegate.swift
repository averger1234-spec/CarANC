import CarPlay
import UIKit

/// 對齊 Android `ANCAppService` + `CarAncAutoScreen`：CarPlay 模板主畫面。
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    var interfaceController: CPInterfaceController?
    private var root: CarPlayRootController?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        let root = CarPlayRootController(interfaceController: interfaceController)
        self.root = root
        root.installRootTemplate()

        Task { @MainActor in
            SessionLogger.shared.event("carplay_ui_connected", [
                "surface": "CPTemplateApplicationScene",
                "mirror": "android_CarAncAutoScreen"
            ])
            // 連上 CarPlay UI 時刷新路由（可能稍晚才有 carAudio port）
            AppController.shared.routeMonitor.refresh()
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnect interfaceController: CPInterfaceController
    ) {
        Task { @MainActor in
            SessionLogger.shared.event("carplay_ui_disconnected", [
                "surface": "CPTemplateApplicationScene"
            ])
            // UI 斷線 ≠ 一定斷音訊；路由監控會處理 stop 策略
            AppController.shared.routeMonitor.refresh()
        }
        root = nil
        self.interfaceController = nil
    }
}
