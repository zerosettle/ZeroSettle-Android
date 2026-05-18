package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.billing.UcbConfig
import kotlinx.coroutines.test.runTest
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
 * UCB Phase 2 Chunk D integration: verifies that `ZeroSettle.bootstrap()`
 *  - fetches `/v1/iap/play-billing-config/` after the existing
 *    products + entitlements calls, and caches the result;
 *  - rebuilds the [com.zerosettle.sdk.billing.PlayBillingCoordinator] with
 *    the fresh [UcbConfig] so the [com.android.billingclient.api.BillingClient]
 *    bakes the right `enableUserChoiceBilling` setting at construction;
 *  - tolerates a backend failure (404) by leaving the cached config at
 *    [UcbConfig.Disabled] — bootstrap still succeeds.
 *
 * We can't drive a real UCB choice through Robolectric (no BillingClient).
 * What we can check is that the configure/bootstrap pipeline reaches the
 * UCB endpoint and that the SDK-singleton state (coordinator existence,
 * cached config) reflects the response. The actual checkout flow is
 * verified separately via [com.zerosettle.sdk.billing.StripeCheckoutLauncherTest]
 * and on-device in Chunk D smoke tests.
 */
@RunWith(RobolectricTestRunner::class)
class ZeroSettleUcbIntegrationTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
        ZeroSettle.resetForTesting()
    }

    /** Path-prefix dispatcher with a 404 catch-all — mirrors prior art in [ZeroSettlePlayBillingTest.routeBy]. */
    private fun routeBy(routes: Map<String, () -> MockResponse>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            val match = routes.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }
            return match?.value?.invoke()
                ?: MockResponse().setResponseCode(404).setBody("unmocked path: $path")
        }
    }

    private fun configure() {
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                // syncPlayPurchases = true so a PlayBillingCoordinator gets built —
                // without it, the bootstrap rebuild path is a no-op.
                syncPlayPurchases = true,
            ),
        )
    }

    @Test fun bootstrap_fetchesUcbConfig_andPopulatesRepository() = runTest {
        // Backend returns an enabled UCB config. After bootstrap, the
        // SDK's repository must reflect it — and the coordinator must
        // exist (built during configure, rebuilt during bootstrap with
        // the fresh config in hand).
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/play-billing-config/" to { MockResponse().setBody(
                """{"is_enabled":true,"dma_alt_billing_only_eea":false,"logo_banner_url":"https://cdn.example.com/b.png","subscription_management_urls":{}}""",
            ) },
        ))

        configure()
        val res = ZeroSettle.identify(Identity.User(id = "u1"))

        assertThat(res.isSuccess).isTrue()
        assertThat(ZeroSettle.isBootstrapped.value).isTrue()
        assertThat(ZeroSettle.playCoordinator).isNotNull()
        // The repository's cached config reflects the backend response.
        val cached = ZeroSettle.ucbConfigRepository?.config?.value
        assertThat(cached).isNotNull()
        assertThat(cached!!.isEnabled).isTrue()
        assertThat(cached.logoBannerUrl).isEqualTo("https://cdn.example.com/b.png")
    }

    @Test fun bootstrap_ucbConfigEndpoint404_leavesConfigDisabled_andStillSucceeds() = runTest {
        // 404 from /play-billing-config/ is the "tenant hasn't opted in" path
        // (or the endpoint isn't shipped yet on a self-hosted backend). The
        // repository's cached value must stay at UcbConfig.Disabled, and
        // bootstrap must still report success — the rest of the SDK is
        // unaffected.
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/play-billing-config/" to { MockResponse().setResponseCode(404).setBody("not found") },
        ))

        configure()
        val res = ZeroSettle.identify(Identity.User(id = "u1"))

        assertThat(res.isSuccess).isTrue()
        assertThat(ZeroSettle.isBootstrapped.value).isTrue()
        assertThat(ZeroSettle.playCoordinator).isNotNull()
        assertThat(ZeroSettle.ucbConfigRepository?.config?.value).isEqualTo(UcbConfig.Disabled)
    }

    @Test fun bootstrap_ucbConfigEndpoint5xx_leavesConfigDisabled_andStillSucceeds() = runTest {
        // A 5xx (transient backend hiccup at launch) must NOT block
        // bootstrap. HttpClient already retries once on 5xx; whether the
        // retry succeeds or also 5xxs, the SDK must default to the
        // standard Play Billing path (cached config stays Disabled).
        server.dispatcher = routeBy(mapOf(
            "/v1/iap/products/" to { MockResponse().setBody("""{"products":[]}""") },
            "/v1/iap/entitlements/" to { MockResponse().setBody("""{"entitlements":[]}""") },
            "/v1/iap/play-billing-config/" to { MockResponse().setResponseCode(500).setBody("boom") },
        ))

        configure()
        val res = ZeroSettle.identify(Identity.User(id = "u1"))

        assertThat(res.isSuccess).isTrue()
        assertThat(ZeroSettle.ucbConfigRepository?.config?.value).isEqualTo(UcbConfig.Disabled)
    }
}
