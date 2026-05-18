package com.zerosettle.sdk.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UserChoiceBillingListener
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.zerosettle.sdk.core.ZeroSettleLogger
import com.zerosettle.sdk.models.ZeroSettleError
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Outcome of interpreting a single [PurchasesUpdatedListener] fire.
 *
 * The listener delivers exactly one of these per callback:
 *  - [Deliver]: `OK` with a non-empty purchase list — hand off to the normal
 *    sync path.
 *  - [Fail]: a terminal result that means "no purchase is coming" — the
 *    purchase flow ended without a purchase, so any in-flight
 *    `purchaseViaPlayBilling()` deferred must be resolved with a failure
 *    rather than left hanging. Covers `USER_CANCELED` (mapped to
 *    [ZeroSettleError.PurchaseCancelled]), every other non-`OK` code, AND
 *    `OK` with a null/empty purchase list (Play can report this when the
 *    flow closes without yielding a purchase).
 */
internal sealed interface PurchaseListenerOutcome {
    data class Deliver(val purchases: List<Purchase>) : PurchaseListenerOutcome
    data class Fail(val error: ZeroSettleError) : PurchaseListenerOutcome
}

/**
 * Pure classifier for a [PurchasesUpdatedListener] fire — top-level so it's
 * unit-testable without an Android context or a real [BillingClient] (mirrors
 * [isConsumable] / [playBillingProductType]).
 *
 * `OK` + non-empty purchases → [PurchaseListenerOutcome.Deliver]. Everything
 * else → [PurchaseListenerOutcome.Fail]:
 *  - `USER_CANCELED` → [ZeroSettleError.PurchaseCancelled] (a cancellation,
 *    not a generic error — the SDK distinguishes them).
 *  - any other non-`OK` code → [ZeroSettleError.PlayBillingError] via
 *    [PlayBillingManager.mapBillingError].
 *  - `OK` with a null/empty purchase list → [ZeroSettleError.PurchaseCancelled]:
 *    the flow closed without yielding a purchase, semantically equivalent to
 *    a cancellation from the caller's perspective.
 *
 * The historical leak this fixes: the listener used to silently swallow
 * `USER_CANCELED` (and merely log other non-`OK` codes), so a
 * `purchaseViaPlayBilling()` caller awaiting the deferred-bridge hung forever
 * when the user dismissed the Play sheet / choice screen.
 */
internal fun classifyPurchaseListenerResult(
    responseCode: Int,
    debugMessage: String,
    purchases: List<Purchase>?,
): PurchaseListenerOutcome = when {
    responseCode == BillingClient.BillingResponseCode.OK && !purchases.isNullOrEmpty() ->
        PurchaseListenerOutcome.Deliver(purchases)
    responseCode == BillingClient.BillingResponseCode.OK ->
        // OK but nothing delivered — treat as a cancellation so an armed
        // deferred resolves instead of hanging.
        PurchaseListenerOutcome.Fail(ZeroSettleError.PurchaseCancelled)
    else ->
        PurchaseListenerOutcome.Fail(PlayBillingManager.mapBillingError(responseCode, debugMessage))
}

/**
 * Owns the [BillingClient]: connection lifecycle (with auto-reconnect),
 * `queryProductDetails`, `launchBillingFlow` (with the deterministic
 * `obfuscatedAccountId`), the [PurchasesUpdatedListener], `acknowledgePurchase`,
 * `consumeAsync`, and `queryPurchasesAsync` (used by [SubscriptionReconciler]).
 *
 * Purchases observed via the listener (and via reconcile) are handed to
 * [onPurchases]; the SDK wires that to [PurchaseSyncProcessor]. Finalization is
 * NOT done here automatically — the processor calls [acknowledge] or [consume]
 * (routed by product type in [PlayBillingCoordinator]) only after backend
 * confirmation (3-day-window rule, see [PurchaseSyncProcessor]).
 */
