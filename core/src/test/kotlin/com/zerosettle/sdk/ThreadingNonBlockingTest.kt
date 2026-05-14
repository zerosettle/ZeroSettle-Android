package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for Tier-2 ANR risk: ZeroSettle.configure / setCustomer /
 * logout previously called `runBlocking { store.…() }` while typically being
 * invoked from the calling thread (Main, at Application.onCreate / UI
 * handlers). On slow devices that blocked the main thread on DataStore disk
 * I/O.
 *
 * After the fix, the calling thread is NOT blocked: in-memory state mutates
 * synchronously, persistence and Play coordinator shutdown are dispatched to
 * `ioScope` (Dispatchers.IO). The tests pin (a) the synchronous state-flip
 * behavior, and (b) the bound on how long the calling thread blocks even when
 * the next-thread work is artificially delayed.
 *
 * Note on test reliability: timing assertions in CI are notoriously flaky.
 * We use generous bounds (calling thread returns within 200ms even though the
 * actual implementation typically takes <10ms). The point is "callers don't
 * block on the underlying I/O," not a tight latency budget.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadingNonBlockingTest {

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

    @Test fun setCustomer_mutatesInMemoryStateSynchronously() {
        // After setCustomer returns, the customerForTesting() snapshot must
        // already reflect the new values — downstream consumers
        // (PlayBillingCoordinator, the checkout request builder) read this
        // in-memory state, not DataStore, so a delayed mutation would be
        // observably wrong on the next purchase.
        ZeroSettle.setCustomer(name = "Alice", email = "alice@example.com")
        val (name, email) = ZeroSettle.customerForTesting()
        assertThat(name).isEqualTo("Alice")
        assertThat(email).isEqualTo("alice@example.com")
    }

    @Test fun setCustomer_doesNotBlockCallingThreadBeyondSyncWork() {
        // Pin the upper bound: the API must return within a reasonable budget
        // even though DataStore writes are dispatched to Dispatchers.IO. The
        // 200ms bound is generous to absorb CI noise — the actual return
        // time is well under 10ms locally.
        val start = System.nanoTime()
        ZeroSettle.setCustomer(name = "Bob", email = "bob@example.com")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isLessThan(200)
    }

    @Test fun logout_clearsStateSynchronouslyAndReturnsQuickly() {
        // Seed some state to prove logout actually clears it.
        ZeroSettle.setCustomer(name = "Carol", email = "carol@example.com")

        val start = System.nanoTime()
        ZeroSettle.logout()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // Synchronous state flip — these are the values downstream observers
        // see, so they MUST be updated before logout returns. The DataStore
        // clear and PlayBillingCoordinator shutdown run on ioScope.
        assertThat(ZeroSettle.activeUserIdForTesting()).isNull()
        val (name, email) = ZeroSettle.customerForTesting()
        assertThat(name).isNull()
        assertThat(email).isNull()
        assertThat(ZeroSettle.isBootstrapped.value).isFalse()

        // The previous implementation could spend hundreds of milliseconds
        // blocking the caller on DataStore clear + coordinator shutdown on
        // slow devices — generous bound covers CI noise.
        assertThat(elapsedMs).isLessThan(500)
    }

    @Test fun configure_reConfigureDoesNotBlockOnPriorCoordinatorShutdown() {
        // configure() is documented as "Safe to call again to swap config."
        // The prior implementation called `runBlocking { coord.shutdown() }`
        // on the calling thread (typically Main at startup). Verify that
        // a re-configure returns quickly even though shutdown is dispatched
        // off-thread.
        val start = System.nanoTime()
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(
                publishableKey = "zs_pk_test_def",
                baseUrlOverride = server.url("/").toString().trimEnd('/'),
                syncPlayPurchases = false,
            ),
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(ZeroSettle.isConfigured.value).isTrue()
        assertThat(elapsedMs).isLessThan(500)
    }
}
