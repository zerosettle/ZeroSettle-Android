package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.ZeroSettleEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleIdentifyTest {

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

    @After fun tearDown() {
        server.shutdown()
        ZeroSettle.resetForTesting()
    }

    /**
     * Bootstrap (Phase 2 Chunk D) fetches `/v1/iap/play-billing-config/` after
     * products + entitlements. A 404 leaves the cached `UcbConfig` at the
     * disabled default; bootstrap proceeds normally. Tests that exercise the
     * bootstrap pipeline must enqueue this third response in order.
     */
    private fun enqueueUcbConfig404() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
    }

    @Test fun identify_user_fetchesProductsAndSetsBootstrapped() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        enqueueUcbConfig404()
        val res = ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(res.isSuccess).isTrue()
        assertThat(ZeroSettle.isBootstrapped.value).isTrue()
        assertThat(ZeroSettle.activeUserIdForTesting()).isEqualTo("u1")
    }

    @Test fun identify_anonymous_generatesStableUuid() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        enqueueUcbConfig404()
        ZeroSettle.identify(Identity.Anonymous)
        val first = ZeroSettle.activeUserIdForTesting()
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        enqueueUcbConfig404()
        ZeroSettle.identify(Identity.Anonymous)
        val second = ZeroSettle.activeUserIdForTesting()
        assertThat(first).isNotNull()
        assertThat(first).isEqualTo(second)
    }

    @Test fun identify_deferred_doesNotBootstrap() = runTest {
        val res = ZeroSettle.identify(Identity.Deferred)
        assertThat(res.isSuccess).isTrue()
        assertThat(res.getOrNull()).isNull()
        assertThat(ZeroSettle.isBootstrapped.value).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun identify_user_emitsEntitlementsRefreshedEvent() = runTest {
        val collected = mutableListOf<ZeroSettleEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ZeroSettle.events.collect { collected.add(it) }
        }
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        enqueueUcbConfig404()
        ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(collected).contains(ZeroSettleEvent.EntitlementsRefreshed(count = 0))
        job.cancel()
    }

    @Test fun setCustomer_persists() = runTest {
        ZeroSettle.setCustomer(name = "Alice", email = "a@x")
        assertThat(ZeroSettle.customerForTesting()).isEqualTo("Alice" to "a@x")
    }

    @Test fun logout_clearsState() = runTest {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        enqueueUcbConfig404()
        ZeroSettle.identify(Identity.User(id = "u1"))
        ZeroSettle.setCustomer(name = "Alice", email = "a@x")
        ZeroSettle.logout()
        assertThat(ZeroSettle.isBootstrapped.value).isFalse()
        assertThat(ZeroSettle.activeUserIdForTesting()).isNull()
        assertThat(ZeroSettle.customerForTesting()).isEqualTo(null to null)
    }
}
