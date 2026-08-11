import Foundation

/// 對齊 Android `CarRoadTuningScript`（car_road_tuning_v1）— iOS 路測引導
struct GuidedTestStep: Identifiable {
    let id: String
    let title: String
    let instructions: [String]
    /// 行駛步：需累計的有效秒；prep/finish：壁鐘秒
    let durationSec: Int
    let suggestedTier: UserTier?
    let requiresAncRunning: Bool
    let checklist: [String]
    let minSpeedKmh: Float
    let wallClockOnly: Bool
    let maxWallSec: Int
    let debugPresets: [String: String]
}

enum CarRoadTuningScript {
    static let scriptId = "car_road_tuning_v1"
    static let scriptName = "實車路噪調校（僅路噪 · 有效行駛秒數）"

    static let steps: [GuidedTestStep] = [
        GuidedTestStep(
            id: "tuning_prep",
            title: "調校準備（快速）",
            instructions: [
                "iOS：本機喇叭 / 車用藍牙（注意延遲）；Android AA 路徑請用 Android 版",
                "車型 / 手機位置填清楚（建議 floor/seat，勿中控/手上）",
                "點「開始降噪」完成校正；開 ANC 應安靜或低頻悶，非電台靜電",
                "同一條路 50–70 km/h、音樂小聲或關",
                "★ 行駛步只累計「車速達標」有效秒；紅燈/怠速不計"
            ],
            durationSec: 20,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: ["ANC 已啟動", "placement=floor/seat", "開 ANC 無電台靜電"],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 90,
            debugPresets: ["forceNormalMode": "true", "musicLowAncEnabled": "true", "tier": "STANDARD"]
        ),
        GuidedTestStep(
            id: "tuning_4",
            title: "#4 musicLow 對比（mu=1.7）",
            instructions: [
                "累計約 50 秒「車速 ≥40」有效數據（紅燈不計）",
                "觀察延遲、低頻 KPI、主觀 rumble",
                "聽感：低頻沙沙是否下降"
            ],
            durationSec: 50,
            suggestedTier: .standard,
            requiresAncRunning: true,
            checklist: ["mu 偏高", "musicLow=ON", "tier=STANDARD", "有有效行駛秒"],
            minSpeedKmh: 40,
            wallClockOnly: false,
            maxWallSec: 600,
            debugPresets: ["lmsMuMultiplier": "1.7", "musicLowAncEnabled": "true", "tier": "STANDARD"]
        ),
        GuidedTestStep(
            id: "tuning_4b",
            title: "#4b A/B baseline（mu=1.6）",
            instructions: [
                "50 秒有效行駛（車速 ≥40）",
                "A/B baseline：與後續 #6/#7 比較低頻 KPI 與聽感"
            ],
            durationSec: 50,
            suggestedTier: .standard,
            requiresAncRunning: true,
            checklist: ["mu=1.6", "musicLow=ON", "A/B baseline", "tier=STANDARD"],
            minSpeedKmh: 40,
            wallClockOnly: false,
            maxWallSec: 600,
            debugPresets: ["lmsMuMultiplier": "1.6", "musicLowAncEnabled": "true", "tier": "STANDARD"]
        ),
        GuidedTestStep(
            id: "tuning_5",
            title: "#5 musicLow OFF 對比",
            instructions: [
                "musicLow OFF 對比；45 秒有效行駛（≥40）",
                "比較 #4/#4b：關 musicLow 時 rumble 差異"
            ],
            durationSec: 45,
            suggestedTier: .light,
            requiresAncRunning: true,
            checklist: ["musicLow=OFF", "tier=LIGHT", "對比聽感"],
            minSpeedKmh: 40,
            wallClockOnly: false,
            maxWallSec: 600,
            debugPresets: ["lmsMuMultiplier": "2.2", "musicLowAncEnabled": "false", "tier": "LIGHT"]
        ),
        GuidedTestStep(
            id: "tuning_6",
            title: "#6 路噪加強（mu=1.8 · PRO）",
            instructions: [
                "50 秒有效行駛（車速 ≥45）",
                "A/B vs #4b：低頻 KPI + 主觀 rumble",
                "無電台靜電"
            ],
            durationSec: 50,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["tier=PRO", "speed>45", "無電台靜電", "低頻有記錄"],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 600,
            debugPresets: ["lmsMuMultiplier": "1.8", "musicLowAncEnabled": "true", "tier": "PRO"]
        ),
        GuidedTestStep(
            id: "tuning_7",
            title: "#7 主驗（mu=2.05 · PRO · ≥50km/h）",
            instructions: [
                "主驗：55 秒有效行駛（車速 ≥50；紅燈不計）",
                "聽感：低頻悶、無電台靜電；A/B vs #4b",
                "記下主觀 rumble 0–10、是否嘶聲"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["tier=PRO", "speed>50", "低頻改善?", "無雜訊", "vs #4b"],
            minSpeedKmh: 50,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: ["lmsMuMultiplier": "2.05", "musicLowAncEnabled": "true", "tier": "PRO"]
        ),
        GuidedTestStep(
            id: "tuning_finish",
            title: "結束與匯出",
            instructions: [
                "停止降噪",
                "在「測試平台」或本頁匯出 session log",
                "scenario 註：tier / 連線方式 / placement / speed / 主觀 rumble 0–10",
                "把 log 傳回分析"
            ],
            durationSec: 10,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: ["已停止 ANC", "已匯出 Log"],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 30,
            debugPresets: [:]
        )
    ]
}

@MainActor
final class GuidedTestRunner: ObservableObject {
    @Published var active = false
    @Published var finished = false
    @Published var stepIndex = 0
    @Published var validSec = 0
    @Published var wallSec = 0
    @Published var statusLine = "尚未開始"
    @Published var logLines: [String] = []
    @Published var checked: Set<String> = []

