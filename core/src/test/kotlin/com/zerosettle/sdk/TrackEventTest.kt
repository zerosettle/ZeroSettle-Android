package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.FunnelEventType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TrackEventTest {
    private val server = MockWebServer()

    private fun configure() {
        server.start()
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

    @Test fun rawValuesMatchIos() {
        assertThat(FunnelEventType.values().map { it.wireValue })
            .containsExactly("paywall_viewed", "checkout_started", "checkout_abandoned").inOrder()
    }

    @Test fun postsEventWithAnalyticsUserId_andOmitsEnvironment() {
        configure()
        server.enqueue(MockResponse().setBody("{}"))
        ZeroSettle.trackEvent(
            FunnelEventType.PAYWALL_VIEWED, productId = "pro", screenName = "home", metadata = mapOf("k" to "v"),
        )
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(req.path).isEqualTo("/v1/iap/events/")
        val body = JSONObject(req.body.readUtf8())
        assertThat(body.getString("event_type")).isEqualTo("paywall_viewed")
        assertThat(body.getString("product_id")).isEqualTo("pro")
        assertThat(body.getString("screen_name")).isEqualTo("home")
        assertThat(body.getJSONObject("metadata").getString("k")).isEqualTo("v")
        assertThat(body.getString("user_id")).isNotEmpty()
        assertThat(body.has("environment")).isFalse()
    }

    @Test fun omitsOptionalKeys_andNeverThrowsOnServerError() {
        configure()
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        ZeroSettle.trackEvent(FunnelEventType.CHECKOUT_STARTED, productId = "pro")
        val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertThat(body.has("screen_name")).isFalse()
        assertThat(body.has("metadata")).isFalse()
    }

    @Test fun beforeConfigure_dropsSilently() {
        ZeroSettle.trackEvent(FunnelEventType.CHECKOUT_ABANDONED, productId = "pro")
    }
}
