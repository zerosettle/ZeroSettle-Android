package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression for the offer-impression anonymous-bucket bug: reportOfferViewed
 * previously fell back to `user_id="anonymous"` before identify(), collapsing
 * every distinct pre-identify user into one impression bucket. It now no-ops
 * until an identity exists.
 */
@RunWith(RobolectricTestRunner::class)
class ZeroSettleOfferImpressionSuppressionTest {

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

    private fun routeBy(routes: Map<String, () -> MockResponse>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            val match = routes.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }
            return match?.value?.invoke()
                ?: MockResponse().setResponseCode(404).setBody("unmocked path: $path")
        }
    }

    @Test fun reportOfferViewed_beforeIdentify_isSuppressed() = runBlocking<Unit> {
        var offerViewedHit = false
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/offer-viewed/" to {
                offerViewedHit = true
                MockResponse().setResponseCode(200).setBody("{}")
            },
        ))

        // No identify() yet → must be a no-op, never reaching the network.
        ZeroSettle.reportOfferViewed(productId = "com.app.pro", variantId = 7, flowType = "migration")

        // Give the (suppressed) background launch a chance to fire if it were going to.
        delay(300)
        assertThat(offerViewedHit).isFalse()
    }

    @Test fun reportOfferViewed_afterIdentify_reportsWithIdentifiedUser() = runBlocking<Unit> {
        // Bootstrap fires several requests in a non-deterministic order, so we
        // skip past them below rather than draining a fixed count.
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/play-billing-config/" to { MockResponse().setBody("""{"is_enabled":false}""") },
            "/v1/iap/offer-viewed/" to { MockResponse().setResponseCode(200).setBody("{}") },
        ))
        ZeroSettle.identify(Identity.User(id = "u1"))

        ZeroSettle.reportOfferViewed(productId = "com.app.pro", variantId = 7, flowType = "migration")

        val req = withTimeout(5000) {
            var r: RecordedRequest
            do { r = server.takeRequest() } while (!r.path.orEmpty().startsWith("/v1/iap/offer-viewed/"))
            r
        }
        val body = req.body.readUtf8()
        assertThat(body).contains(""""user_id":"u1"""")
        assertThat(body).doesNotContain("anonymous")
    }
}
