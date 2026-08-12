import CarPlay
import Combine
import Foundation

/// 對齊 `CarAncAutoScreen`：狀態 + 啟動/停止 + 輕/中/高 等級。
@MainActor
final class CarPlayRootController: NSObject {
    private let interfaceController: CPInterfaceController
    private var listTemplate: CPListTemplate?
    private var cancellables = Set<AnyCancellable>()
    private var refreshTimer: Timer?

    init(interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
        super.init()
        observeApp()
    }

    func installRootTemplate() {
        let template = buildListTemplate()
        listTemplate = template
        interfaceController.setRootTemplate(template, animated: true, completion: nil)
        // 週期刷新（CarPlay 無 Combine 綁定模板）
        refreshTimer?.invalidate()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.reload()
            }
        }
        if let t = refreshTimer {
            RunLoop.main.add(t, forMode: .common)
        }
    }

    private func observeApp() {
        let app = AppController.shared
        app.model.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.reload()
            }
            .store(in: &cancellables)
    }

    private func reload() {
        guard let listTemplate else { return }
        listTemplate.updateSections(buildSections())
    }

    private func buildListTemplate() -> CPListTemplate {
        let t = CPListTemplate(title: "CarANC", sections: buildSections())
        t.tabImage = UIImage(systemName: "waveform.circle")
        return t
    }

    private func buildSections() -> [CPListSection] {
        let app = AppController.shared
        let m = app.model
        let running = m.isRunning
        let needsConsent = !m.safetyConsentAccepted

        let statusTitle: String
        if needsConsent && !running {
            statusTitle = "請先在手機接受安全聲明"
        } else {
            statusTitle = m.statusText
        }

        let link = m.aaLinkType
        let kpi = String(format: "低頻KPI %.2f · 輸入 %.1f dB · 反噪 %.1f dB",
                         m.lowBandRumbleReduction, m.rawDb, m.antiDb)
        let routeLine = "路由 \(link)" + (m.carPlayConnected ? " · CarPlay" : " · 本機")
        let nvh = m.nvhFocus.displayName
        let speed: String = {
            if m.vehicleSpeedValid {
                return String(format: "車速 %.0f km/h", m.vehicleSpeedKmh)
            }
            return "車速 不可用"
        }()

        let statusItem = CPListItem(
            text: "車內主動降噪",
            detailText: "\(statusTitle)\n\(kpi)\n\(routeLine) · \(nvh) · \(speed)"
        )
        statusItem.isEnabled = false

        let noteItem = CPListItem(
            text: "架構（對齊 Android AA）",
            detailText: "本畫面 = CarPlay 模板，非原廠 RNC ECU。音訊在手機 DSP，輸出走車機。建議有線 CarPlay；無線高延遲走 SpeedScheduled 增益表。"
        )
        noteItem.isEnabled = false

        // 主操作：啟動 / 停止
        let actionTitle = running ? "停止降噪" : "啟動降噪"
        let actionItem = CPListItem(text: actionTitle, detailText: running ? "停止本機/車機 ANC" : "開始 KMP MultiBand ANC")
        actionItem.handler = { [weak self] _, completion in
            Task { @MainActor in
                defer { completion() }
                let app = AppController.shared
                if app.model.isRunning {
                    app.stopAnc()
                } else if !app.model.safetyConsentAccepted {
                    // 無法在車機顯示完整聲明 — 同 AA
                    app.model.showSafetyConsent = true
                } else {
                    _ = await app.startAnc()
                }
                self?.reload()
            }
        }

        let controlSection = CPListSection(
            items: [statusItem, actionItem, noteItem],
            header: "狀態",
            sectionIndexTitle: nil
        )

        // 等級：輕 / 中 / 高（對齊 AA ActionStrip）
        let tiers: [(String, UserTier)] = [
            ("輕", .light),
            ("中", .standard),
            ("高", .pro)
        ]
        let tierItems: [CPListItem] = tiers.map { label, tier in
            let selected = m.tier == tier
            let item = CPListItem(
                text: selected ? "● \(label)" : label,
                detailText: tier.rawValue
            )
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    defer { completion() }
                    AppController.shared.setTier(tier)
                    self?.reload()
                }
            }
            return item
        }
        let tierSection = CPListSection(items: tierItems, header: "降噪等級", sectionIndexTitle: nil)

        return [controlSection, tierSection]
    }

    deinit {
        refreshTimer?.invalidate()
    }
}
