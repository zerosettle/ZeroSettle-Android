package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class ZeroSettleWebPurchaseTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = false,
            ),
        )
    }

    @After fun tearDown() { server.shutdown(); ZeroSettle.resetForTesting() }

    @Test fun purchase_withoutIdentify_returnsUserNotIdentified() = runTest {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val res = ZeroSettle.purchase(activity, productId = "pro_monthly")
        assertThat(res.exceptionOrNull()).isInstanceOf(ZeroSettleError.UserNotIdentified::class.java)
    }

    @Test fun purchase_afterIdentify_launchesCheckoutAndSetsPendingCheckout() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        ZeroSettle.identify(Identity.User(id = "u1"))

        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://checkout.zerosettle.com/c/abc"}"""))
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val res = ZeroSettle.purchase(activity, productId = "pro_monthly")
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()).isEqualTo("https://checkout.zerosettle.com/c/abc")
        assertThat(ZeroSettle.pendingCheckout.value).isTrue()
    }

    @Test fun completeWebCheckout_clearsPendingAndRefreshesEntitlements() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        ZeroSettle.identify(Identity.User(id = "u1"))
        server.enqueue(MockResponse().setBody("""{"checkout_url":"https://c/x"}"""))
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        ZeroSettle.purchase(activity, productId = "pro_monthly")

        server.enqueue(
            MockResponse().setBody(
                """{"entitlements":[{"id":"e1","product_id":"pro_monthly","source":"web_checkout","is_active":true,"status":"active","purchased_at":"2026-05-11T00:00:00Z"}]}""",
            ),
        )
        ZeroSettle.completeWebCheckout("zerosettle://checkout/return?status=success&transaction_id=txn_1")
        assertThat(ZeroSettle.pendingCheckout.value).isFalse()
        assertThat(ZeroSettle.entitlements.value).hasSize(1)
    }
}
