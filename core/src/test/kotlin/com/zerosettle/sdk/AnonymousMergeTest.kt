package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zerosettle.sdk.internal.PendingIdentityLink
import com.zerosettle.sdk.internal.identityDataStore
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnonymousMergeTest {
    private lateinit var server: MockWebServer
    private var linkResponse: () -> MockResponse = { MockResponse().setBody("""{"status":"merged"}""") }
    private val linkBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val store get() = ZeroSettle.identityStore!!

    @Before fun setUp() = runTest {
        server = MockWebServer().also {
            it.dispatcher = routeBy(
                bootstrapRoutes() + ("/v1/iap/identity-link/" to { r -> linkBodies.add(r.body.readUtf8()); linkResponse() }),
            )
            it.start()
        }
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = false,
            ),
        )
        store.clear()
    }

    @After fun tearDown() { server.shutdown(); ZeroSettle.resetForTesting() }

    @Test fun newMint_hasPrefixAndLowercaseUuid() = runTest {
        val id = store.anonymousUuid()
        assertThat(id).startsWith("zs_anon_")
        assertThat(id).isEqualTo(id.lowercase())
        assertThat(java.util.UUID.fromString(id.removePrefix("zs_anon_")).toString()).isEqualTo(id.removePrefix("zs_anon_"))
    }

    private suspend fun seedStoredAnonymousId(id: String) {
        ApplicationProvider.getApplicationContext<android.content.Context>().identityDataStore.edit {
            it[stringPreferencesKey("anonymous_session_uuid")] = id
        }
    }

    @Test fun storedBareUuid_isKept() = runTest {
        val bare = java.util.UUID.randomUUID().toString()
        seedStoredAnonymousId(bare)
        assertThat(store.anonymousUuid()).isEqualTo(bare)
    }

    @Test fun userWithAnonymousPrefix_failsInvalidUserId_caseInsensitive() = runTest {
        val r = ZeroSettle.identify(Identity.User(id = "ZS_ANON_abc"))
        assertThat(r.exceptionOrNull()).isInstanceOf(ZeroSettleError.InvalidUserId::class.java)
        assertThat(linkBodies).isEmpty()
    }

    @Test fun merged_writesPairBeforeCall_thenClearsPairAndAnonymousId() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        val anon = store.storedAnonymousId()!!
        linkResponse = {
            // The pair is already persisted while the call is in flight.
            assertThat(kotlinx.coroutines.runBlocking { store.pendingIdentityLink() })
                .isEqualTo(PendingIdentityLink(from = anon, into = "acct-1"))
            MockResponse().setBody("""{"status":"merged","reconciliation_id":3}""")
        }
        assertThat(ZeroSettle.identify(Identity.User(id = "acct-1")).isSuccess).isTrue()
        assertThat(linkBodies.single()).isEqualTo("""{"previous_user_id":"$anon","user_id":"acct-1"}""")
        assertThat(store.pendingIdentityLink()).isNull()
        assertThat(store.storedAnonymousId()).isNull()
    }

    @Test fun unknownStatus_isTreatedAsSkipped_andSettles() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        linkResponse = { MockResponse().setBody("""{"status":"something_new"}""") }
        ZeroSettle.identify(Identity.User(id = "acct-1"))
        assertThat(store.pendingIdentityLink()).isNull()
        assertThat(store.storedAnonymousId()).isNull()
    }

    @Test fun clientError_settles_butRateLimitAndServerErrorKeep() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        val anon = store.storedAnonymousId()!!
        linkResponse = { MockResponse().setResponseCode(429).addHeader("Retry-After", "5") }
        assertThat(ZeroSettle.identify(Identity.User(id = "acct-1")).isSuccess).isTrue()
        assertThat(store.pendingIdentityLink()).isEqualTo(PendingIdentityLink(anon, "acct-1"))
        linkResponse = { MockResponse().setResponseCode(503) }
        ZeroSettle.identify(Identity.User(id = "acct-1"))
        assertThat(store.pendingIdentityLink()).isEqualTo(PendingIdentityLink(anon, "acct-1"))
        linkResponse = { MockResponse().setResponseCode(400).setBody("""{"error":{"code":"invalid_user_id"}}""") }
        ZeroSettle.identify(Identity.User(id = "acct-1"))
        assertThat(store.pendingIdentityLink()).isNull()
        assertThat(store.storedAnonymousId()).isNull()
    }

    @Test fun networkError_keepsPair_identifyStillSucceeds_andNextIdentifyRetries() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        val anon = store.storedAnonymousId()!!
        linkResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertThat(ZeroSettle.identify(Identity.User(id = "acct-1")).isSuccess).isTrue()
        assertThat(store.pendingIdentityLink()).isEqualTo(PendingIdentityLink(anon, "acct-1"))

        linkResponse = { MockResponse().setBody("""{"status":"switched"}""") }
        linkBodies.clear()
        ZeroSettle.identify(Identity.User(id = "acct-1"))
        assertThat(linkBodies).containsExactly("""{"previous_user_id":"$anon","user_id":"acct-1"}""")
        assertThat(store.pendingIdentityLink()).isNull()
        assertThat(store.storedAnonymousId()).isNull()
    }

    @Test fun slowServer_timesOutAsNetworkError_keepsPair() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        val anon = store.storedAnonymousId()!!
        linkResponse = { MockResponse().setBody("""{"status":"merged"}""").setBodyDelay(8, java.util.concurrent.TimeUnit.SECONDS) }
        val started = System.nanoTime()
        assertThat(ZeroSettle.identify(Identity.User(id = "acct-1")).isSuccess).isTrue()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(7_500)
        assertThat(store.pendingIdentityLink()).isEqualTo(PendingIdentityLink(anon, "acct-1"))
    }

    @Test fun noStoredAnonymousId_makesNoCall() = runTest {
        ZeroSettle.identify(Identity.User(id = "acct-1"))
        assertThat(linkBodies).isEmpty()
    }

    @Test fun bareStoredUuid_makesNoCall() = runTest {
        seedStoredAnonymousId(java.util.UUID.randomUUID().toString())
        ZeroSettle.identify(Identity.User(id = "acct-1"))
        assertThat(linkBodies).isEmpty()
    }
}
