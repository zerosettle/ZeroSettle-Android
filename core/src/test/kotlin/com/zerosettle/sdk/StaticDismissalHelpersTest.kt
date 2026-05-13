package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the three static dismissal helpers on [ZeroSettle] —
 * [ZeroSettle.isOfferPermanentlyDismissed], [ZeroSettle.setOfferDismissed],
 * [ZeroSettle.resetOfferDismissedState] — which the Flutter plugin's
 * `zerosettle/offer_manager_static` MethodChannel routes calls to.
 *
 * Mirrors iOS `ZSOfferManager`'s class-level dismissal API.
 */
@RunWith(RobolectricTestRunner::class)
class StaticDismissalHelpersTest {

    @Before fun setUp() = runTest {
        ZeroSettle.configure(
            context = ApplicationProvider.getApplicationContext(),
            config = ZeroSettleConfig(
                publishableKey = "zs_pk_test_xxx",
                syncPlayPurchases = false,
            ),
        )
        // The Preferences DataStore file survives ZeroSettle.resetForTesting()
        // across test classes — wipe it so each test starts from a clean slate.
        ZeroSettle.resetOfferDismissedState()
    }

    @After fun tearDown() {
        ZeroSettle.resetForTesting()
    }

    @Test fun setOfferDismissed_true_flipsIsOfferPermanentlyDismissedToTrue() = runTest {
        assertThat(ZeroSettle.isOfferPermanentlyDismissed("alice")).isFalse()
        ZeroSettle.setOfferDismissed("alice", dismissed = true)
        assertThat(ZeroSettle.isOfferPermanentlyDismissed("alice")).isTrue()
    }

    @Test fun setOfferDismissed_false_flipsItBackToFalse() = runTest {
        ZeroSettle.setOfferDismissed("alice", dismissed = true)
        ZeroSettle.setOfferDismissed("alice", dismissed = false)
        assertThat(ZeroSettle.isOfferPermanentlyDismissed("alice")).isFalse()
    }

    @Test fun resetOfferDismissedState_takesNoArgsAndClearsAllUsers() = runTest {
        ZeroSettle.setOfferDismissed("alice", true)
        ZeroSettle.setOfferDismissed("bob", true)
        ZeroSettle.resetOfferDismissedState()
        assertThat(ZeroSettle.isOfferPermanentlyDismissed("alice")).isFalse()
        assertThat(ZeroSettle.isOfferPermanentlyDismissed("bob")).isFalse()
    }
}
