package io.zerosettle.justone.screens.paywall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

/**
 * Walks the [ContextWrapper] chain to the host [Activity]. Needed because
 * `LocalContext.current` inside a ModalBottomSheet (or any Dialog) is the
 * dialog's ContextThemeWrapper, not the Activity — a direct `as Activity`
 * cast there throws ClassCastException.
 */
internal tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No Activity found in the Context chain")
}

/**
 * Renders the purchase button(s) for a given product, adapting to UCB state.
 *
 * When UCB is enabled and a Play SKU is available, a single "Buy" button is shown — Google's
 * system-level choice screen handles routing (Play Billing vs web). When UCB is disabled, the
 * legacy two-button (web + Play) or single-button layout is preserved.
 *
 * The public signature is unchanged so all call sites (LaunchPaywallScreen, PremiumUpsellSheet,
 * ConsumableShopScreen) remain untouched.
 */
@Composable
fun DualPriceButtons(
    productId: String,
    onPurchased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findActivity()
    val products by ZeroSettle.products.collectAsState()
    val product = remember(products) { ZeroSettle.product(productId) }
    val ucbEnabled by ZeroSettle.isUcbEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    if (product == null) {
        Button(
            onClick = {},
            enabled = false,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Unavailable")
        }
        return
    }

    val webAvailable = product.webPrice != null
    val playAvailable = product.playStorePrice != null

    // Local helpers to avoid duplicating scope.launch blocks.
    val buyWeb: () -> Unit = {
        scope.launch {
            busy = true
            try {
                val r = ZeroSettle.purchase(activity, productId)
                if (r.isSuccess) onPurchased()
            } finally {
                busy = false
            }
        }
        Unit
    }

    val buyPlay: () -> Unit = {
        scope.launch {
            busy = true
            try {
                val r = ZeroSettle.purchaseViaPlayBilling(activity, productId)
                if (r.isSuccess) onPurchased()
            } finally {
                busy = false
            }
        }
        Unit
    }

    when {
        // ── UCB enabled + Play SKU available ────────────────────────────────
        // Show a single unified button. Google's choice screen handles routing;
        // the app must NOT render its own web-vs-Play picker.
        ucbEnabled && playAvailable -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = buyPlay,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Buy")
                }
                Text(
                    text = "You'll choose how to pay",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── UCB enabled but no Play SKU ──────────────────────────────────────
        // No Play Billing route available; fall back to web checkout.
        ucbEnabled && !playAvailable -> {
            Button(
                onClick = buyWeb,
                enabled = !busy,
                modifier = modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Pay on web")
            }
        }

        // ── UCB disabled: legacy two-button / single-button layout ───────────
        webAvailable && playAvailable -> {
            val webCents = product.webPrice!!.amountCents
            val playCents = product.playStorePrice!!.amountCents
            val webLabel = if (webCents < playCents) {
                val savings = (playCents - webCents) * 100 / playCents
                "Pay on web — save $savings%"
            } else {
                "Pay on web"
            }

            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = buyWeb,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(webLabel)
                }

                OutlinedButton(
                    onClick = buyPlay,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Google Play")
                }
            }
        }

        webAvailable -> {
            Button(
                onClick = buyWeb,
                enabled = !busy,
                modifier = modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Pay on web")
            }
        }

        playAvailable -> {
            Button(
                onClick = buyPlay,
                enabled = !busy,
                modifier = modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Google Play")
            }
        }

        else -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = modifier.fillMaxWidth(),
            ) {
                Text("Unavailable")
            }
        }
    }
}
