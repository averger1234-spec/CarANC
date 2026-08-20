import Foundation
import CarANCShared

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
    /// 對齊 Android 1.2.15：boomOut 修復 + 中頻 cabin winner + 更緊 LF
    static let scriptName = "三目標·1.2.15 boomOut+中頻winner"

    static let steps: [GuidedTestStep] = [
        GuidedTestStep(
            id: "tuning_prep",
            title: "準備：1.2.15 boomOut+中頻winner",
            instructions: [
                "★ 對齊 Android v1.2.15（boomOut≠0 / mid cabin winner / 70Hz×4）",
                "iOS 本機或 CarPlay；Android 有線 AA 完整診斷",
                "★ placement=floor/seat；音樂關；PRO；預設極性 −1",
                "★ 只收等長 ~20s 三件套 off/ppos/pneg（≥45 km/h）",
                "★ 停速場作廢；on 應 boomOut≫0、openBoom=true；180–350 不大增"
            ],
            durationSec: 20,
            suggestedTier: .pro,
            requiresAncRunning: false,
            checklist: [
                "對齊1.2.15",
                "tier=PRO",
                "placement=floor/seat",
                "音樂關",
                "ANC可啟動"
            ],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 60,
            debugPresets: [
                "forceNormalMode": "true",
                "musicLowAncEnabled": "true",
                "userAncGain": "1.0",
                "muteAnti": "false",
                "forceBoomPolarity": "0",
                "tier": "PRO",
                "diagToneHz": "0"
            ]
        ),
        GuidedTestStep(
            id: "diag_tone_50",
            title: "⓪ AA 低頻診斷 50Hz tone（30s）",
            instructions: [
                "停車或怠速；開 ANC",
                "應聽到低沉 50Hz；完全沒低音 → 路徑不通"
            ],
            durationSec: 30,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["ANC開", "聽到50Hz?", "tier=PRO"],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 45,
            debugPresets: [
                "tier": "PRO",
                "userAncGain": "1.0",
                "muteAnti": "false",
                "diagToneHz": "50",
                "forceNormalMode": "true"
            ]
        ),
        GuidedTestStep(
            id: "target_road_off",
            title: "①a 路悶 mute anti 艙錄 20s（baseline）",
            instructions: [
                "45–60 km/h；真 mute anti",
                "艙錄達速後 ~20s",
                "log：antiNoiseDb≤−90、boomPressureOut=0"
            ],
            durationSec: 20,
            suggestedTier: .pro,
            requiresAncRunning: false,
            checklist: ["anti已mute", "antiDb極低", "達速艙錄", "45+kmh"],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 120,
            debugPresets: [
                "tier": "PRO",
                "userAncGain": "0.0",
                "muteAnti": "true",
                "forceBoomPolarity": "0",
                "cabinRecord": "true",
                "cabinRecordOnValidSpeed": "true",
                "diagToneHz": "0",
                "forceNvhFocus": "ROAD_RUMBLE"
            ]
        ),
        GuidedTestStep(
            id: "target_road_ppos",
            title: "①b 路悶 極性+1 艙錄 20s",
            instructions: [
                "同路段 45–60；forceBoomPolarity=+1",
                "艙錄 ~20s；對照 off 的 40–80 與 180–350",
                "★ 1.2.15：openBoom=true 且 boomOut≫0；中頻參與 winner"
            ],
            durationSec: 20,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["pol=+1", "forced=ROAD", "艙錄~20s", "boomOut≫0?", "中頻無大增?"],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 120,
            debugPresets: [
                "lmsMuMultiplier": "2.0",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "muteAnti": "false",
                "forceBoomPolarity": "1",
                "tier": "PRO",
                "forceNvhFocus": "ROAD_RUMBLE",
                "cabinRecord": "true",
                "cabinRecordOnValidSpeed": "true",
                "diagToneHz": "0"
            ]
        ),
        GuidedTestStep(
            id: "target_road_pneg",
            title: "①c 路悶 極性−1 艙錄 20s",
            instructions: [
                "同路段；forceBoomPolarity=−1",
                "與 ppos/off 對照；較佳極性寫入 store",
                "禁止採信時長差>30% 的對",
                "PASS：40–80 on<off 且 180–350 增幅 <+1.5 dB"
            ],
            durationSec: 20,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: ["pol=-1", "forced=ROAD", "艙錄~20s", "主觀悶0-10", "中頻無大增?"],
            minSpeedKmh: 45,
            wallClockOnly: false,
            maxWallSec: 120,
            debugPresets: [
                "lmsMuMultiplier": "2.0",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "muteAnti": "false",
                "forceBoomPolarity": "-1",
                "tier": "PRO",
                "forceNvhFocus": "ROAD_RUMBLE",
                "cabinRecord": "true",
                "cabinRecordOnValidSpeed": "true",
                "diagToneHz": "0"
            ]
        ),
        GuidedTestStep(
            id: "target_tire",
            title: "② 輪噪 TIRE（mid + notch）",
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
                "tier=PRO",
                "tireNotchEnergy>0",
                "spectrum_kpi有",
                "主觀嗡0-10"
            ],
            minSpeedKmh: 50,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.05",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "muteAnti": "false",
                "forceBoomPolarity": "0",
                "tier": "PRO",
                "forceNvhFocus": "TIRE_NOISE"
            ]
        ),
        GuidedTestStep(
            id: "target_wind",
            title: "③ 風切 WIND",
            instructions: [
                "70+ km/h；50 秒有效秒（≥65）",
                "forceNvhFocus=WIND_SHEAR",
                "★ windNotch*、deltaWindDb；更噪必寫"
            ],
            durationSec: 50,
            suggestedTier: .pro,
            requiresAncRunning: true,
            checklist: [
                "forced=WIND",
                "tier=PRO",
                "windNotch或spectrum有",
                "主觀風感0-10",
                "有無更噪"
            ],
            minSpeedKmh: 65,
            wallClockOnly: false,
            maxWallSec: 720,
            debugPresets: [
                "lmsMuMultiplier": "2.1",
                "musicLowAncEnabled": "true",
                "forceNormalMode": "true",
                "userAncGain": "1.0",
                "muteAnti": "false",
                "forceBoomPolarity": "0",
                "tier": "PRO",
                "forceNvhFocus": "WIND_SHEAR"
            ]
        ),
        GuidedTestStep(
            id: "tuning_finish",
            title: "結束：1.2.15 對照匯出",
            instructions: [
                "停 ANC → 匯出 Log",
                "★ PASS：boomOut≫0；40–80 on<off；180–350 <+1.5dB",
                "★ winner 看 CabinMidAvg；mute≈−200；openBoom",
                "FAIL：boomOut≈0 / 中頻大增 / 停速或不等長"
            ],
            durationSec: 15,
            suggestedTier: nil,
            requiresAncRunning: false,
            checklist: [
                "已存Log",
                "mute時antiDb極低",
                "等長三件套",
                "非停速場",
                "boomOut有值",
                "40-80on小於off",
                "boom_polarity_winner",
                "tier=PRO"
            ],
            minSpeedKmh: 0,
            wallClockOnly: true,
            maxWallSec: 50,
            debugPresets: [
                "userAncGain": "1.0",
                "muteAnti": "false",
                "forceBoomPolarity": "0",
                "diagToneHz": "0"
            ]
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
    /// 腳本完成後自動分享用（View 觀察後彈出系統分享）— **檔案**（log + cabin wav），對齊 Android
    @Published var pendingAutoExportItems: [URL] = []
    /// 最後一次寫入的 guided export log 路徑（UI 提示）
    @Published var lastSavedLogPath: String = ""

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
        BoomPolarityAbTracker.shared.reset()
        GuidedCabinRecorder.shared.stop(reason: "script_start")
        GuidedCabinRecorder.shared.clearPending()
        engine?.setMuteAnti(false)
        engine?.setUserAncGain(1)
        engine?.setForceBoomPolarity(0)
        engine?.setDiagToneHz(0)
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
        // 開始即開 ANC（對齊全自動；mute 步靠 muteAnti 真靜音）
        Task { @MainActor in
            if let model, !model.isRunning, model.safetyConsentAccepted {
                try? await engine?.start(preferCarAudio: AppController.shared.routeMonitor.linkType.isCarPlay)
            }
        }
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
        engine?.setMuteAnti(false)
        engine?.setForceBoomPolarity(0)
        engine?.setDiagToneHz(0)
        GuidedCabinRecorder.shared.stop(reason: "script_abort")
        GuidedCabinRecorder.shared.clearPending()
        BoomPolarityAbTracker.shared.reset()
        model?.forcedNvhFocus = "auto"
        SessionLogger.shared.event("guided_test_abort", ["stepIndex": "\(stepIndex)"])
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
            // 1.2.10：達有效車速才開始艙錄（等長 A/B）
            if GuidedCabinRecorder.shared.hasPending {
                GuidedCabinRecorder.shared.startIfPending(
                    sampleRate: engine?.currentSampleRate ?? 48_000
                )
            }
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
            "boomPressureOut": String(format: "%.4f", model?.boomPressureOut ?? 0),
            "effectiveLowMu": String(format: "%.4f", model?.effectiveLowMu ?? 0)
        ])
        appendLog(
            "step_done id=\(step.id) valid=\(validSec) wall=\(wallSec) auto=\(auto) note=\(note) " +
            "rawDb=\(String(format: "%.1f", model?.rawDb ?? 0)) " +
            "lowBandRumbleReduction=\(String(format: "%.2f", model?.lowBandRumbleReduction ?? 0)) " +
            "speed=\(String(format: "%.0f", model?.vehicleSpeedKmh ?? 0)) " +
            "focus=\(model?.nvhFocus.rawValue ?? "?") " +
            "boomPressure=\(String(format: "%.3f", model?.boomPressureOut ?? 0)) " +
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
        GuidedCabinRecorder.shared.stop(reason: "script_end")
        GuidedCabinRecorder.shared.clearPending()
        engine?.setForcedNvhFocus(nil)
        engine?.setMuteAnti(false)
        engine?.setForceBoomPolarity(0)
        engine?.setDiagToneHz(0)
        model?.forcedNvhFocus = "auto"
        // 1.2.15：cabin score=low+0.8×mid；不足樣本則 −1
        let winner = BoomPolarityAbTracker.shared.winnerPolarity()
        let link = AppController.shared.routeMonitor.linkType
        let route = PlantPathStore.routeLabel(carPlay: link.isCarPlay, wireless: link.wirelessSuspected)
        let delay = model?.plantElectricalDelaySamples ?? 0
        PlantPathStore.persistPolarityWinner(
            winner: winner,
            profileId: "ios_default",
            routeLabel: route,
            electricalDelaySamples: delay
        )
        engine?.setForceBoomPolarity(0)
        SessionLogger.shared.event("boom_polarity_winner", [
            "winner": String(format: "%.0f", winner),
            "posAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.posAvg()?.floatValue ?? 0),
            "negAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.negAvg()?.floatValue ?? 0),
            "posCabinAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.posCabinAvg()?.floatValue ?? 0),
            "negCabinAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.negCabinAvg()?.floatValue ?? 0),
            "posCabinLowAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.posCabinLowAvg()?.floatValue ?? 0),
            "negCabinLowAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.negCabinLowAvg()?.floatValue ?? 0),
            "posCabinMidAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.posCabinMidAvg()?.floatValue ?? 0),
            "negCabinMidAvg": String(format: "%.2f", BoomPolarityAbTracker.shared.negCabinMidAvg()?.floatValue ?? 0),
            "posN": "\(BoomPolarityAbTracker.shared.posCount())",
            "negN": "\(BoomPolarityAbTracker.shared.negCount())",
            "discardedLowSpeed": "\(BoomPolarityAbTracker.shared.discardedLowSpeedCount())",
            "fairAb": "\(BoomPolarityAbTracker.shared.isFairAbComplete())",
            "profileId": "ios_default",
            "routeLabel": route,
            "note": "1.2.15_cabin_low_plus_mid_winner"
        ])
        appendLog("boom_polarity_winner=\(winner) fair=\(BoomPolarityAbTracker.shared.isFairAbComplete()) midPos=\(BoomPolarityAbTracker.shared.posCabinMidAvg()?.floatValue ?? 0) midNeg=\(BoomPolarityAbTracker.shared.negCabinMidAvg()?.floatValue ?? 0)")
        BoomPolarityAbTracker.shared.reset()
        SessionLogger.shared.event("test_script_complete", [
            "scriptId": CarRoadTuningScript.scriptId,
            "advanceMode": "valid_drive_sec",
            "autoAdvance": "\(autoAdvance)",
            "autoExport": "true"
        ])
        appendLog("script_complete")
        if model?.isRunning == true {
            engine?.stop()
        }
        // 對齊 Android：先存檔再分享檔案（不是只丟文字）
        let text = exportText()
        var urls: [URL] = []
        if let logURL = SessionLogger.shared.saveExportFile(text: text, prefix: "anc_guided_export") {
            urls.append(logURL)
            lastSavedLogPath = logURL.path
            appendLog("log_saved path=\(logURL.path)")
        }
        if let sessionLog = SessionLogger.shared.currentLogFileURL,
           FileManager.default.fileExists(atPath: sessionLog.path),
           !urls.contains(sessionLog) {
            urls.append(sessionLog)
        }
        let cabins = SessionLogger.recentCabinWavURLs(limit: 6)
        urls.append(contentsOf: cabins)
        for c in cabins {
            appendLog("cabin_attach \(c.lastPathComponent)")
        }
        SessionLogger.shared.event("guided_export_files", [
            "logCount": "\(urls.filter { $0.pathExtension == "log" }.count)",
            "cabinCount": "\(cabins.count)",
            "paths": urls.map(\.lastPathComponent).joined(separator: ",")
        ])
        statusLine = cabins.isEmpty
            ? "腳本完成 — 已存 Log 檔，開啟分享"
            : "腳本完成 — 已存 Log + \(cabins.count) 段艙錄，開啟分享"
        appendLog("auto_export_share files=\(urls.count)")
        pendingAutoExportItems = urls
    }

    private func applyPresets(for step: GuidedTestStep) {
        guard let model else { return }
        // 1.2.7+ 腳本全程 PRO（除非步驟覆寫）
        model.setTier(.pro)
        engine?.applyTier(.pro)
        if let t = step.debugPresets["tier"] {
            switch t {
            case "LIGHT": model.setTier(.light); engine?.applyTier(.light)
            case "STANDARD": model.setTier(.standard); engine?.applyTier(.standard)
            case "PRO": model.setTier(.pro); engine?.applyTier(.pro)
            default: break
            }
        } else if let s = step.suggestedTier {
            model.setTier(s)
            engine?.applyTier(s)
        }
        let force = step.debugPresets["forceNvhFocus"]
        engine?.setForcedNvhFocus(force)
        model.forcedNvhFocus = force ?? "auto"

        let tone = Float(step.debugPresets["diagToneHz"] ?? "0") ?? 0
        let mute = (step.debugPresets["muteAnti"] ?? "false").lowercased() == "true"
        let gain = Float(step.debugPresets["userAncGain"] ?? (mute ? "0" : "1")) ?? 1
        let pol = Float(step.debugPresets["forceBoomPolarity"] ?? "0") ?? 0

        engine?.setMuteAnti(mute)
        engine?.setUserAncGain(mute ? 0 : gain)
        engine?.setForceBoomPolarity(pol)
        engine?.setDiagToneHz(mute ? 0 : tone)

        // 艙錄：換步先停；本步要錄則 pending（達速才開始）
        let cabinOn = (step.debugPresets["cabinRecord"] ?? "false").lowercased() == "true"
        if !cabinOn {
            GuidedCabinRecorder.shared.stop(reason: "step_change")
            GuidedCabinRecorder.shared.clearPending()
        } else {
            GuidedCabinRecorder.shared.stop(reason: "step_change")
            let waitValid = (step.debugPresets["cabinRecordOnValidSpeed"] ?? "true").lowercased() != "false"
            GuidedCabinRecorder.shared.setPending(stepId: step.id, waitValidSpeed: waitValid)
            if !waitValid {
                GuidedCabinRecorder.shared.startIfPending(sampleRate: engine?.currentSampleRate ?? 48_000)
            }
        }

        // mute 步仍保持 ANC running（真靜音靠 muteAnti）；非 mute 且需 ANC 則確保開著
        if step.requiresAncRunning || mute {
            Task { @MainActor in
                if !model.isRunning, model.safetyConsentAccepted {
                    try? await engine?.start(preferCarAudio: AppController.shared.routeMonitor.linkType.isCarPlay)
                    SessionLogger.shared.event("guided_anc_start", ["stepId": step.id])
                }
            }
        }

        appendLog(
            "presets step=\(step.id) tier=\(model.tier.rawValue) forceNvh=\(force ?? "auto") " +
            "mute=\(mute) gain=\(gain) pol=\(pol) tone=\(tone) cabin=\(cabinOn)"
        )
        SessionLogger.shared.event("guided_presets", [
            "stepId": step.id,
            "tier": model.tier.rawValue,
            "forceNvhFocus": force ?? "auto",
            "muteAnti": "\(mute)",
            "userAncGain": "\(gain)",
            "forceBoomPolarity": "\(pol)",
            "diagToneHz": "\(tone)",
            "cabinRecord": "\(cabinOn)",
            "presets": step.debugPresets.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ",")
        ])
        SessionLogger.shared.event("debug_presets_apply", step.debugPresets.merging([
            "stepId": step.id
        ]) { _, new in new })
    }

    private func appendLog(_ line: String) {
        let ts = ISO8601DateFormatter().string(from: Date())
        logLines.append("[\(ts)] \(line)")
    }

    /// 手動／再次匯出：存檔後回傳要分享的檔案列表
    func buildShareFileItems() -> [Any] {
        let text = exportText()
        var urls: [URL] = []
        if let logURL = SessionLogger.shared.saveExportFile(text: text, prefix: "anc_guided_export") {
            urls.append(logURL)
            lastSavedLogPath = logURL.path
        }
        if let sessionLog = SessionLogger.shared.currentLogFileURL ?? SessionLogger.latestSessionLogURL(),
           FileManager.default.fileExists(atPath: sessionLog.path),
           !urls.contains(where: { $0.path == sessionLog.path }) {
            urls.append(sessionLog)
        }
        urls.append(contentsOf: SessionLogger.recentCabinWavURLs(limit: 6))
        return urls.isEmpty ? [text] : urls.map { $0 as Any }
    }

    func exportText() -> String {
        var header = """
        === GUIDED SCRIPT ===
        script=\(CarRoadTuningScript.scriptId)
        name=\(CarRoadTuningScript.scriptName)
        align=android_CarRoadTuningScript_v1.2.15
        autoAdvance=\(autoAdvance)
        stepIndex=\(stepIndex) finished=\(finished)
        logDir=\(SessionLogger.logsDirectory.path)

        === HOW TO VERIFY (1.2.15 boomOut + 中頻winner) ===
        PASS:
          - openBoom 時 boomOut med ≫ 0.01（不可再凍在 0）
          - 等長 ~20s ≥45 km/h；40–80 on<off；180–350 <+1.5 dB
          - winner 看 pos/negCabinMidAvg（low+0.8×mid）
        FAIL:
          - boomOut≈0 / 中頻大增 / 停速或不等長 / mute 時 antiDb 仍高

        === SCRIPT EVENT LINES ===
        """
        header += "\n" + logLines.joined(separator: "\n")
        header += "\n\n" + SessionLogger.shared.exportText(headerNote: "merged session snapshots during guided test")
        return header
    }
}
