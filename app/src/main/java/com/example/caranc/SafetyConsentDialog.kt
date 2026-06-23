package com.example.caranc

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.commercial.SafetyDisclaimer

@Composable
fun SafetyConsentDialog(
    onAccepted: () -> Unit,
    onDismiss: () -> Unit
) {
    val sessionContext = remember { GlobalAncSessionContext }
    var marketingOptIn by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SafetyDisclaimer.TITLE) },
        text = {
            Column {
                SafetyDisclaimer.bullets.forEach { line ->
                    Text("• $line", modifier = Modifier.padding(bottom = 6.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = marketingOptIn, onCheckedChange = { marketingOptIn = it })
                    Text("願意接收產品更新與優惠資訊（可選）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                sessionContext.entitlementManager.acceptSafetyConsent(marketingOptIn = marketingOptIn)
                onAccepted()
            }) {
                Text("同意並繼續")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("暫不使用")
            }
        }
    )
}