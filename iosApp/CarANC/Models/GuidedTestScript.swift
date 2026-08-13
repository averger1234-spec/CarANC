import Foundation

/// 對齊 Android `CarRoadTuningScript`（`shared/.../AncTestScript.kt`）— **同源步驟 ID／秒數／門檻**
/// 自動進階：有效行駛秒達標（或 maxWall）→ 下一步，對齊 `GuidedTestController.autoAdvance=true`
struct GuidedTestStep: Identifiable {
    let id: String
    let title: String
    let instructions: [String]
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
    /// 與 Android `CarRoadTuningScript.SCRIPT_NAME` 一致
    static let scriptName = "三目標壓制·路噪/輪噪/風切（有效行駛秒）"

    /// 與 Android 1.2.3 `CarRoadTuningScript.steps` 對齊（含 notch 收集說明）
    static let steps: [GuidedTestStep] = [
        GuidedTestStep(
            id: "tuning_prep",
            title: "準備：三目標壓制實驗",
            instructions: [
                "★ 目標：路噪(low)／輪噪(mid)／風切(high) 都要壓——各自武器，不是只防護",
                "iOS：本機或 CarPlay；Android 路測請用有線 AA（projection_submix）",
                "★ placement=floor/seat；音樂關；userAncGain≈1",
                "啟動 ANC → PRO；確認 antiNoiseDb 行駛非長期靜音、outputPathActive",
                "之後三步：有效秒自動進階；每步主觀 0–10（關→開）"
            ],
            durationSec: 25,
            suggestedTier: .pro,
            requiresAncRunning: false,
            checklist: [
                "placement=floor/seat",
                "音樂關",
                "知悉三目標武器",
                "ANC可啟動"
            ],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 90,
            debugPresets: [
                "forceNormalMode": "true",
                "musicLowAncEnabled": "true",
                "userAncGain": "1.0",
                "tier": "PRO"
            ]
        ),
        GuidedTestStep(
            id: "target_road",
            title: "① 路噪壓制 ROAD（low 武器）",
            instructions: [
                "粗糙路 45–60 km/h；55 秒有效秒（≥45）— 達標自動下一步",
                "期望 ROAD_RUMBLE、road_5kmh、speedNvhBin≈45/50/55",
                "★ 收集：lowBandRumbleReduction、speedNvhLowGain、speedNvhTotalAnti、antiNoiseDb",
                "★ 主觀：低頻悶 0–10（先關 ANC → 開 ANC）",
                "PASS：TotalAnti≥0.95、LowGain 高、lowBand 有正、悶感↓"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "nvhFocus多ROAD",
                "tableId=road_5kmh",
                "TotalAnti≥0.9",
                "lowBand有記錄",
                "主觀悶感0-10",
                "tier=PRO"
            ],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.0",
                "freezeThreshold": "10",
                "freezeConsec": "2",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "tier": "PRO"
            ]
        ),
        GuidedTestStep(
            id: "target_tire",
            title: "② 輪噪壓制 TIRE（mid 武器）",
            instructions: [
                "55–75 km/h；55 秒有效秒（≥50）— 自動進階",
                "期望 TIRE_NOISE 或 mid 抬；tableId=tire_5kmh 或 road 混 mid",
                "★ 收集：tireNotchEnergy、tireNotchF0Hz、notchMixAnti、speedNvhMidGain",
                "★ 主觀：輪胎嗡 0–10",
                "PASS：tireNotchEnergy>0 + 嗡感↓；FAIL：notch 全 0"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "nvhFocus=TIRE或ROAD",
                "tireNotchEnergy>0",
                "tireNotchF0有值",
                "主觀嗡感0-10",
                "tier=PRO"
            ],
            minSpeedKmh: 50,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.05",
                "freezeThreshold": "9",
                "freezeConsec": "2",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "tier": "PRO"
            ]
        ),
        GuidedTestStep(
            id: "target_wind",
            title: "③ 風切壓制 WIND（high·主動）",
            instructions: [
                "70+ km/h；50 秒有效秒（≥65）— 自動進階",
                "★ 主動壓制：WIND_SHEAR、wind_5kmh、TotalAnti≥0.85",
                "★ 必收：windNotchEnergy、windNotchActiveCount、notchMixAnti（6 頻）",
                "★ 主觀：風感 0–10；更嘶也寫",
                "PASS：windNotchEnergy>0 + ActiveCount>0 + 風↓"
            ],
            durationSec: 50,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "nvhFocus=WIND(或N/A)",
                "TotalAnti≥0.85",
                "windNotchEnergy>0",
                "主觀風感0-10",
                "有無更嘶",
                "tier=PRO"
            ],
            minSpeedKmh: 65,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.1",
                "freezeThreshold": "9",
                "freezeConsec": "2",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "tier": "PRO"
            ]
        ),
        GuidedTestStep(
            id: "tuning_finish",
            title: "結束：三目標對照 + 匯出",
            instructions: [
                "停止 ANC → 匯出 Log；scenario 寫 placement + 三段主觀",
                "★ 分析切 guidedTestStepId：target_road / target_tire / target_wind",
                "路：lowBand+悶↓；輪：tireNotch*+嗡↓；風：windNotch*+TotalAnti≥0.85",
                "FAIL：TotalAnti 該高不高 / 全段 IDLE / anti 靜音 / 只更嘶"
            ],
            durationSec: 12,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: [
                "已存Log",
                "三段主觀分已寫",
                "含speedNvh*與nvhFocus",
                "含tire/wind notch 欄位",
                "placement已註"
            ],
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
    /// 對齊 Android GuidedTestController.autoAdvance（預設 true）
    @Published var autoAdvance = true
    @Published var pauseReason = ""
    @Published var collectingNow = false

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
        pauseReason = ""
        collectingNow = false
        SessionLogger.shared.guidedTestActive = true
        SessionLogger.shared.guidedTestStepId = CarRoadTuningScript.steps[0].id
        SessionLogger.shared.event("test_script_start", [
            "scriptId": CarRoadTuningScript.scriptId,
            "scriptName": CarRoadTuningScript.scriptName,
            "source": "android_CarRoadTuningScript",
            "autoAdvance": "\(autoAdvance)",
            "totalSteps": "\(CarRoadTuningScript.steps.count)",
            "stepIds": CarRoadTuningScript.steps.map(\.id).joined(separator: ",")
        ])
        appendLog("script_start id=\(CarRoadTuningScript.scriptId) autoAdvance=\(autoAdvance) source=android_CarRoadTuningScript")
        applyPresets(for: CarRoadTuningScript.steps[0])
        statusLine = "自動進階 · \(CarRoadTuningScript.steps[0].title)"
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
        SessionLogger.shared.event("test_script_abort", ["stepIndex": "\(stepIndex)"])
        statusLine = "已中止"
        appendLog("script_abort step=\(stepIndex)")
    }

    /// 手動完成（對齊 Android 手動 complete；auto 模式仍可用）
    func completeManually() {
        advance(auto: false, note: "manual")
    }

    func toggleCheck(_ item: String) {
        if checked.contains(item) { checked.remove(item) } else { checked.insert(item) }
    }

    private func tick() {
        guard active, !finished, let step = currentStep, let model else { return }
        wallSec += 1

        if step.wallClockOnly {
            collectingNow = true
            pauseReason = "準備/結束（壁鐘）"
            statusLine = "\(step.title) · 壁鐘 \(wallSec)/\(step.durationSec)s · 自動進階"
            if autoAdvance && (wallSec >= step.durationSec || wallSec >= step.maxWallSec) {
                let note = wallSec >= step.durationSec ? "auto_wall" : "auto_max_wall_timeout"
                advance(auto: true, note: note)
            }
            return
        }

        let speedOk = model.vehicleSpeedValid && model.vehicleSpeedKmh >= step.minSpeedKmh
        let ancOk = !step.requiresAncRunning || model.isRunning
        if !model.vehicleSpeedValid {
            collectingNow = false
            pauseReason = "車速無效（GPS/hold/IMU）· 不計有效秒"
        } else if model.vehicleSpeedKmh < step.minSpeedKmh {
            collectingNow = false
            pauseReason = String(format: "車速 %.0f < %.0f · 不計有效秒", model.vehicleSpeedKmh, step.minSpeedKmh)
        } else if !ancOk {
            collectingNow = false
            pauseReason = "ANC 未運行 · 不計有效秒"
        } else {
            collectingNow = true
            pauseReason = ""
            validSec += 1
        }

        if collectingNow {
            statusLine = String(
                format: "%@ · 有效 %d/%ds（%.0f km/h）· 自動",
                step.title, validSec, step.durationSec, model.vehicleSpeedKmh
            )
        } else {
            statusLine = String(
                format: "%@ · %@ · 有效 %d/%d · 壁鐘 %d",
                step.title, pauseReason, validSec, step.durationSec, wallSec
            )
        }

        if autoAdvance {
            if validSec >= step.durationSec {
                advance(auto: true, note: "auto_valid_drive")
            } else if wallSec >= step.maxWallSec {
                advance(auto: true, note: "auto_max_wall_timeout")
            }
        }
    }

    private func advance(auto: Bool, note: String) {
        guard let step = currentStep else { return }
        SessionLogger.shared.event("test_step_complete", [
            AndroidSnapshotKeys.guidedTestStepId: step.id,
            "validSec": "\(validSec)",
            "wallSec": "\(wallSec)",
            "targetValidSec": "\(step.durationSec)",
            "autoAdvanced": "\(auto)",
            "userNote": note,
            "advanceMode": step.wallClockOnly ? "wall" : "valid_drive_sec",
            AndroidSnapshotKeys.rawDb: String(format: "%.1f", model?.rawDb ?? 0),
            AndroidSnapshotKeys.antiNoiseDb: String(format: "%.1f", model?.antiDb ?? 0),
            AndroidSnapshotKeys.lowBandRumbleReduction: String(format: "%.2f", model?.lowBandRumbleReduction ?? 0),
            AndroidSnapshotKeys.vehicleSpeedKmh: String(format: "%.0f", model?.vehicleSpeedKmh ?? 0),
            AndroidSnapshotKeys.nvhFocus: model?.nvhFocus.rawValue ?? "?",
            "speedSource": model?.speedSource ?? "?",
            "tireNotchEnergy": String(format: "%.4f", model?.tireNotchEnergy ?? 0),
            "windNotchEnergy": String(format: "%.4f", model?.windNotchEnergy ?? 0)
        ])
        appendLog(
            "step_done id=\(step.id) valid=\(validSec) wall=\(wallSec) auto=\(auto) note=\(note) " +
            "rawDb=\(String(format: "%.1f", model?.rawDb ?? 0)) " +
            "lowBandRumbleReduction=\(String(format: "%.2f", model?.lowBandRumbleReduction ?? 0)) " +
            "speed=\(String(format: "%.0f", model?.vehicleSpeedKmh ?? 0)) " +
            "focus=\(model?.nvhFocus.rawValue ?? "?") " +
            "tireNotch=\(String(format: "%.3f", model?.tireNotchEnergy ?? 0)) " +
            "windNotch=\(String(format: "%.3f", model?.windNotchEnergy ?? 0))"
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
        statusLine = "自動進階 · \(CarRoadTuningScript.steps[next].title)"
    }

    private func finish() {
        timer?.invalidate()
        timer = nil
        active = false
        finished = true
        SessionLogger.shared.guidedTestActive = false
        SessionLogger.shared.event("test_script_complete", [
            "scriptId": CarRoadTuningScript.scriptId,
            "advanceMode": "valid_drive_sec",
            "autoAdvance": "\(autoAdvance)"
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
            engine?.applyTier(model.tier)
        } else if let s = step.suggestedTier {
            model.setTier(s)
            engine?.applyTier(s)
        }
        // Android 會 bake mu/freeze 等；iOS 記錄 presets 並套用 tier（完整 debug 覆寫待 KMP 暴露）
        appendLog("presets step=\(step.id) \(step.debugPresets) appliedTier=\(model.tier.rawValue)")
        SessionLogger.shared.event("guided_presets", [
            "stepId": step.id,
            "tier": model.tier.rawValue,
            "presets": step.debugPresets.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ",")
        ])
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
        align=android_CarRoadTuningScript_v1.2.3
        autoAdvance=\(autoAdvance)
        stepIndex=\(stepIndex) finished=\(finished)

        === HOW TO VERIFY (1.2.3 三目標 + notch) ===
        PASS:
          - target_road: lowBand↑ / TotalAnti 高 / 主觀悶↓
          - target_tire: tireNotchEnergy>0 / MidGain / 主觀嗡↓
          - target_wind: windNotchEnergy>0 / ActiveCount>0 / TotalAnti≥0.85 / 主觀風↓
          - speedSource=gps|gps_hold|imu_proxy；valid 秒有累積
          - 步驟達標會 auto_valid_drive 自動進階（對齊 Android GuidedTestController）
        FAIL:
          - notch 全 0、anti 長期 -140、valid 全 0、只更嘶

        === SCRIPT EVENT LINES ===
        """
        header += "\n" + logLines.joined(separator: "\n")
        header += "\n\n" + SessionLogger.shared.exportText(headerNote: "merged session snapshots during guided test")
        return header
    }
}
