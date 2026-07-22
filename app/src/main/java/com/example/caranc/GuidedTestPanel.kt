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
import androidx.compose.runtime.remember
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

    // 1 Hz tick → timed auto-advance
    LaunchedEffect(guidedState.active, guidedState.finished) {
        if (!guidedState.active || guidedState.finished) return@LaunchedEffect
        while (GuidedTestController.state.value.active && !GuidedTestController.state.value.finished) {
            delay(1000)
            GuidedTestController.tickSecond()
        }
    }

    // Snapshot every 5s while running
    LaunchedEffect(guidedState.active, guidedState.finished, guidedState.stepIndex) {
        if (!guidedState.active || guidedState.finished) return@LaunchedEffect
        while (GuidedTestController.state.value.active && !GuidedTestController.state.value.finished) {
            delay(5000)
            GuidedTestController.logStepSnapshot()
        }
    }

    // Auto-start ANC when script begins (not on finish step)
    LaunchedEffect(guidedState.active, guidedState.finished, guidedState.currentStep?.id, ancRunning) {
        val onFinish = guidedState.currentStep?.id.orEmpty().contains("finish", ignoreCase = true)
        if (guidedState.active && !guidedState.finished && !onFinish && !ancRunning) {
            onRequestStartAnc()
        }
    }

    // IMPORTANT: do NOT stop ANC when finish step *starts* — that destroyed the service
    // (session_end service_destroyed) and cut the log before test_script_complete.
    // Stop only after the whole script is finished (finish wall seconds completed).
    LaunchedEffect(guidedState.finished, ancRunning) {
        if (guidedState.finished && ancRunning) {
            delay(800) // allow test_script_complete + last snapshot to flush
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
            Text("路噪調校測試", style = MaterialTheme.typography.titleMedium)
            Text(
                "含今日算法。推進靠「有效行駛秒數」（車速達標才計）；紅燈/怠速暫停。開始 + 存 log 即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                guidedState.finished -> FinishedScriptView(
                    onExport = {
                        val exported = TestLogExporter.shareLatestLog(context)
                        val msg = if (exported) "已開啟分享選單" else "沒有可匯出的 log"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    onSaveToDownloads = {
                        val path = TestLogExporter.saveLatestLogToDownloads(context)
                        val n = TestLogExporter.listSessionLogs(context).size
                        val msg = if (path != null) {
                            "已儲存到：$path\n（App 內 session 共 $n 份；下載夾每次存成獨立檔名）"
                        } else {
                            "儲存失敗（可能尚無 session log）"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    },
                    onRestart = {
                        GuidedTestController.start(
                            CarRoadTuningScript.steps,
                            CarRoadTuningScript.SCRIPT_ID,
                            CarRoadTuningScript.SCRIPT_NAME,
                            autoAdvance = true
                        )
                    }
                )
                guidedState.active -> ActiveScriptView(
                    state = guidedState,
                    sessionContext = sessionContext,
                    ancRunning = ancRunning,
                    onAbort = { GuidedTestController.abort() }
                )
                else -> IdleScriptView(
                    onStartTuning = {
                        GuidedTestController.start(
                            CarRoadTuningScript.steps,
                            CarRoadTuningScript.SCRIPT_ID,
                            CarRoadTuningScript.SCRIPT_NAME,
                            autoAdvance = true
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun IdleScriptView(
    onStartTuning: () -> Unit
) {
    Text(
        CarRoadTuningScript.SCRIPT_NAME,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        "今日變更已含：極性/FxLMS、HIGH_LAT_PRED_BANK、neural bank、coherence/bankMatch log。",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "流程：開始 → 自動 ANC → 各步累計「有效行駛秒」（#4–#5 車速≥40、#6≥45、#7≥50）→ 達標自動下一步 → 結束存 log。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        "紅燈/塞車時進度暫停（不計秒）。需約 ${approxValidSec()} 秒有效行駛 + prep/finish。可中止。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer
    )
    Spacer(modifier = Modifier.height(10.dp))
    Button(onClick = onStartTuning, modifier = Modifier.fillMaxWidth()) {
        Text("開始（有效行駛自動推進）")
    }
}

private fun approxValidSec(): Int {
    // prep wall 20 + 50+50+45+50+55 valid + finish 10
    return 50 + 50 + 45 + 50 + 55
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
        "步驟 ${state.stepIndex + 1} / ${state.totalSteps} · 有效行駛推進",
        style = MaterialTheme.typography.labelLarge
    )
    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(4.dp))
    LinearProgressIndicator(progress = stepProgress, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))

    Text(step.title, style = MaterialTheme.typography.titleSmall)
    step.instructions.take(2).forEachIndexed { index, line ->
        Text("${index + 1}. $line", style = MaterialTheme.typography.bodySmall)
    }

    Spacer(modifier = Modifier.height(8.dp))
    if (state.wallClockOnly) {
        Text(
            "準備/結束：壁鐘 ${state.wallElapsedSec}/${state.targetValidSec} 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(
            "有效數據 ${state.validSec}/${state.targetValidSec} 秒 · 還差 ${state.remainingValidSec} 秒" +
                " · 門檻 ≥${"%.0f".format(state.minSpeedKmh)} km/h",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            if (state.collectingNow) {
                "● 收集中 · 車速 ${"%.0f".format(state.currentSpeedKmh)} km/h · 壁鐘已過 ${state.wallElapsedSec}s"
            } else {
                "○ 暫停 · ${state.pauseReason.ifBlank { "等待達標車速" }} · 壁鐘 ${state.wallElapsedSec}s"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.collectingNow) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }

    if (ancRunning && estimatedLatencyMs > 0f) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "即時：延遲 ${"%.0f".format(estimatedLatencyMs)} ms · ≤${"%.0f".format(maxCancelHz)} Hz · " +
                "reduction ${"%.1f".format(reductionDb)} dB",
            style = MaterialTheme.typography.bodySmall
        )
    } else if (!ancRunning) {
        Text("正在啟動 ANC…", style = MaterialTheme.typography.bodySmall)
    }

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
        Text("中止測試")
    }
}

@Composable
private fun FinishedScriptView(onExport: () -> Unit, onSaveToDownloads: () -> Unit, onRestart: () -> Unit) {
    Text("腳本已跑完（有效行駛秒數達標自動結束）。請存 log。", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "建議立即「儲存到下載 / CarANC_Logs」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onExport, modifier = Modifier.weight(1f)) {
            Text("分享 Log")
        }
        Button(onClick = onSaveToDownloads, modifier = Modifier.weight(1f)) {
            Text("儲存到下載 / CarANC_Logs")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
        Text("再測一次")
    }
}
