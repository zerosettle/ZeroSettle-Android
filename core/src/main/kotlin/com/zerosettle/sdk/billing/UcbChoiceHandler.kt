package com.zerosettle.sdk.billing

import com.android.billingclient.api.UserChoiceBillingListener
import com.android.billingclient.api.UserChoiceDetails
import com.zerosettle.sdk.core.ZeroSettleLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Receives Google's UCB choice-screen callback when the user picks
 * alternative billing. Captures the [UserChoiceDetails.getExternalTransactionToken]
 * + product list and hands off to a [UcbCheckoutLauncher] which will, in
 * Chunk C:
 *   - POST the token to `/v1/iap/play-ucb/initiate/`
 *   - Launch Stripe PaymentSheet with the returned PaymentIntent client_secret
 *   - Report the result back via the SDK's existing purchase deferred bridge
 *
 * The handler is constructed once per SDK session when [UcbConfig.isEnabled]
 * is true and DMA-only mode is false (see [PlayBillingCoordinator]). It is
 * not constructed in standard Play Billing mode — [PlayBillingManager]'s
 * builder simply doesn't call `enableUserChoiceBilling(...)` in that case.
 *
 * The listener has no return channel back to Play: if we can't honor the
 * callback (missing token / product / user) we drop it with a warn log; the
 * upstream purchase deferred will time out or surface its own error.
 */
internal class UcbChoiceHandler(
    private val scope: CoroutineScope,
    private val logger: ZeroSettleLogger,
    private val launcher: UcbCheckoutLauncher,
    private val userIdProvider: () -> String?,
) : UserChoiceBillingListener {

    override fun userSelectedAlternativeBilling(details: UserChoiceDetails) {
        val token = details.externalTransactionToken.orEmpty()
        val productId = details.products.firstOrNull()?.id.orEmpty()
        val userId = userIdProvider().orEmpty()
        if (token.isEmpty() || productId.isEmpty() || userId.isEmpty()) {
            logger.warn(
                "ucb",
                "userSelectedAlternativeBilling: missing token/productId/userId — dropping " +
                    "(tokenEmpty=${token.isEmpty()} productEmpty=${productId.isEmpty()} userEmpty=${userId.isEmpty()})",
            )
            return
        }
        logger.info(
            "ucb",
            "userSelectedAlternativeBilling: token=${token.take(12)}… productId=$productId",
        )
        scope.launch {
            val result = launcher.launch(token = token, productId = productId, userId = userId)
            result.onFailure { logger.warn("ucb", "Stripe checkout launch failed: ${it.message}") }
        }
    }
}
