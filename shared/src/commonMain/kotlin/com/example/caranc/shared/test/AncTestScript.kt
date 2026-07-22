package com.example.caranc.shared.test

import com.example.caranc.shared.UserTier

data class TestScriptStep(
    val id: String,
    val title: String,
    val instructions: List<String>,
    /**
     * For drive steps: required **valid** seconds (speed≥minSpeedKmh) before auto-next.
     * For prep/finish (wallClockOnly): wall-clock seconds.
     * Red light / idle does NOT count toward valid seconds.
     */
    val durationSec: Int = 0,
    val suggestedTier: UserTier? = null,
    val requiresAncRunning: Boolean = true,
    val checklist: List<String> = emptyList(),
    val logPhases: List<String> = emptyList(),
    // For automatic application in tuning scripts (e.g. car_road_tuning_v1)
    // Keys: "lmsMuMultiplier", "freezeThreshold", "freezeConsec", "latencyOverrideMs",
    //       "forceNormalMode", "musicLowAncEnabled", "userAncGain", "tier"
    val debugPresets: Map<String, Any> = emptyMap(),
    /** Min speed (km/h) for a second to count as valid data. */
    val minSpeedKmh: Float = 40f,
    /** true = only wall clock (prep/finish); false = accumulate only while driving valid. */
    val wallClockOnly: Boolean = false,
    /** Safety: max wall seconds on this step before force-advance (avoid infinite wait). */
    val maxWallSec: Int = 720
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
                "填寫上方「實車測試 Log」的車型、手機位置（建議 floor/seat）",
                "（可選）在測試設定填入手動 RPM（用於引擎諧波測試，怠速約 800）",
                "點主畫面「開始降噪」，允許麥克風、定位權限",
                "等待校正完成，狀態顯示「降噪中」",
                "開 ANC 後聽感應安靜或低頻悶，不應像電台收訊不良（core polarity 已修）",
                "每步需手動按「完成這步」才會往下（適合上下班分段測）"
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("AA 已連接", "ANC 已啟動", "校正已完成", "開ANC無電台靜電"),
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
                "觀察 latencyMidEnabled 是否依延遲自動開啟",
                "確認無持續嘶嘶／電台雜訊（怠速 hard-zero 已改為只抑微 hiss）"
            ),
            durationSec = 30,
            suggestedTier = UserTier.LIGHT,
            checklist = listOf("車輛靜止", "無音樂", "無電台靜電", "reductionDb 有記錄"),
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
        "musicDominantRumbleMode",
        "rumbleVibBoost",
        "effectiveLowMu",
        "virtualSuppressionQuality",
        "rumbleEnergyProxy",
        "musicStreamVolume", "musicStreamMax", "musicVolNorm",

        "sirenOverride",
        "sonificationOverride",
        "sonificationGainScale",
        "dominantNoiseBand",
        "debugLmsMuMultiplier",
        "debugFreezeThreshold",
        "debugFreezeConsec",
        "debugLatencyOverrideMs",
        "usingLatencyOverride",
        // High-lat path (post-2026-07-22: HIGH_LAT_PRED_BANK, not FF_PREVIEW magnitude inject)
        "latencyStrategy",
        "measuredLatencyMs",
        "plantElectricalDelaySamples",
        "previewRumble",
        "predictionHorizonMs",
        "previewHistoryAgeMs",
        "previewHistoryCount",
        "preLearnedBinCount",
        "effectiveRumbleMode",
        // #6–#9
        "fdafDelayless",
        "fdafPartitions",
        "fixedBankOut",
        "learnedBinCount",
        "audioBackend",
        "wirelessAaSuspected",
        "wiredCarPathAvailable",
        "aaLinkType",
        "roadRoughness",
        "reductionDbLegacy",
        "lowBandRumbleReduction",
        "primaryReductionKpi",
        "requireWiredAa",
        "lowBandMuScale",
        "midBandMuScale",
        "highBandMuScale",
        "effectiveMidMu",
        "antiNoiseDb",
        "lmsUpdateCount",
        "lowBandLmsUpdateCount",
        "freezeBlocksRemaining",
        "processingMode",
        "lmsPfxEma",
        "lmsPfxVarEma",
        "accelMag",
        "accelSource",
        "coarseLat",
        "coarseLon",
        "roughness",
        "speedKmh",
        "speedValid",
        "crowdsourcedPreloadBoost",
        "rumbleAuxFactor",
        "crowdsourcedNVHPreload",
        "rumbleAuxPreviewFactor",
        "imuHybridImprove",
        "hasClusterMatch",
        "clusterHash",
        // 2026-07-22 literature / patent diagnostics (must appear in running_snapshot)
        "imuMicCoherence",       // IMU↔mic low coupling 0..1; low = damp boost
        "bankMatchQuality",      // neural soft-max peak 0..1
        "bankMatchCosine",       // best cell cosine −1..1
        "neuralLatentEnabled",   // true = MLP latent path on
        "latent0", "latent1", "latent2"  // query latent dims for log plots
    )
}

