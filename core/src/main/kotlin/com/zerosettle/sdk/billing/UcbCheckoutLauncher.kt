package com.zerosettle.sdk.billing

import com.zerosettle.sdk.models.ZeroSettleError

/**
 * Stripe-backed checkout launcher fired by [UcbChoiceHandler] after the
 * user picks alternative billing on Google's choice screen. The Chunk C
 * implementation (`StripeCheckoutLauncher`) will:
 *   1. POST `{externalTransactionToken, productId, userId}` to
 *      `/v1/iap/play-ucb/initiate/`
 *   2. Receive a Stripe PaymentIntent `client_secret`
 *   3. Present `PaymentSheet` and propagate the result back into the SDK's
 *      existing purchase deferred bridge
 *
 * Until Chunk C lands, [NoopUcbCheckoutLauncher] is wired as the default —
 * it returns [ZeroSettleError.NotConfigured] so the SDK builds and tests
 * pass without dragging Stripe APIs into PlayBillingCoordinator's construction
 * path.
 */
internal interface UcbCheckoutLauncher {
    suspend fun launch(token: String, productId: String, userId: String): Result<Unit>
}

/**
 * Default placeholder used by [PlayBillingCoordinator] until Chunk C wires
 * the real Stripe-backed launcher. Returning [ZeroSettleError.NotConfigured]
 * matches the SDK's convention for "feature path hit before its dependency
 * is wired" and keeps the call-site Result-based.
 */
internal object NoopUcbCheckoutLauncher : UcbCheckoutLauncher {
    override suspend fun launch(token: String, productId: String, userId: String): Result<Unit> =
        Result.failure(ZeroSettleError.NotConfigured)
}
