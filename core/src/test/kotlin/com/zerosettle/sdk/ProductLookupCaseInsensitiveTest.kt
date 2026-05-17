package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Backend just shipped case-insensitive `Product.reference_id` lookups + a unique
 * constraint. The local catalog cache must match: a product stored as
 * `io.zerosettle.justone.streakSaver1` must be found when callers ask for
 * `io.zerosettle.justone.streaksaver1` (and any other casing variant).
 */
@RunWith(RobolectricTestRunner::class)
class ProductLookupCaseInsensitiveTest {

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

    private suspend fun seedCatalog(productId: String) {
        server.enqueue(
            MockResponse().setBody(
                """{"products":[{"id":"$productId","display_name":"Streak Saver","product_description":"d","type":"consumable"}]}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        ZeroSettle.identify(Identity.User(id = "u1"))
    }

    @Test fun product_findsByMixedCaseStoredId_whenQueryIsLowercase() = runTest {
        seedCatalog("io.zerosettle.JustOne.streakSaver1")
        val found = ZeroSettle.product("io.zerosettle.justone.streaksaver1")
        assertThat(found).isNotNull()
        assertThat(found?.id).isEqualTo("io.zerosettle.JustOne.streakSaver1")
    }

    @Test fun product_findsByLowercaseStoredId_whenQueryIsUppercase() = runTest {
        seedCatalog("lowercase.thing")
        val found = ZeroSettle.product("LOWERCASE.THING")
        assertThat(found).isNotNull()
        assertThat(found?.id).isEqualTo("lowercase.thing")
    }

    @Test fun product_findsByExactCase_stillWorks() = runTest {
        seedCatalog("exact.case.id")
        val found = ZeroSettle.product("exact.case.id")
        assertThat(found).isNotNull()
        assertThat(found?.id).isEqualTo("exact.case.id")
    }

    @Test fun product_unknownId_returnsNull() = runTest {
        seedCatalog("known.id")
        assertThat(ZeroSettle.product("unknown.id")).isNull()
    }

    @Test fun product_emptyString_returnsNull() = runTest {
        seedCatalog("known.id")
        assertThat(ZeroSettle.product("")).isNull()
    }

    @Test fun product_withEmptyCatalog_returnsNull() = runTest {
        // No identify → catalog stays empty.
        assertThat(ZeroSettle.product("anything")).isNull()
    }
}
