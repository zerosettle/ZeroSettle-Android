package io.zerosettle.justone.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.models.UpgradeOffer
import com.zerosettle.ui.ZeroSettleUpgradeOffer
import kotlinx.coroutines.launch

/** Web→web subscription upgrade offer — mirrors JustOne's PremiumUpsellView. */
@Composable
fun UpgradeOfferScreen() {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<UpgradeOffer.Config?>(null) }
    var status by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Upgrade offer", style = MaterialTheme.typography.titleLarge)
        Button(onClick = {
            scope.launch {
                val r = ZeroSettle.fetchUpgradeOfferConfig()
                if (r.isSuccess) { config = r.getOrNull(); status = "config loaded" }
                else status = "fetchUpgradeOfferConfig failed: ${r.exceptionOrNull()?.message}"
            }
        }) { Text("Check upgrade offer") }

        config?.let { cfg ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("from=${cfg.fromProductId} → to=${cfg.toProductId}  savings=${cfg.savingsPercent}%")
                    Text("title=\"${cfg.display.offerTitle}\"  cta=\"${cfg.display.offerCta}\"")
                }
            }
            ZeroSettleUpgradeOffer(
                config = cfg,
                onResult = { result ->
                    scope.launch {
                        status = when (result) {
                            is UpgradeOffer.Result.Accepted -> {
                                val r = ZeroSettle.executeUpgradeOffer(cfg.fromProductId, result.newProductId)
                                if (r.isSuccess) "upgrade executed → ${r.getOrNull()}" else "executeUpgradeOffer failed: ${r.exceptionOrNull()?.message}"
                            }
                            UpgradeOffer.Result.Dismissed -> "dismissed upgrade offer"
                            is UpgradeOffer.Result.Failed -> "upgrade offer failed: ${result.error}"
                        }
                        ZeroSettle.restoreEntitlements()
                        config = null
                    }
                },
            )
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
