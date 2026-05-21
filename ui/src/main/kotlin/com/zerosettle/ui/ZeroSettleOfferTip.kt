package com.zerosettle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.offers.OfferManager
import com.zerosettle.ui.theme.resolvedZeroSettleStyles
import kotlinx.coroutines.launch

/**
 * Drop-in offer banner wired to a headless [OfferManager]. Observes
 * [OfferManager.state] / [OfferManager.offerData] / [OfferManager.checkoutError] and
 * renders the offer card (Switch-now / Not-now), an "almost done" card while
 * `ACCEPTED`, and a congratulations card when `COMPLETED`. Renders nothing while
 * `LOADING` / `INELIGIBLE` / `DISMISSED` / `ERROR` (an `ERROR` is surfaced via
 * [onError] and the host's `ZeroSettle.events` — the tip stays quiet rather than
 * showing a broken card).
 *
 * Auto-bookkeeping: tapping the CTA calls [OfferManager.acceptOffer]; the manager
 * drives every state transition. For migrations / store→web upgrades the manager also
 * publishes [OfferManager.pendingCheckoutUrl] — pair this with [ZeroSettleCheckoutHost]
 * to actually present the web checkout.
 *
 * Mirrors iOS's unified offer-tip view (the `ZSOfferManager`-backed tip, not the
 * deprecated `ZSMigrateTipView` migration-only path).
 */
@Composable
public fun ZeroSettleOfferTip(
    offerManager: OfferManager,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    accentColor: Color? = null,
    showCompletedCard: Boolean = true,
    onError: (Throwable) -> Unit = {},
) {
    val state by offerManager.state.collectAsState()
    val offer by offerManager.offerData.collectAsState()
    val checkoutError by offerManager.checkoutError.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(checkoutError) { checkoutError?.let(onError) }

    // Self-trigger eligibility resolution when the tip enters composition.
    // OfferManager is a pure, host-driven state machine — it starts in
    // LOADING with no offer data and never fetches until evaluate() is
    // called. Without this, a host that simply drops ZeroSettleOfferTip onto
    // a screen sees nothing (the `offer ?: return` below short-circuits).
    // Mirrors iOS, where ZSOfferManager self-evaluates via its observation
    // tracking rather than requiring each host screen to wire it. The
    // offerManager instance is stable (hosts share one), so this runs once
    // per tip placement, not on every recomposition.
    LaunchedEffect(offerManager) { offerManager.evaluate() }

    val data = offer ?: return
    ZeroSettleOfferTipContent(
        state = state,
        offer = data,
        onAccept = { scope.launch { offerManager.acceptOffer() } },
        onDismiss = { scope.launch { offerManager.dismiss() } },
        modifier = modifier,
        backgroundColor = backgroundColor,
        accentColor = accentColor,
        showCompletedCard = showCompletedCard,
    )
}

/**
 * Stateless / hoisted offer-tip surface — the visual half of [ZeroSettleOfferTip].
 * Useful for previews, custom presentations, and tests (no [OfferManager] needed).
 */
@Composable
public fun ZeroSettleOfferTipContent(
    state: OfferManager.OfferState,
    offer: UserOffer.OfferData,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    accentColor: Color? = null,
    showCompletedCard: Boolean = true,
) {
    val styles = resolvedZeroSettleStyles()
    val accent = accentColor ?: styles.offerAccentColor
    val surface = backgroundColor ?: styles.offerSurfaceColor
    val titleStyle = styles.offerTitleStyle ?: MaterialTheme.typography.titleMedium
    val bodyStyle = styles.offerBodyStyle ?: MaterialTheme.typography.bodyMedium
    val ctaStyle = styles.offerCtaStyle ?: MaterialTheme.typography.labelLarge

    val display = offer.display

    @Composable
    fun card(content: @Composable () -> Unit) = Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = surface),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }

    fun String.orElse(fallback: String): String = ifBlank { fallback }

    when (state) {
        OfferManager.OfferState.LOADING,
        OfferManager.OfferState.INELIGIBLE,
        OfferManager.OfferState.DISMISSED,
        OfferManager.OfferState.ERROR -> Unit

        OfferManager.OfferState.ELIGIBLE,
        OfferManager.OfferState.PRESENTED -> card {
            Text((display?.title ?: "").orElse("Save ${offer.savingsPercent}%"), style = titleStyle)
            Text((display?.body ?: "").orElse("Switch to direct billing and save."), style = bodyStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text((display?.ctaText ?: "").orElse("Switch now"), style = ctaStyle)
                }
                TextButton(onClick = onDismiss) { Text((display?.dismissText ?: "").orElse("Not now")) }
            }
        }

        OfferManager.OfferState.ACCEPTED -> card {
            Text((display?.acceptedTitle ?: "").orElse("Almost done"), style = titleStyle)
            Text((display?.acceptedBody ?: "").orElse("Finishing up your switch…"), style = bodyStyle)
        }

        OfferManager.OfferState.COMPLETED -> if (showCompletedCard) card {
            Text((display?.completedTitle ?: "").orElse("All set!"), style = titleStyle)
            Text((display?.completedBody ?: "").orElse("You're now billed directly."), style = bodyStyle)
        }
    }
}
