package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.billing.PendingPurchaseSync
import com.zerosettle.sdk.core.AppAccountToken
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentifySwitchTest {
    private lateinit var server: MockWebServer
    private val logger = RecordingLogger()

    @Before fun setUp() {
        server = MockWebServer().also { it.dispatcher = routeBy(bootstrapRoutes()); it.start() }
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_abc",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = true,
                logger = logger,
            ),
        )
    }

    @After fun tearDown() { server.shutdown(); ZeroSettle.resetForTesting() }

    private fun row(token: String, user: String) = PendingPurchaseSync(token, "pro", "com.app", user)

    @Test fun userToDifferentUser_warns() = runTest {
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.identify(Identity.User(id = "B"))
        assertThat(logger.warnings.filter { it.contains("without logout()") }).hasSize(1)
        assertThat(logger.warnings.single { it.contains("without logout()") }).contains("have your server reconcile the ids")
        assertThat(ZeroSettle.userSwitchWarningCount).isEqualTo(1)
    }

    @Test fun sameUser_caseInsensitive_doesNotWarn() = runTest {
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.identify(Identity.User(id = "a"))
        assertThat(ZeroSettle.userSwitchWarningCount).isEqualTo(0)
    }

    @Test fun anonymousToUser_doesNotWarn() = runTest {
        ZeroSettle.identify(Identity.Anonymous)
        ZeroSettle.identify(Identity.User(id = "A"))
        assertThat(ZeroSettle.userSwitchWarningCount).isEqualTo(0)
    }

    @Test fun logoutBetweenUsers_doesNotWarn() = runTest {
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.logout()
        ZeroSettle.identify(Identity.User(id = "B"))
        assertThat(ZeroSettle.userSwitchWarningCount).isEqualTo(0)
    }

    @Test fun switchAtoB_keepsAsRow_andRetriesItUnderA() = runTest {
        routeSync()
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.playSyncQueueForTesting().enqueue(row("tokA", "A"))
        ZeroSettle.identify(Identity.User(id = "B"))
        val pending = ZeroSettle.playSyncQueueForTesting().pending()
        assertThat(pending.map { it.purchaseToken }).containsExactly("tokA")
        assertThat(pending.single().userId).isEqualTo("A")
        // The new coordinator's start() retries the row; it must go out under A's id.
        val deadline = System.currentTimeMillis() + 5000
        while (!syncedUsers.contains("A") && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(50)
        assertThat(syncedUsers).contains("A")
        assertThat(syncedUsers).doesNotContain("B")
    }

    @Test fun reidentifySameUser_keepsQueue() = runTest {
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.playSyncQueueForTesting().enqueue(row("tokA", "A"))
        ZeroSettle.identify(Identity.User(id = "A"))
        assertThat(ZeroSettle.playSyncQueueForTesting().pending().map { it.purchaseToken }).containsExactly("tokA")
    }

    // ─── Purchases bought under another account are never synced under the current one ───

    private val syncedUsers = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun routeSync() {
        server.dispatcher = routeBy(
            bootstrapRoutes() + ("/v1/iap/play-store-transactions/" to { req ->
                syncedUsers.add(org.json.JSONObject(req.body.readUtf8()).optString("user_id"))
                MockResponse().setBody("""{"owned":true,"transaction_id":1,"transaction_ref":"txn_1"}""")
            }),
        )
    }

    private fun purchase(token: String, accountId: String?): com.android.billingclient.api.Purchase {
        val acct = accountId?.let { ""","obfuscatedAccountId":"$it"""" } ?: ""
        val json = """{"orderId":"GPA.1","packageName":"com.app","productId":"pro","purchaseTime":1700000000000,
            "purchaseState":0,"purchaseToken":"$token","autoRenewing":true,"acknowledged":false$acct}"""
        return com.android.billingclient.api.Purchase(json, "sig")
    }

    private fun tokenFor(uid: String) =
        AppAccountToken.derive(uid, ApplicationProvider.getApplicationContext<android.content.Context>().packageName).toString()

    @Test fun purchaseIsAlwaysProcessed_regardlessOfObfuscatedAccountId() = runTest {
        routeSync()
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.identify(Identity.User(id = "B"))
        syncedUsers.clear()
        // Reaching the sync (owned=true) means the processor runs its acknowledge step;
        // nothing on the device skips a purchase, whichever account id it carries.
        ZeroSettle.playCoordinator!!.reconcileWithPurchasesForTesting(
            subs = listOf(purchase("fromA", tokenFor("A")), purchase("fromB", tokenFor("B")), purchase("untagged", null)),
            inapp = emptyList(),
        )
        assertThat(syncedUsers).containsExactly("B", "B", "B")
    }
}
