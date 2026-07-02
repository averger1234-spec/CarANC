package com.example.caranc.shared.test

import com.example.caranc.shared.UserTier

data class TestScriptStep(
    val id: String,
    val title: String,
    val instructions: List<String>,
    val durationSec: Int = 0,
    val suggestedTier: UserTier? = null,
    val requiresAncRunning: Boolean = true,
    val checklist: List<String> = emptyList(),
    val logPhases: List<String> = emptyList(),
    // For automatic application in tuning scripts (e.g. car_road_tuning_v1)
    // Keys: "lmsMuMultiplier", "freezeThreshold", "freezeConsec", "latencyOverrideMs",
    //       "forceNormalMode", "musicLowAncEnabled", "userAncGain", "tier"
    // NOTE: TIER ONLY MANUAL (per user req): User only flips LIGHT/STANDARD/PRO (light/medium/heavy UI).
    // All else (Leaky leakage, VSS from blockRms var, IMU rumbleBoost, native switch) AUTO via processor.updateTier + tier* funcs (sim_iter.ps1 tuned values).
    // Legacy manual debugLeakage etc deprecated/hidden in UI; display "effective*FromTier" read-only in snapshots/TestLogPanel.
    // script uses suggestedTier + "tier" in presets; controller applies setTier which triggers auto config.
    val debugPresets: Map<String, Any> = emptyMap()
)

object CarAncTestScript {
    const val SCRIPT_ID = "car_field_v3"
    const val SCRIPT_NAME = "實車進階測試（v3·手動步進·約 14 輪）"

