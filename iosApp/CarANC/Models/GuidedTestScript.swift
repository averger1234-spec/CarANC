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
    /// 與 Android `CarRoadTuningScript.SCRIPT_NAME` 一致（1.2.5）
    static let scriptName = "三目標壓制·路/輪/風 + 1.2.5悶鎖相"

    /// 與 Android 1.2.5 `CarRoadTuningScript.steps` 對齊
    static let steps: [GuidedTestStep] = [
        GuidedTestStep(
            id: "tuning_prep",
            title: "準備：1.2.5 悶鎖相 + 三目標",
            instructions: [
                "★ 版號確認 v1.2.5；目標：路悶(low+boom)／輪(mid)／風 — 禁假 anti 沙沙",
                "iOS：本機或 CarPlay；Android 請用有線 AA",
                "★ placement=floor/seat；音樂關；userAncGain≈1",
                "啟動 ANC → PRO；outputPathActive；anti 非長期靜音",
                "★ 備外部錄音：target_road 關ANC 20s + 開ANC 40s",
                "每步主觀 0–10；沙沙=FAIL"
            ],
            durationSec: 25,
            suggestedTier: .pro,
            requiresAncRunning: false,
            checklist: [
                "版號v1.2.5",
                "placement=floor/seat",
                "音樂關",
                "備外部錄音",
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
            title: "① 路噪/悶 ROAD（low + 鎖相 boom）",
            instructions: [
                "粗糙路 45–60 km/h；60 秒有效秒（≥45）— 自動進階",
                "期望 ROAD_RUMBLE、road_5kmh",
                "★ 1.2.5 必收：effectiveLowMu、roadBoomWeightEnergy（上升=鎖相）、roadNotchEnergy、notchMixAnti",
                "★ 仍收：lowBandRumbleReduction、speedNvhLowGain、antiNoiseDb",
                "★ 外部：關ANC 20s → 開ANC 40s",
                "PASS：WeightEnergy↑ + 悶↓；沙沙為主=FAIL"
            ],
            durationSec: 60,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "nvhFocus多ROAD",
                "effectiveLowMu有值",
                "roadBoomWeightEnergy有上升",
                "roadNotch/notchMix有記錄",
                "外部錄音關開各一段",
                "主觀悶0-10",
                "有無沙沙",
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
                "55–75 km/h；55 秒有效秒（≥50）",
                "★ 收集：tireNotchEnergy、tireNotchF0Hz、notchMixAnti、speedNvhMidGain",
                "★ 主觀嗡 0–10",
                "PASS：tireNotchEnergy>0 + 嗡↓"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "nvhFocus=TIRE或ROAD",
                "tireNotchEnergy>0",
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
            title: "③ 風切 WIND（高延遲：主觀優先）",
            instructions: [
                "70+ km/h；50 秒有效秒（≥65）",
                "★ 1.2.5：高延遲刻意關 HF wind notch（防沙）；windNotch 可為 0",
                "★ 仍收：TotalAnti、antiNoiseDb；主觀風 0–10；更沙必寫",
                "PASS（高延遲）：風↓或不更沙"
            ],
            durationSec: 50,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "nvhFocus有記錄",
                "latencyMs有記錄",
                "主觀風感0-10",
                "有無更嘶沙",
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
            title: "結束：1.2.5 對照 + 匯出",
            instructions: [
                "停 ANC → 匯出 Log；三段主觀 + 有無沙沙",
                "★ 路噪 PASS：effectiveLowMu 高延遲段明顯 + roadBoomWeightEnergy↑ + 悶↓",
                "輪：tireNotch* 或嗡↓；風：高延遲 windNotch=0 可接受，FAIL=更沙",
                "一併交外部 road 關/開錄音（若有）"
            ],
            durationSec: 15,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: [
                "已存Log",
                "三段主觀分已寫",
                "含roadBoomWeightEnergy",
                "含effectiveLowMu",
                "placement已註"
            ],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 50,
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
            "windNotchEnergy": String(format: "%.4f", model?.windNotchEnergy ?? 0),
            "roadNotchEnergy": String(format: "%.4f", model?.roadNotchEnergy ?? 0),
            "roadBoomWeightEnergy": String(format: "%.4f", model?.roadBoomWeightEnergy ?? 0),
            "effectiveLowMu": String(format: "%.4f", model?.effectiveLowMu ?? 0)
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
        align=android_CarRoadTuningScript_v1.2.5
        autoAdvance=\(autoAdvance)
        stepIndex=\(stepIndex) finished=\(finished)

        === HOW TO VERIFY (1.2.5 悶鎖相 + 三目標) ===
        PASS:
          - target_road: effectiveLowMu 高延遲段明顯、roadBoomWeightEnergy↑、悶↓（非沙沙）
          - target_tire: tireNotchEnergy / 嗡↓
          - target_wind: 高延遲 windNotch=0 可接受；不更沙
          - 步驟 auto_valid_drive 自動進階
        FAIL:
          - 只有 anti 輸出但悶不變 / 沙沙為主 / boom weight 不升

        === SCRIPT EVENT LINES ===
        """
        header += "\n" + logLines.joined(separator: "\n")
        header += "\n\n" + SessionLogger.shared.exportText(headerNote: "merged session snapshots during guided test")
        return header
    }
}
