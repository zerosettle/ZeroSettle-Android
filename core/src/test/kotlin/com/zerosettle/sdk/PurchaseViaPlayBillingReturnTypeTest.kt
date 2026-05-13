package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.CheckoutTransaction
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Compile-time-checked contract pin for [ZeroSettle.purchaseViaPlayBilling]:
 * the function returns `Result<CheckoutTransaction>` end-to-end (mirror of
 * [PurchaseReturnTypeTest] for the web checkout path).
 *
 * Strategy mirrors A2: bind the call result to an explicitly-typed
 * `Result<CheckoutTransaction>` local. If `purchaseViaPlayBilling` regresses
 * to `Result<Unit>` (or any other generic param) this test stops compiling.
 * We invoke without `identify()` so the call short-circuits with
 * `UserNotIdentified` — no Play Billing or network involved.
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseViaPlayBillingReturnTypeTest {

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

    @Test fun `purchaseViaPlayBilling return type binds to Result of CheckoutTransaction`() = runTest {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val typed: Result<CheckoutTransaction> = ZeroSettle.purchaseViaPlayBilling(activity, productId = "pro_monthly")
        // Force the compiler to emit the bind (some compilers fold unused bindings).
        assertThat(typed.isFailure).isTrue()
        // Sanity: identity short-circuit is reached without any Play Billing wiring.
        assertThat(typed.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }
}
