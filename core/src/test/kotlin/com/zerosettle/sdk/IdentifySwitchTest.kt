package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.billing.PendingPurchaseSync
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

    @Test fun switchAtoB_clearsPlaySyncQueue_deterministically() = runTest {
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.playSyncQueueForTesting().enqueue(row("tokA", "A"))
        assertThat(ZeroSettle.playSyncQueueForTesting().pending()).hasSize(1)
        ZeroSettle.identify(Identity.User(id = "B"))
        // Rows queued after the switch must survive the old coordinator's async shutdown.
        ZeroSettle.playSyncQueueForTesting().enqueue(row("tokB", "B"))
        Thread.sleep(300)
        assertThat(ZeroSettle.playSyncQueueForTesting().pending().map { it.purchaseToken }).containsExactly("tokB")
    }

    @Test fun reidentifySameUser_keepsQueue() = runTest {
        ZeroSettle.identify(Identity.User(id = "A"))
        ZeroSettle.playSyncQueueForTesting().enqueue(row("tokA", "A"))
        ZeroSettle.identify(Identity.User(id = "A"))
        Thread.sleep(300)
        assertThat(ZeroSettle.playSyncQueueForTesting().pending().map { it.purchaseToken }).containsExactly("tokA")
    }
}
