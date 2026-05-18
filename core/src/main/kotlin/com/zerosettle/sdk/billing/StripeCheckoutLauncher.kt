package com.zerosettle.sdk.billing

import android.content.Context
import android.content.Intent
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.ZeroSettleLogger
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Stripe-backed implementation of [UcbCheckoutLauncher]. Fires when Google's
 * UCB choice screen routes the user to alternative billing
 * ([UcbChoiceHandler]). Steps:
 *
 *   1. `POST /v1/iap/play-ucb/initiate/` with the [externalTransactionToken]
 *      Google handed to [UserChoiceDetails], the [productId] the user
 *      selected, and the SDK's identified [userId]. Backend exchanges the
 *      token with Google (server-side) and returns a Stripe PaymentIntent
 *      client_secret.
 *   2. Dispatches an Intent to [UcbPaymentSheetActivity] carrying the
 *      client_secret + Stripe configuration extras. The activity hosts
 *      [com.stripe.android.paymentsheet.PaymentSheet] and reports its
 *      outcome back through [UcbResultBridge].
 *   3. Suspends on the bridge until the activity completes; maps the
 *      [UcbPurchaseOutcome] onto an SDK-facing `Result<Unit>`.
 *
 * **Retry policy.** Google issues exactly **one**
 * `externalTransactionToken` per `launchBillingFlow`. If `/initiate/`
 * transiently 5xxs and we surface immediately, the token is wasted and the
 * user has to re-attempt the entire purchase (including the choice-screen
 * pick). The launcher retries up to [MAX_INITIATE_ATTEMPTS] times with
 * exponential backoff (1s/2s/4s) before surfacing — matches the plan's
 * "1s/2s/4s" budget.
 *
 * Note that [com.zerosettle.sdk.core.HttpClient] additionally retries 5xx
 * once internally with a 200ms backoff before surfacing as
 * [ZeroSettleError.BackendError]. So the launcher's retry is the *outer*
 * loop on top of that — the effective wire request count per attempt is up
 * to 2× when the upstream is sustained 5xx.
 *
 * **Side-effects on entry.** Reserves the [UcbResultBridge] *before* the
 * `/initiate/` POST, so a failure on `/initiate/` still leaves the bridge
 * cleanly disarmed (the [Result.failure] path calls [UcbResultBridge.reset]
 * via `runCatching { … }` cleanup — see the early-return paths below).
 */