    private var timer: Timer?
    private weak var model: AncAppModel?
    private weak var engine: AncAudioEngine?

    var currentStep: GuidedTestStep? {
        guard stepIndex >= 0, stepIndex < CarRoadTuningScript.steps.count else { return nil }
        return CarRoadTuningScript.steps[stepIndex]
    }

    var progress: Double {
        guard let step = currentStep, step.durationSec > 0 else { return 0 }
        let cur = step.wallClockOnly ? wallSec : validSec
        return min(Double(cur) / Double(step.durationSec), 1)
    }

    func bind(model: AncAppModel, engine: AncAudioEngine) {
        self.model = model
        self.engine = engine
    }

    func start() {
        active = true
        finished = false
        stepIndex = 0
        validSec = 0
        wallSec = 0
        checked = []
        logLines = []
        SessionLogger.shared.guidedTestActive = true
        SessionLogger.shared.guidedTestStepId = CarRoadTuningScript.steps[0].id
        SessionLogger.shared.event("guided_test_start", [
            "scriptId": CarRoadTuningScript.scriptId,
            "scriptName": CarRoadTuningScript.scriptName,
            "source": "android_CarRoadTuningScript"
        ])
        appendLog("script_start id=\(CarRoadTuningScript.scriptId) source=android_CarRoadTuningScript")
        applyPresets(for: CarRoadTuningScript.steps[0])
        statusLine = "進行中：\(CarRoadTuningScript.steps[0].title)"
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        if let t = timer { RunLoop.main.add(t, forMode: .common) }
    }

    func abort() {
        timer?.invalidate()
        timer = nil
        active = false
        SessionLogger.shared.guidedTestActive = false
        SessionLogger.shared.event("guided_test_abort", ["stepIndex": "\(stepIndex)"])
        statusLine = "已中止"
        appendLog("script_abort step=\(stepIndex)")
    }

    func completeManually() {
        advance()
    }

    func toggleCheck(_ item: String) {
        if checked.contains(item) { checked.remove(item) } else { checked.insert(item) }
    }

    private func tick() {
        guard active, !finished, let step = currentStep, let model else { return }
        wallSec += 1

        if step.wallClockOnly {
            statusLine = "\(step.title) · 壁鐘 \(wallSec)/\(step.durationSec)s"
            if wallSec >= step.durationSec || wallSec >= step.maxWallSec {
                advance()
            }
            return
        }

        let speedOk = model.vehicleSpeedValid && model.vehicleSpeedKmh >= step.minSpeedKmh
        if speedOk {
            validSec += 1
            statusLine = String(
                format: "%@ · 有效 %d/%ds（%.0f km/h）",
                step.title, validSec, step.durationSec, model.vehicleSpeedKmh
            )
        } else {
            statusLine = String(
                format: "%@ · 暫停（需 ≥%.0f km/h）有效 %d/%d · 壁鐘 %d",
                step.title, step.minSpeedKmh, validSec, step.durationSec, wallSec
            )
        }

        if validSec >= step.durationSec || wallSec >= step.maxWallSec {
            advance()
        }
    }

