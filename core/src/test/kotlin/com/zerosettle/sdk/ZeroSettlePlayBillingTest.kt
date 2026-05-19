package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettlePlayBillingTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = true,
            ),
        )
    }

    @After fun tearDown() { server.shutdown(); ZeroSettle.resetForTesting() }

    @Test fun purchaseViaPlayBilling_withoutIdentify_returnsUserNotIdentified() = runTest {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val res = ZeroSettle.purchaseViaPlayBilling(activity, productId = "pro_monthly")
        assertThat(res.exceptionOrNull()).isAnyOf(
            ZeroSettleError.UserNotIdentified, ZeroSettleError.ProductNotFound("pro_monthly"),
        )
    }

    @Test fun logout_clearsSyncQueue() = runTest {
        ZeroSettle.playSyncQueueForTesting().enqueue(
            com.zerosettle.sdk.billing.PendingPurchaseSync("tok1", "pro", "com.app", "u1"),
        )
        assertThat(ZeroSettle.playSyncQueueForTesting().pending()).hasSize(1)
        ZeroSettle.logout()
        assertThat(ZeroSettle.playSyncQueueForTesting().pending()).isEmpty()
    }

    // ─── Deferred-bridge integration (Task A3 follow-up) ─────────────────────
    //
    // The 6 PurchaseSyncProcessorTest cases verify the processor's callback
    // INVOCATION (with locally-injected list-recorder callbacks). They don't
    // exercise the configure-time wiring at ZeroSettle.kt where the production
    // bridge resolves the SDK's `pendingPlayPurchaseDeferred` slot:
    //
    //   onPurchaseSynced = { txnId -> pendingPlayPurchaseDeferred?.complete(txnId) },
    //   onPurchaseFailed = { err -> pendingPlayPurchaseDeferred?.completeExceptionally(err) },
    //
    // The seam where a silent regression is most likely (slot swap with A2's
    // pendingCheckoutDeferred, complete/completeExceptionally swap). These
    // tests drive a fake Purchase through the coordinator that `configure()`
    // built, and assert the awaiter resolves correctly.

    /** Route MockWebServer responses by URL path. */
    private fun routeBy(routes: Map<String, () -> MockResponse>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            val match = routes.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }
            return match?.value?.invoke()
                ?: MockResponse().setResponseCode(404).setBody("unmocked path: $path")
        }
    }

    /** Build a fake `com.android.billingclient.api.Purchase` from inline JSON. */
    private fun fakePurchase(
        token: String = "tok-abc",
        productId: String = "pro_monthly",
        packageName: String = "com.app",
        purchaseState: Int = 0, // 0 = PURCHASED in originalJson; mapped to 1 by SDK
    ): com.android.billingclient.api.Purchase {
        val json = """
        {"orderId":"GPA.1234","packageName":"$packageName","productId":"$productId",
         "purchaseTime":1700000000000,"purchaseState":$purchaseState,"purchaseToken":"$token",
         "autoRenewing":true,"acknowledged":false}
        """.trimIndent()
        return com.android.billingclient.api.Purchase(json, "sig-xyz")
    }

    @Test fun deferredBridge_onPurchaseSynced_resolvesAwaiterWithTransactionId() = runTest {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            // syncPlayPurchase response: owned=true plus a transactionId
            "/v1/iap/play-store-transactions/" to { MockResponse().setBody(
                """{"owned":true,"transaction_id":9101}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        // Arm the slot directly (the real purchaseViaPlayBilling() would do this
        // BEFORE launching the Play dialog, but the dialog launch needs a real
        // BillingClient which Robolectric can't provide).
        val deferred = ZeroSettle.armPendingPlayPurchaseForTesting()

        // Drive a fake purchase through the same coordinator that configure()
        // built — its `onPurchaseSynced` callback is the production bridge wiring.
        ZeroSettle.playCoordinator!!.processPurchaseForTesting(fakePurchase(token = "tok_1"))

        val txnId = withTimeout(5000) { deferred.await() }
        assertThat(txnId).isEqualTo("9101")
    }

    @Test fun deferredBridge_notOwnedResponse_failsAwaiter() = runTest {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/play-store-transactions/" to { MockResponse().setBody("""{"owned":false}""") },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val deferred = ZeroSettle.armPendingPlayPurchaseForTesting()
        ZeroSettle.playCoordinator!!.processPurchaseForTesting(fakePurchase(token = "tok_2"))

        val err = try {
            withTimeout(5000) { deferred.await() }
            error("expected deferred to complete exceptionally")
        } catch (e: ZeroSettleError.CheckoutFailed) {
            e
        }
        assertThat(err.reason).isEqualTo("not_owned")
    }

    @Test fun deferredBridge_ownershipConflict_failsAwaiterWithOwnershipConflict() = runTest {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/play-store-transactions/" to { MockResponse().setBody(
                """{"owned":false,"conflict":true,"claim_available":true,"existing_owner_hint":"al***"}""",
            ) },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val deferred = ZeroSettle.armPendingPlayPurchaseForTesting()
        ZeroSettle.playCoordinator!!.processPurchaseForTesting(fakePurchase(token = "tok_3"))

        val err = try {
            withTimeout(5000) { deferred.await() }
            error("expected deferred to complete exceptionally")
        } catch (e: ZeroSettleError.CheckoutFailed) {
            e
        }
        assertThat(err.reason).isEqualTo("ownership_conflict")
    }

    /**
     * Regression for Issue 1: even if [com.zerosettle.sdk.billing.PlayBillingManager.acknowledge]
     * THROWS, the awaiter must still resolve with the transactionId. The local
     * Play ack is bookkeeping that the user-visible purchase doesn't depend on
     * — `onPurchaseSynced` runs BEFORE `acknowledge` in [PurchaseSyncProcessor.process],
     * and the ack call is wrapped in `runCatching` so a throw is logged-only.
     *
     * Direct-processor test (rather than going through ZeroSettle.playCoordinator)
     * because the production coordinator's `acknowledge` lambda routes through a
     * real `BillingClient` which we can't make throw deterministically. The
     * `processor.process()` contract is what we're pinning.
     */
    // ─── Cancel-hang regression ──────────────────────────────────────────────
    //
    // Before the fix, PlayBillingManager's PurchasesUpdatedListener silently
    // swallowed USER_CANCELED — so dismissing the Play sheet / choice screen
    // left a pending purchaseViaPlayBilling() deferred unresolved forever
    // (hung until process death). The listener now routes terminal results
    // through onPurchaseFailed, resolving the deferred with a failure.
    //
    // Without the fix these tests hang and fail via withTimeout.

    @Test fun listenerUserCanceled_resolvesAwaiterWithPurchaseCancelled() = runTest {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        // Arm the slot as purchaseViaPlayBilling() would BEFORE launching the
        // Play dialog (the real dialog launch needs a BillingClient Robolectric
        // can't provide).
        val deferred = ZeroSettle.armPendingPlayPurchaseForTesting()

        // User dismisses the Play purchase sheet / UCB choice screen.
        ZeroSettle.playCoordinator!!.simulateListenerForTesting(
            com.android.billingclient.api.BillingClient.BillingResponseCode.USER_CANCELED,
            "user dismissed",
            purchases = null,
        )

        val err = try {
            withTimeout(5000) { deferred.await() }
            error("expected deferred to complete exceptionally (cancel must not hang)")
        } catch (e: com.zerosettle.sdk.models.ZeroSettleError) {
            e
        }
        assertThat(err).isEqualTo(ZeroSettleError.PurchaseCancelled)
    }

    @Test fun listenerServiceError_resolvesAwaiterWithPlayBillingError() = runTest {
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        val deferred = ZeroSettle.armPendingPlayPurchaseForTesting()
        ZeroSettle.playCoordinator!!.simulateListenerForTesting(
            com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            "billing service down",
            purchases = null,
        )

        val err = try {
            withTimeout(5000) { deferred.await() }
            error("expected deferred to complete exceptionally")
        } catch (e: com.zerosettle.sdk.models.ZeroSettleError) {
            e
        }
        assertThat(err).isInstanceOf(ZeroSettleError.PlayBillingError::class.java)
    }

    @Test fun deferredBridge_resolvesEvenWhenAcknowledgeThrows() = runTest {
        // Standalone processor with the same callback wiring as the production
        // coordinator. If `acknowledge` throwing strands the deferred, this test
        // hangs and fails with timeout.
        val queue = com.zerosettle.sdk.billing.PlaySyncQueue(ApplicationProvider.getApplicationContext()).also { it.clear() }
        val backend = com.zerosettle.sdk.core.Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
        server.enqueue(MockResponse().setBody("""{"owned":true,"transaction_id":9102}"""))

        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        val proc = com.zerosettle.sdk.billing.PurchaseSyncProcessor(
            backend = backend, queue = queue,
            // Finalize throws — the very regression Issue 1 is about.
            finalize = { _, _ -> throw RuntimeException("finalize boom (simulated)") },
            emitEvent = { },
            onPurchaseSynced = { txnId -> deferred.complete(txnId) },
            onPurchaseFailed = { err -> deferred.completeExceptionally(err) },
            strictAck = false, nowMillis = { 0L },
        )
        // process() itself must not propagate the acknowledge throw (it's
        // wrapped in runCatching). The awaiter must still resolve.
        proc.process(
            com.zerosettle.sdk.billing.PurchaseDescriptor(
                purchaseToken = "tok_x", productId = "pro_monthly", packageName = "com.app",
                userId = "u1", orderId = "GPA.1", purchaseState = 1, isAcknowledged = false,
                signature = "sig", originalJson = "{}", willAutoRenew = true,
                customerName = null, customerEmail = null,
            ),
        )
        val txnId = withTimeout(5000) { deferred.await() }
        assertThat(txnId).isEqualTo("9102")
    }
}
