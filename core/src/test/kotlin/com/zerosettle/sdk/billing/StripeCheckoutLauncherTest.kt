package com.zerosettle.sdk.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.Backend
import com.zerosettle.sdk.core.LogcatLogger
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * StripeCheckoutLauncher contract (Phase 2 Chunk C, Task 2.5).
 *
 * The launcher is the Stripe-backed implementation of [UcbCheckoutLauncher].
 * It hits `POST /v1/iap/play-ucb/initiate/`, dispatches an Intent to
 * [UcbPaymentSheetActivity] with all extras populated, and awaits the
 * activity result via the static [UcbResultBridge].
 *
 * These tests verify everything up to-but-not-including the actual
 * [com.stripe.android.paymentsheet.PaymentSheet] presentation:
 *   - Wire-shape of the `/initiate/` POST body
 *   - Decoding of the response into the Intent extras
 *   - Retry policy on 5xx (1s/2s/4s exponential backoff)
 *   - 422 `stripe_tax_not_configured` surfacing as a typed failure
 *   - 5xx exhaustion surfacing the final error
 *
 * The PaymentSheet presentation itself is verified on a device in Chunk D —
 * Robolectric doesn't fully provision Stripe's Activity-result hosts.
 *
 * **Why `runBlocking` instead of `runTest`?** `runTest` virtualizes
 * [kotlinx.coroutines.delay] — useful for the retry-helper unit tests — but
 * it also enforces "all coroutines complete inside the test scope" with a
 * hard 60s wall-clock budget. The launcher's `suspend fun launch()` parks on
 * a `CompletableDeferred.await()` waiting for the [UcbResultBridge] to be
 * completed by an Activity (or, in this test, by a delivery thread).
 * Coordinating that across `Dispatchers.IO` (HttpClient.withContext) and the
 * test scheduler reliably forced timeouts. `runBlocking` is simpler: a real
 * thread for the launcher, a real thread for the bridge delivery, real
 * sleeps in the retry helper but only briefly because each launcher test
 * uses ≤1 retry. The retry helper's full 1s/2s/4s budget is exercised in
 * [StripeCheckoutLauncherRetryTest] with virtual time.
 */