object CarRoadTuningScript {
    const val SCRIPT_ID = "car_road_tuning_v1"
    // 2026-07-22 core + literature (3c0016b / 73ba9bb / 67a77a0):
    // - plant/maxCancel = measured only；latencyOverrideMs 僅 log A/B 標記
    // - 高 lat + rumble → HIGH_LAT_PRED_BANK（predictive bipolar ref + neural latent bank + low FxLMS）
    //   舊名 FF_PREVIEW_ONLY / magnitude→喇叭 已廢止（會出電台雜訊）
    // - #6 delayless FDAF + #7 bank；bank 現為 soft-max cosine on neural latent
    // - 驗證 KPI：lowBandRumbleReduction 主；imuMicCoherence / bankMatchQuality / neuralLatentEnabled
    // - 實測請 USB 有線 AA；無線 projection 更差
    // 驗證：latencyStrategy / imuMicCoherence / bankMatch* / fixedBankOut / lowBandRumbleReduction / 聽感無靜電
    const val SCRIPT_NAME = "路噪自動調校（有效行駛秒數推進；紅燈不計）"

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
                "★ #9 建議 USB 有線 Android Auto（BT 無線更差）。A-fix：remote_submix → aaLinkType=projection_submix，不是 wireless",
                "USB AA 後啟動 ANC；audio_init：audioBackend=AUDIOTRACK_AA_SUBMIX",
                "車型/手機位置/情境填清楚（例：「Pixel + USB AA + 粗糙國道」）",
                "★ placement：phone 放 floor/seat（勿中控/手上）。好 coupling：accelMag>0.5 + roughness>0.5 + imuMicCoherence>0.4",
                "點「開始降噪」完成校正；開 ANC 應「安靜或低頻悶」而非電台靜電（極性/plant 已修）",
                "同一條粗糙路 50–70km/h、嚴格低音樂（<20% 或 off）",
                "每步「完成這步」套用 debug presets（ov 僅 log）",
                "本腳本驗證 HIGH_LAT_PRED_BANK + neural latent bank + #6 FDAF；#4b=A/B。高 lat mid 關閉=正常",
                "★ 推進方式：行駛步只累計「有效秒」（車速達標）；紅燈/怠速暫停不計，達標後自動下一步"
            ),
            durationSec = 20,
            requiresAncRunning = false,
            wallClockOnly = true,
            maxWallSec = 90,
            checklist = listOf(
                "USB有線AA",
                "wirelessAaSuspected=false",
                "ANC已跑",
                "placement=floor/seat",
                "開ANC無電台靜電"
            ),
            logPhases = listOf("audio_init", "calibration", "rpm_config", "running_snapshot", "wireless_aa_warning"),
            debugPresets = mapOf(
                "forceNormalMode" to true,
                "musicLowAncEnabled" to true,
                "userAncGain" to 1.0f
            )
        ),
        TestScriptStep(
            id = "tuning_4",
            title = "#4 musicLow 對比（mu=1.7, freeze=11）— ov 僅 log 標記，plant=measured",
            instructions = listOf(
                "系統已自動套用（mu=1.7…）；需累計約 50 秒「車速≥40」有效數據（紅燈不計）",
                "AA 高 lat：看 latencyStrategy、lowBandRumbleReduction、imuMicCoherence",
                "neuralLatentEnabled=true；bankMatchQuality 有值"
            ),
            durationSec = 50,
            minSpeedKmh = 40f,
            maxWallSec = 600,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf(
                "muMult=1.7", "ov=log-only", "musicLow=ON",
                "latencyStrategy 已記錄", "neuralLatentEnabled=true", "tier=STANDARD"
            ),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.7f,
                "freezeThreshold" to 11f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 120f,  // log-only tag (P0: does not drive plant)
                "tier" to "STANDARD"
            )
        ),
        TestScriptStep(
            id = "tuning_4b_Skoda",
            title = "#4b musicLow 延伸（mu=1.6）— stable A/B baseline，ov 僅 log",
            instructions = listOf(
                "系統已自動套用（mu=1.6…）；需 50 秒有效行駛（車速≥40，紅燈不計）",
                "A/B baseline：與後續 #6/#7 比 lowBandRumbleReduction"
            ),
            durationSec = 50,
            minSpeedKmh = 40f,
            maxWallSec = 600,
            suggestedTier = UserTier.STANDARD,
            checklist = listOf("muMult=1.6", "freezeTh=12", "ov=log-only", "musicLow=ON", "A/B baseline", "tier=STANDARD"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.6f,
                "freezeThreshold" to 12f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 150f,  // log-only tag
                "musicLowAncEnabled" to true,
                "tier" to "STANDARD"
            )
        ),
        TestScriptStep(
            id = "tuning_5_contrast",
            title = "#5 musicLow OFF 對比（mu=2.2, freeze=9, c=2, override=0） - 證明 musicLow 重要",
            instructions = listOf(
                "musicLow OFF 對比；需 45 秒有效行駛（車速≥40）",
                "比較 #4/#4b：有無 musicLow 時 rumble 差異"
            ),
            durationSec = 45,
            minSpeedKmh = 40f,
            maxWallSec = 600,
            suggestedTier = UserTier.LIGHT,
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
            title = "#6 road+FDAF 對比（mu=1.8；ov僅log；驗 fdafDelayless + mid 若啟用）",
            instructions = listOf(
                "需 50 秒有效行駛（車速≥45）；#6 FDAF",
                "fdafDelayless=true；高 lat mid 關=正常",
                "A/B vs #4b：lowBandRumbleReduction + fixedBankOut"
            ),
            durationSec = 50,
            minSpeedKmh = 45f,
            maxWallSec = 600,
            suggestedTier = UserTier.PRO,
            checklist = listOf(
                "muMult=1.8", "fdafDelayless=true", "speed>50",
                "lowBandRumble 有記錄", "無電台靜電", "tier=PRO"
            ),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing", "debug_presets_apply"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.8f,
                "freezeThreshold" to 10f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 110f,  // log-only
                "musicLowAncEnabled" to true,
                "forceNormalMode" to false,
                "tier" to "PRO"
            )
        ),
        // Iter4 + Subagent3 Extended #7 variant: strong road rumble (on top of #6): mu higher, ov=80 for maxC 300+ sim, stronger DSP mid focus (even music), classifier tweak for pure ROAD_MID even with music.
        // Goal: deeper rumble gains, dominant=ROAD_MID/LOW, effMidMu 0.6+, reduction -4~-6dB in 200-350 (mid contrib). Prioritize shift from MUSIC_BROAD.
        // Use with strict conditions (speed50+ rough low-music) + old prep/4/4b/5 UNCHANGED + #6 for A/B in one run. mid center 335Hz focus 300-350.
        // All changes minimal+guarded (roadMode + speed>28 + energy + musicLow).
        TestScriptStep(
            id = "tuning_7_strong_road",
            title = "#7 HIGH_LAT_PRED_BANK+neural bank 主驗（mu=2.05；USB AA）",
            instructions = listOf(
                "主驗：需 55 秒有效行駛（車速≥50；紅燈不計）",
                "latencyStrategy=HIGH_LAT_PRED_BANK；neuralLatent / bankMatch / imuMicCoherence",
                "KPI：lowBandRumbleReduction；聽感無電台靜電",
                "A/B vs #4b；fixedBankOut 行駛應非零"
            ),
            durationSec = 55,
            minSpeedKmh = 50f,
            maxWallSec = 720,
            suggestedTier = UserTier.PRO,
            checklist = listOf(
                "USB有線AA",
                "HIGH_LAT_PRED_BANK 或 CONSERVATIVE",
                "neuralLatentEnabled=true",
                "imuMicCoherence 已記錄",
                "bankMatchQuality 已記錄",
                "fixedBankOut 非零(行駛)",
                "fdafDelayless",
                "無電台靜電",
                "lowBandRumble 主 KPI",
                "speed>55 rough low-music",
                "tier=PRO",
                "vs #4b A/B"
            ),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing", "debug_presets_apply"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 2.05f,
                "freezeThreshold" to 9f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 80f,  // log-only
                "musicLowAncEnabled" to true,
                "forceNormalMode" to false,
                "tier" to "PRO"
            )
        ),
        TestScriptStep(
            id = "tuning_finish",
            title = "結束與匯出 + 準備下次迭代",
            instructions = listOf(
                "停止降噪",
                "用 GuidedTest finish「儲存到下載 / CarANC_Logs」或測試平台匯出",
                "把完整 log 傳回分析（或 pull-latest-log.ps1）",
                "scenario 註：tier / USB-AA / placement / speed / musicLow / 主觀靜電? / 主觀 rumble 0–10",
                "必查欄位：latencyStrategy, imuMicCoherence, bankMatchQuality, bankMatchCosine, neuralLatentEnabled, latent0/1/2, fixedBankOut, learnedBinCount, fdafDelayless, plantElectricalDelaySamples, lowBandRumbleReduction, reductionDb, antiNoiseDb",
                "PASS 條件：#7 無電台靜電 + lowBandRumbleReduction 常≥0 或主觀低頻有改善；FAIL：anti 大但 red 大負",
                "A/B：#4b vs #6 vs #7 的 lowBandRumbleReduction + 主觀",
                "下一輪：固定 USB AA + floor",
                "★ 約 10 秒壁鐘後自動結束 → 請存 log"
            ),
            durationSec = 10,
            requiresAncRunning = false,
            wallClockOnly = true,
            maxWallSec = 30,
            checklist = listOf(
                "腳本將自動結束",
                "結束後儲存 Log",
                "含 imuMicCoherence/bankMatch/neuralLatent"
            ),
            logPhases = listOf("test_script_complete")
        )
    )

    val monitoredLogPhases: List<String> = CarAncTestScript.monitoredLogPhases

    val monitoredSnapshotFields: List<String> = CarAncTestScript.monitoredSnapshotFields
}