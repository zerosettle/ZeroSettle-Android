package io.zerosettle.justone.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.OfferHolder
import com.zerosettle.ui.ZeroSettleCheckoutHost
import com.zerosettle.ui.ZeroSettleOfferTip
import kotlinx.coroutines.launch

/** Raw OfferManager inspector — mirrors JustOne's PrivateOfferDemoView. */
@Composable
fun OffersScreen() {
    val manager = remember { OfferHolder.get() }
    val state by manager.state.collectAsState()
    val offer by manager.offerData.collectAsState()
    val checkoutError by manager.checkoutError.collectAsState()
    val pendingUrl by manager.pendingCheckoutUrl.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }

    LaunchedEffect(manager) { manager.evaluate() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Offers", style = MaterialTheme.typography.titleLarge)
        Text("state=$state  loading=$isLoading", style = MaterialTheme.typography.bodyMedium)
        checkoutError?.let { Text("checkoutError=$it", style = MaterialTheme.typography.bodySmall) }
        pendingUrl?.let { Text("pendingCheckoutUrl=$it", style = MaterialTheme.typography.bodySmall) }

        offer?.let { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("OfferData", style = MaterialTheme.typography.titleSmall)
                    Text("actionType=${o.actionType}  eligible=${o.isEligible}")
                    Text("checkoutProductId=${o.checkoutProductId}  fromProductId=${o.fromProductId ?: "—"}")
                    Text("savings=${o.savingsPercent}%  trialDays=${o.freeTrialDays}  rollout=${o.rolloutPercent}%")
                    Text("source=${o.source ?: "—"}  requiresAppleCancel=${o.requiresAppleCancel}")
                    o.display?.let { d -> Text("title=\"${d.title}\"  cta=\"${d.ctaText}\"") }
                    o.proration?.let { p -> Text("proration=${p.amountCents} ${p.currency} next=${p.nextBillingDate ?: "—"}") }
                }
            }
        } ?: Text("(no offer data)", style = MaterialTheme.typography.bodySmall)

        // Drop-in tip (handles its own Accept/Dismiss).
        ZeroSettleOfferTip(offerManager = manager, onError = { status = "tip error: ${it.message}" })
        // Host so accepting opens the WebView even from this screen.
        ZeroSettleCheckoutHost(offerManager = manager, onFailed = { status = "checkout failed: $it" })

        Text("Direct controls:", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { manager.evaluate(); status = "re-evaluated" } }) { Text("Re-evaluate") }
            Button(onClick = {
                scope.launch {
                    val r = manager.acceptOffer()
                    status = if (r.isSuccess) "acceptOffer ok" else "acceptOffer failed: ${r.exceptionOrNull()?.message}"
                }
            }) { Text("Accept") }
            OutlinedButton(onClick = { scope.launch { manager.dismiss(); status = "dismissed" } }) { Text("Dismiss") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
