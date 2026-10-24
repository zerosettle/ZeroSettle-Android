package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalyticsUserIdTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.dispatcher = routeBy(bootstrapRoutes()); it.start() }
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

    @Test fun beforeIdentify_isPersistedAnonymousUuid_andStable() = runTest {
        val first = ZeroSettle.analyticsUserId()
        assertThat(first).isNotEmpty()
        assertThat(ZeroSettle.analyticsUserId()).isEqualTo(first)
    }

    @Test fun anonymousIdentify_adoptsTheSameUuid() = runTest {
        val before = ZeroSettle.analyticsUserId()
        ZeroSettle.identify(Identity.Anonymous)
        assertThat(ZeroSettle.activeUserIdForTesting()).isEqualTo(before)
        assertThat(ZeroSettle.analyticsUserId()).isEqualTo(before)
    }

    @Test fun afterUserIdentify_isTheUserId() = runTest {
        ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(ZeroSettle.analyticsUserId()).isEqualTo("u1")
    }

    @Test fun afterLogout_fallsBackToNewAnonymousUuid_notTheUserNorThePreviousGuest() = runTest {
        val guest = ZeroSettle.analyticsUserId()
        ZeroSettle.identify(Identity.User(id = "u1"))
        ZeroSettle.logout()
        assertThat(ZeroSettle.activeUserIdForTesting()).isNull()
        val id = ZeroSettle.analyticsUserId()
        assertThat(id).isNotEmpty()
        assertThat(id).isNotEqualTo("u1")
        assertThat(id).isNotEqualTo(guest)
        assertThat(ZeroSettle.analyticsUserId()).isEqualTo(id)
    }

    @Test fun concurrentFirstCalls_agreeOnOneUuid() = runTest {
        val ids = (1..16).map { async(Dispatchers.IO) { ZeroSettle.analyticsUserId() } }.awaitAll()
        assertThat(ids.toSet()).hasSize(1)
    }
}
