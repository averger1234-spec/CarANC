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
        "effectiveMidMu",  // Iter2+: tracks actual midMu post road/music boost for rumble contrib diagnosis
        "antiNoiseDb",
        "lmsUpdateCount",
        "lowBandLmsUpdateCount",
        "freezeBlocksRemaining",
        "processingMode"
    )
}

object CarRoadTuningScript {
    const val SCRIPT_ID = "car_road_tuning_v1"
    const val SCRIPT_NAME = "Skoda 200-350Hz rumble 快速迭代測試（基於#4/#4b/#6/#7_ext Subagent3 Extended#7+iter variant 低延遲 + musicLow + mid-force + strong-road-pure-ROAD_MID-even-music 對比，iter1-4+ S3突破，跳過無用 baseline，4 次快速循環直達有感；用 old prep/4/4b/5 UNCHANGED 穩定 baseline + #6/#7 A/B 單輪比）"

    // 注意：根據 Skoda Octavia 2019 真實錄音頻譜分析（用戶提供）：
    // 主要路噪能量在 200-350 Hz（57.6%），峰值 ~305 Hz 會隨路段浮動 220-310 Hz。
    // 目前 150 Hz maxCancel 嚴重不足，只吃到邊緣 → 即使 LMS 活躍，reduction 仍極低。
    // 快速迭代版本（已 streamline）：移除無用早期 baseline（1/2/3，已知問題，不新增數據）。保留 old prep/4/4b/5 UNCHANGED 作為穩定 baseline；延伸 #6 midforce + #7 strong road rumble（iter4 + Subagent3 Extended #7 variant: stronger mid boost, classifier pure ROAD_MID even with music (speed>28+low+mid energy>=0.30), ov=80 maxC300+, mid error*1.28, centerHz 335 for 300-350 focus）。
    // 目標：每次跑完整 prep+4+4b+5+6+7+finish（更少步驟，更快），配外部錄音 + spectrum。迭代 後，effective latency 改善（override 推 maxCancel 250-340Hz+）、200-350Hz reduction 有感（-4~-6dB+，mid 貢獻 via effectiveMidMu 0.6+）。使用 old parts (prep/4/4b/5) 單輪 A/B 測試穩定 baseline 對比新 #6/#7 突破。所有新 boost 最小安全 guarded by roadMode + speed + energy。
    // 調校時優先觀察 midBand 貢獻、effectiveMidMu、maxC 與 200-350 Hz reduction。每步 scenario 註 "Skoda #4/#4b/#6/#7 經驗, iter X"。finish 強調下一輪優先重跑#4b/#6/#7 變體（old for A/B）。