    private func advance() {
        guard let step = currentStep else { return }
        SessionLogger.shared.event("test_step_snapshot", [
            AndroidSnapshotKeys.guidedTestStepId: step.id,
            "validSec": "\(validSec)",
            "wallSec": "\(wallSec)",
            AndroidSnapshotKeys.rawDb: String(format: "%.1f", model?.rawDb ?? 0),
            AndroidSnapshotKeys.antiNoiseDb: String(format: "%.1f", model?.antiDb ?? 0),
            AndroidSnapshotKeys.lowBandRumbleReduction: String(format: "%.2f", model?.lowBandRumbleReduction ?? 0),
            AndroidSnapshotKeys.vehicleSpeedKmh: String(format: "%.0f", model?.vehicleSpeedKmh ?? 0),
            AndroidSnapshotKeys.nvhFocus: model?.nvhFocus.rawValue ?? "?"
        ])
        appendLog(
            "step_done id=\(step.id) valid=\(validSec) wall=\(wallSec) " +
            "rawDb=\(String(format: "%.1f", model?.rawDb ?? 0)) " +
            "lowBandRumbleReduction=\(String(format: "%.2f", model?.lowBandRumbleReduction ?? 0)) " +
            "speed=\(String(format: "%.0f", model?.vehicleSpeedKmh ?? 0)) " +
            "focus=\(model?.nvhFocus.rawValue ?? "?")"
        )
        let next = stepIndex + 1
        if next >= CarRoadTuningScript.steps.count {
            finish()
            return
        }
        stepIndex = next
        validSec = 0
        wallSec = 0
        checked = []
        SessionLogger.shared.guidedTestStepId = CarRoadTuningScript.steps[next].id
        applyPresets(for: CarRoadTuningScript.steps[next])
        statusLine = "進行中：\(CarRoadTuningScript.steps[next].title)"
    }

    private func finish() {
        timer?.invalidate()
        timer = nil
        active = false
        finished = true
        SessionLogger.shared.guidedTestActive = false
        SessionLogger.shared.event("test_script_complete", [
            "scriptId": CarRoadTuningScript.scriptId
        ])
        statusLine = "腳本完成 — 請匯出 Log"
        appendLog("script_complete")
        if model?.isRunning == true {
            engine?.stop()
        }
    }

    private func applyPresets(for step: GuidedTestStep) {
        guard let model else { return }
        if let t = step.debugPresets["tier"] {
            switch t {
            case "LIGHT": model.setTier(.light)
            case "STANDARD": model.setTier(.standard)
            case "PRO": model.setTier(.pro)
            default: break
            }
        } else if let s = step.suggestedTier {
            model.setTier(s)
        }
        appendLog("presets step=\(step.id) \(step.debugPresets)")
    }

    private func appendLog(_ line: String) {
        let ts = ISO8601DateFormatter().string(from: Date())
        logLines.append("[\(ts)] \(line)")
    }

    func exportText() -> String {
        var header = """
        === GUIDED SCRIPT ===
        script=\(CarRoadTuningScript.scriptId)
        name=\(CarRoadTuningScript.scriptName)
        stepIndex=\(stepIndex) finished=\(finished)

        === HOW TO VERIFY (road test) ===
        PASS signals:
          - outputPathActive=true while ANC on (anti not silent)
          - driving steps: vehicleSpeedValid + valid sec accumulated
          - lowBandRumbleReduction trend on rough road (compare steps #4b vs #7)
          - nvhFocus often ROAD_RUMBLE/TIRE_NOISE when moving (not always WIND_SHEAR chase)
          - lmsLowUpdates increases over time (learning alive)
          - no subjective hiss/telegraph; idle quieter than drive rumble path
        FAIL signals:
          - antiDb always ~-90 / outputPathActive=false
          - lmsLowUpdates stuck at 0
          - GPS never valid on open road (placement/permission)
          - subjective louder hiss after start

        === SCRIPT EVENT LINES ===
        """
        header += "\n" + logLines.joined(separator: "\n")
        // 合併全程 running_snapshot
        header += "\n\n" + SessionLogger.shared.exportText(headerNote: "merged session snapshots during guided test")
        return header
    }
}
