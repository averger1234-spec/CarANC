package com.example.caranc

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.commercial.CommercialFeature
import com.example.caranc.shared.commercial.DevEntitlementOverrides
import com.example.caranc.shared.commercial.ProductCatalog
import com.example.caranc.shared.commercial.SubscriptionPlan

@Composable
fun CommercialPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sessionContext = remember { GlobalAncSessionContext }
    val entitlement by sessionContext.entitlementManager.snapshot.collectAsState()
    val currentPlan = entitlement.plan

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(ProductCatalog.PRODUCT_NAME, style = MaterialTheme.typography.titleMedium)
            Text(ProductCatalog.PRODUCT_TAGLINE, style = MaterialTheme.typography.bodySmall)
            if (BuildConfig.IS_STORE && ProductCatalog.PUBLIC_BETA) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    ProductCatalog.PUBLIC_BETA_LABEL,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    ProductCatalog.PUBLIC_BETA_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "目前方案：${currentPlan.displayName}（${currentPlan.marketLabel}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "市場定位：${ProductCatalog.MARKET_POSITION}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "支援：${ProductCatalog.SUPPORT_EMAIL}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text("方案與功能", style = MaterialTheme.typography.labelLarge)
            ProductCatalog.planOrder.forEach { plan ->
                val active = plan == currentPlan
                val isRecommended = plan == SubscriptionPlan.STANDARD_MONTHLY
                val featureCount = ProductCatalog.featuresForPlan(plan).size
                val comingSoon = BuildConfig.IS_STORE && ProductCatalog.PUBLIC_BETA && plan.isPaid
                val label = buildString {
                    append(if (isRecommended) "★ " else "")
                    append(plan.displayName)
                    append(" · ")
                    append(if (comingSoon) "即將推出" else ProductCatalog.suggestedPriceHint(plan))
                    append(" · ")
                    append(featureCount)
                    append(" 項功能")
                    if (active) append("  ← 你目前方案")
                    if (isRecommended && !active && !comingSoon) append(" （推薦）")
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary else if (isRecommended) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = if (isRecommended) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            val enabled = CommercialFeature.entries.filter { sessionContext.commercialGate.isFeatureEnabled(it, currentPlan) }
            Text(
                "已解鎖：${enabled.joinToString(" · ") { it.title }}",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openUrl(context, ProductCatalog.PRIVACY_POLICY_URL) },
                    modifier = Modifier.weight(1f)
                ) { Text("隱私政策\n（GitHub 完整版）") }
                OutlinedButton(
                    onClick = { openUrl(context, ProductCatalog.TERMS_URL) },
                    modifier = Modifier.weight(1f)
                ) { Text("服務條款\n（GitHub 完整版）") }
            }

            Text(
                "（目前無獨立網站，GitHub 為公開 Markdown 來源；「方案」分頁上方另有 App 內完整對話框版本，離線可用）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Internal / dev builds only — never ship fake plan unlock to Play store flavor.
            if (BuildConfig.ENABLE_DEV_BILLING_BYPASS) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "開發用方案切換（僅 internal 建置）",
                    style = MaterialTheme.typography.labelSmall
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DevPlanChip("免費") {
                        DevEntitlementOverrides.activate(SubscriptionPlan.FREE)
                        sessionContext.tierManager.syncToEntitlement()
                        toast(context, "已切換免費版")
                    }
                    DevPlanChip("標準") {
                        DevEntitlementOverrides.activate(SubscriptionPlan.STANDARD_MONTHLY)
                        sessionContext.tierManager.syncToEntitlement()
                        toast(context, "已切換標準版")
                    }
                    DevPlanChip("專業") {
                        DevEntitlementOverrides.activate(SubscriptionPlan.PRO_MONTHLY)
                        sessionContext.tierManager.syncToEntitlement()
                        toast(context, "已切換專業版")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (BuildConfig.IS_STORE && ProductCatalog.PUBLIC_BETA) {
                        "訂閱尚未開放購買。目前可免費使用「輕度」降噪；中度／重度將隨 Play 訂閱上線。"
                    } else if (BuildConfig.IS_STORE) {
                        "請透過 Google Play 管理訂閱（尚未接線時顯示即將推出）。"
                    } else {
                        "Google Play 訂閱尚未接線。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        toast(
                            context,
                            if (ProductCatalog.PUBLIC_BETA) {
                                "公開體驗中：付費方案即將推出"
                            } else {
                                "Google Play 訂閱將於後續版本啟用"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ProductCatalog.PUBLIC_BETA) "付費方案（即將推出）" else "升級方案")
                }
            }
        }
    }
}

@Composable
private fun RowScope.DevPlanChip(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "無法開啟連結：$url", Toast.LENGTH_SHORT).show()
    }
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}