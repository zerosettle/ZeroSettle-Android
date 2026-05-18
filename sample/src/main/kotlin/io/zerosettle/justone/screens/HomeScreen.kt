package io.zerosettle.justone.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.OfferHolder
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.models.Price
import com.zerosettle.ui.ZeroSettleCheckoutHost
import com.zerosettle.ui.ZeroSettleOfferTip
import kotlinx.coroutines.launch

private fun Price?.fmt(): String =
    this?.let { "${"%.2f".format(it.amountCents / 100.0)} ${it.currencyCode}" } ?: "—"

/** Paywall + product list + offer tip + in-app checkout host. Mirrors JustOne's LaunchPaywallView + premium gate. */
@Composable
fun HomeScreen() {
    val products by ZeroSettle.products.collectAsState()
    val entitlements by ZeroSettle.entitlements.collectAsState()
    val activity = LocalContext.current as android.app.Activity
    val scope = rememberCoroutineScope()
    var lastAction by remember { mutableStateOf("") }

    // Shared offer manager (constructed once for the current identity).
    val offerManager = remember { OfferHolder.get() }
    LaunchedEffect(offerManager) { offerManager.evaluate() }

    val activeEnts = entitlements.filter { it.isActive }
    val isPremium = activeEnts.isNotEmpty()

    LaunchedEffect(Unit) { ZeroSettle.fetchProducts() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Home / Paywall", style = MaterialTheme.typography.titleLarge)

        // Offer tip (Switch & Save / upgrade) — appears only when the server says eligible.
        ZeroSettleOfferTip(offerManager = offerManager, onError = { lastAction = "offer error: ${it.message}" })
        // In-app WebView checkout host — presents when the offer tip's Accept sets pendingCheckoutUrl.
        ZeroSettleCheckoutHost(offerManager = offerManager, onFailed = { lastAction = "checkout failed: $it" })

        if (isPremium) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("You're premium ✓", style = MaterialTheme.typography.titleMedium)
                    activeEnts.forEach { e ->
                        Text("• ${e.productId} (${e.source} / ${e.status}) renews=${e.willRenew} expires=${e.expiresAt ?: "n/a"}")
                    }
                }
            }
        } else {
            Text("Not premium — pick a product to unlock.", style = MaterialTheme.typography.bodyMedium)
        }

        if (lastAction.isNotBlank()) Text(lastAction, style = MaterialTheme.typography.bodySmall)

        Text("Products (${products.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${p.displayName}  (${p.type})", style = MaterialTheme.typography.titleSmall)
                        Text(p.productDescription, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "web=${p.webPrice.fmt()}  play=${p.playStorePrice.fmt()}  appStore=${p.appStorePrice.fmt()}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        p.playProductId?.let { Text("playProductId=$it", style = MaterialTheme.typography.labelSmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    // Canonical server-driven path: the backend's
                                    // checkout-create response carries a
                                    // ``checkout_presentation`` field sourced
                                    // from the dashboard's CheckoutDefaults
                                    // setting (translated per X-ZS-SDK-Platform
                                    // for Android). The SDK's per-call
                                    // ``presentation`` override stays as an
                                    // adopter-facing escape hatch — JustOne
                                    // demonstrates the "trust the dashboard"
                                    // behaviour.
                                    val r = ZeroSettle.purchase(activity, p.id)
                                    lastAction = if (r.isSuccess) "web checkout completed: txn=${r.getOrNull()?.id}" else "web purchase failed: ${r.exceptionOrNull()?.message}"
                                }
                            }) { Text("Buy — Web") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val sdkProducts = ZeroSettle.products.value
                                    android.util.Log.w(
                                        "ZS-debug",
                                        "tap Buy-Play: p.id=${p.id!!} sdk.products.size=${sdkProducts.size} sdk.products.ids=${sdkProducts.map { it.id }}",
                                    )
                                    val r = ZeroSettle.purchaseViaPlayBilling(activity, p.id)
                                    android.util.Log.w(
                                        "ZS-debug",
                                        "purchaseViaPlayBilling returned: success=${r.isSuccess} error=${r.exceptionOrNull()?.message}",
                                    )
                                    lastAction = if (r.isSuccess) "Play purchase completed: txn=${r.getOrNull()?.id}" else "Play purchase failed: ${r.exceptionOrNull()?.message}"
                                }
                            }) { Text("Buy — Google Play") }
                        }
                    }
                }
            }
            if (products.isEmpty()) {
                item {
                    TextButton(onClick = { scope.launch { ZeroSettle.fetchProducts() } }) { Text("Reload products") }
                }
            }
        }
    }
}
