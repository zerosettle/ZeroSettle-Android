package io.zerosettle.justone.screens.paywall

import android.app.Activity
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.13 supports up to SDK 34; pin explicitly so the test works
// regardless of the app's targetSdk setting.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindActivityTest {

    @Test
    fun `findActivity returns the activity itself`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertThat(activity.findActivity()).isSameInstanceAs(activity)
    }

    @Test
    fun `findActivity unwraps a ContextThemeWrapper over an activity (the dialog case)`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // A ModalBottomSheet hosts content in a Dialog whose context is a
        // ContextThemeWrapper over the activity — reproduce that wrapping.
        val dialogish = ContextThemeWrapper(activity, 0)
        assertThat(dialogish.findActivity()).isSameInstanceAs(activity)
    }

    @Test
    fun `findActivity unwraps nested ContextWrappers`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val nested = ContextWrapper(ContextThemeWrapper(activity, 0))
        assertThat(nested.findActivity()).isSameInstanceAs(activity)
    }

    @Test
    fun `findActivity throws when no activity in the chain`() {
        val appCtx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // application context has no Activity in its chain
        try {
            appCtx.findActivity()
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
