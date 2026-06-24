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
    var latestLogName by remember { mutableStateOf(TestLogExporter.latestLogFileName(context)) }

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
            Spacer(modifier = Modifier.height(8.dp))
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
            Spacer(modifier = Modifier.height(8.dp))
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
            Spacer(modifier = Modifier.height(8.dp))
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
            OutlinedTextField(
                value = manualRpm,
                onValueChange = {
                    manualRpm = it.filter { ch -> ch.isDigit() }
                    val rpm = manualRpm.toFloatOrNull() ?: 0f
                    AncTestPreferences.setManualTestRpm(context, rpm)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手動測試 RPM（怠速）") },
                placeholder = { Text("例：800（無 OBD 時使用）") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = obdAddress,
                onValueChange = {
                    obdAddress = it.uppercase()
                    AncTestPreferences.setObdDeviceAddress(context, obdAddress)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手動 RPM 藍牙位址（已移除自動 OBD，僅 legacy）") },
                placeholder = { Text("例：00:1A:7D:DA:71:13") },
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val exported = TestLogExporter.shareLatestLog(context)
                        latestLogName = TestLogExporter.latestLogFileName(context)
                        val message = if (exported) "已開啟分享選單" else "沒有可匯出的 log 檔"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("匯出 Log")
                }
                OutlinedButton(
                    onClick = { latestLogName = TestLogExporter.latestLogFileName(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重新整理")
                }
            }
        }
    }
}