package com.example.caranc

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.AncSessionLogger
import com.example.caranc.shared.AncState
import com.example.caranc.shared.AncTestPreferences
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.test.CarRoadTuningScript
import com.example.caranc.shared.test.GuidedTestController
import com.example.caranc.shared.test.GuidedTestState
import kotlinx.coroutines.delay

/**
 * Road-test guided flow hardened for complete captures:
 * - Force logging ON before start
 * - Preflight readiness shown on idle
 * - Auto-save fat log to Download when script completes (before stop ANC)
 * - Never stop ANC on finish step start
 */
@Composable
fun GuidedTestPanel(
    modifier: Modifier = Modifier,
    onRequestStartAnc: () -> Unit,
    onRequestStopAnc: () -> Unit
) {
    val context = LocalContext.current
    val sessionContext = remember { GlobalAncSessionContext }
    GuidedTestController.configure(sessionContext)

    val guidedState by GuidedTestController.state.collectAsState()
    val ancState by sessionContext.stateManager.state.collectAsState()
    val ancRunning = ancState !is AncState.Stopped
    var autoSavedPath by remember { mutableStateOf<String?>(null) }
    var lastAutoSaveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        GuidedTestController.eventSink = { phase, fields ->
            AncSessionLogger.log(phase = phase, fields = fields)

            if (phase == "debug_presets_apply") {
                fields["forceNormalMode"]?.let { v ->
                    if (v is Boolean) AncTestPreferences.setForceNormalMode(context, v)
                }
                fields["musicLowAncEnabled"]?.let { v ->
                    if (v is Boolean) AncTestPreferences.setMusicLowAncEnabled(context, v)
                }
                fields["userAncGain"]?.let { v ->
                    if (v is Number) AncTestPreferences.setUserAncGain(context, v.toFloat())
                }
                fields["lmsMuMultiplier"]?.let { v ->
                    if (v is Number) AncTestPreferences.setDebugLmsMuMultiplier(context, v.toFloat())
                }
                fields["freezeThreshold"]?.let { v ->
                    if (v is Number) AncTestPreferences.setDebugFreezeThreshold(context, v.toFloat())
                }
                fields["freezeConsec"]?.let { v ->
                    if (v is Number) AncTestPreferences.setDebugFreezeConsecutive(context, v.toInt())
                }
                fields["latencyOverrideMs"]?.let { v ->
                    if (v is Number) AncTestPreferences.setDebugLatencyOverrideMs(context, v.toFloat())
                }
                fields["debugLeakage"]?.let { v ->
                    if (v is Number) AncTestPreferences.setDebugLeakage(context, v.toFloat())
                }
            }
        }
    }

    // 1 Hz tick → valid-drive auto-advance
    LaunchedEffect(guidedState.active, guidedState.finished) {
        if (!guidedState.active || guidedState.finished) return@LaunchedEffect
        while (GuidedTestController.state.value.active && !GuidedTestController.state.value.finished) {
            delay(1000)
            GuidedTestController.tickSecond()
        }
    }

    LaunchedEffect(guidedState.active, guidedState.finished, guidedState.stepIndex) {
        if (!guidedState.active || guidedState.finished) return@LaunchedEffect
        while (GuidedTestController.state.value.active && !GuidedTestController.state.value.finished) {
            delay(5000)
            GuidedTestController.logStepSnapshot()
        }
    }

    // Auto-start ANC when script begins (not on finish)
    LaunchedEffect(guidedState.active, guidedState.finished, guidedState.currentStep?.id, ancRunning) {
        val onFinish = guidedState.currentStep?.id.orEmpty().contains("finish", ignoreCase = true)
        if (guidedState.active && !guidedState.finished && !onFinish && !ancRunning) {
            onRequestStartAnc()
        }
    }

    // On script complete: AUTO-SAVE first (while service still up), then stop ANC.
    LaunchedEffect(guidedState.finished) {
        if (!guidedState.finished) return@LaunchedEffect
        autoSavedPath = null
        lastAutoSaveError = null
        delay(1200) // let test_script_complete + writer flush
        val path = TestLogExporter.saveLatestLogToDownloads(context)
        if (path != null) {
            autoSavedPath = path
            AncSessionLogger.log(
                phase = "test_log_auto_saved",
                fields = mapOf("path" to path, "bytes" to (TestLogExporter.listSessionLogs(context).firstOrNull()?.length() ?: 0))
            )
            Toast.makeText(context, "已自動儲存 log：\n$path", Toast.LENGTH_LONG).show()
        } else {
            lastAutoSaveError = "自動存檔失敗 — 請手動按儲存"
            Toast.makeText(context, lastAutoSaveError, Toast.LENGTH_LONG).show()
        }
        delay(1500)
        if (sessionContext.stateManager.state.value !is AncState.Stopped) {
            onRequestStopAnc()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("路噪調校測試（完整擷取）", style = MaterialTheme.typography.titleMedium)
            Text(
                "有效行駛推進 · 結束自動存 MB log · 不中途砍 service",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                guidedState.finished -> FinishedScriptView(
                    autoSavedPath = autoSavedPath,
                    autoSaveError = lastAutoSaveError,
                    onExport = {
                        val exported = TestLogExporter.shareLatestLog(context)
                        Toast.makeText(
                            context,
                            if (exported) "已開啟分享" else "沒有可匯出的 log",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onSaveToDownloads = {
                        val path = TestLogExporter.saveLatestLogToDownloads(context)
                        val n = TestLogExporter.listSessionLogs(context).size
                        Toast.makeText(
                            context,
                            if (path != null) "已儲存：$path\n(App 內 $n 份 session)" else "儲存失敗",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onRestart = {
                        autoSavedPath = null
                        lastAutoSaveError = null
                        startRoadTest(context, onRequestStartAnc)
                    }
                )
                guidedState.active -> ActiveScriptView(
                    state = guidedState,
                    sessionContext = sessionContext,
                    ancRunning = ancRunning,
                    onAbort = { GuidedTestController.abort() }
                )
                else -> IdleScriptView(
                    sessionContext = sessionContext,
                    loggingOn = AncTestPreferences.isLoggingEnabled(context),
                    consentOk = !sessionContext.entitlementManager.requiresSafetyConsent(),
                    onStartTuning = {
                        startRoadTest(context, onRequestStartAnc)
                    }
                )
            }
        }
    }
}

private fun startRoadTest(context: android.content.Context, onRequestStartAnc: () -> Unit) {
    // Hard guarantees before any drive time is spent
    AncTestPreferences.setLoggingEnabled(context, true)
    AncSessionLogger.init(context)
    if (GlobalAncSessionContext.entitlementManager.requiresSafetyConsent()) {
        Toast.makeText(context, "請先接受安全聲明，再開始測試", Toast.LENGTH_LONG).show()
        return
    }
    onRequestStartAnc()
    GuidedTestController.start(
        CarRoadTuningScript.steps,
        CarRoadTuningScript.SCRIPT_ID,
        CarRoadTuningScript.SCRIPT_NAME,
        autoAdvance = true
    )
}

@Composable
private fun IdleScriptView(
    sessionContext: AncSessionContext,
    loggingOn: Boolean,
    consentOk: Boolean,
    onStartTuning: () -> Unit
) {
    val ready = loggingOn && consentOk
    Text(
        CarRoadTuningScript.SCRIPT_NAME,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Text("路測前檢查（不通過不要出發）：", style = MaterialTheme.typography.labelLarge)
    Text(
        "• Log 記錄：${if (loggingOn) "ON ✓" else "OFF ✗（開始會強制開啟）"}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "• 安全聲明：${if (consentOk) "已接受 ✓" else "未接受 ✗"}",
        style = MaterialTheme.typography.bodySmall,
        color = if (consentOk) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.error
    )
    Text(
        "• USB Android Auto 連上車後再按開始（否則可能無喇叭輸出）",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "• 手機固定 floor/seat；進度只計車速達標秒數（紅燈不計）",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "• 結束會自動存 Download/CarANC_Logs（獨立檔名）；無需手動也可再按一次",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(modifier = Modifier.height(10.dp))
    Button(
        onClick = onStartTuning,
        modifier = Modifier.fillMaxWidth(),
        enabled = consentOk
    ) {
        Text(if (ready) "開始完整路測" else "開始（請先接受安全聲明）")
    }
}

@Composable
private fun ActiveScriptView(
    state: GuidedTestState,
    sessionContext: AncSessionContext,
    ancRunning: Boolean,
    onAbort: () -> Unit
) {
    val step = state.currentStep ?: return
    val progress = (state.stepIndex + 1).toFloat() / state.totalSteps.coerceAtLeast(1)
    val stepProgress = if (state.targetValidSec > 0) {
        (state.validSec.toFloat() / state.targetValidSec).coerceIn(0f, 1f)
    } else 0f
    val estimatedLatencyMs by sessionContext.stateManager.estimatedLatencyMs.collectAsState()
    val maxCancelHz by sessionContext.stateManager.maxCancelFrequencyHz.collectAsState()
    val rawDb by sessionContext.stateManager.rawDb.collectAsState()
    val cancelledDb by sessionContext.stateManager.cancelledDb.collectAsState()
    val reductionDb = (rawDb - cancelledDb).coerceAtLeast(0f)

    Text(
        "步驟 ${state.stepIndex + 1} / ${state.totalSteps}",
        style = MaterialTheme.typography.labelLarge
    )
    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(4.dp))
    LinearProgressIndicator(progress = stepProgress, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))

    Text(step.title, style = MaterialTheme.typography.titleSmall)

    if (state.wallClockOnly) {
        Text(
            "準備/結束：${state.wallElapsedSec}/${state.targetValidSec} 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(
            "有效 ${state.validSec}/${state.targetValidSec}s · 還差 ${state.remainingValidSec}s · ≥${"%.0f".format(state.minSpeedKmh)} km/h",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            if (state.collectingNow) {
                "● 收集中 ${"%.0f".format(state.currentSpeedKmh)} km/h"
            } else {
                "○ 暫停：${state.pauseReason.ifBlank { "等待車速" }}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.collectingNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }

    if (!ancRunning) {
        Text("⚠ ANC 未運行 — 正在重試啟動（喇叭可能無聲）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    } else if (estimatedLatencyMs > 0f) {
        Text(
            "ANC ON · 延遲 ${"%.0f".format(estimatedLatencyMs)} ms · ≤${"%.0f".format(maxCancelHz)} Hz · red ${"%.1f".format(reductionDb)}",
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
        Text("中止測試")
    }
}

@Composable
private fun FinishedScriptView(
    autoSavedPath: String?,
    autoSaveError: String?,
    onExport: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onRestart: () -> Unit
) {
    Text("腳本已完整結束。", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(6.dp))
    when {
        autoSavedPath != null -> Text(
            "已自動存檔：\n$autoSavedPath",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        autoSaveError != null -> Text(
            autoSaveError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        else -> Text("正在自動存檔…", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onExport, modifier = Modifier.weight(1f)) {
            Text("分享 Log")
        }
        Button(onClick = onSaveToDownloads, modifier = Modifier.weight(1f)) {
            Text("再存一份")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
        Text("再測一次")
    }
}
