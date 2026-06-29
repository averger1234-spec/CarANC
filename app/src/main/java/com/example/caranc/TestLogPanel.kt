package com.example.caranc

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.caranc.shared.AncTestEnvironment
import com.example.caranc.shared.AncTestPreferences

@Composable
fun TestLogPanel(
    modifier: Modifier = Modifier,
    onEnvironmentChanged: (AncTestEnvironment) -> Unit = {}
) {
    val context = LocalContext.current
    var loggingEnabled by remember {
        mutableStateOf(AncTestPreferences.isLoggingEnabled(context))
    }
    var vehicleModel by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf("") }
    var phonePlacement by remember { mutableStateOf("") }
    var connectionType by remember { mutableStateOf("") }
    var manualRpm by remember { mutableStateOf("") }
    var obdAddress by remember { mutableStateOf("") }
    var forceNormalMode by remember { mutableStateOf(false) }
    var musicLowAnc by remember { mutableStateOf(true) }
    var userAncGain by remember { mutableStateOf(1f) }
    var lmsMuMult by remember { mutableStateOf(1f) }
    var freezeThreshold by remember { mutableStateOf(15f) }
    var freezeConsec by remember { mutableStateOf(3) }
    var latencyOverrideMs by remember { mutableStateOf(0f) }
    var debugLeakage by remember { mutableStateOf(0.9998f) }  // Leaky LMS alpha for A/B stability (0.9998 vs 0.9995)
    var useNativeLow by remember { mutableStateOf(false) }  // native low band switch (enable when port ready)
    var latestLogName by remember { mutableStateOf(TestLogExporter.latestLogFileName(context)) }

    // UI improvement: collapse advanced tuning by default (guided script auto-applies most of them)
    // Privacy section grouped and clearly labeled to avoid long intimidating list of fields
    var showAdvancedTuning by remember { mutableStateOf(false) }

    fun persistEnvironment() {
        val environment = AncTestEnvironment(
            vehicleModel = vehicleModel.trim(),
            scenario = scenario.trim(),
            phonePlacement = phonePlacement.trim(),
            connectionType = connectionType.trim()
        )
        AncTestPreferences.saveEnvironment(context, environment)
        onEnvironmentChanged(environment)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val environment = AncTestPreferences.getEnvironment(context)
        vehicleModel = environment.vehicleModel
        scenario = environment.scenario
        phonePlacement = environment.phonePlacement
        connectionType = environment.connectionType
        manualRpm = AncTestPreferences.getManualTestRpm(context).let { rpm ->
            if (rpm > 0f) rpm.toInt().toString() else ""
        }
        obdAddress = AncTestPreferences.getObdDeviceAddress(context)
        forceNormalMode = AncTestPreferences.isForceNormalMode(context)
        musicLowAnc = AncTestPreferences.isMusicLowAncEnabled(context)
        userAncGain = AncTestPreferences.getUserAncGain(context)
        lmsMuMult = AncTestPreferences.getDebugLmsMuMultiplier(context)
        freezeThreshold = AncTestPreferences.getDebugFreezeThreshold(context)
        freezeConsec = AncTestPreferences.getDebugFreezeConsecutive(context)
        latencyOverrideMs = AncTestPreferences.getDebugLatencyOverrideMs(context)
        debugLeakage = AncTestPreferences.getDebugLeakage(context)
        useNativeLow = AncTestPreferences.isDebugUseNativeLowBand(context)  // for native low band switch point toggle
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("實車測試 Log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "每次啟動降噪會寫入 JSONL session log（含 latency_optimization_applied、running_snapshot）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = loggingEnabled,
                    onCheckedChange = {
                        loggingEnabled = it
                        AncTestPreferences.setLoggingEnabled(context, it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Privacy / metadata section - grouped and labeled to be less intimidating and clearly explain data use
            Text("測試情境記錄（隱私保護）", style = MaterialTheme.typography.titleSmall)
            Text(
                "以下資訊只會寫入你本機匯出的 JSONL log 檔，方便你之後自己分析或分享給開發者。不會上傳任何雲端或第三方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = vehicleModel,
                onValueChange = {
                    vehicleModel = it
                    persistEnvironment()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("車型") },
                placeholder = { Text("例：Toyota Camry 2022") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = scenario,
                onValueChange = {
                    scenario = it
                    persistEnvironment()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("測試場景") },
                placeholder = { Text("例：怠速 / 60km/h / 音樂開啟") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = phonePlacement,
                onValueChange = {
                    phonePlacement = it
                    persistEnvironment()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手機位置") },
                placeholder = { Text("例：中控台杯架 / 副駕前方") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = connectionType,
                onValueChange = {
                    connectionType = it
                    persistEnvironment()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("連接方式") },
                placeholder = { Text("例：USB Android Auto / 無線 AA / 本機") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = forceNormalMode,
                    onCheckedChange = {
                        forceNormalMode = it
                        AncTestPreferences.setForceNormalMode(context, it)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "強制正常模式（忽略音樂/通話偵測，用於比較 LIGHT/中/重 tier 差異，AA 環境推薦開啟）",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = musicLowAnc,
                    onCheckedChange = {
                        musicLowAnc = it
                        AncTestPreferences.setMusicLowAncEnabled(context, it)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "音樂模式仍抗低頻路噪（低頻持續 ANC，中高頻保護音樂）",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("ANC 獨立強度 (0~1，獨立於系統音樂/語音音量)：${"%.2f".format(userAncGain)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = userAncGain,
                onValueChange = {
                    userAncGain = it
                    AncTestPreferences.setUserAncGain(context, it)
                },
                valueRange = 0f..1f,
                steps = 99
            )
            Text("${"%.2f".format(userAncGain)}", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(12.dp))

            // Advanced tuning collapsed by default - makes the panel much shorter and less overwhelming by default
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("進階 LMS 調校（預設收合）", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = showAdvancedTuning,
                    onCheckedChange = { showAdvancedTuning = it }
                )
            }
            Text(
                "注意：使用「路噪調校測試」引導腳本時，這些參數會由腳本自動套用。你只需要按「完成這步」和最後匯出 log。手動調整僅用於自訂實驗。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (showAdvancedTuning) {
                // Legacy fields moved here (OBD is removed, only for manual RPM experiments)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualRpm,
                    onValueChange = {
                        manualRpm = it.filter { ch -> ch.isDigit() }
                        val rpm = manualRpm.toFloatOrNull() ?: 0f
                        AncTestPreferences.setManualTestRpm(context, rpm)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手動測試 RPM（怠速，僅 legacy）") },
                    placeholder = { Text("例：800") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = obdAddress,
                    onValueChange = {
                        obdAddress = it.uppercase()
                        AncTestPreferences.setObdDeviceAddress(context, obdAddress)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手動 RPM 藍牙位址（已移除自動 OBD）") },
                    placeholder = { Text("例：00:1A:7D:DA:71:13") },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("進階 LMS 調校（類似 PID 學習率實驗）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "LMS mu 是學習率。提高 → 適應更快，但高延遲易不穩。freeze 保護它。建議觀察 log 裡 lowBandLms / freezeRem / reduction 的變化。",
                    style = MaterialTheme.typography.bodySmall
                )

                Text("LMS 學習率倍率 (0.1~3.0)：${"%.2f".format(lmsMuMult)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = lmsMuMult,
                    onValueChange = {
                        lmsMuMult = it
                        AncTestPreferences.setDebugLmsMuMultiplier(context, it)
                    },
                    valueRange = 0.1f..3.0f,
                    steps = 28
                )

                Text("Leaky LMS 洩漏因子 α (0.99~0.99999，越低越保守防發散/clicking with 高mu)：${"%.5f".format(debugLeakage)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = debugLeakage,
                    onValueChange = {
                        debugLeakage = it
                        AncTestPreferences.setDebugLeakage(context, it)
                    },
                    valueRange = 0.99f..0.99999f,
                    steps = 99
                )

                Text("凍結門檻 (能量比，預設15；越高越不易凍 LMS)：${"%.1f".format(freezeThreshold)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = freezeThreshold,
                    onValueChange = {
                        freezeThreshold = it
                        AncTestPreferences.setDebugFreezeThreshold(context, it)
                    },
                    valueRange = 8f..25f,
                    steps = 16
                )

                Text("凍結連續次數 (1~5，預設3)：${freezeConsec}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = freezeConsec.toFloat(),
                    onValueChange = {
                        freezeConsec = it.toInt()
                        AncTestPreferences.setDebugFreezeConsecutive(context, freezeConsec)
                    },
                    valueRange = 1f..5f,
                    steps = 3
                )

                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = if (latencyOverrideMs > 0f) latencyOverrideMs.toInt().toString() else "",
                    onValueChange = {
                        val v = it.filter { ch -> ch.isDigit() }.toFloatOrNull() ?: 0f
                        latencyOverrideMs = v
                        AncTestPreferences.setDebugLatencyOverrideMs(context, v)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("延遲覆蓋測試 (ms，0=自動；設 60-80 可手動模擬較好延遲看 mid/high band 開啟)") },
                    placeholder = { Text("0 或 60~120") },
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("開啟 Native Low Band (stub 切換點，正式 port 後有效)：", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = useNativeLow, onCheckedChange = {
                        useNativeLow = it
                        AncTestPreferences.setDebugUseNativeLowBand(context, it)
                    })
                }
                Text(
                    "提示：高 mu + 低門檻 容易看到 freeze 頻繁，注意 log 裡 freezeBlocksRemaining。改參數後建議重啟 ANC 讓 LMS 重新適應。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = latestLogName?.let { "最新 log：$it" } ?: "尚無 log 檔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "引導測試會額外寫入 test_step_*，並在 snapshot 附帶延遲/頻帶欄位",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 主要匯出按鈕 - 針對快速迭代優化
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val exported = TestLogExporter.shareLatestLog(context)
                        latestLogName = TestLogExporter.latestLogFileName(context)
                        val message = if (exported) "已開啟分享選單（可直接選 Google Drive）" else "沒有可匯出的 log 檔"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("分享 Log（選 Google Drive / Email 等）")
                }

                Button(
                    onClick = {
                        val savedPath = TestLogExporter.saveLatestLogToDownloads(context)
                        latestLogName = TestLogExporter.latestLogFileName(context)
                        val message = if (savedPath != null) {
                            "已儲存到：${savedPath}\n請用檔案總管或 Drive App 找到 CarANC_Logs 資料夾上傳"
                        } else "儲存失敗"
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("儲存到「下載 / CarANC_Logs」（推薦用來同步 Google Drive）")
                }

                OutlinedButton(
                    onClick = { latestLogName = TestLogExporter.latestLogFileName(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重新整理最新 log 名稱")
                }
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "開發者提示：推薦啟用「無線偵錯」，連上 adb 後可用 scripts/pull-latest-log.ps1 直接拉 log 到電腦，不用手動上傳 Drive。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}