internal class StripeCheckoutLauncher(
    private val context: Context,
    private val backend: Backend,
    private val publishableKey: String,
    private val isSandbox: Boolean,
    private val merchantDisplayName: String,
    private val logger: ZeroSettleLogger,
) : UcbCheckoutLauncher {

    override suspend fun launch(token: String, productId: String, userId: String): Result<Unit> {
        // 1) Reserve the result bridge first. If `/initiate/` then fails, the
        //    bridge is still cleaned up before we surface — otherwise a stale
        //    pending deferred would leak into the next purchase.
        val pending = UcbResultBridge.reserve()
        logger.info("ucb", "StripeCheckoutLauncher.launch token=${token.take(12)}… productId=$productId")

        // 2) POST `/v1/iap/play-ucb/initiate/` with retry on transient errors
        //    (5xx + network). 4xx errors are caller / config issues — retrying
        //    them just delays the failure and wastes the externalTransactionToken
        //    budget on the user's side.
        val initiateResult = retry(MAX_INITIATE_ATTEMPTS, ::isRetryable) {
            backend.initiatePlayUcb(
                externalTransactionToken = token,
                productId = productId,
                userId = userId,
            )
        }
        val response = initiateResult.getOrElse { err ->
            // Map a 422 `stripe_tax_not_configured` to a typed failure — it's
            // the merchant misconfiguration the dashboard tells you to fix.
            // Other errors flow through unchanged.
            UcbResultBridge.reset()
            val mapped = if (err is ZeroSettleError.BackendError && err.statusCode == 422 &&
                err.body.contains("stripe_tax_not_configured")
            ) {
                logger.warn("ucb", "/initiate/ returned 422 stripe_tax_not_configured — surfacing as CheckoutFailed")
                ZeroSettleError.CheckoutFailed("stripe_tax_not_configured: enable Stripe Tax for this merchant in the dashboard")
            } else {
                err
            }
            return Result.failure(mapped)
        }

        // 3) Dispatch the Intent. We have no Activity reference here (the
        //    launcher is process-scoped), so FLAG_ACTIVITY_NEW_TASK is
        //    mandatory — Android will reject a context-less startActivity()
        //    otherwise. The activity inherits whichever task it was started
        //    against and renders the PaymentSheet over the host.
        val intent = Intent(context, UcbPaymentSheetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UcbPaymentSheetActivity.EXTRA_CLIENT_SECRET, response.clientSecret)
            putExtra(UcbPaymentSheetActivity.EXTRA_STRIPE_ACCOUNT, response.stripeAccount)
            putExtra(UcbPaymentSheetActivity.EXTRA_MERCHANT_COUNTRY, response.merchantCountry)
            putExtra(UcbPaymentSheetActivity.EXTRA_PUBLISHABLE_KEY, publishableKey)
            putExtra(UcbPaymentSheetActivity.EXTRA_IS_SANDBOX, isSandbox)
            putExtra(UcbPaymentSheetActivity.EXTRA_MERCHANT_DISPLAY_NAME, merchantDisplayName)
        }
        context.startActivity(intent)

        // 4) Await the bridge. The activity completes the deferred at its
        //    PaymentSheet result callback; we then map the outcome onto a
        //    Result for the SDK boundary.
        val outcome = pending.await()
        return when (outcome) {
            is UcbPurchaseOutcome.Completed -> Result.success(Unit)
            is UcbPurchaseOutcome.Canceled -> Result.failure(ZeroSettleError.PurchaseCancelled)
            is UcbPurchaseOutcome.Failed -> Result.failure(ZeroSettleError.CheckoutFailed(outcome.message))
        }
    }

    /**
     * Retry [op] up to [times] times with exponential backoff (1s, 2s, 4s, …
     * capped at [MAX_BACKOFF_MS]). Returns the first success, or the LAST
     * failure if every attempt fails. Does NOT retry on the final attempt —
     * the loop body sleeps before the *next* attempt, not after the last
     * one.
     *
     * **Why an `internal` member with a separate `retryForTest` shim?** The
     * retry helper is pure (no PaymentSheet, no Stripe SDK) and worth
     * unit-testing in isolation — but it lives inside the launcher class so
     * its dependency on `delay` + logger stays explicit. Exposing it via
     * [retryForTest] on the companion gives tests a callable handle without
     * requiring a full launcher instance.
     */
    private suspend fun <T> retry(
        times: Int,
        retryable: (Throwable) -> Boolean = { true },
        op: suspend () -> Result<T>,
    ): Result<T> {
        var lastErr: Throwable? = null
        repeat(times) { attempt ->
            val r = op()
            if (r.isSuccess) return r
            lastErr = r.exceptionOrNull()
            // Stop early on non-retryable errors (4xx, configuration errors).
            // Retrying them just delays the inevitable surfacing.
            if (lastErr != null && !retryable(lastErr!!)) {
                logger.info("ucb", "/initiate/ failed with non-retryable error: ${lastErr?.message}")
                return Result.failure(lastErr!!)
            }
            if (attempt < times - 1) {
                val backoff = min(BASE_BACKOFF_MS * (1L shl attempt), MAX_BACKOFF_MS)
                logger.warn(
                    "ucb",
                    "/initiate/ failed (attempt ${attempt + 1}/$times): ${lastErr?.message}; retrying in ${backoff}ms",
                )
                delay(backoff)
            }
        }
        return Result.failure(lastErr ?: IllegalStateException("/initiate/ failed without exception"))
    }

    private fun isRetryable(err: Throwable): Boolean = when (err) {
        is ZeroSettleError.BackendError -> err.statusCode in 500..599
        is ZeroSettleError.NetworkError -> true
        else -> false
    }

    internal companion object {
        /**
         * Total attempts on `/initiate/`. With [BASE_BACKOFF_MS] = 1000 the
         * backoff sequence between attempts is 1s / 2s / 4s — matches the
         * plan reference (1s/2s/4s, 4 attempts total). Worst-case latency:
         * ~7s before surfacing. Acceptable trade for not wasting Google's
         * one-shot externalTransactionToken on a flapping backend.
         */
        internal const val MAX_INITIATE_ATTEMPTS: Int = 4
        internal const val BASE_BACKOFF_MS: Long = 1_000L
        internal const val MAX_BACKOFF_MS: Long = 4_000L

        /**
         * Test-only entry point for the [retry] helper. The production
         * helper is `private suspend` inside the class to keep its
         * dependency on the launcher's logger explicit; this shim
         * exposes the same loop body without requiring a launcher
         * instance. Keep the two bodies in sync — if [retry] changes,
         * this must too.
         */
        internal suspend fun <T> retryForTest(
            times: Int,
            retryable: (Throwable) -> Boolean = { true },
            op: suspend () -> Result<T>,
        ): Result<T> {
            var lastErr: Throwable? = null
            repeat(times) { attempt ->
                val r = op()
                if (r.isSuccess) return r
                lastErr = r.exceptionOrNull()
                if (lastErr != null && !retryable(lastErr!!)) return Result.failure(lastErr!!)
                if (attempt < times - 1) {
                    val backoff = min(BASE_BACKOFF_MS * (1L shl attempt), MAX_BACKOFF_MS)
                    delay(backoff)
                }
            }
            return Result.failure(lastErr ?: IllegalStateException("retry failed without exception"))
        }
    }
}
