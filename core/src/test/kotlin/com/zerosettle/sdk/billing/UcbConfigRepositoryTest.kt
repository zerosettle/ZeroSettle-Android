package com.zerosettle.sdk.billing

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.Backend
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UcbConfigRepository contract:
 *  - refresh() on success populates config.value with the decoded UcbConfig.
 *  - refresh() on backend failure leaves config.value at UcbConfig.Disabled
 *    (the SDK treats an unavailable endpoint as "UCB off").
 *  - hasFetchedOnce() flips true after the first refresh() regardless of outcome.
 *  - The mutex serializes parallel refresh() calls so the state can't be
 *    corrupted (last writer wins on success; on failure the value isn't
 *    clobbered).
 *
 * Backend is the production class (constructed against a MockWebServer) —
 * see PurchaseSyncProcessorTest for prior art. This keeps the test wired
 * against the real HTTP/JSON path and avoids leaking an interface purely for
 * mocking.
 */
@RunWith(RobolectricTestRunner::class)
class UcbConfigRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var backend: Backend

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        backend = Backend(server.url("/").toString().trimEnd('/'), "zs_pk_test_abc", "1.0.0")
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun refresh_success_populatesConfigValue() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "is_enabled": true,
              "dma_alt_billing_only_eea": true,
              "logo_banner_url": "https://cdn.example.com/banner.png",
              "subscription_management_urls": {"pro_monthly": "https://billing.example.com/p/abc"}
            }
        """.trimIndent()))

        val repo = UcbConfigRepository(backend)
        val result = repo.refresh()

        assertThat(result.isSuccess).isTrue()
        assertThat(repo.config.value.isEnabled).isTrue()
        assertThat(repo.config.value.dmaAltBillingOnlyEea).isTrue()
        assertThat(repo.config.value.logoBannerUrl).isEqualTo("https://cdn.example.com/banner.png")
        assertThat(repo.config.value.subscriptionManagementUrls)
            .containsExactly("pro_monthly", "https://billing.example.com/p/abc")
    }

    @Test fun refresh_success_defaultedFields_decodeIntoDisabledLikeConfig() = runTest {
        // Backend ships the endpoint but a tenant hasn't enabled UCB yet —
        // the server returns the documented all-defaults payload. The
        // repository should still record this as a successful fetch and
        // leave the config in a "disabled, no banner, no management URLs"
        // shape (identical to UcbConfig.Disabled).
        server.enqueue(MockResponse().setBody("""
            {
              "is_enabled": false,
              "dma_alt_billing_only_eea": false,
              "logo_banner_url": "",
              "subscription_management_urls": {}
            }
        """.trimIndent()))

        val repo = UcbConfigRepository(backend)
        val result = repo.refresh()

        assertThat(result.isSuccess).isTrue()
        assertThat(repo.config.value).isEqualTo(UcbConfig.Disabled)
    }

    @Test fun refresh_backendFailure_leavesConfigAtDisabled() = runTest {
        // Endpoint not yet shipped → 404. Repository must NOT throw, must
        // surface a failure Result, and must leave config.value untouched.
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val repo = UcbConfigRepository(backend)
        val before = repo.config.value
        val result = repo.refresh()

        assertThat(result.isFailure).isTrue()
        assertThat(repo.config.value).isEqualTo(before)
        assertThat(repo.config.value).isEqualTo(UcbConfig.Disabled)
    }

    @Test fun hasFetchedOnce_flipsTrue_onSuccess() = runTest {
        server.enqueue(MockResponse().setBody("""{"is_enabled":true}"""))

        val repo = UcbConfigRepository(backend)
        assertThat(repo.hasFetchedOnce()).isFalse()

        repo.refresh()

        assertThat(repo.hasFetchedOnce()).isTrue()
    }

    @Test fun hasFetchedOnce_flipsTrue_onFailure() = runTest {
        // The contract says hasFetchedOnce flips regardless of outcome —
        // observability needs to distinguish "never tried" from "tried,
        // got 404". A repository that only flipped on success would be
        // indistinguishable from a brand-new repo after the endpoint 404s.
        server.enqueue(MockResponse().setResponseCode(500))

        val repo = UcbConfigRepository(backend)
        repo.refresh()

        assertThat(repo.hasFetchedOnce()).isTrue()
    }

    @Test fun parallelRefresh_doesNotCorruptState() = runTest {
        // Two concurrent refresh() calls. Both backend responses are valid;
        // whichever finishes last is reflected in config.value, but neither
        // throws, neither leaves the repo in a half-updated state, and
        // hasFetchedOnce is true at the end.
        server.enqueue(MockResponse().setBody("""{"is_enabled":true,"logo_banner_url":"a"}"""))
        server.enqueue(MockResponse().setBody("""{"is_enabled":true,"logo_banner_url":"b"}"""))

        val repo = UcbConfigRepository(backend)
        val results = listOf(
            async { repo.refresh() },
            async { repo.refresh() },
        ).awaitAll()

        assertThat(results.all { it.isSuccess }).isTrue()
        assertThat(repo.config.value.isEnabled).isTrue()
        assertThat(repo.config.value.logoBannerUrl).isAnyOf("a", "b")
        assertThat(repo.hasFetchedOnce()).isTrue()
    }
}
