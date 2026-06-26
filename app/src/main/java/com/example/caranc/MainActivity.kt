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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon

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

    var showPrivacy by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }

    // Bottom navigation for cleaner UI: 狀態 / 方案 (含隱私政策、服務條款) / 測試腳本 / 測試平台
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("狀態", "方案", "測試腳本", "測試平台")
    val tabIcons = listOf(Icons.Filled.Home, Icons.Filled.ShoppingCart, Icons.Filled.List, Icons.Filled.Settings)

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

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("隱私政策") },
            text = {
                Text(
                    "本應用程式（CarANC）僅在您的裝置本機記錄測試資料，包括車型、測試場景、手機放置位置、麥克風音訊、車速、降噪參數等，用於個人降噪效果調校與分析。\n\n" +
                    "資料完全不會上傳至任何伺服器、雲端服務或分享給第三方。\n\n" +
                    "您可隨時在「測試平台」匯出或刪除 log 檔。\n\n" +
                    "本應用為實驗性質，使用時請注意周圍環境安全。"
                )
            },
            confirmButton = {
                Button(onClick = { showPrivacy = false }) { Text("關閉") }
            }
        )
    }

    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text("服務條款與免責聲明") },
            text = {
                Text(
                    "本應用程式為實驗性主動降噪（ANC）工具，僅供個人研究與測試使用。\n\n" +
                    "1. 效果不保證：降噪效果因車型、環境、麥克風位置、硬體延遲等因素差異極大，可能無法達到預期。\n" +
                    "2. 安全第一：請勿在駕駛時分心操作 UI 或調整參數。使用本軟體時請保持對路況的專注。\n" +
                    "3. 無保固：本軟體「依原樣」提供，不提供任何明示或暗示的保證（包括但不限於適售性、特定用途適用性）。\n" +
                    "4. 責任限制：開發者對於因使用本應用程式而造成的任何直接、間接、附帶或衍生損害不承擔責任。\n\n" +
                    "繼續使用即表示您同意上述條款。"
                )
            },
            confirmButton = {
                Button(onClick = { showTerms = false }) { Text("我同意並關閉") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabTitles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tabIcons[index], contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (selectedTab) {
                0 -> {
                    // 狀態 tab：等級、狀態卡片、頻譜、開始/停止
                    Text(text = "CarANC 控制中心", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TierButton("輕度", UserTier.LIGHT, currentTier == UserTier.LIGHT) { tier ->
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

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("即時頻譜分析", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp).background(Color.Black, RoundedCornerShape(8.dp)).padding(8.dp)
                    ) {
                        SpectrumCanvas(noiseSpectrum, cancelledSpectrum)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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

                1 -> {
                    // 方案 tab：方案切換 + 隱私政策 + 服務條款
                    CommercialPanel()

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("隱私與條款", style = MaterialTheme.typography.titleMedium)

                    Button(
                        onClick = { showPrivacy = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("隱私政策")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showTerms = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("服務條款與免責聲明")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "提示：方案切換影響降噪強度與功能開放。隱私政策與服務條款僅供參考，本應用為實驗性質。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                2 -> {
                    // 測試腳本 tab
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
                }

                3 -> {
                    // 測試平台 tab
                    TestLogPanel()
                }
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
