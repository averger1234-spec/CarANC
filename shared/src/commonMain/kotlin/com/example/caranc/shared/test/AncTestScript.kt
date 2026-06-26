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
    val logPhases: List<String> = emptyList()
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

    val steps: List<TestScriptStep> = listOf(
        TestScriptStep(
            id = "tuning_prep",
            title = "調校準備（設定 TestLogPanel）",
            instructions = listOf(
                "USB AA 連車機，開啟「實車測試 Log」面板",
                "車型/手機位置/情境填寫清楚（例如「個人 Pixel + USB AA + 粗糙國道」）",
                "強制正常模式 = 開啟（重要，避免 AA 一直觸發 floor）",
                "音樂模式仍抗低頻路噪 = 開啟",
                "ANC 獨立強度 slider 設 0.75~0.9",
                "PRO 等級（付費）",
                "手動 RPM 留 0（除非想測引擎）",
                "點「開始降噪」完成校正，狀態顯示「降噪中」",
                "準備好後進入同一条粗糙路面（40-70km/h 顛簸），全程無/低音樂",
                "每組調校前先在 TestLogPanel 改參數，再按「完成這步」進入下一組"
            ),
            durationSec = 0,
            requiresAncRunning = false,
            checklist = listOf("AA 已連", "ANC 已跑", "forceNormal=ON", "musicLow=ON", "PRO"),
            logPhases = listOf("audio_init", "calibration", "rpm_config", "running_snapshot")
        ),
        TestScriptStep(
            id = "tuning_1_baseline",
            title = "#1 Baseline（mu=1.0, freeze=15, c=3, override=0）",
            instructions = listOf(
                "在 TestLogPanel 設定：",
                "  LMS 學習率倍率 = 1.0",
                "  凍結門檻 = 15",
                "  凍結連續次數 = 3",
                "  延遲覆蓋測試 = 留空或 0",
                "進入/維持 40-70 km/h 同一段粗糙路面",
                "無/低音樂，維持 60-90 秒",
                "預期觀察重點：目前穩定度、lmsUpdate 是否正常上升、freeze 很少"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.0", "freezeTh=15", "consec=3", "override=0", "40-70km/h 粗路"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing")
        ),
        TestScriptStep(
            id = "tuning_2",
            title = "#2 積極一點（mu=1.3, freeze=14, c=3, override=0）",
            instructions = listOf(
                "TestLogPanel 改為：",
                "  LMS 學習率倍率 = 1.3",
                "  凍結門檻 = 14",
                "  凍結連續次數 = 3",
                "  延遲覆蓋 = 0",
                "同一段粗糙路 40-70km/h，無/低音樂，維持 60-90 秒",
                "預期觀察重點：lmsUpdateCount 是否明顯比 #1 上升更快"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.3", "freezeTh=14", "consec=3", "override=0"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing")
        ),
        TestScriptStep(
            id = "tuning_3",
            title = "#3 更積極（mu=1.5, freeze=12, c=2, override=0）",
            instructions = listOf(
                "TestLogPanel 改為：",
                "  LMS 學習率倍率 = 1.5",
                "  凍結門檻 = 12",
                "  凍結連續次數 = 2",
                "  延遲覆蓋 = 0",
                "同一段路，60-90 秒",
                "預期觀察重點：較積極適應，但觀察 freezeBlocksRemaining 是否變得太頻繁"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.5", "freezeTh=12", "consec=2", "override=0"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing")
        ),
        TestScriptStep(
            id = "tuning_4",
            title = "#4 強制低延遲測試（mu=1.5, freeze=12, c=2, override=80）",
            instructions = listOf(
                "TestLogPanel 改為：",
                "  LMS 學習率倍率 = 1.5",
                "  凍結門檻 = 12",
                "  凍結連續次數 = 2",
                "  延遲覆蓋測試 = 80 （測試用，模擬較好延遲）",
                "同一段粗糙路 60-90 秒",
                "預期觀察重點：mid band 是否開始有貢獻（bandMuScale 提升、midGain >0、midEnabled）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.5", "freezeTh=12", "consec=2", "override=80"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing")
        ),
        TestScriptStep(
            id = "tuning_5",
            title = "#5 再激進（mu=1.8, freeze=13, c=3, override=0）",
            instructions = listOf(
                "TestLogPanel 改為：",
                "  LMS 學習率倍率 = 1.8",
                "  凍結門檻 = 13",
                "  凍結連續次數 = 3",
                "  延遲覆蓋 = 0",
                "同一段路 60-90 秒",
                "預期觀察重點：anti 更強，但注意是否有白噪（antiArtifactGain 是否壓制）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.8", "freezeTh=13", "consec=3", "override=0"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing")
        ),
        TestScriptStep(
            id = "tuning_finish",
            title = "結束與匯出（務必）",
            instructions = listOf(
                "停止降噪",
                "在「實車測試 Log」點「匯出 Log」",
                "重要：在 scenario 或 log 檔名記錄當次組合（例如 tuning#3_mu1.5_f12_c2_o0）",
                "把完整 log 傳回分析（至少 5 組都要有清楚的 running_snapshot）",
                "比較重點：lmsUpdate 上升速度、freeze 頻率、antiNoiseDb 負值、reductionDb、midBand 貢獻、是否出現 artifact"
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