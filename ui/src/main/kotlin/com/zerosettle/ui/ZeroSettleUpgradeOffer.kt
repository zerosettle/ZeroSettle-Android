package com.zerosettle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.UpgradeOffer

/**
 * Renders the web→web (or cross-store) subscription upgrade offer sheet. The host
 * supplies [config] (from `ZeroSettle.fetchUpgradeOfferConfig()`); accepting fires
 * [UpgradeOffer.Result.Accepted] with the target product id — the host then calls
 * `ZeroSettle.executeUpgradeOffer(fromProductId, toProductId)` (or routes via
 * `OfferManager.acceptOffer()` for the unified server-decided path). Mirrors iOS's
 * upgrade-offer sheet.
 */
@Composable
public fun ZeroSettleUpgradeOffer(
    config: UpgradeOffer.Config,
    onResult: (UpgradeOffer.Result) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(config.display.offerTitleOrDefault("Save ${config.savingsPercent}%"), style = MaterialTheme.typography.titleMedium)
        Text(config.display.offerMessageOrDefault("Switch to a better plan."), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { onResult(UpgradeOffer.Result.Accepted(newProductId = config.toProductId)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(config.display.offerCtaOrDefault("Upgrade")) }
        TextButton(onClick = { onResult(UpgradeOffer.Result.Dismissed) }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}
