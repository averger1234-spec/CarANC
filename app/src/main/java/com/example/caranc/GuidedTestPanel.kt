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
import androidx.compose.material3.OutlinedTextField
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
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.test.CarAncTestScript
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
    // Obtain from provided context for test panel (instead of direct singletons).
    // Configures the controller too. Scoped context recommended for testing/multi-session.
    val sessionContext = remember { GlobalAncSessionContext }
    GuidedTestController.configure(sessionContext)

    val guidedState by GuidedTestController.state.collectAsState()
    val ancState by sessionContext.stateManager.state.collectAsState()
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        GuidedTestController.eventSink = { phase, fields ->
            AncSessionLogger.log(phase = phase, fields = fields)
        }
    }

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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("引導測試腳本", style = MaterialTheme.typography.titleMedium)
            Text(
                "手動步進：每步完成後按「完成這步」。適合上下班分段測試。",
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
                    onRestart = {
                        note = ""
                        GuidedTestController.start()
                    }
                )
                guidedState.active -> ActiveScriptView(
                    state = guidedState,
                    sessionContext = sessionContext,
                    ancRunning = ancState !is AncState.Stopped,
                    note = note,
                    onNoteChange = { note = it },
                    onRequestStartAnc = onRequestStartAnc,
                    onRequestStopAnc = onRequestStopAnc,
                    onComplete = { GuidedTestController.completeCurrentStep(userNote = note).also { note = "" } },
                    onSkip = { GuidedTestController.skipCurrentStep() },
                    onAbort = { GuidedTestController.abort() }
                )
                else -> IdleScriptView(
                    onStart = {
                        note = ""
                        GuidedTestController.start()
                    }
                )
            }
        }
    }
}

@Composable
private fun IdleScriptView(onStart: () -> Unit) {
    Text(
        "${CarAncTestScript.SCRIPT_NAME}：延遲驗證 → MIMO → 怠速（引擎可選） → 市區 → 高速 → 音樂 → 通話 → 匯出",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "監控 ${CarAncTestScript.monitoredLogPhases.size} 種 log phase、${CarAncTestScript.monitoredSnapshotFields.size} 個 snapshot 欄位",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text("開始引導測試")
    }
}

@Composable
private fun ActiveScriptView(
    state: GuidedTestState,
    sessionContext: AncSessionContext,
    ancRunning: Boolean,
    note: String,
    onNoteChange: (String) -> Unit,
    onRequestStartAnc: () -> Unit,
    onRequestStopAnc: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onAbort: () -> Unit
) {
    val step = state.currentStep ?: return
    val progress = (state.stepIndex + 1).toFloat() / state.totalSteps.coerceAtLeast(1)
    val estimatedLatencyMs by sessionContext.stateManager.estimatedLatencyMs.collectAsState()
    val maxCancelHz by sessionContext.stateManager.maxCancelFrequencyHz.collectAsState()
    val latencyMidEnabled by sessionContext.stateManager.latencyMidEnabled.collectAsState()
    val latencyHighEnabled by sessionContext.stateManager.latencyHighEnabled.collectAsState()
    val rawDb by sessionContext.stateManager.rawDb.collectAsState()
    val cancelledDb by sessionContext.stateManager.cancelledDb.collectAsState()
    val reductionDb = (rawDb - cancelledDb).coerceAtLeast(0f)

    Text(
        "步驟 ${state.stepIndex + 1} / ${state.totalSteps}",
        style = MaterialTheme.typography.labelLarge
    )
    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.height(8.dp))

    Text(step.title, style = MaterialTheme.typography.titleSmall)
    step.instructions.forEachIndexed { index, line ->
        Text("${index + 1}. $line", style = MaterialTheme.typography.bodySmall)
    }

    if (step.checklist.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text("確認：${step.checklist.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
    }

    if (step.logPhases.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "監控 phase：${step.logPhases.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    if (ancRunning && estimatedLatencyMs > 0f) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "即時：延遲 ${"%.0f".format(estimatedLatencyMs)} ms · ≤${"%.0f".format(maxCancelHz)} Hz · " +
                "reduction ${"%.1f".format(reductionDb)} dB · " +
                "band[${if (latencyMidEnabled) "中" else "中×"}/${if (latencyHighEnabled) "高" else "高×"}]",
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        if (step.durationSec > 0) {
            "已進行 ${state.elapsedSec} 秒 · 建議觀察 ${step.durationSec} 秒（完成後按「完成這步」）"
        } else {
            "已進行 ${state.elapsedSec} 秒 · 完成後按「完成這步」"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = note,
        onValueChange = onNoteChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("備註（可選）") },
        placeholder = { Text("例：副駕前座椅下、有風噪") },
        singleLine = true
    )

    Spacer(modifier = Modifier.height(8.dp))

    if (step.requiresAncRunning && !ancRunning) {
        Button(onClick = onRequestStartAnc, modifier = Modifier.fillMaxWidth()) {
            Text("啟動降噪（此步驟需要）")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (!step.requiresAncRunning && step.id == "finish" && ancRunning) {
        OutlinedButton(onClick = onRequestStopAnc, modifier = Modifier.fillMaxWidth()) {
            Text("停止降噪（此步驟需要）")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onComplete, modifier = Modifier.weight(1f)) {
            Text("完成這步")
        }
        OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
            Text("跳過")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
        Text("中止測試")
    }
}

@Composable
private fun FinishedScriptView(onExport: () -> Unit, onRestart: () -> Unit) {
    Text("測試腳本已完成，log 已寫入 session。", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onExport, modifier = Modifier.weight(1f)) {
            Text("匯出 Log")
        }
        OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
            Text("再測一次")
        }
    }
}