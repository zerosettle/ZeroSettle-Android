package com.zerosettle.sdk.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.Identity
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.ZeroSettleConfig
import com.zerosettle.sdk.models.Access
import com.zerosettle.sdk.models.FeatureValue
import com.zerosettle.sdk.models.Plan
import com.zerosettle.sdk.models.PlanKind
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessStateTest {

    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        configure()
    }

    @After fun tearDown() {
        server.shutdown()
        ZeroSettle.resetForTesting()
    }

    private fun configure(defaults: Map<String, FeatureValue> = emptyMap()) {
        ZeroSettle.configure(
            context,
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = false,
                offlineFeatureDefaults = defaults,
            ),
        )
    }

    private fun accessBody(features: String = """{"pro_export":true,"quality":"4K","empty":""}""") =
        """{"entitlements":[],"plan":{"key":"pro","name":"Pro","kind":"tier"},"add_ons":[{"key":"x","name":"X","kind":"add_on"}],""" +
            """"features":$features,"balances":{"credits":{"currency":{"key":"credits","name":"Credit","plural":"Credits"},"remaining":7}}}"""

    private suspend fun identify(userId: String, entitlementsBody: String = accessBody()) {
        server.enqueue(MockResponse().setBody("""{"products":[]}"""))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        ZeroSettle.identify(Identity.User(id = userId))
    }

    private fun store() = AccessStore(context, "test")

    private fun awaitUntil(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
    }

    @Test fun hasAccessBoolAndText() = runTest {
        identify("u-bool")
        assertThat(ZeroSettle.hasAccess("pro_export")).isTrue()
        assertThat(ZeroSettle.hasAccess("quality")).isTrue()
        assertThat(ZeroSettle.hasAccess("empty")).isFalse()
        assertThat(ZeroSettle.hasAccess("missing")).isFalse()
        assertThat(ZeroSettle.plan.value).isEqualTo(Plan("pro", "Pro", PlanKind.TIER))
        assertThat(ZeroSettle.addOns.value.map { it.key }).containsExactly("x")
        assertThat(ZeroSettle.balance("credits")?.remaining).isEqualTo(7)
        assertThat(ZeroSettle.balance("nope")).isNull()
        assertThat(ZeroSettle.balances.value.keys).containsExactly("credits")
        assertThat(ZeroSettle.features.value["quality"]).isEqualTo(FeatureValue.Text("4K"))
    }

    @Test fun valueReturnsTextOnly() = runTest {
        identify("u-text")
        assertThat(ZeroSettle.value("quality")).isEqualTo("4K")
        assertThat(ZeroSettle.value("pro_export")).isNull()
        assertThat(ZeroSettle.value("missing")).isNull()
    }

    @Test fun offlineDefaultsBeforeFirstFetch() = runTest {
        ZeroSettle.resetForTesting()
        configure(mapOf("pro_export" to FeatureValue.Bool(true), "quality" to FeatureValue.Text("HD")))
        assertThat(ZeroSettle.hasAccess("pro_export")).isTrue()
        assertThat(ZeroSettle.value("quality")).isEqualTo("HD")
        assertThat(ZeroSettle.hasAccess("other")).isFalse()
        // Once any access has loaded, a key it omits is false, whatever the default says.
        identify("u-offline", entitlementsBody = accessBody(features = """{"quality":"4K"}"""))
        assertThat(ZeroSettle.hasAccess("pro_export")).isFalse()
        assertThat(ZeroSettle.value("quality")).isEqualTo("4K")
    }

    @Test fun missingAccessKeysKeepCachedValue() = runTest {
        // Seed the cache, then identify against a backend that has no access keys.
        store().save("u-keep", Access(
            Plan("pro", "Pro", PlanKind.TIER), emptyList(),
            mapOf("pro_export" to FeatureValue.Bool(true)), emptyMap(), true,
        ))
        identify("u-keep", entitlementsBody = """{"entitlements":[]}""")
        assertThat(ZeroSettle.hasAccess("pro_export")).isTrue()
        assertThat(ZeroSettle.plan.value?.key).isEqualTo("pro")
        // A later refresh without the keys also leaves it alone.
        server.enqueue(MockResponse().setBody("""{"entitlements":[]}"""))
        ZeroSettle.restoreEntitlements()
        assertThat(ZeroSettle.hasAccess("pro_export")).isTrue()
    }

    @Test fun everyRefreshPathUpdatesAccess() = runTest {
        // Bootstrap
        identify("u-paths", accessBody(features = """{"a":true}"""))
        assertThat(ZeroSettle.hasAccess("a")).isTrue()
        // restoreEntitlements
        server.enqueue(MockResponse().setBody(accessBody(features = """{"b":true}""")))
        ZeroSettle.restoreEntitlements()
        assertThat(ZeroSettle.hasAccess("b")).isTrue()
        assertThat(ZeroSettle.hasAccess("a")).isFalse()
        // Poller
        server.enqueue(MockResponse().setBody(accessBody(features = """{"c":true}""")))
        ZeroSettle.entitlementPoller!!.pollOnce()
        assertThat(ZeroSettle.hasAccess("c")).isTrue()
        assertThat(ZeroSettle.hasAccess("b")).isFalse()
    }

    @Test fun cacheIsPerUserAndClearedOnLogout() = runTest {
        identify("u-one")
        awaitUntil { runBlocking { store().load("u-one") } != null }
        val saved = store().load("u-one")
        assertThat(saved?.features?.get("quality")).isEqualTo(FeatureValue.Text("4K"))
        assertThat(saved?.plan?.key).isEqualTo("pro")
        assertThat(saved?.balances?.get("credits")?.remaining).isEqualTo(7)

        // Another user whose fetch fails never sees u-one's features.
        ZeroSettle.logout()
        assertThat(ZeroSettle.access.value).isEqualTo(Access.EMPTY)
        awaitUntil { runBlocking { store().load("u-one") } == null }
        assertThat(store().load("u-one")).isNull()

        identify("u-one")
        awaitUntil { runBlocking { store().load("u-one") } != null }
        server.enqueue(MockResponse().setResponseCode(500))
        ZeroSettle.identify(Identity.User(id = "u-two"))
        assertThat(ZeroSettle.hasAccess("quality")).isFalse()
        // Logout removes only the current user's entry.
        assertThat(store().load("u-one")).isNotNull()
    }
}