@RunWith(RobolectricTestRunner::class)
class StripeCheckoutLauncherTest {

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
        // Each test starts from a clean bridge state — a previous test's stale
        // outcome must not leak forward.
        UcbResultBridge.reset()
    }

    @After fun tearDown() {
        server.shutdown()
        UcbResultBridge.reset()
    }

    private fun newLauncher(
        isSandbox: Boolean = true,
        publishableKey: String = "pk_test_stripe_123",
    ): StripeCheckoutLauncher = StripeCheckoutLauncher(
        context = context,
        backend = backend,
        publishableKey = publishableKey,
        isSandbox = isSandbox,
        merchantDisplayName = "Acme Inc",
        logger = logger,
    )

    /**
     * Spawn a daemon thread that polls until the launcher has reserved the
     * bridge, then delivers [outcome]. The launcher reserves synchronously
     * at the top of `launch()` (before the HTTP POST), so the poll usually
     * exits within a few millis once `runBlocking { launcher.launch() }`
     * starts. Bounded to 10s wall-clock so a misconfigured test surfaces as
     * the suspension-side `pending.await()` returning a Failed outcome via
     * `reset()` at tearDown, rather than as a deadlock.
     */
    private fun spawnDeliverer(status: PaymentSheetStatus) {
        Thread {
            val deadline = System.currentTimeMillis() + 10_000L
            while (!UcbResultBridge.isReservedForTest() && System.currentTimeMillis() < deadline) {
                Thread.sleep(2)
            }
            UcbResultBridge.deliver(status)
        }.apply { isDaemon = true }.start()
    }

    @Test fun happyPath_postsCorrectBody_andDispatchesIntent() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_test_abc_secret_xyz",
                    "stripe_account":"acct_test_123",
                    "merchant_country":"US",
                    "external_transaction_id":"abcdef0123456789abcdef0123456789",
                    "transaction_id":42
                }""".trimIndent(),
            ),
        )
        val launcher = newLauncher()

        // Spawn the deliverer first so the launcher's pending.await() returns
        // promptly once the HTTP roundtrip completes and the Intent is
        // dispatched.
        spawnDeliverer(PaymentSheetStatus.Completed)
        val result = launcher.launch(token = "tok-abc", productId = "pro_monthly", userId = "user-7")

        // ── HTTP wire-shape ────────────────────────────────────────────────
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/iap/play-ucb/initiate/")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"external_transaction_token\":\"tok-abc\"")
        assertThat(body).contains("\"product_id\":\"pro_monthly\"")
        assertThat(body).contains("\"user_id\":\"user-7\"")

        // ── Intent dispatched to UcbPaymentSheetActivity with all extras ──
        val shadow = shadowOf(context as android.app.Application)
        val nextIntent = shadow.peekNextStartedActivity()
        assertThat(nextIntent).isNotNull()
        assertThat(nextIntent.component?.className).isEqualTo(UcbPaymentSheetActivity::class.java.name)
        assertThat(nextIntent.getStringExtra(UcbPaymentSheetActivity.EXTRA_CLIENT_SECRET))
            .isEqualTo("pi_test_abc_secret_xyz")
        assertThat(nextIntent.getStringExtra(UcbPaymentSheetActivity.EXTRA_STRIPE_ACCOUNT))
            .isEqualTo("acct_test_123")
        assertThat(nextIntent.getStringExtra(UcbPaymentSheetActivity.EXTRA_MERCHANT_COUNTRY))
            .isEqualTo("US")
        assertThat(nextIntent.getStringExtra(UcbPaymentSheetActivity.EXTRA_PUBLISHABLE_KEY))
            .isEqualTo("pk_test_stripe_123")
        assertThat(nextIntent.getBooleanExtra(UcbPaymentSheetActivity.EXTRA_IS_SANDBOX, false))
            .isTrue()
        assertThat(nextIntent.getStringExtra(UcbPaymentSheetActivity.EXTRA_MERCHANT_DISPLAY_NAME))
            .isEqualTo("Acme Inc")

        // ── launch() resolves to success when the bridge completes ────────
        assertThat(result.isSuccess).isTrue()
    }

    @Test fun bridge_canceled_resultsInPurchaseCancelledError() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_test","stripe_account":null,"merchant_country":null,
                    "external_transaction_id":"x","transaction_id":1
                }""".trimIndent(),
            ),
        )
        val launcher = newLauncher()
        spawnDeliverer(PaymentSheetStatus.Canceled)

        val r = launcher.launch("tok", "pro", "u")
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()).isInstanceOf(ZeroSettleError.PurchaseCancelled::class.java)
    }

    @Test fun bridge_failed_resultsInCheckoutFailedError() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_test","stripe_account":null,"merchant_country":null,
                    "external_transaction_id":"x","transaction_id":1
                }""".trimIndent(),
            ),
        )
        val launcher = newLauncher()
        spawnDeliverer(PaymentSheetStatus.Failed("card_declined"))

        val r = launcher.launch("tok", "pro", "u")
        assertThat(r.isFailure).isTrue()
        val err = r.exceptionOrNull()
        assertThat(err).isInstanceOf(ZeroSettleError.CheckoutFailed::class.java)
        assertThat((err as ZeroSettleError.CheckoutFailed).reason).contains("card_declined")
    }

    @Test fun http422_stripeTaxNotConfigured_surfacesAsCheckoutFailed() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody("""{"error":"stripe_tax_not_configured","detail":"Enable Tax in Stripe."}"""),
        )
        val launcher = newLauncher()
        val r = launcher.launch("tok", "pro", "u")

        assertThat(r.isFailure).isTrue()
        val err = r.exceptionOrNull()
        assertThat(err).isInstanceOf(ZeroSettleError.CheckoutFailed::class.java)
        assertThat((err as ZeroSettleError.CheckoutFailed).reason).contains("stripe_tax_not_configured")
    }

    @Test fun http500_thenSuccess_retriesAndSucceeds() = runBlocking {
        // First launcher attempt sees 500→200ms-retry→500 (both fail).
        // Second launcher attempt sees the success response. The launcher's
        // own backoff between attempt 1 and 2 is 1s real time (BASE_BACKOFF_MS).
        // We stop the retry chain after 1 launcher-level retry — the full
        // 1s/2s/4s budget is exercised in StripeCheckoutLauncherRetryTest
        // with virtual time, here we just verify the launcher's outer loop
        // actually runs at least once.
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(
            MockResponse().setBody(
                """{
                    "client_secret":"pi_test","stripe_account":null,"merchant_country":null,
                    "external_transaction_id":"x","transaction_id":1
                }""".trimIndent(),
            ),
        )
        val launcher = newLauncher()
        spawnDeliverer(PaymentSheetStatus.Completed)

        val r = launcher.launch("tok", "pro", "u")

        assertThat(r.isSuccess).isTrue()
        // At least 3 wire requests should have hit MockWebServer (2 failing,
        // 1 succeeding). HttpClient's inner retry may bump the count higher.
        assertThat(server.requestCount).isAtLeast(3)
    }

    @Test fun http500_allAttemptsExhausted_surfacesLastError() = runBlocking {
        // Exhaust the budget: 4 launcher attempts × 2 wire requests each
        // (HttpClient retries 5xx once internally) = 8 total 500s before the
        // launcher surfaces a final failure. Enqueue 12 to be safe.
        //
        // Wall-clock cost: launcher's exponential backoff between attempts is
        // 1s/2s/4s = 7s plus HttpClient inner retry 200ms × 4 = 800ms. Test
        // is expected to take ~8s, faster than the gradle default test
        // timeout.
        repeat(12) { server.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
        val launcher = newLauncher()

        val r = launcher.launch("tok", "pro", "u")

        assertThat(r.isFailure).isTrue()
        val err = r.exceptionOrNull()
        assertThat(err).isInstanceOf(ZeroSettleError.BackendError::class.java)
        assertThat((err as ZeroSettleError.BackendError).statusCode).isEqualTo(500)
    }
}

/**
 * Pure-function tests for the retry helper inside [StripeCheckoutLauncher].
 * Verified through public observable behaviour (calls into a fake op) instead
 * of reflection — the helper's contract is "retry on failure with delays
 * 1s/2s/4s, surface the last error after 4 attempts".
 */
class StripeCheckoutLauncherRetryTest {

    @Test fun retry_succeedsImmediately_callsOpOnce() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val r = StripeCheckoutLauncher.retryForTest(times = 4) {
            calls++
            Result.success("ok")
        }
        assertThat(r.getOrNull()).isEqualTo("ok")
        assertThat(calls).isEqualTo(1)
    }

    @Test fun retry_failsThenSucceeds_returnsSuccess() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val r = StripeCheckoutLauncher.retryForTest(times = 4) {
            calls++
            if (calls < 3) Result.failure(RuntimeException("transient")) else Result.success("ok")
        }
        assertThat(r.getOrNull()).isEqualTo("ok")
        assertThat(calls).isEqualTo(3)
    }

    @Test fun retry_allAttemptsFail_returnsLastError() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val r: Result<String> = StripeCheckoutLauncher.retryForTest(times = 4) {
            calls++
            Result.failure(RuntimeException("err-$calls"))
        }
        assertThat(r.isFailure).isTrue()
        assertThat(r.exceptionOrNull()?.message).isEqualTo("err-4")
        assertThat(calls).isEqualTo(4)
    }

    @Test fun retry_skipsNonRetryableErrors() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val r: Result<String> = StripeCheckoutLauncher.retryForTest(
            times = 4,
            retryable = { false },
        ) {
            calls++
            Result.failure(RuntimeException("non-retryable"))
        }
        assertThat(r.isFailure).isTrue()
        // No retries because retryable returned false on the first failure.
        assertThat(calls).isEqualTo(1)
    }
}