    val steps: List<TestScriptStep> = listOf(
        TestScriptStep(
            id = "prep",
            title = "準備與啟動",
            instructions = listOf(
                "確認手機已 USB 連接 Android Auto（或記錄本機模式）",
                "填寫上方「實車測試 Log」的車型、手機位置",
                "（可選）在測試設定填入手動 RPM（用於引擎諧波測試，怠速約 800）",
                "點主畫面「開始降噪」，允許麥克風、定位權限",
                "等待校正完成，狀態顯示「降噪中」",
                "每步需手動按「完成這步」才會往下（適合上下班分段測）"
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("AA 已連接", "ANC 已啟動", "校正已完成"),
            logPhases = listOf(
                "audio_init",
                "calibration",
                "latency_optimization_applied",
                "mimo_profile_applied",
                "media_ref_start",
                "rpm_config"
            )
        ),
        TestScriptStep(
            id = "latency_verify",
            title = "延遲優化驗證",
            instructions = listOf(
                "保持安靜 15 秒，觀察主畫面「延遲 / 可抵消頻率」",
                "確認 log 出現 latency_optimization_applied",
                "estimatedLatencyMs 應 < 120（低延遲模式）",
                "maxCancelFrequencyHz 應 > 50 Hz",
                "processingReadSize 應為 64"
            ),
            durationSec = 15,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf(
                "latency_optimization_applied 已出現",
                "estimatedLatencyMs < 120",
                "maxCancelFrequencyHz > 50"
            ),
            logPhases = listOf("latency_optimization_applied", "running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "mimo_verify",
            title = "AMBEEO-lite MIMO 驗證",
            instructions = listOf(
                "保持車內安靜 20 秒",
                "確認 log 出現 mimo_profile_applied，zoneCount ≥ 4",
                "觀察 running_snapshot 的 mimoZoneCount 是否 > 1"
            ),
            durationSec = 20,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("mimo_profile_applied 已出現", "mimoZoneCount > 1"),
            logPhases = listOf("mimo_profile_applied", "running_snapshot")
        ),
        TestScriptStep(
            id = "idle_light",
            title = "怠速降噪（輕度）",
            instructions = listOf(
                "車輛靜止、冷氣維持平常設定",
                "選擇降噪等級「輕度」",
                "保持安靜 30 秒，不要播放音樂",
                "觀察 latencyMidEnabled 是否依延遲自動開啟"
            ),
            durationSec = 30,
            suggestedTier = UserTier.LIGHT,
            checklist = listOf("車輛靜止", "無音樂", "reductionDb 有記錄"),
            logPhases = listOf("running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "idle_engine",
            title = "怠速引擎諧波（手動 RPM，可選）",
            instructions = listOf(
                "（可選）在 TestLogPanel 設定手動 RPM（怠速約 800~1500）",
                "維持怠速 30 秒",
                "觀察 running_snapshot 的 engineRpmSource 是否為 \"manual_test\"",
                "若設定 RPM，低頻 reductionDb 可能有 engine comb 輔助變化（可跳過此步）"
            ),
            durationSec = 30,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("（可選）已設定手動 RPM", "怠速穩定"),
            logPhases = listOf("rpm_config", "running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "idle_standard",
            title = "怠速降噪（中度）",
            instructions = listOf(
                "維持怠速，切換降噪等級為「中度」",
                "保持 30 秒，觀察 reductionDb 是否穩定"
            ),
            durationSec = 30,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("已切換中度"),
            logPhases = listOf("running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "drive_city",
            title = "市區行駛 30–50 km/h",
            instructions = listOf(
                "安全駛入市區路段，維持 30–50 km/h 約 60 秒",
                "確認狀態是否切換為「路噪降噪中（XX km/h）」",
                "GPS 車速應顯示有效數值",
                "觀察 Wiener feedforward：reductionDb 在低頻應維持"
            ),
            durationSec = 60,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("車速 30–50", "GPS 有效"),
            logPhases = listOf("running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "drive_highway",
            title = "高速行駛 80+ km/h",
            instructions = listOf(
                "進入快速道路或高速，維持 80 km/h 以上 60 秒",
                "觀察主導頻帶是否為 ROAD_LOW / ROAD_MID",
                "高延遲時 latencyHighEnabled 可能為 false（正常）",
                "低頻 reductionDb 仍應 > 0"
            ),
            durationSec = 60,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("車速 80+", "latencyHighEnabled 已記錄"),
            logPhases = listOf("running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "music_bypass",
            title = "音樂 bypass（媒體參考扣除）",
            instructions = listOf(
                "播放車機音樂（音量中等）",
                "確認狀態為「底噪降噪中（音樂播放·媒體參考扣除）」",
                "觀察 playbackRefActive=true、mediaCorrelation 有值",
                "維持 60 秒，reductionDb 應 > 0（低頻底噪）"
            ),
            durationSec = 60,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("音樂播放中", "playbackRefActive", "reductionDb > 0"),
            logPhases = listOf("media_ref_start", "running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "nav_voice",
            title = "導航語音清晰度",
            instructions = listOf(
                "播放 Google Maps / 車機導航語音（非音樂）",
                "確認導航語音清晰可辨，ANC 不應完全關閉",
                "觀察 aecErleDb 是否有提升"
            ),
            durationSec = 45,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("導航語音可聽", "aecErleDb 有記錄"),
            logPhases = listOf("running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "call_bypass",
            title = "通話 bypass（語音頻帶保護）",
            instructions = listOf(
                "撥打測試電話或車機藍牙通話（可用語音信箱）",
                "確認狀態為「底噪降噪中（通話中·語音頻帶保護）」",
                "通話聲音應清晰，低頻路噪/怠速噪仍可降噪",
                "維持 45 秒"
            ),
            durationSec = 45,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("通話進行中", "語音清晰", "call=true"),
            logPhases = listOf("state_change", "running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "siren_sim",
            title = "警笛模擬（可選）",
            instructions = listOf(
                "（可選）用手機播放救護車警笛音效 10 秒",
                "觀察 log 是否出現 siren_detected、sirenOverride=true",
                "若無法模擬可跳過此步"
            ),
            durationSec = 20,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("已嘗試模擬或跳過"),
            logPhases = listOf("siren_detected", "running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "bump_stress",
            title = "顛簸壓力測試",
            instructions = listOf(
                "以安全方式經過減速帶，或輕敲中控台一次",
                "等待 20 秒讓系統恢復",
                "log 應出現 bump_detected（若無也請繼續）"
            ),
            durationSec = 20,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("已製造顛簸/敲擊"),
            logPhases = listOf("bump_detected", "running_snapshot", "test_step_snapshot")
        ),
        TestScriptStep(
            id = "finish",
            title = "結束與匯出",
            instructions = listOf(
                "點「停止降噪」",
                "到「實車測試 Log」區塊點「匯出 Log」",
                "將 log 傳給分析（LINE / Drive / email）",
                "匯出檔應含 latency_optimization_applied 與各步 test_step_*"
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("ANC 已停止", "Log 已匯出"),
            logPhases = listOf("test_script_complete")
        )
    )

    val monitoredLogPhases: List<String> = listOf(
        "latency_optimization_applied",
        "running_snapshot",
        "test_step_start",
        "test_step_snapshot",
        "test_step_complete",
        "mimo_profile_applied",
        "media_ref_start",
        "rpm_config",
        "siren_detected",
        "bump_detected",
        "profile_aging_detected"
    )

    val monitoredSnapshotFields: List<String> = listOf(
        "estimatedLatencyMs",
        "maxCancelFrequencyHz",
        "latencyLowGain",
        "latencyMidGain",
        "latencyHighGain",
        "latencyMidEnabled",
        "latencyHighEnabled",
        "latencyRecordMs",
        "latencyTrackMs",
        "latencyBlockMs",
        "processingReadSize",
        "reductionDb",
        "mimoZoneCount",
        "playbackRefActive",
        "aecErleDb",
        "mediaSubtracted",
        "mediaCorrelation",
        "mediaActiveFilterLen",
        "mediaMuStep",
        "mediaAdaptationActive",
        "musicSuppressionQuality",  // P1: track when conservative mode kicks in during music dominant tests
        "musicRoadEnergyRatio",  // music vs road energy ratio guard
        "musicDominantRumbleMode",  // direction C: IMU-dominant rumble when music (07-01: stronger multipliers 2.8x+3.5x, EMA for stability, micFactor 0.18, lower clear threshold 0.25)
        "rumbleVibBoost",  // actual applied (stronger base + dynamic; EMA smoothed for less flicker)
        "effectiveLowMu",  // = baseLowMu * rumbleVibBoost (verify stronger/more stable IMU effect in music)
        "couplingQuality",  // IMU coupling (accelMag baseline / 0.3); <0.5 dampens boost in dominant mode
        "virtualSuppressionQuality",  // 混合 media quality + IMU rumble energy proxy；virtual quality 改善 quality 卡 0 時仍能依 rumble 能量 aggressive low band
        "rumbleEnergyProxy",  // raw accel-derived (0-1) to correlate with virtualQ, boost activation, and when IMU takes over vs music q=0
        "musicStreamVolume", "musicStreamMax", "musicVolNorm",  // for volume adjust experiments + music bleed/conflict diagnosis (correlate vol up with blockRms/reduction/freeze/sonif)

        "sirenOverride",
        "sonificationOverride",  // notification / sonification 事件保護（最高優先修復 choppy + echo 問題）
        "sonificationGainScale",
        // 新增：LMS 調校實驗關鍵欄位（mu/freeze/latency override + band muScale + dominant）
        "dominantNoiseBand",
        "debugLmsMuMultiplier",
        "debugFreezeThreshold",
        "debugFreezeConsec",
        "debugLatencyOverrideMs",
        "usingLatencyOverride",
        "lowBandMuScale",
        "midBandMuScale",
        "highBandMuScale",
        "effectiveMidMu",  // Iter2+: tracks actual midMu post road/music boost for rumble contrib diagnosis
        "antiNoiseDb",
        "lmsUpdateCount",
        "lowBandLmsUpdateCount",
        "freezeBlocksRemaining",
        "processingMode",
        // Added for EMA variance logging of lastLmsPfx (item2) + IMU accel (item3) + leakage verification in tuning runs
        "lmsPfxEma",
        "lmsPfxVarEma",
        "accelMag",
        "accelSource",
        "coarseLat",
        "coarseLon",
        "roughness",
        "speedKmh",
        "speedValid",
        // C8: crowd vision / IMU hybrid Road Preview / NVH predictive preload (1.5x on agg coarse/rough from prior #7)
        "crowdsourcedPreloadBoost",
        "rumbleAuxFactor",
        "crowdsourcedNVHPreload",
        "rumbleAuxPreviewFactor",
        "imuHybridImprove",
        "hasClusterMatch",
        "clusterHash"
    )
}

object CarRoadTuningScript {
    const val SCRIPT_ID = "car_road_tuning_v1"
    const val SCRIPT_NAME = "Skoda 200-350Hz rumble 快速迭代測試（tier-only: LIGHT/STANDARD/PRO auto-config leakage/VSS/IMU/native via updateTier；基於 sim_iter.ps1 推薦值平衡stab/perf；#4/#4b/#6/#7_ext ... 對比；suggestedTier 切換讓 auto apply；用戶未來只 flip tier，sims 決定其餘）"

    // TIER-ONLY MANUAL (per user): switch LIGHT/STANDARD/PRO only; leakage (alpha), blockRmsVssScale, rumbleBoostFactor (IMU), useNativeLowBand ALL auto via updateTier in processor.
    // sim_iter.ps1 runs full per-tier sims (normal/strict +/- rough IMU accel +/- native 2x save, pothole impulses, 06-29 log calib) to recommend best values balancing stability (low pfxVarEma, no pop) + perf (high effMidMu, red in 200-350Hz, lms).
    // 根據 sims: LIGHT conservative (0.9999/0.65/0.015/false), STANDARD balanced(0.9998/0.85/0.045/false), PRO aggressive(0.9995/1.0/0.09/true).
    // 注意：根據 Skoda Octavia 2019 真實錄音頻譜分析（用戶提供）+ 2026-06-29 實車 AA log 分析：
    // 主要路噪能量在 200-350 Hz（57.6%），峰值 ~305 Hz 會隨路段浮動 220-310 Hz。
    // AA 實測：即使 90kmh rough，low+mid ratio 最高僅 ~0.071（music 能量主導 highRatio~0.999），原 0.30 thresh 永遠不 trigger force ROAD_MID。
    // → 已 data-driven 放寬 classifier (0.06) + processor rumbleContext guard + MUSIC mid gain 0.15->0.28。
    // 目前 150 Hz maxCancel 嚴重不足，只吃到邊緣 → 即使 LMS 活躍，reduction 仍極低。
    // 快速迭代版本（已 streamline）：移除無用早期 baseline（1/2/3，已知問題，不新增數據）。保留 old prep/4/4b/5 UNCHANGED 作為穩定 baseline；延伸 #6 midforce + #7 strong road rumble（iter4 + Subagent3 Extended #7 variant）。使用 suggestedTier 切換來觸發不同 auto 配置（e.g. prep LIGHT, #7 PRO）。
    // 目標：每次跑完整 prep+4+4b+5+6+7+finish，配外部錄音 + spectrum。使用 old parts 單輪 A/B 測試穩定 baseline 對比新。所有 boost 最小安全 guarded。
    // 調校時優先觀察 tier + effective from tier (leakage etc read-only), midBand 貢獻、effectiveMidMu、maxC 與 200-350 Hz reduction。每步 scenario 註 "tier=PRO auto". finish 強調下一輪優先重跑不同 tier 變體 + 外部 spectrum 驗證 + re-run sims 微調 tier*。
    // sims 決定其餘；未來用戶只 flip tier。
//
// First-principles update (2026-06-30): 在音樂主導時，優先用 IMU 作為 rumble 主 ref（震動前兆不受音樂/延遲影響），減少 mic residue 依賴。
// 測試重點：觸發 MUSIC_DOMINANT_RUMBLE（高音樂 vol + rough 路，suppressionQuality 低 <0.4 時 IMU 額外 boost 1.3-1.5x）。
// 記錄 phone placement 測試 couplingQuality（accelMag baseline，<0.3/poor 時 dampen boost）。
// 觀察 rumbleVibBoostApplied（動態）、musicRoadEnergyRatio guard、micFactor 降低後的 low band 行為（blended more to road/IMU）。
// scenario 註 "placement=中控下方, suppression=低, coupling=好/差"。finish 匯出 clusters 給 NVH map + re-sim。
    //
    // === 完全由 log 驅動的迭代（你不要手動，我根據你給的 log 自動更新基準）===
    // 流程（零手動調參數）：
    // 1. 你只跑完整 script（按 UI 按鈕，照裡面最新說明開車）。
    // 2. 匯出 log 給我（或我 pull 最新）。
    // 3. 我 parse 真實 #7 數據（effMidMu, reduction, dominant, speed, personalRumbleBias, roughness, rumbleAccelEma, crowd boost...）。
    // 4. 對照 sim_iter.ps1 模型 + 上次 best，決定下一輪最佳配置。
    // 5. 我直接 edit 這支 script 的 debugPresets（把新 mu/ov/personal 等 bake 進去）、UI default、說明。
    // 6. Push → 你 pull + install-debug → 下次 script 已經是新 bake 的「當前基準」。
    // 你永遠不用自己記數字或調滑桿 — log 進來，我負責把迭代結果寫回程式碼當新 baseline。#4b 永遠固定當 old control。
    //
    // 主頁輕/中/重度 vs script #4（你的確認）：
    // 主頁的 LIGHT/STANDARD/PRO **不是** 直接複製 #4 的測試參數。
    // #4 是 script 裡的**測試點**：以 STANDARD tier 當 base，再加 debug override（muMult=1.7、ov=120、musicLow=ON、forceNormal=true）來強制低延遲 musicLow 情境，驗證對 200-350Hz rumble 的效果（收集數據用）。
    // 
    // 日常不跑 script 時，主頁 tier 選擇直接決定 auto-config（由 sim_iter.ps1 調校的基礎：filterLen、baseMu、leakage、blockRmsVssScale、rumbleBoostFactor、useNativeLowBand）。
    // 
    // 「當前基準」迭代方式（log 驅動，零手動）：
    // 跑完整 script 後，好的 #7 通常會推薦用 PRO tier + personalRumbleBias=1.28 + musicLow=ON 作為日常 baseline。
    // 我從你給的 log 分析實際跑在哪個 tier + 效果（effMidMu/red/dominant/新 fields），然後直接更新程式碼推薦（UI default bias、script 說明「日常用這個 tier + bias」），下次你開主頁或跑 script 就預設最新從 log 迭代出的最佳。
    // #4b 永遠 script 內固定 old control，不影響日常 tier。

    val steps: List<TestScriptStep> = listOf(
        TestScriptStep(
            id = "tuning_prep",
            title = "調校準備（快速）",
            instructions = listOf(
                "USB AA 連車機",
                "車型/手機位置/情境在「實車測試 Log」填寫清楚（例如「個人 Pixel + USB AA + 粗糙國道 iter4」）",
                "★ 最高優先提醒（07-02 log 證實）：phone placement 對 IMU rumble coupling 極關鍵！這次用「中控下方」導致 accelMag / rumbleEnergyProxy 很低（0.01-0.12），virtualSuppressionQuality 卡低，boost 難起。即使進入 musicDominant 也沒用。強烈建議放 floor（腳踏墊下）或座椅底部（更好結構震動傳導）。填寫時註明 'placement=floor_seat for good coupling'，並觀察 log 裡 accelMag >0.5 + roughness >0.5 + rumbleEnergyProxy >0.3 才算好條件。",
                "點「開始降噪」完成校正，狀態顯示「降噪中」",
                "準備好後進入同一条粗糙路面（50-70km/h 顛簸強 rumble），全程**嚴格低音樂（音量<20% 或 off）** + speed 50+ 維持 rough road",
                "接下來每步按「完成這步」前，系統會自動套用對應的 LMS 調校參數",
                "只需專心開車並在每步維持時間即可，最後一步匯出 log",
                "延伸：這是 Skoda 專用低延遲 musicLow 快速迭代腳本（基於#4/#4b +#6 +#7 iter4）。跳過無用早期 baseline（已知高延遲問題），直接進入#4/#4b/#6/#7 核心對比。計劃跑 3-4 次完整腳本，每次配錄音+spectrum。優先記錄 mid band 貢獻、effectiveMidMu、200-350Hz reduction。#6/#7 步強制 pure road（forceNormal=false + 維持車速50+ 讓 roadMode 觸發 + strict low music 避免 MUSIC_BROAD）。GPS 需有效，車速>30kmh + low/mid energy > thresh 觸發 ROAD_MID（classifier iter4 強化）。old parts #4b 作為穩定 baseline A/B 對照新 #6/#7 rumble 突破。"
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("AA 已連", "ANC 已跑"),
            logPhases = listOf("audio_init", "calibration", "rpm_config", "running_snapshot"),
            debugPresets = mapOf(
                "forceNormalMode" to true,
                "musicLowAncEnabled" to true,
                "userAncGain" to 1.0f
            )
        ),
        TestScriptStep(
            id = "tuning_4",
            title = "#4 強制低延遲 + musicLow 對比（Skoda 200-350Hz rumble 專用）（mu=1.7, freeze=11, c=2, override=120）",
            instructions = listOf(
                "系統已自動套用參數（mu=1.7 / freeze=11 / c=2 / override=120）",
                "同一段粗糙路 60-90 秒",
                "Skoda Octavia 2019 專用：這步用 override=120 強制推 maxCancel 接近 250Hz+，觀察 mid band (200-350Hz) 是否開始有貢獻（這是你錄音主力頻段）",
                "預期觀察重點：mid band 貢獻增加、reduction 在 200-350Hz 是否改善，比較 musicLow ON/OFF 感覺（此 step ON，記錄 scenario 註 musicLow=ON + \"Skoda mid-rumble test\"）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.STANDARD,  // tier switch here auto-applies conservative leakage=0.9998 / vss=0.85 etc for STANDARD (no manual debugLeakage needed)
            checklist = listOf("muMult=1.7", "freezeTh=11", "consec=2", "override=120", "musicLow=ON", "Skoda 200-350Hz focus", "tier=STANDARD (auto leakage/VSS/IMU/native)"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.7f,
                "freezeThreshold" to 11f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 120f,
                "tier" to "STANDARD"  // explicit for log; updateTier auto-configs advanced (future: hide manual sliders)
            )
        ),
        TestScriptStep(
            id = "tuning_4b_Skoda",
            title = "#4b 延伸低延遲 musicLow 對比（基於#4 Skoda 經驗，override=150, mu=1.6 mid-focus）",
            instructions = listOf(
                "系統已自動套用參數（mu=1.6 / freeze=12 / c=2 / override=150） - 基於前次#4經驗進一步推延遲，針對 mid band 微調 mu",
                "同一段粗糙路 60-90 秒",
                "Skoda Octavia 2019 專用延伸：延續#4的200-350Hz rumble 對比，觀察是否能讓 reduction 在主力頻段更深、更穩（mid band 貢獻持續增加）。記錄 scenario 註 \"Skoda #4b延伸, musicLow=ON, based on #4 data\""
            ),
            durationSec = 75,
            suggestedTier = UserTier.STANDARD,  // tier auto: leakage/vssScale/rumbleBoost/native applied via updateTier; NO manual debugLeakage
            checklist = listOf("muMult=1.6", "freezeTh=12", "consec=2", "override=150", "musicLow=ON", "Skoda 200-350Hz #4延伸", "tier=STANDARD auto params"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.6f,
                "freezeThreshold" to 12f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 150f,
                "musicLowAncEnabled" to true,
                "tier" to "STANDARD"  // lets auto apply (leakage 0.9998 etc); deprecate debugLeakage in future UI
            )
        ),
        TestScriptStep(
            id = "tuning_5_contrast",
            title = "#5 musicLow OFF 對比（mu=2.2, freeze=9, c=2, override=0） - 證明 musicLow 重要",
            instructions = listOf(
                "系統已自動套用參數（mu=2.2 / freeze=9 / c=2 / override=0） - musicLow OFF 對比",
                "同一段路 60-90 秒",
                "預期觀察重點：anti 更強但注意 artifact；比較前 step（尤其是#4/#4b的低延遲 musicLow）有無 musicLow 時 rumble 降低（記錄 scenario musicLow=OFF）。基於#4經驗，注意 mid band 是否因 OFF 而掉。快速對比用，不需多次重複。"
            ),
            durationSec = 75,
            suggestedTier = UserTier.LIGHT,  // use LIGHT tier for contrast step (conservative auto params); demonstrates only tier flip
            checklist = listOf("muMult=2.2", "freezeTh=9", "consec=2", "override=0", "musicLow=OFF", "tier=LIGHT (conservative auto)"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 2.2f,
                "freezeThreshold" to 9f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 0f,
                "musicLowAncEnabled" to false,
                "tier" to "LIGHT"  // tier change auto applies higher leakage etc; remove legacy debugLeakage
            )
        ),
        // Iter2-4 + S3: #6 mid-force contrast (roadMode forced + musicLow ON + mu for mid focus). Updated: stricter no-music instr + dominant force in DSP.
        // Use forceNormal=false to allow road detection, musicLow ON, override low for maxC, mu high.
        // Goal: isolate mid band rumble contrib vs #4/#4b; observe effectiveMidMu >0.5 , reduction on 250-350Hz. Use as A/B vs #4b stable (old unchanged).
        TestScriptStep(
            id = "tuning_6_midforce",
            title = "#6 mid-force road rumble 對比（mu=1.8, freeze=10, c=2, override=110, musicLow=ON, force road） - 驗證 mid 突破 (iter4+S3: stricter for dominant shift)",
            instructions = listOf(
                "系統已自動套用參數（mu=1.8 / freeze=10 / c=2 / override=110 / musicLow=ON）",
                "切到**粗糙路面，嚴格維持 speed 50+ km/h**（>30 且 rough 讓 GPS+energy 觸發 roadMode + classifier ROAD_MID/LOW） 60-90秒；**全程低音樂或無音樂（vol<15-20% 或 off）** —— 這是關鍵！music 音量高會讓 highRatio~1.0、low+mid<0.07，無法有效 shift dominant 或拿到高 midMu（見 20260629 log 實測）。低音量讓 rumble energy 相對浮現，0.06 thresh 才能 trigger。",
                "Skoda Octavia 專用：強制 mid 貢獻，觀察 effectiveMidMu 是否 >0.5 (iter4 目標)，midOut 對 200-350Hz rumble 的貢獻，比較 reductionDb 與 band ratios；與前#4b A/B（old baseline）",
                "記錄 scenario \"Skoda #6 midforce iter4, roadMode active, effectiveMidMu=XX dominant=ROAD_MID speed=XX musicLow=ON music=low\"",
                "★ 關鍵確認（running_snapshot 重點）：guidedTestStepId=tuning_6_midforce + effectiveMidMu>0.5 + dominant=ROAD_MID/ROAD_LOW + midBandMuScale>0.5 + reduction >2dB improvement；低速/高音樂仍會 MUSIC_BROAD（effMidMu=0，無效此步，改用#4b baseline）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,  // tier=PRO auto applies aggressive leakage=0.9995 + vss=1.0 + boost=0.09 + native=true ; step switch tier lets auto apply (no manual debugLeakage)
            checklist = listOf("muMult=1.8", "freezeTh=10", "consec=2", "override=110", "musicLow=ON", "roadMode active", "effectiveMidMu>0.5", "speed>50 rough low-music", "tier=PRO (auto leakage/vss/rumbleBoost/native)"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing", "debug_presets_apply"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.8f,
                "freezeThreshold" to 10f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 110f,
                "musicLowAncEnabled" to true,
                "forceNormalMode" to false,
                "tier" to "PRO"  // primary control: updateTier auto-configs the advanced params (sims tuned); legacy debugLeakage deprecated for these
            )
        ),
        // Iter4 + Subagent3 Extended #7 variant: strong road rumble (on top of #6): mu higher, ov=80 for maxC 300+ sim, stronger DSP mid focus (even music), classifier tweak for pure ROAD_MID even with music.
        // Goal: deeper rumble gains, dominant=ROAD_MID/LOW, effMidMu 0.6+, reduction -4~-6dB in 200-350 (mid contrib). Prioritize shift from MUSIC_BROAD.
        // Use with strict conditions (speed50+ rough low-music) + old prep/4/4b/5 UNCHANGED + #6 for A/B in one run. mid center 335Hz focus 300-350.
        // All changes minimal+guarded (roadMode + speed>28 + energy + musicLow).
        TestScriptStep(
            id = "tuning_7_strong_road",
            title = "#7 strong road rumble ignore-music 對比（mu=2.05, freeze=9, c=2, override=80, musicLow=ON, force road+mid） - iter4 + S3 Extended#7 更深 rumble 突破",
            instructions = listOf(
                "系統已自動套用參數（mu=2.05 / freeze=9 / c=2 / override=80 / musicLow=ON） - 基於#6經驗 + iter4 + Subagent3 Extended DSP 強化 (stronger mid boost, pure ROAD_MID classifier, midErr*1.28, center 335)",
                "**切到粗糙路面，嚴格維持 50+ km/h 粗糙顛簸路 60-90秒**（確保 speed>28 + low/mid energy ratio >0.06 觸發 classifier pure ROAD_MID，即使 music=true；guarded by roadMode+speed+energy。2026-06-29 log 實測：高音樂時 max 只有 0.071，必須嚴格低 vol 才行）",
                "Skoda Octavia 專用：#7 目標是 dominant shift 至 rumble + bigger mid 貢獻。觀察 effectiveMidMu 0.6+、midScale high、maxC 300-380Hz、200-350Hz reduction -4~-6dB（比#6 更深）；比較 vs old #4b baseline + #6 A/B（old parts 穩定不變）",
                "記錄 scenario \"Skoda #7_ext strong S3 iter4, roadMode active, effectiveMidMu=XX dominant=ROAD_MID speed=XX music=low-strict reduction=XX\"",
                "★ 07-02 log 核心教訓：即使 #7 參數 + musicDominant=true 71% 有高 boost (>=1.5)，red 仍低，主因 placement coupling 差 (中控下方) 導致 proxy 低。務必確認這次 placement 讓 accelMag / roughness / rumbleEnergyProxy 在 #7 期間明顯高（>0.5/0.5/0.3）。如果 proxy 低，virtualQ 起不來，boost 再高也沒用。",
                "★ 關鍵確認（running_snapshot 重點）：guidedTestStepId=tuning_7_strong_road + effectiveMidMu>0.6 + dominant=ROAD_MID + midBandMuScale>0.6 + reductionDb>3 + maxC>300 + lmsUpdateCount high；若 music 強仍 MUSIC_BROAD 則無效（退回用#4b/#6 baseline A/B）",
                "C8: 嚴格維持 speed 55+ rough (sustained) 觸發 full crowd vision 1.5x preloadBoost (agg from prior #7 coarse/rough clusters) + new fields: crowdsourcedPreloadBoost/rumbleAuxFactor/crowdsourcedNVHPreload/rumbleAuxPreviewFactor/imuHybridImprove 高值。記錄 scenario 含 'C8 crowdPre=1.5 rough=XX coarse=XX'。",
                "C15/C11 延伸（更多 cycle 模擬）：STRICT: 維持 sustained spd>55kmh (enforce via vehicleSpeedProvider; if <50 during step WARN + partial data) rough (國道/台68 bumps) low-music<15% pers=1.28 tier=PRO. #7 rumble 200-350Hz focus. LOG clusters (coarse~0.001 + rough>1.1 + rumbleEma>2.5 + red>4) for NVH preload. Monitor running_snapshot: rumbleAuxPreviewFactor crowdsourcedPreloadBoost imuHybridMidErrImprove roughness personalRumbleBias rumbleAccelEma coarse* energyFactor speedKmh dominant effectiveMidMu reductionDb. If spd<55 -> repeat for full C15 data. Compare vs same-run #4b A/B (old unchanged). C15 master: high spd rough pers1.28 + IMU hybrid + crowd 1.5-1.8 unlocks 8-10.5dB / 1.65 effMid vs real partial logs (0.147/3.95, newfields=0, low spd~10); 11-18x delta vs #4b baseline. Use c15_full_cycle11_report.txt + c11_cycle_output.txt for planning.",
                "07-01 改善重點（針對音樂主導策略 + AA高延遲瓶頸）：musicDominantRumbleMode 應更頻繁/穩定為 true；rumbleVibBoost / effectiveLowMu 應有更高更穩定的提升（目標 >2.5 sustained 而非尖峰閃爍，觀察 EMA 是否讓 boost 較平滑）；在音樂主導時仍看到較多/較穩的 reduction 尖峰（>2-3dB 持續）；micFactor 低至 0.18 後 low band 更 reliance IMU/road。記錄 music dominant 時 rumble 能量 (accel) 與 boost 的對應、sonification 事件是否干擾。仍需觀察 quality=0 時是否過保守，以及高延遲下 IMU precursor 是否提供穩定預覽優勢。",
"07-02 log 教訓 + 好消息（Skoda #7 實際跑）：red 仍多 0.00x（1076 sonif + 26k bumps），但高 boost 快照存在（全 log 551 個 rumbleVibBoost >=2；#7 步 239 個 boost field 中 171 個 >=1.5，最高 6.5+）。virtualQ 0.05-0.13 > rawQ=0.0，證明 proxy 正在 lift quality，aggressive 邏輯 (2.8x + EMA + energy) 在 post-shadowing fix 後有生效（高 boost 出現在 tuning steps）。仍低 red 可能因 high band music 主導整體 dB metric、或 placement coupling 差 (中控下方 accel 低)。改善建議：phone 放 floor/seat 更好 coupling；觀察 virtualSuppressionQuality / rumbleEnergyProxy 是否 >0.3 才 boost；sonif 時若 rumbleEnergy 高則 milder duck。記錄 placement + accel + volNorm + sonif 叢集 vs red + 是否有高 boost 時 red 仍低。目標 #7 red >3 sustained in rumble periods + 確認 sonif 期間 boost 不降。",
                "Notification / Sonification 保護（06-30 另一問題 + 07-01 測試）：當車機通知鈴聲出現時，AA 音訊 choppy + 鈴聲有 echo。已實作 SonificationDetector + setSonificationOverride：playbackRef 或 mic 偵測到短 burst 時，立即將 ANC 輸出 gain duck 到 ~0.06-0.18，並觸發 freeze + 降低 mu，避免把 notification 當 noise 處理產生延遲 echo，也避免干擾 routing 造成 underrun。事件結束自動恢復。只保護短暫事件，rumble boost / IMU ref 應持續（vibration preview 不中斷）。log 會出現 sonification_detected + sonificationOverride + gainScale。今日 log 看到 4500+ sonif 事件（保護生效）。測試時可故意觸發通知觀察是否還有 echo/choppy，並記錄當時的 route、speed、accel 與 rumbleVibBoost 曲線（應平滑 sustained）。",
                "今日 log (07-01) 分析後續方向 + 突破困境關鍵數據：從 logcat 真實抓的數據重點（解決 quality=0 + MUSIC_BROAD + 低 red + freeze on music + boost 有限）：1. musicDominantRumbleMode flag 進入 + 處理模式 floor_noise_music_road 時，rumbleVibBoost 是否從 ~1.1 跳到 >2.5 (因 fix 了 processor 內 local val shadowing member flag 的 bug，現在 floor+flag 路徑會觸發 2.8x extra + energyProxy continuous + EMA)；2. rumbleEnergyProxy (raw 0-1 from accel) 與 virtualSuppressionQuality 的對應（即使 q=0 時 virtual 是否 >0.2 驅動 aggressive）；3. 調整音量時 (新增 musicStreamVolume / musicVolNorm 每2s snapshot)：vol 上升是否導致 blockRms 波動、freeze 增加、reduction 掉、virtual 掉、更多 sonif-like bleed；4. freeze_state / bump_detected 在 music dominant 時的 blockRms 閾值 (debug 9.0 +1.5x 放寬？) 與 red 期間是否過度 freeze；5. dominantNoiseBand 即使 speed58+accel1.3 仍 MUSIC_BROAD (high band energy主導分類，mediaSub=0 因 AA submix capture不可用)，virtual 如何 bypass；6. lowBandMuScale / effectiveLowMu / lmsUpdateCount low band 在 rumble mode 的實際活躍度 + sonif 期間 low muScale 是否維持1f (不被 duck)。 無線adb (10.176.11.105:5555 AA時) 實時 logcat 會抓到 force_music_dominant_rumble Log + freeze/sonif/ANCService 事件。 從這些真實數據 (不是猜) 驅動下次 code (e.g. 更 aggressive virtual proxy scale、rumble mode 下放寬 freeze 更多、加 low-band specific red/energy 指標到 snapshot)。 sub-agent C18+ 會用新 log stats 模擬預測。 phone 更新後重跑 strict high spd low music + 調 vol + 觸發 notif 來抓數據。",
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,  // tier PRO for most aggressive auto (low leak high boost native); demonstrates only manual switch is tier (sims pick values for balance)
            checklist = listOf("muMult=2.05", "freezeTh=9", "consec=2", "override=80", "musicLow=ON", "roadMode active", "effectiveMidMu>0.6", "speed>55 sustained rough low-music pers=1.28", "compare vs old #4b/#6 A/B", "tier=PRO auto (leakage=0.9995 etc from sims)", "C15: log clusters coarse/rough/ema/red for NVH 1.5-1.8x; monitor new fields"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing", "debug_presets_apply"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 2.05f,
                "freezeThreshold" to 9f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 80f,
                "musicLowAncEnabled" to true,
                "forceNormalMode" to false,
                "tier" to "PRO"  // tier switch triggers updateTier -> auto advanced params. See sim_iter.ps1 for per-tier predicted effMidMu/red/varEma/stab. Deprecate manual debug* for leakage/vss
            )
        ),
        TestScriptStep(
            id = "tuning_finish",
            title = "結束與匯出 + 準備下次迭代（快速）",
            instructions = listOf(
                "停止降噪",
                "在「實車測試 Log」點「匯出 Log」或直接用 GuidedTest finish 的「儲存到下載 / CarANC_Logs」按鈕（無需切測試平台）",
                "把完整 log 傳回分析",
                "記錄 scenario 註明各 step 參數組合 + speed 範圍 + \"musicLow=ON/OFF\"",
                "建議配外部錄音 + spectrum（重點 50-250Hz rumble 能量下降，特別 200-350Hz）",
                "觀察重點：不同 debug 設定下 lowBandLms 更新率、freezeRem 頻率、reduction 在 rumble 主導時變化、主觀低頻 rumble 降低程度（0-10分）",
                "比較重點：lmsUpdate 上升速度、freeze 頻率、antiNoiseDb 負值、reductionDb、midBand 貢獻（尤其是 Skoda 200-350Hz 區）、是否出現 artifact、effectiveMidMu、dominant shift、maxC",
                "延伸重點（tier auto + sim_iter）：現在**唯一手動切換是 tier (LIGHT/STANDARD/PRO)**； leakage / vssScale / rumbleBoost / native 全部由 updateTier 自動（依 sims 推薦值）。建議在 prep 用 LIGHT，#4b 用 STANDARD，#6/#7 用 PRO 測試不同 auto 行為。記錄 tier 變化 + running_snapshot 中的 tier + debugLeakage(effective from tier) + blockRmsVssScale + rumbleBoostFactor + useNativeLowBand + lmsPfxVarEma (stability) + effMidMu + reductionDb。單輪內 A/B 用不同 suggestedTier 步驟比較 auto 配置。**sims (sim_iter.ps1) 決定其餘**；累積 log 後 re-run sims 微調 tier* 值。目標：用戶只 flip tier，sims 保駕護航平衡穩定(低varEma無pop)與性能(高effMid red rumble)。驗證 red -4~-6dB on PRO strict rough。",
                "C15/C11 匯出 + NVH：after drive, terminal: powershell -File scripts/pull-latest-log.ps1; Select-String -Path \"C:\\Users\\user\\AndroidStudioProjects\\CarANC\\log\\anc_session_*.log\" -Pattern \"tuning_7_strong_road|coarseLat|roughness|personalRumbleBias|rumbleAccelEma|reductionDb|ROAD_MID|rumbleAuxPreviewFactor|effectiveMidMu\" | group coarse/rough/ema/red -> save local NVH json (privacy, e.g. clusters.json). Future #7 on 國道 match auto *1.5 rumbleAuxPreviewFactor + crowdsourcedPreloadBoost (predictive from prior C15 clusters). Update scenario log with C15 cond name + spectrum 200-350Hz red validate. re-run powershell -File sim_iter.ps1 for C15 update + seed 1.5-1.8x. Monitor in #7: all new IMU/rough/pers/crowd fields for validation.",
                "C14 5-drive NVH Waze (long-term cumulative): after drive, parse log for clusters -> save to local nvh_map.json (quantized coarse~0.001° hash + rough>1.1 + ema>2.0 + red>4); next drive auto preload in sim/real (crowdsourcedPreloadBoost=1.5 + rumbleAuxPreviewFactor*1.5 on match e.g. 國道/台68). Use Get-ClusterMatchBoost + Simulate-C14NVHStep. Cumulative after 3+: #7 red +50% from crowd history (1.28 pers * 1.12 imu * 1.5 crowd). Full 5-drive target: eff>1.8 red>11 98% ROAD_MID. Old parts (prep/4/4b/5) always unchanged for low A/B control. Monitor hasClusterMatch/clusterHash/crowdsourcedPreloadBoost/rumbleAuxPreviewFactor etc. See c14_nvh_longterm_5drive.txt for table/JSONL/nvh_map ex +50% quant. re-run powershell -File sim_iter.ps1 (C14 funcs loaded)."
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("Log 已匯出", "每組都有 scenario 註記"),
            logPhases = listOf("test_script_complete")
        )
    )

    val monitoredLogPhases: List<String> = CarAncTestScript.monitoredLogPhases

    val monitoredSnapshotFields: List<String> = CarAncTestScript.monitoredSnapshotFields
}