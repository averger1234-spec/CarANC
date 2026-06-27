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
    //       "forceNormalMode", "musicLowAncEnabled", "userAncGain"
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
        "sirenOverride",
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
        "antiNoiseDb",
        "lmsUpdateCount",
        "lowBandLmsUpdateCount",
        "freezeBlocksRemaining",
        "processingMode"
    )
}

object CarRoadTuningScript {
    const val SCRIPT_ID = "car_road_tuning_v1"
    const val SCRIPT_NAME = "路噪 LMS 調校測試（v1·第一次實車推薦·粗糙路 40-70km/h 低音樂·5 組）"

    // 注意：根據 Skoda Octavia 2019 真實錄音頻譜分析（用戶提供）：
    // 主要路噪能量在 200-350 Hz（57.6%），峰值 ~305 Hz 會隨路段浮動 220-310 Hz。
    // 目前 150 Hz maxCancel 嚴重不足，只吃到邊緣 → 即使 LMS 活躍，reduction 仍極低。
    // 下一階段重點：壓低延遲（目標 <120ms 以推到 220-250 Hz）、在 roadMode + musicLow 時放寬 mid band 保護 (200-350Hz)。
    // 調校時優先觀察 midBand 貢獻與 200-350 Hz reduction。

    val steps: List<TestScriptStep> = listOf(
        TestScriptStep(
            id = "tuning_prep",
            title = "調校準備",
            instructions = listOf(
                "USB AA 連車機",
                "車型/手機位置/情境在「實車測試 Log」填寫清楚（例如「個人 Pixel + USB AA + 粗糙國道」）",
                "點「開始降噪」完成校正，狀態顯示「降噪中」",
                "準備好後進入同一条粗糙路面（40-70km/h 顛簸），全程無/低音樂",
                "接下來每步按「完成這步」前，系統會自動套用對應的 LMS 調校參數",
                "只需專心開車並在每步維持時間即可，最後一步匯出 log"
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("AA 已連", "ANC 已跑"),
            logPhases = listOf("audio_init", "calibration", "rpm_config", "running_snapshot"),
            debugPresets = mapOf(
                "forceNormalMode" to true,
                "musicLowAncEnabled" to true,
                "userAncGain" to 1.0f  // full aggressive output for perceived reduction (user feedback: still insensitive)
            )
        ),
        TestScriptStep(
            id = "tuning_1_baseline",
            title = "#1 Baseline（mu=1.0, freeze=15, c=3, override=0）",
            instructions = listOf(
                "系統已自動套用 Baseline 參數（mu=1.0 / freeze=15 / c=3 / override=0）",
                "進入/維持 40-70 km/h 同一段粗糙路面",
                "無/低音樂，維持 60-90 秒",
                "預期觀察重點：目前穩定度、lmsUpdate 是否正常上升、freeze 很少",
                "Skoda Octavia 2019 特別注意：真實路噪主力 200-350Hz（錄音證實），目前 150Hz 上限只能碰邊緣，觀察 mid band 是否有貢獻、reduction 是否仍低"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.0", "freezeTh=15", "consec=3", "override=0", "40-70km/h 粗路"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.2f,  // slightly more aggressive baseline for better perceived reduction (user feedback)
                "freezeThreshold" to 15f,
                "freezeConsec" to 3,
                "latencyOverrideMs" to 0f
            )
            // Skoda Octavia 2019 專用提醒：這台車路噪主力 ~305Hz (200-350Hz 區)，若這步 reduction 仍低，下一輪可試 latencyOverride=80~120 來推 maxCancel 接近 200Hz+ 做對照（即使實際延遲高，override 會影響 processor 內部 band 限制計算）
        ),
        TestScriptStep(
            id = "tuning_2",
            title = "#2 積極一點（mu=1.4, freeze=13, c=2, override=0）",
            instructions = listOf(
                "系統已自動套用參數（mu=1.4 / freeze=13 / c=2 / override=0）",
                "同一段粗糙路 40-70km/h，無/低音樂，維持 60-90 秒",
                "預期觀察重點：lmsUpdateCount 是否明顯比 #1 上升更快，低頻 rumble 感覺是否改善"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.4", "freezeTh=13", "consec=2", "override=0"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.4f,
                "freezeThreshold" to 13f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 0f
            )
        ),
        TestScriptStep(
            id = "tuning_3",
            title = "#3 更激進（mu=1.8, freeze=10, c=2, override=0）",
            instructions = listOf(
                "系統已自動套用參數（mu=1.8 / freeze=10 / c=2 / override=0）",
                "同一段路，60-90 秒",
                "預期觀察重點：lowBandLms 成長更快，reduction 在 rumble 時是否有感（配 spectrum 50-250Hz）。這是較激進設定，觀察 artifact。"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.8", "freezeTh=10", "consec=2", "override=0"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.8f,
                "freezeThreshold" to 10f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 0f
            )
        ),
        TestScriptStep(
            id = "tuning_4",
            title = "#4 強制低延遲 + musicLow 對比（Skoda 200-350Hz rumble 專用）（mu=1.7, freeze=11, c=2, override=120）",
            instructions = listOf(
                "系統已自動套用參數（mu=1.7 / freeze=11 / c=2 / override=70）",
                "同一段粗糙路 60-90 秒",
                "Skoda Octavia 2019 專用：這步用 override=120 強制推 maxCancel 接近 250Hz+，觀察 mid band (200-350Hz) 是否開始有貢獻（這是你錄音主力頻段）",
                "預期觀察重點：mid band 貢獻增加、reduction 在 200-350Hz 是否改善，比較 musicLow ON/OFF 感覺（此 step ON，記錄 scenario 註 musicLow=ON + \"Skoda mid-rumble test\"）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.7", "freezeTh=11", "consec=2", "override=120", "musicLow=ON", "Skoda 200-350Hz focus"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.7f,
                "freezeThreshold" to 11f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 120f
            )
        ),
        TestScriptStep(
            id = "tuning_5",
            title = "#5 極激進 + musicLow OFF 對比（mu=2.2, freeze=9, c=2, override=0）",
            instructions = listOf(
                "系統已自動套用參數（mu=2.2 / freeze=9 / c=2 / override=0） - musicLow OFF 對比",
                "同一段路 60-90 秒",
                "預期觀察重點：anti 更強但注意 artifact；比較前 step 有無 musicLow 時 rumble 降低（記錄 scenario musicLow=OFF）。這是最激進，觀察是否感覺到明顯降噪。"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=2.2", "freezeTh=9", "consec=2", "override=0", "musicLow=OFF"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 2.2f,
                "freezeThreshold" to 9f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 0f,
                "musicLowAncEnabled" to false
            )
        ),
        TestScriptStep(
            id = "tuning_finish",
            title = "結束與匯出（務必）",
            instructions = listOf(
                "停止降噪",
                "在「實車測試 Log」點「匯出 Log」",
                "把完整 log 傳回分析",
                "記錄 scenario 註明各 step 參數組合 + speed 範圍 + \"musicLow=ON/OFF\"",
                "建議配外部錄音 + spectrum（重點 50-250Hz rumble 能量下降）",
                "觀察重點：不同 debug 設定下 lowBandLms 更新率、freezeRem 頻率、reduction 在 rumble 主導時變化、主觀低頻 rumble 降低程度（0-10分）",
                "比較重點：lmsUpdate 上升速度、freeze 頻率、antiNoiseDb 負值、reductionDb、midBand 貢獻（尤其是 Skoda 200-350Hz 區）、是否出現 artifact"
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