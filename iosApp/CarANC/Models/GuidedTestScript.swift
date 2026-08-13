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
    /// 對齊 Android 三目標主動壓制（路 low / 輪 mid / 風 high）
    static let scriptName = "三目標壓制·路噪/輪噪/風切（有效行駛秒）"

    static let steps: [GuidedTestStep] = [
        GuidedTestStep(
            id: "tuning_prep",
            title: "準備：三目標壓制",
            instructions: [
                "路噪(low)／輪噪(mid)／風切(high) 都要壓；floor/seat 放置；音樂關",
                "iOS：本機或 CarPlay；Android AA 請用 Android 版測 projection_submix",
                "每步主觀 0–10（該噪音煩的程度；開 ANC 後是否變輕）"
            ],
            durationSec: 25,
            suggestedTier: .pro,
            requiresAncRunning: false,
            checklist: ["placement=floor/seat", "音樂關", "ANC可啟動"],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 90,
            debugPresets: ["musicLowAncEnabled": "true", "tier": "PRO"]
        ),
        GuidedTestStep(
            id: "target_road",
            title: "① 路噪 ROAD（low）",
            instructions: [
                "粗糙路 45–60 km/h；55 秒有效秒（≥45）",
                "期望 ROAD_RUMBLE / road_5kmh；收集 lowBandRumbleReduction、speedNvhLowGain、TotalAnti",
                "主觀：低頻悶 0–10"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["nvhFocus多ROAD", "TotalAnti高", "主觀悶0-10"],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: ["lmsMuMultiplier": "2.0", "musicLowAncEnabled": "true", "tier": "PRO"]
        ),
        GuidedTestStep(
            id: "target_tire",
            title: "② 輪噪 TIRE（mid）",
            instructions: [
                "55–75 km/h；55 秒有效秒（≥50）",
                "期望 TIRE 或 mid 抬；收集 MidGain、主觀嗡 0–10"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["Mid有出力", "主觀嗡0-10"],
            minSpeedKmh: 50,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: ["lmsMuMultiplier": "2.05", "musicLowAncEnabled": "true", "tier": "PRO"]
        ),
        GuidedTestStep(
            id: "target_wind",
            title: "③ 風切 WIND（high·主動）",
            instructions: [
                "70+ km/h；50 秒有效秒（≥65）",
                "★ 主動壓制：TotalAnti 應≥0.85（不是舊版壓低）",
                "主觀風感 0–10；若更嘶也要記"
            ],
            durationSec: 50,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["WIND或N/A", "TotalAnti≥0.85", "主觀風0-10"],
            minSpeedKmh: 65,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: ["lmsMuMultiplier": "2.1", "musicLowAncEnabled": "true", "tier": "PRO"]
        ),
        GuidedTestStep(
            id: "tuning_finish",
            title: "結束匯出",
            instructions: [
                "停 ANC → 匯出 log；scenario 寫三段主觀分",
                "PASS：三段都有壓制證據 + 主觀變輕；FAIL：TotalAnti該高不高或只更嘶"
            ],
            durationSec: 12,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: ["已匯出Log", "三段主觀已寫"],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 40,
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

        // valid：DSP 可用車速即可累加（含 gps_hold / imu_proxy）；嚴格路測看 log speedSource
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
