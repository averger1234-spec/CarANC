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
    /// Android 1.2.6：spectrum_kpi + forceNvhFocus
    static let scriptName = "三目標壓制·1.2.6 spectrum_kpi + 強制 focus"

    static let steps: [GuidedTestStep] = [
        GuidedTestStep(
            id: "tuning_prep",
            title: "準備：1.2.6 spectrum_kpi + 三目標",
            instructions: [
                "★ 版號確認 v1.2.6；路悶/輪/風 + 禁假 anti 沙沙",
                "iOS 本機或 CarPlay；Android 有線 AA 較佳",
                "★ placement=floor/seat；音樂關",
                "★ 頻譜：App 每 2s 寫 spectrum_kpi（不必外接 m4a）",
                "啟動 ANC → PRO；outputPathActive",
                "每步主觀 0–10；沙沙=FAIL"
            ],
            durationSec: 25,
            suggestedTier: .pro,
            requiresAncRunning: false,
            checklist: [
                "版號≥1.2.6",
                "placement=floor/seat",
                "音樂關",
                "知spectrum_kpi",
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
            title: "① 路噪/悶 ROAD（force + boom）",
            instructions: [
                "45–60 km/h；60 秒有效秒（≥45）— 自動進階",
                "forceNvhFocus=ROAD_RUMBLE",
                "★ 必收：effectiveLowMu、roadBoomWeightEnergy、roadNotchEnergy",
                "★ spectrum_kpi：deltaBoomDb（開ANC 應有正趨勢）",
                "主觀悶 0–10；沙沙為主=FAIL"
            ],
            durationSec: 60,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "forced=ROAD",
                "effectiveLowMu有值",
                "roadBoomWeight有上升",
                "spectrum_kpi有",
                "主觀悶0-10",
                "有無沙沙",
                "tier=PRO"
            ],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.0",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "tier": "PRO",
                "forceNvhFocus": "ROAD_RUMBLE"
            ]
        ),
        GuidedTestStep(
            id: "target_tire",
            title: "② 輪噪 TIRE（force + 3 notch）",
            instructions: [
                "55–75 km/h；55 秒有效秒（≥50）",
                "forceNvhFocus=TIRE_NOISE",
                "★ tireNotchEnergy、deltaTireDb、主觀嗡 0–10"
            ],
            durationSec: 55,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "forced=TIRE",
                "tireNotchEnergy>0",
                "spectrum_kpi有",
                "主觀嗡0-10",
                "tier=PRO"
            ],
            minSpeedKmh: 50,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.05",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "tier": "PRO",
                "forceNvhFocus": "TIRE_NOISE"
            ]
        ),
        GuidedTestStep(
            id: "target_wind",
            title: "③ 風切 WIND（force + multi-notch）",
            instructions: [
                "70+ km/h；50 秒有效秒（≥65）",
                "forceNvhFocus=WIND_SHEAR；高延遲仍跑 notch（權重 gate）",
                "★ windNotch*、deltaWindDb；更沙必寫"
            ],
            durationSec: 50,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "forced=WIND",
                "windNotch或spectrum有",
                "主觀風感0-10",
                "有無更嘶沙",
                "tier=PRO"
            ],
            minSpeedKmh: 65,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.1",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "tier": "PRO",
                "forceNvhFocus": "WIND_SHEAR"
            ]
        ),
        GuidedTestStep(
            id: "tuning_finish",
            title: "結束：spectrum_kpi + 三段匯出",
            instructions: [
                "停 ANC → 匯出 Log",
                "分析：guidedTestStepId 切三段 + phase=spectrum_kpi",
                "路 deltaBoomDb；輪 tireNotch/deltaTire；風 windNotch/deltaWind",
                "外部 m4a 可選"
            ],
            durationSec: 15,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: [
                "已存Log",
                "三段主觀已寫",
                "含spectrum_kpi",
                "含notch欄位",
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
    /// 腳本完成後自動分享用（View 觀察後彈出系統分享）
    @Published var pendingAutoExportText: String?

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
        engine?.setForcedNvhFocus(nil)
        model?.forcedNvhFocus = "auto"
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
        engine?.setForcedNvhFocus(nil)
        model?.forcedNvhFocus = "auto"
        SessionLogger.shared.event("test_script_complete", [
            "scriptId": CarRoadTuningScript.scriptId,
            "advanceMode": "valid_drive_sec",
            "autoAdvance": "\(autoAdvance)",
            "autoExport": "true"
        ])
        appendLog("script_complete")
        // 先停 ANC（寫入 session_end），再組完整 export 並自動分享
        if model?.isRunning == true {
            engine?.stop()
        }
        let text = exportText()
        statusLine = "腳本完成 — 自動開啟分享"
        appendLog("auto_export_share")
        pendingAutoExportText = text
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
        // 1.2.6：腳本強制 NVH（對齊 Android GuidedNvhOverride）
        let force = step.debugPresets["forceNvhFocus"]
        engine?.setForcedNvhFocus(force)
        model.forcedNvhFocus = force ?? "auto"
        appendLog("presets step=\(step.id) \(step.debugPresets) appliedTier=\(model.tier.rawValue) forceNvh=\(force ?? "auto")")
        SessionLogger.shared.event("guided_presets", [
            "stepId": step.id,
            "tier": model.tier.rawValue,
            "forceNvhFocus": force ?? "auto",
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
        align=android_CarRoadTuningScript_v1.2.6
        autoAdvance=\(autoAdvance)
        stepIndex=\(stepIndex) finished=\(finished)

        === HOW TO VERIFY (1.2.6 spectrum_kpi + 三目標) ===
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
