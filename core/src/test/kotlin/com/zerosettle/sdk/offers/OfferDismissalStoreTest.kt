package com.zerosettle.sdk.offers

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfferDismissalStoreTest {
    private val store = OfferDismissalStore(ApplicationProvider.getApplicationContext())

    @Test fun notDismissedByDefault() = runTest {
        store.resetAll()
        assertThat(store.isDismissed("u1")).isFalse()
    }

    @Test fun dismiss_thenIsDismissedForThatUserOnly() = runTest {
        store.resetAll()
        store.dismiss("u1")
        assertThat(store.isDismissed("u1")).isTrue()
        assertThat(store.isDismissed("u2")).isFalse()
    }

    @Test fun resetAll_clearsEverything() = runTest {
        store.dismiss("u1"); store.dismiss("u2")
        store.resetAll()
        assertThat(store.isDismissed("u1")).isFalse()
        assertThat(store.isDismissed("u2")).isFalse()
    }

    @Test fun undismiss_clearsTheDismissedFlagForASingleUser() = runTest {
        store.resetAll()
        store.dismiss("alice")
        assertThat(store.isDismissed("alice")).isTrue()
        store.undismiss("alice")
        assertThat(store.isDismissed("alice")).isFalse()
    }

    @Test fun undismiss_doesNotAffectOtherUsersDismissals() = runTest {
        store.resetAll()
        store.dismiss("alice")
        store.dismiss("bob")
        store.undismiss("alice")
        assertThat(store.isDismissed("alice")).isFalse()
        assertThat(store.isDismissed("bob")).isTrue()
    }
}