public class PlayBillingManager(
    context: Context,
    private val obfuscatedAccountIdProvider: () -> String?,
    private val onPurchases: (List<Purchase>) -> Unit,
    private val logger: ZeroSettleLogger,
    // Terminal-failure hook for the [PurchasesUpdatedListener]. Invoked when
    // the listener fires with a result that means "no purchase is coming"
    // (user cancelled the Play sheet / choice screen, item unavailable,
    // service error, etc.). The SDK wires this to resolve any in-flight
    // [com.zerosettle.sdk.ZeroSettle.purchaseViaPlayBilling] deferred — without
    // it, a cancelled purchase strands that suspend call forever (cancel-hang).
    // Defaults to a no-op so non-bridging call sites compile unchanged; the
    // wired lambda is itself null-guarded on the deferred slot, so the
    // redelivery-on-relaunch path (no awaiter armed) stays a clean no-op.
    private val onPurchaseFailed: (ZeroSettleError) -> Unit = {},
    // UCB enablement (Phase 2 Chunk B). Defaults preserve binary/source
    // compatibility for existing call sites — when [UcbConfig.isEnabled] is
    // false (the default), the BillingClient builder is identical to the
    // pre-UCB path. See [PlayBillingCoordinator] for the wiring decisions.
    ucbConfig: UcbConfig = UcbConfig.Disabled,
    ucbChoiceListener: UserChoiceBillingListener? = null,
) {
    private val app = context.applicationContext

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (val outcome = classifyPurchaseListenerResult(result.responseCode, result.debugMessage, purchases)) {
            is PurchaseListenerOutcome.Deliver -> onPurchases(outcome.purchases)
            is PurchaseListenerOutcome.Fail -> {
                // Terminal: no purchase is coming. Route through the failure
                // hook so any awaiting purchaseViaPlayBilling() deferred
                // resolves with Result.failure instead of hanging.
                if (outcome.error !is ZeroSettleError.PurchaseCancelled) {
                    logger.warn("billing", "PurchasesUpdated error ${result.responseCode}: ${result.debugMessage}")
                }
                onPurchaseFailed(outcome.error)
            }
        }
    }

    private val client: BillingClient = run {
        val builder = BillingClient.newBuilder(app)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        when {
            ucbConfig.isEnabled && ucbConfig.dmaAltBillingOnlyEea -> {
                // EEA DMA: only show our alt-billing option, no Google choice screen.
                builder.enableAlternativeBillingOnly()
                logger.info("billing", "UCB: Alternative Billing Only (EEA DMA mode)")
            }
            ucbConfig.isEnabled -> {
                // Standard UCB: Google's choice screen routes the alt-billing
                // selection back through our listener. The listener is required
                // at construction — fail fast rather than silently lose the
                // callback at runtime.
                require(ucbChoiceListener != null) {
                    "ucbChoiceListener required when ucbConfig.isEnabled && !dmaAltBillingOnlyEea"
                }
                builder.enableUserChoiceBilling(ucbChoiceListener)
                logger.info("billing", "UCB: User Choice Billing enabled")
            }
            else -> {
                logger.info("billing", "UCB: disabled — standard Play Billing")
            }
        }
        builder.build()
    }

    /**
     * Connect (idempotent). Suspends until the connection is established or fails.
     *
     * PBL 7.x has no `enableAutoServiceReconnection()` (added in 8.0), so every
     * `BillingClient` call goes through [ensureConnected] which re-establishes the
     * connection if `isReady` is false — covering the disconnect-then-call path.
     */
    public suspend fun ensureConnected(): Result<Unit> = suspendCancellableCoroutine { cont ->
        if (client.isReady) { cont.resume(Result.success(Unit)); return@suspendCancellableCoroutine }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(Result.success(Unit))
                else cont.resume(Result.failure(mapBillingError(result.responseCode, result.debugMessage)))
            }
            override fun onBillingServiceDisconnected() { /* next call re-runs ensureConnected() */ }
        })
    }

    public suspend fun queryProductDetails(
        productIds: List<String>,
        productType: String,
    ): Result<List<ProductDetails>> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            productIds.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(productType).build()
            },
        ).build()
        val res = client.queryProductDetails(params)
        return if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(res.productDetailsList ?: emptyList())
        } else {
            Result.failure(mapBillingError(res.billingResult.responseCode, res.billingResult.debugMessage))
        }
    }

    /** Launch the purchase flow for [details] (using its first base-plan offer if a subscription). */
    public suspend fun launchBillingFlow(activity: Activity, details: ProductDetails): Result<Unit> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.subscriptionOfferDetails?.firstOrNull()?.let {
            productParamsBuilder.setOfferToken(it.offerToken)
        }
        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
        obfuscatedAccountIdProvider()?.let { flowParamsBuilder.setObfuscatedAccountId(it) }
        val result = client.launchBillingFlow(activity, flowParamsBuilder.build())
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) Result.success(Unit)
        else Result.failure(mapBillingError(result.responseCode, result.debugMessage))
    }

    public suspend fun acknowledge(purchaseToken: String): Result<Unit> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val result = client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
        )
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) Result.success(Unit)
        else Result.failure(mapBillingError(result.responseCode, result.debugMessage))
    }

    /**
     * Consume a one-time CONSUMABLE purchase via [BillingClient.consumeAsync].
     *
     * `consume` is BOTH an acknowledgement (satisfies Play's 3-day window rule)
     * AND marks the purchase consumed, releasing the product so the user can
     * buy it again. Without it Play returns
     * [BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED] on every
     * subsequent purchase attempt for the same consumable SKU.
     *
     * Only [com.zerosettle.sdk.models.ProductType.CONSUMABLE] goes through here;
     * non-consumables and subscriptions go through [acknowledge]. The routing
     * lives in [PlayBillingCoordinator.isConsumable].
     */
    public suspend fun consume(purchaseToken: String): Result<Unit> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val res = client.consumePurchase(
            ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build(),
        )
        return if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) Result.success(Unit)
        else Result.failure(mapBillingError(res.billingResult.responseCode, res.billingResult.debugMessage))
    }

    /** Query all current purchases of [productType] (SUBS / INAPP). Used by the reconciler. */
    public suspend fun queryPurchases(productType: String): Result<List<Purchase>> {
        ensureConnected().getOrElse { return Result.failure(it) }
        val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(productType).build())
        return if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) Result.success(res.purchasesList)
        else Result.failure(mapBillingError(res.billingResult.responseCode, res.billingResult.debugMessage))
    }

    public fun endConnection() { client.endConnection() }

    /**
     * Test-only: drive the [PurchasesUpdatedListener] path directly with a
     * synthetic result. Robolectric can't provision real Play Services, so a
     * test can't make the system fire the listener — this seam lets a test
     * exercise the exact branching (and `onPurchaseFailed` wiring) the real
     * BillingClient callback would. Mirrors
     * [PlayBillingCoordinator.processPurchaseForTesting].
     */
    internal fun simulateListenerForTesting(responseCode: Int, debugMessage: String, purchases: List<Purchase>?) {
        purchasesListener.onPurchasesUpdated(
            BillingResult.newBuilder().setResponseCode(responseCode).setDebugMessage(debugMessage).build(),
            purchases,
        )
    }

    public companion object {
        public fun mapBillingError(responseCode: Int, debugMessage: String): ZeroSettleError = when (responseCode) {
            BillingClient.BillingResponseCode.USER_CANCELED -> ZeroSettleError.PurchaseCancelled
            else -> ZeroSettleError.PlayBillingError(responseCode = responseCode, debugMessage = debugMessage)
        }

        /** Build a storefront-agnostic [PurchaseDescriptor] from a Play [Purchase]. */
        public fun describePurchase(
            purchase: Purchase,
            userId: String,
            customerName: String?,
            customerEmail: String?,
        ): PurchaseDescriptor = PurchaseDescriptor(
            purchaseToken = purchase.purchaseToken,
            productId = purchase.products.firstOrNull().orEmpty(),
            packageName = purchase.packageName,
            userId = userId,
            orderId = purchase.orderId,
            purchaseState = purchase.purchaseState,
            isAcknowledged = purchase.isAcknowledged,
            signature = purchase.signature,
            originalJson = purchase.originalJson,
            willAutoRenew = purchase.isAutoRenewing,
            customerName = customerName,
            customerEmail = customerEmail,
        )
    }
}
