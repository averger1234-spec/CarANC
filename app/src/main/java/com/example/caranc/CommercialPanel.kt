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

            Spacer(modifier = Modifier.height(12.dp))
            Text("方案與功能", style = MaterialTheme.typography.labelLarge)
            ProductCatalog.planOrder.forEach { plan ->
                val active = plan == currentPlan
                val featureCount = ProductCatalog.featuresForPlan(plan).size
                Text(
                    text = buildString {
                        append(plan.displayName)
                        append(" · ")
                        append(ProductCatalog.suggestedPriceHint(plan))
                        append(" · ")
                        append(featureCount)
                        append(" 項功能")
                        if (active) append(" ✓")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onTertiaryContainer
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
                ) { Text("隱私政策") }
                OutlinedButton(
                    onClick = { openUrl(context, ProductCatalog.TERMS_URL) },
                    modifier = Modifier.weight(1f)
                ) { Text("服務條款") }
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("開發用方案切換（上架前移除）", style = MaterialTheme.typography.labelSmall)
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
                Button(
                    onClick = { toast(context, "Google Play 訂閱將於上架版本啟用") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("升級方案（即將推出）")
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