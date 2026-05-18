package com.zerosettle.sdk.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.LogcatLogger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2 Chunk D contract: [StripeCheckoutLauncher] must fan its composed
 * [UcbPurchaseOutcome] out to its `onResult` callback BEFORE returning to
 * its caller. This is the bridge that lets [com.zerosettle.sdk.ZeroSettle]'s
 * `buildPlayCoordinator` resolve the in-flight `pendingPlayPurchaseDeferred`
 * when a UCB purchase completes — without it, a UCB-completed purchase
 * would leave the [com.zerosettle.sdk.ZeroSettle.purchaseViaPlayBilling]
 * caller hanging on `await()`.
 *
 * We verify the callback fires with the IDs the launcher reserved from
 * the `/initiate/` response — confirming the bridge composition path
 * (Activity emits a [PaymentSheetStatus], bridge folds in the reserved
 * IDs, launcher hands the final [UcbPurchaseOutcome.Completed] to
 * `onResult`).
 */
@RunWith(RobolectricTestRunner::class)
class StripeCheckoutLauncherCompletionTest {

    private lateinit var server: MockWebServer
    private lateinit var backend: Backend
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val logger = LogcatLogger

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(
            baseUrl = server.url("/").toString().trimEnd('/'),
            publishableKey = "zs_pk_test_abc",
            sdkVersion = "1.0.0",
        )
        UcbResultBridge.reset()
    }

    @After fun tearDown() {
        server.shutdown()
        UcbResultBridge.reset()
    }

    private fun spawnDeliverer(status: PaymentSheetStatus) {
        Thread {
            val deadline = System.currentTimeMillis() + 10_000L
            while (!UcbResultBridge.isReservedForTest() && System.currentTimeMillis() < deadline) {
                Thread.sleep(2)
            }
            UcbResultBridge.deliver(status)
        }.apply { isDaemon = true }.start()
    }

    @Test fun completed_outcome_carriesReservedIds_andIsForwardedToOnResult() = runBlocking {
        // The launcher reserves the bridge with (externalTransactionId, transactionId)
        // from /initiate/. When the activity delivers PaymentSheetStatus.Completed,
        // the bridge composes UcbPurchaseOutcome.Completed using those reserved
        // IDs. The launcher then forwards that to `onResult` before returning.
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_test_abc",
                    "stripe_account":"acct_test_1",
                    "merchant_country":"US",
                    "external_transaction_id":"ext_zzz",
                    "transaction_id":1234
                }""".trimIndent(),
            ),
        )

        val captured = mutableListOf<UcbPurchaseOutcome>()
        val launcher = StripeCheckoutLauncher(
            context = context,
            backend = backend,
            publishableKey = "pk_test_stripe_x",
            isSandbox = true,
            merchantDisplayName = "ZeroSettle",
            logger = logger,
            onResult = { outcome -> captured.add(outcome) },
        )

        spawnDeliverer(PaymentSheetStatus.Completed)
        val result = launcher.launch(token = "tok-x", productId = "pro_monthly", userId = "u")

        assertThat(result.isSuccess).isTrue()
        // onResult fires exactly once with the composed Completed outcome
        // carrying the IDs minted by /initiate/.
        assertThat(captured).hasSize(1)
        val outcome = captured.first()
        assertThat(outcome).isInstanceOf(UcbPurchaseOutcome.Completed::class.java)
        val completed = outcome as UcbPurchaseOutcome.Completed
        assertThat(completed.externalTransactionId).isEqualTo("ext_zzz")
        assertThat(completed.transactionId).isEqualTo(1234L)
    }

    @Test fun canceled_outcome_isForwardedToOnResult() = runBlocking {
        // PaymentSheetStatus.Canceled → UcbPurchaseOutcome.Canceled — IDs aren't
        // carried because there's no transaction to hydrate from a cancel.
        server.enqueue(
            MockResponse().setBody(
                """{"client_secret":"pi","stripe_account":null,"merchant_country":null,"external_transaction_id":"e","transaction_id":1}""",
            ),
        )

        val captured = mutableListOf<UcbPurchaseOutcome>()
        val launcher = StripeCheckoutLauncher(
            context = context, backend = backend, publishableKey = "pk_x",
            isSandbox = true, merchantDisplayName = "ZeroSettle", logger = logger,
            onResult = { outcome -> captured.add(outcome) },
        )

        spawnDeliverer(PaymentSheetStatus.Canceled)
        launcher.launch(token = "t", productId = "p", userId = "u")

        assertThat(captured).hasSize(1)
        assertThat(captured.first()).isEqualTo(UcbPurchaseOutcome.Canceled)
    }

    @Test fun failed_outcome_isForwardedToOnResult() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"client_secret":"pi","stripe_account":null,"merchant_country":null,"external_transaction_id":"e","transaction_id":1}""",
            ),
        )

        val captured = mutableListOf<UcbPurchaseOutcome>()
        val launcher = StripeCheckoutLauncher(
            context = context, backend = backend, publishableKey = "pk_x",
            isSandbox = true, merchantDisplayName = "ZeroSettle", logger = logger,
            onResult = { outcome -> captured.add(outcome) },
        )

        spawnDeliverer(PaymentSheetStatus.Failed("card_declined"))
        launcher.launch(token = "t", productId = "p", userId = "u")

        assertThat(captured).hasSize(1)
        val outcome = captured.first()
        assertThat(outcome).isInstanceOf(UcbPurchaseOutcome.Failed::class.java)
        assertThat((outcome as UcbPurchaseOutcome.Failed).message).contains("card_declined")
    }

    @Test fun initiate_failure_forwardsFailedOutcomeToOnResult_evenWithoutActivity() = runBlocking {
        // When /initiate/ exhausts retries (5xx forever), the launcher never
        // reserves the bridge nor dispatches the Intent. The `onResult`
        // contract still needs to fire so the caller's bridge consumer
        // (e.g., the [pendingPlayPurchaseDeferred] in ZeroSettle.kt) resolves
        // — otherwise a stuck `await()` would lock out future purchases.
        //
        // Enqueue many 500s — launcher retries up to MAX_INITIATE_ATTEMPTS=4
        // and HttpClient inner-retries each, so 12 is generous.
        repeat(12) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }

        val captured = mutableListOf<UcbPurchaseOutcome>()
        val launcher = StripeCheckoutLauncher(
            context = context, backend = backend, publishableKey = "pk_x",
            isSandbox = true, merchantDisplayName = "ZeroSettle", logger = logger,
            onResult = { outcome -> captured.add(outcome) },
        )

        val r = launcher.launch(token = "t", productId = "p", userId = "u")
        assertThat(r.isFailure).isTrue()
        // Even on the no-bridge path, onResult fires with a Failed outcome so
        // any deferred-bridge consumer downstream resolves cleanly.
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).isInstanceOf(UcbPurchaseOutcome.Failed::class.java)
    }
}
