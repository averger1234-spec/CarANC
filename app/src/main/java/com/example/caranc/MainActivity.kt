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
import androidx.compose.foundation.clickable
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
import com.example.caranc.shared.commercial.PrivacyPolicy
import com.example.caranc.shared.commercial.TermsOfService
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

    fun openUrlInBrowser(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "無法開啟：$url", Toast.LENGTH_SHORT).show()
        }
    }

    var showPrivacy by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }

    // Bottom navigation for cleaner UI: 狀態 / 方案 (含隱私政策、服務條款；目前無網站，GitHub + in-app) / 測試腳本 / 測試平台
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("狀態", "方案", "測試腳本", "測試平台")
    val tabIcons = listOf(Icons.Filled.Home, Icons.Filled.ShoppingCart, Icons.Filled.List, Icons.Filled.Settings)

    // P0: toggle for advanced engineering info (hidden by default for consumer)
    var showAdvancedInfo by remember { mutableStateOf(false) }

    // P1: 開發者模式（連點版本號 7 次解鎖，只給自己/核心測試者；一般測試者看不到 debug 參數）
    var devTapCount by remember { mutableStateOf(0) }
    val isDevMode = devTapCount >= 7

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
            title = { Text(PrivacyPolicy.TITLE) },
            text = {
                val dialogScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .verticalScroll(dialogScroll)
                        .heightIn(max = 320.dp)
                ) {
                    Text(
                        "${PrivacyPolicy.VERSION} · 最後更新 ${PrivacyPolicy.LAST_UPDATED}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(PrivacyPolicy.SHORT_SUMMARY)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "完整版本（含權限表、資料刪除說明、未來雲端規劃）請見 GitHub：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        PrivacyPolicy.GITHUB_URL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "提示：您也可以在「方案」分頁點擊「隱私政策（GitHub 完整版）」用瀏覽器開啟。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showPrivacy = false }) { Text("關閉") }
            },
            dismissButton = {
                TextButton(onClick = {
                    openUrlInBrowser(PrivacyPolicy.GITHUB_URL)
                    showPrivacy = false
                }) {
                    Text("在瀏覽器開啟完整版")
                }
            }
        )
    }

    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text(TermsOfService.TITLE) },
            text = {
                val dialogScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .verticalScroll(dialogScroll)
                        .heightIn(max = 320.dp)
                ) {
                    Text(
                        "${TermsOfService.VERSION} · 最後更新 ${TermsOfService.LAST_UPDATED}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(TermsOfService.SHORT_SUMMARY)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "完整版本（含更詳細安全聲明、責任限制、管轄法律）請見 GitHub：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        TermsOfService.GITHUB_URL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "提示：您也可以在「方案」分頁點擊「服務條款（GitHub 完整版）」用瀏覽器開啟。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showTerms = false }) { Text("我同意並關閉") }
            },
            dismissButton = {
                TextButton(onClick = {
                    openUrlInBrowser(TermsOfService.GITHUB_URL)
                    showTerms = false
                }) {
                    Text("在瀏覽器開啟完整版")
                }
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
                    // P0 消費者體驗核心：狀態感知而非數據監控
                    // 轉型為「讓一般車主感受到『我現在變安靜了』」
                    Text(text = "車內主動降噪", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "感受安靜的駕駛體驗",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // P1 dev mode unlock: tap version 7x (hidden to normal users)
                    Text(
                        text = "v0.9 • ${if (isDevMode) "開發者模式已解鎖" else "點此 7 次啟用開發者模式"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDevMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable {
                                devTapCount++
                                if (devTapCount == 7) {
                                    Toast.makeText(context, "開發者模式已解鎖（僅供內部測試）", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

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

                    Spacer(modifier = Modifier.height(12.dp))

                    // P0: 狀態感知卡片 + 環形 Gauge + 放大動態 dB + 使用者價值語言
                    // 工程術語（延遲/頻帶/模式）預設隱藏在進階
                    val contextualTitle = when {
                        uiState is AncState.MusicMode || (uiState is AncState.Running && dominantNoiseBand.contains("MUSIC", ignoreCase = true)) -> "音樂模式：保護音質 + rumble 基礎抵消"
                        uiState is AncState.DrivingMode || (uiState is AncState.Running && (dominantNoiseBand.contains("ROAD", ignoreCase = true) || dominantNoiseBand.contains("TIRE", ignoreCase = true) || vehicleSpeedKmh > 15f)) -> "低頻路噪抑制中"
                        uiState is AncState.Running && vehicleSpeedKmh < 8f -> "怠速環境最佳化"
                        uiState is AncState.Running -> "主動降噪運作中"
                        uiState is AncState.Learning -> "車廂聲學學習中"
                        uiState is AncState.Calibrating -> "初始化中"
                        else -> statusText
                    }

                    val targetReduction = (rawDb - cancelledDb).coerceAtLeast(0f)
                    // Note: animate*AsState removed temporarily for compile (BOM/animation module resolution in current env);
                    // visuals remain dynamic on next state update. Re-add with proper dep for production polish.
                    val animatedReduction = targetReduction
                    val reductionColor = when {
                        targetReduction >= 6f -> Color(0xFF1B5E20)
                        targetReduction >= 3f -> Color(0xFF2E7D32)
                        targetReduction >= 1.5f -> Color(0xFF43A047)
                        targetReduction > 0.5f -> Color(0xFFFFB74D)
                        else -> Color(0xFF90A4AE)
                    }

                    val isRunning = uiState !is AncState.Stopped && uiState !is AncState.Error

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = contextualTitle,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                maxLines = 1
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // P0: 環形進度條 + 中央英雄數字（降噪效果最凸顯）
                            Box(
                                modifier = Modifier.size(148.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularArcGauge(
                                    progress = (animatedReduction / 12f).coerceIn(0f, 1f),
                                    color = reductionColor,
                                    modifier = Modifier.size(148.dp)
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "-${"%.1f".format(animatedReduction)}",
                                        fontSize = 46.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                        color = reductionColor,
                                        maxLines = 1
                                    )
                                    Text(
                                        "dB",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 價值回饋：只有降噪明顯時顯示
                            if (isRunning && animatedReduction > 1.2f) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "✓ 你現在的車廂更安靜了",
                                    color = Color(0xFF2E7D32),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Smaller raw / processed
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("原始噪音", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${"%.1f".format(rawDb)} dB", fontSize = 15.sp, maxLines = 1)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("處理後", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${"%.1f".format(cancelledDb)} dB", fontSize = 15.sp, maxLines = 1)
                                }
                            }

                            // P0: 進階資訊收納（Toggle / BottomSheet 建議，現用簡單 toggle）
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(onClick = { showAdvancedInfo = !showAdvancedInfo }) {
                                Text(if (showAdvancedInfo) "隱藏進階資訊" else "顯示進階資訊（延遲 / 頻帶）")
                            }

                            if (showAdvancedInfo) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = speedText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(text = bandText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(text = latencyText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // P0: 升級為聲學視覺化（貝茲平滑 + 漸層）
                    Text("聲學視覺化", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Start))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF111111), RoundedCornerShape(12.dp)).padding(6.dp)
                    ) {
                        SpectrumCanvas(noiseSpectrum, cancelledSpectrum)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // P0: 單一大型置中主控按鈕（狀態切換即時反映；完整 AnimatedContent 動畫待 dep 穩定後恢復）
                    val onToggleClick = {
                        if (isRunning) {
                            onStopClick()
                        } else {
                            if (sessionContext.entitlementManager.requiresSafetyConsent()) {
                                pendingStartAfterConsent = true
                                showSafetyConsent = true
                            } else {
                                onStartClick()
                            }
                        }
                    }

                    Button(
                        onClick = onToggleClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(34.dp)
                    ) {
                        Text(
                            text = if (isRunning) "停止降噪" else "開始降噪",
                            fontSize = 20.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }

                1 -> {
                    // 方案 tab：方案切換 + 隱私政策 + 服務條款（目前無獨立網站，使用 GitHub + App 內對話框）
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
                        "提示：方案切換影響降噪強度與功能開放。隱私政策與服務條款以 App 內文字為主（離線可讀），完整 Markdown 版放在 GitHub（無獨立網站前使用此方式）。",
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
                    TestLogPanel(currentTier = currentTier, isDevMode = isDevMode)
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
    // P0: 升級為平滑貝茲曲線 + 漸層填充的聲學視覺化（降噪好時線條趨平緩柔和）
    // 使用 cubicTo 模擬平滑貝茲，避免折線生硬。
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (noise.isEmpty() || cancelled.isEmpty()) return@Canvas
        val n = noise.size.coerceAtMost(cancelled.size)
        if (n < 2) return@Canvas

        val stepX = size.width / (n - 1)
        val scaleY = size.height * 0.88f

        fun buildSmoothPath(values: FloatArray): androidx.compose.ui.graphics.Path {
            val p = androidx.compose.ui.graphics.Path()
            var prevX = 0f
            var prevY = (size.height - (values[0] * scaleY).coerceIn(0f, size.height))
            p.moveTo(prevX, prevY)
            for (i in 1 until n) {
                val x = i * stepX
                val y = size.height - (values[i] * scaleY).coerceIn(0f, size.height)
                // cubicTo 控制點：取段落 1/3 與 2/3 位置，產生平滑貝茲效果
                val c1x = prevX + (x - prevX) * 0.33f
                val c1y = prevY
                val c2x = prevX + (x - prevX) * 0.67f
                val c2y = y
                p.cubicTo(c1x, c1y, c2x, c2y, x, y)
                prevX = x
                prevY = y
            }
            return p
        }

        // 噪音（較淡紅，細線）
        val noisePath = buildSmoothPath(noise)
        drawPath(
            path = noisePath,
            color = Color(0xFFE57373),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
        )

        // 處理後（綠色主線 + 較粗）
        val cancelPath = buildSmoothPath(cancelled)
        drawPath(
            path = cancelPath,
            color = Color(0xFF66BB6A),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.5f)
        )

        // 漸層填充（acoustic 柔和感），降噪佳時更明顯平穩
        val fillPath = androidx.compose.ui.graphics.Path()
        fillPath.addPath(cancelPath)
        fillPath.lineTo(size.width, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()
        drawPath(
            path = fillPath,
            color = Color(0xFF66BB6A).copy(alpha = 0.12f)
        )
    }
}

/**
 * P0 環形進度條（Circular Gauge）：直觀顯示降噪效果，讓用戶「看到」安靜程度。
 * 270° 弧形，中心疊加大數字。進度/顏色動態變化（>6dB 深綠）。
 */
@Composable
fun CircularArcGauge(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    // Simplified (no internal animateFloatAsState) to ensure compile without animation module friction in env;
    // update is still snappy on recomposition from state flows. Reintroduce anim + dep for release.
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val strokeWidth = 13.dp.toPx()
        val radius = (size.minDimension / 2f) - strokeWidth / 2f - 2.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val arcSize = Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)

        // 背景弧（淺灰）
        drawArc(
            color = Color(0xFFE0E0E0),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
        // 進度弧（動態顏色）
        drawArc(
            color = color,
            startAngle = 135f,
            sweepAngle = 270f * p,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
        // 微弱內圈裝飾
        drawArc(
            color = color.copy(alpha = 0.12f),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}
