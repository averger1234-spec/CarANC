package com.example.caranc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.caranc.BuildConfig
import com.example.caranc.shared.UserTier

/**
 * Android 狀態頁對齊 iOS `StatusTabView`：標題卡、藥丸 KPI、雙頻譜、開始/停止。
 */
@Composable
fun IosStyleStatusTab(
    isRunning: Boolean,
    statusText: String,
    versionLabel: String,
    tier: UserTier,
    nvhFocus: String,
    rawDb: Float,
    antiDb: Float,
    lowBandKpi: Float,
    rumbleAccel: Float,
    micSpectrum: FloatArray,
    antiSpectrum: FloatArray,
    speedText: String,
    latencyText: String,
    carConnected: Boolean,
    carLinkLabel: String,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    maxCancelHz: Float,
    midEnabled: Boolean,
    highEnabled: Boolean,
    onToggleAnc: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderCard(
            isRunning = isRunning,
            statusText = statusText,
            versionLabel = versionLabel,
            tier = tier,
            nvhFocus = nvhFocus
        )
        MetricPillsRow(
            rawDb = rawDb,
            antiDb = antiDb,
            lowBandKpi = lowBandKpi,
            rumbleAccel = rumbleAccel
        )
        SpectrumBars(title = "麥克風頻譜", values = micSpectrum, color = Color(0xFFFF9500))
        SpectrumBars(title = "反噪音輸出", values = antiSpectrum, color = Color(0xFF32ADE6))
        InfoBlock(
            speedText = speedText,
            latencyText = latencyText,
            carConnected = carConnected,
            carLinkLabel = carLinkLabel,
            showAdvanced = showAdvanced,
            maxCancelHz = maxCancelHz,
            midEnabled = midEnabled,
            highEnabled = highEnabled
        )
        Button(
            onClick = onToggleAnc,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFFF3B30) else Color(0xFF007AFF)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (isRunning) "停止降噪" else "開始降噪",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("顯示工程資訊", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = showAdvanced, onCheckedChange = onShowAdvancedChange)
        }
    }
}

@Composable
private fun HeaderCard(
    isRunning: Boolean,
    statusText: String,
    versionLabel: String,
    tier: UserTier,
    nvhFocus: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0x2E007AFF), Color(0x1400C7BE))
                )
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) Color(0xFF34C759) else Color(0xFF8E8E93))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning) "運作中" else "已停止",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                versionLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tierShort(tier),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x26007AFF))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF007AFF)
            )
        }
        Text(statusText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            nvhDisplayName(nvhFocus),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Android · v${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun MetricPillsRow(
    rawDb: Float,
    antiDb: Float,
    lowBandKpi: Float,
    rumbleAccel: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MetricPill("輸入 dB", "%.1f".format(rawDb))
        MetricPill("反噪 dB", "%.1f".format(antiDb))
        MetricPill("低頻 KPI", "%.2f".format(lowBandKpi))
        MetricPill("IMU", "%.2f".format(rumbleAccel))
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SpectrumBars(title: String, values: FloatArray, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val n = minOf(values.size, 32).coerceAtLeast(1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val step = (values.size / n).coerceAtLeast(1)
            val peak = values.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
            repeat(n) { i ->
                val v = (values.getOrElse(i * step) { 0f } / peak).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(v.coerceAtLeast(0.03f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(color.copy(alpha = 0.85f))
                )
            }
        }
    }
}

@Composable
private fun InfoBlock(
    speedText: String,
    latencyText: String,
    carConnected: Boolean,
    carLinkLabel: String,
    showAdvanced: Boolean,
    maxCancelHz: Float,
    midEnabled: Boolean,
    highEnabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(speedText, style = MaterialTheme.typography.bodyMedium)
        Text(latencyText, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (carConnected) "Android Auto 已連線 · $carLinkLabel"
            else "本機路徑 · $carLinkLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = if (carConnected) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "產品目標：路噪 · 輪噪 · 風切（高頻不追消）· 車機對齊 AA",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showAdvanced) {
            Text(
                "strategy maxCancel=%.0f mid=%s high=%s".format(
                    maxCancelHz,
                    if (midEnabled) "on" else "off",
                    if (highEnabled) "on" else "off"
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun nvhDisplayName(name: String): String = when (name) {
    "ROAD_RUMBLE" -> "路噪 40–200 Hz"
    "TIRE_NOISE" -> "輪噪 80–350 Hz"
    "WIND_SHEAR" -> "風切 >500 Hz（不追消）"
    "MIXED_CABIN" -> "混合車廂"
    "IDLE" -> "怠速 / 靜止"
    else -> name
}

fun tierShort(tier: UserTier): String = when (tier) {
    UserTier.LIGHT -> "輕"
    UserTier.STANDARD -> "中"
    UserTier.PRO -> "重"
}
