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
}
