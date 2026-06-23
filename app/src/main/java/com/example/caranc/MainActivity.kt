package com.example.caranc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.caranc.shared.*
import com.example.caranc.shared.commercial.TierChangeResult
import com.example.caranc.shared.service.ANCService
import com.example.caranc.ui.theme.CarANCTheme
import android.widget.Toast

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) checkAndRequestLocationPermission()
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startAncService() }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        checkAndRequestRecordAudioPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AncSessionLogger.init(this)
        setContent {
            CarANCTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AncScreen(
                        viewModel = viewModel,
                        onStartClick = { requestPermissions() },
                        onStopClick = { stopAncService() }
                    )
                }
            }
        }
    }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                checkAndRequestRecordAudioPermission()
            } else {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            checkAndRequestRecordAudioPermission()
        }
    }

    private fun checkAndRequestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            checkAndRequestLocationPermission()
        } else {
            requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkAndRequestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startAncService()
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startAncService() {
        val intent = Intent(this, ANCService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopAncService() {
        stopService(Intent(this, ANCService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AncScreen(viewModel: MainViewModel, onStartClick: () -> Unit, onStopClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    // Obtain from (global default) AncSessionContext instead of direct singleton objects.
    // Tier buttons updated below to use context.tierManager . (Test panels, ANCService mode updates similarly.)
    // Scoped context recommended for testing/multi-session.
    val sessionContext = remember { GlobalAncSessionContext }
    // CYCLE3_P2: obtain ALL state from sessionContext.stateManager (was direct AncStateManager singleton).
    // Now consistent with tierManager/entitlement; supports fully scoped state manager.
    val rawDb by sessionContext.stateManager.rawDb.collectAsState()
    val cancelledDb by sessionContext.stateManager.cancelledDb.collectAsState()
    val noiseSpectrum by sessionContext.stateManager.noiseSpectrum.collectAsState()
    val cancelledSpectrum by sessionContext.stateManager.cancelledSpectrum.collectAsState()
    val currentTier by sessionContext.tierManager.currentTier.collectAsState()
    val vehicleSpeedKmh by sessionContext.stateManager.vehicleSpeedKmh.collectAsState()
    val vehicleSpeedValid by sessionContext.stateManager.vehicleSpeedValid.collectAsState()
    val dominantNoiseBand by sessionContext.stateManager.dominantNoiseBand.collectAsState()
    val estimatedLatencyMs by sessionContext.stateManager.estimatedLatencyMs.collectAsState()
    val maxCancelFrequencyHz by sessionContext.stateManager.maxCancelFrequencyHz.collectAsState()
    val latencyMidEnabled by sessionContext.stateManager.latencyMidEnabled.collectAsState()
    val latencyHighEnabled by sessionContext.stateManager.latencyHighEnabled.collectAsState()

    val statusText = when (val state = uiState) {
        is AncState.Calibrating -> state.message
        is AncState.Learning -> state.message
        is AncState.Running -> state.message
        is AncState.DrivingMode -> state.message
        is AncState.MusicMode -> state.message
        is AncState.Paused -> state.message
        is AncState.Stopped -> state.message
        is AncState.Error -> "錯誤: ${state.message}"
    }

    val speedText = if (vehicleSpeedValid) {
        "GPS 車速：${"%.0f".format(vehicleSpeedKmh)} km/h"
    } else {
        "GPS 車速：不可用（怠速/純麥克風模式）"
    }
    val bandText = "主導頻帶：$dominantNoiseBand（80–300 Hz 主攻）"
    val latencyText = if (estimatedLatencyMs > 0f) {
        val midTag = if (latencyMidEnabled) "中" else "中×"
        val highTag = if (latencyHighEnabled) "高" else "高×"
        "延遲 ${"%.0f".format(estimatedLatencyMs)} ms · 可抵消 ≤${"%.0f".format(maxCancelFrequencyHz)} Hz · band[$midTag/$highTag]"
    } else {
        "延遲：啟動降噪後顯示"
    }

    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSafetyConsent by remember {
        mutableStateOf(sessionContext.entitlementManager.requiresSafetyConsent())
    }
    var pendingStartAfterConsent by remember { mutableStateOf(false) }

    if (showSafetyConsent) {
        SafetyConsentDialog(
            onAccepted = {
                showSafetyConsent = false
                if (pendingStartAfterConsent) {
                    pendingStartAfterConsent = false
                    onStartClick()
                }
            },
            onDismiss = {
                showSafetyConsent = false
                pendingStartAfterConsent = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "CarANC 控制中心", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(24.dp))

        // --- 降噪等級選擇 ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TierButton("輕度", UserTier.LIGHT, currentTier == UserTier.LIGHT) { tier ->
                // obtain from provided context (instead of direct TierManager singleton)
                val result = sessionContext.tierManager.setTier(tier)
                if (result is TierChangeResult.Clamped) {
                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                }
            }
            TierButton("中度", UserTier.STANDARD, currentTier == UserTier.STANDARD) { tier ->
                val result = sessionContext.tierManager.setTier(tier)
                if (result is TierChangeResult.Clamped) {
                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                }
            }
            TierButton("重度", UserTier.PRO, currentTier == UserTier.PRO) { tier ->
                val result = sessionContext.tierManager.setTier(tier)
                if (result is TierChangeResult.Clamped) {
                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        CommercialPanel()

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = statusText, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = speedText, style = MaterialTheme.typography.bodySmall)
                Text(text = bandText, style = MaterialTheme.typography.bodySmall)
                Text(text = latencyText, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("原始噪音", style = MaterialTheme.typography.bodySmall)
                        Text("${"%.1f".format(rawDb)} dB", fontSize = 20.sp)
                    }
                    
                    // 新增：顯示減少的總量
                    Column(modifier = Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("降噪效果", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                        Text("-${"%.1f".format((rawDb - cancelledDb).coerceAtLeast(0f))} dB", fontSize = 28.sp, color = Color(0xFF4CAF50), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("處理後", style = MaterialTheme.typography.bodySmall)
                        Text("${"%.1f".format(cancelledDb)} dB", fontSize = 20.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("即時頻譜分析", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)
        ) {
            SpectrumCanvas(noiseSpectrum, cancelledSpectrum)
        }

        Spacer(modifier = Modifier.height(24.dp))

        GuidedTestPanel(
            onRequestStartAnc = {
                if (sessionContext.entitlementManager.requiresSafetyConsent()) {
                    pendingStartAfterConsent = true
                    showSafetyConsent = true
                } else {
                    onStartClick()
                }
            },
            onRequestStopAnc = onStopClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        TestLogPanel()

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (sessionContext.entitlementManager.requiresSafetyConsent()) {
                        pendingStartAfterConsent = true
                        showSafetyConsent = true
                    } else {
                        onStartClick()
                    }
                },
                enabled = uiState is AncState.Stopped,
                modifier = Modifier.weight(1f)
            ) {
                Text("開始降噪")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onStopClick, enabled = uiState !is AncState.Stopped, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("停止")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TierButton(label: String, tier: UserTier, isSelected: Boolean, onSelect: (UserTier) -> Unit) {
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(tier) },
        label = { Text(label) }
    )
}

@Composable
fun SpectrumCanvas(noise: FloatArray, cancelled: FloatArray) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (noise.isEmpty()) return@Canvas
        val barWidth = size.width / noise.size
        for (i in noise.indices) {
            val noiseHeight = (noise[i] * size.height * 12).coerceAtMost(size.height)
            val cancelledHeight = (cancelled[i] * size.height * 12).coerceAtMost(size.height)

            drawRect(
                color = Color.Red.copy(alpha = 0.4f),
                topLeft = Offset(i * barWidth, size.height - noiseHeight),
                size = Size(barWidth - 2f, noiseHeight)
            )
            drawRect(
                color = Color.Green,
                topLeft = Offset(i * barWidth, size.height - cancelledHeight),
                size = Size(barWidth - 2f, cancelledHeight)
            )
        }
    }
}