    val steps: List<TestScriptStep> = listOf(
        TestScriptStep(
            id = "tuning_prep",
            title = "調校準備（快速）",
            instructions = listOf(
                "USB AA 連車機",
                "車型/手機位置/情境在「實車測試 Log」填寫清楚（例如「個人 Pixel + USB AA + 粗糙國道 iter4」）",
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
            id = "tuning_4b_Skoda",
            title = "#4b 延伸低延遲 musicLow 對比（基於#4 Skoda 經驗，override=150, mu=1.6 mid-focus）",
            instructions = listOf(
                "系統已自動套用參數（mu=1.6 / freeze=12 / c=2 / override=150） - 基於前次#4經驗進一步推延遲，針對 mid band 微調 mu",
                "同一段粗糙路 60-90 秒",
                "Skoda Octavia 2019 專用延伸：延續#4的200-350Hz rumble 對比，觀察是否能讓 reduction 在主力頻段更深、更穩（mid band 貢獻持續增加）。記錄 scenario 註 \"Skoda #4b延伸, musicLow=ON, based on #4 data\""
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.6", "freezeTh=12", "consec=2", "override=150", "musicLow=ON", "Skoda 200-350Hz #4延伸"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.6f,
                "freezeThreshold" to 12f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 150f,
                "musicLowAncEnabled" to true
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
        // Iter2-4 + S3: #6 mid-force contrast (roadMode forced + musicLow ON + mu for mid focus). Updated: stricter no-music instr + dominant force in DSP.
        // Use forceNormal=false to allow road detection, musicLow ON, override low for maxC, mu high.
        // Goal: isolate mid band rumble contrib vs #4/#4b; observe effectiveMidMu >0.5 , reduction on 250-350Hz. Use as A/B vs #4b stable (old unchanged).
        TestScriptStep(
            id = "tuning_6_midforce",
            title = "#6 mid-force road rumble 對比（mu=1.8, freeze=10, c=2, override=110, musicLow=ON, force road） - 驗證 mid 突破 (iter4+S3: stricter for dominant shift)",
            instructions = listOf(
                "系統已自動套用參數（mu=1.8 / freeze=10 / c=2 / override=110 / musicLow=ON）",
                "切到**粗糙路面，嚴格維持 speed 50+ km/h**（>30 且 rough 讓 GPS+energy 觸發 roadMode + classifier ROAD_MID/LOW） 60-90秒；**全程低音樂或無音樂（vol<20%）** 避免 high energy 讓 MUSIC_BROAD 勝出",
                "Skoda Octavia 專用：強制 mid 貢獻，觀察 effectiveMidMu 是否 >0.5 (iter4 目標)，midOut 對 200-350Hz rumble 的貢獻，比較 reductionDb 與 band ratios；與前#4b A/B（old baseline）",
                "記錄 scenario \"Skoda #6 midforce iter4, roadMode active, effectiveMidMu=XX dominant=ROAD_MID speed=XX musicLow=ON music=low\"",
                "★ 關鍵確認（running_snapshot 重點）：guidedTestStepId=tuning_6_midforce + effectiveMidMu>0.5 + dominant=ROAD_MID/ROAD_LOW + midBandMuScale>0.5 + reduction >2dB improvement；低速/高音樂仍會 MUSIC_BROAD（effMidMu=0，無效此步，改用#4b baseline）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=1.8", "freezeTh=10", "consec=2", "override=110", "musicLow=ON", "roadMode active", "effectiveMidMu>0.5", "speed>50 rough low-music"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing", "debug_presets_apply"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 1.8f,
                "freezeThreshold" to 10f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 110f,
                "musicLowAncEnabled" to true,
                "forceNormalMode" to false
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
                "**切到粗糙路面，嚴格維持 50+ km/h 粗糙顛簸路 60-90秒**（確保 speed>28 + low/mid energy ratio >0.30 觸發 classifier pure ROAD_MID，即使 music=true；guarded by roadMode+speed+energy）",
                "Skoda Octavia 專用：#7 目標是 dominant shift 至 rumble + bigger mid 貢獻。觀察 effectiveMidMu 0.6+、midScale high、maxC 300-380Hz、200-350Hz reduction -4~-6dB（比#6 更深）；比較 vs old #4b baseline + #6 A/B（old parts 穩定不變）",
                "記錄 scenario \"Skoda #7_ext strong S3 iter4, roadMode active, effectiveMidMu=XX dominant=ROAD_MID speed=XX music=low-strict reduction=XX\"",
                "★ 關鍵確認（running_snapshot 重點）：guidedTestStepId=tuning_7_strong_road + effectiveMidMu>0.6 + dominant=ROAD_MID + midBandMuScale>0.6 + reductionDb>3 + maxC>300 + lmsUpdateCount high；若 music 強仍 MUSIC_BROAD 則無效（退回用#4b/#6 baseline A/B）"
            ),
            durationSec = 75,
            suggestedTier = UserTier.PRO,
            checklist = listOf("muMult=2.05", "freezeTh=9", "consec=2", "override=80", "musicLow=ON", "roadMode active", "effectiveMidMu>0.6", "speed>50 rough low-music", "compare vs old #4b/#6 A/B"),
            logPhases = listOf("running_snapshot", "test_step_snapshot", "perf_timing", "debug_presets_apply"),
            debugPresets = mapOf(
                "lmsMuMultiplier" to 2.05f,
                "freezeThreshold" to 9f,
                "freezeConsec" to 2,
                "latencyOverrideMs" to 80f,
                "musicLowAncEnabled" to true,
                "forceNormalMode" to false
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
                "延伸重點（Subagent3 Extended #7 + iter4）：記錄哪組讓 effectiveMidMu >0.6 且 200-350Hz reduction 最深（觀察 midBandMuScale + effectiveMidMu + bandMidRatio + reduction + dominant=ROAD_MID）。**單輪內 A/B 比較 old prep/4/4b/5 (穩定 baseline 不變) vs #6 (midforce) vs #7_ext (strong road pure-ROAD_MID even music, ov=80, stronger guarded mid boost)**（跳過無用早期 baseline）。累積 4 次快速腳本後，比較 effective maxCancel 與 rumble 有感程度。目標快速迭代到延遲改善、降噪有感。#7_ext 專測更強 road rumble + mid 貢獻（即使 music），old parts 作為穩定對照。下一輪優先重跑#4b/#6/#7_ext 變體 + 外部 spectrum 驗證 red -4~-6dB。"
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