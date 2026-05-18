package io.zerosettle.justone.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@Composable
fun SubscriptionCard(
    onCancel: (String) -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val sub = entitlements.firstOrNull { it.isActive && it.productType != "consumable" }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Subscription",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (sub == null) {
                // No active subscription — CTA to upgrade
                Text(
                    text = "Unlock all features with a premium plan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Upgrade to Premium")
                }
            } else {
                // Active subscription details
                var upgradeAvailable by remember { mutableStateOf(false) }

                LaunchedEffect(sub.productId) {
                    upgradeAvailable = ZeroSettle.fetchUpgradeOfferConfig(sub.productId).isSuccess
                }

                // Plan name
                Text(
                    text = "Plan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = sub.productId,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status line
                val statusLabel = when {
                    sub.isTrial -> "Free trial"
                    sub.pausedAt != null -> "Paused"
                    sub.willRenew -> "Renews"
                    else -> "Active"
                }
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Expiry date
                if (sub.expiresAt != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Expires: ${sub.expiresAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Upgrade available row
                if (upgradeAvailable) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUpgrade)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Upgrade available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Resume button — only when paused
                if (sub.pausedAt != null) {
                    Button(
                        onClick = { scope.launch { ZeroSettle.resumeSubscription(sub.productId) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Resume")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Cancel button
                OutlinedButton(
                    onClick = { onCancel(sub.productId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel subscription")
                }
            }
        }
    }
}
