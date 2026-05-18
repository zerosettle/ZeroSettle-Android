package com.zerosettle.sdk.billing

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Smoke tests for [UcbPaymentSheetActivity].
 *
 * We deliberately keep these light — driving [com.stripe.android.paymentsheet.PaymentSheet]
 * through Robolectric pulls in Stripe internals that aren't worth fighting
 * with at unit-test scope. The full PaymentSheet integration is verified
 * on-device in Chunk D. Here we check:
 *   - Manifest registration: the activity is reachable as a component.
 *   - Defensive path: launching without the required `client_secret` extra
 *     does NOT crash — it should finish itself + deliver a `Failed` outcome
 *     to [UcbResultBridge] so the launcher's awaiting suspend resolves.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UcbPaymentSheetActivityTest {

    @Test
    fun activityIsRegisteredInManifest() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getActivityInfo(
            ComponentName(ctx, UcbPaymentSheetActivity::class.java),
            0,
        )
        assertThat(info.name).isEqualTo(UcbPaymentSheetActivity::class.java.name)
        // Must be internal-only — UCB checkout is launched by the SDK, never
        // by host-app intents.
        assertThat(info.exported).isFalse()
    }

    @Test
    fun newIntent_resolvesToTheRegisteredActivity() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(ctx, UcbPaymentSheetActivity::class.java)
        val resolved = ctx.packageManager.resolveActivity(intent, 0)
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.activityInfo.name).isEqualTo(UcbPaymentSheetActivity::class.java.name)
    }

    @Test
    fun missingClientSecret_finishesAndDeliversFailed() {
        // Defensive: the launcher should never dispatch without a
        // client_secret, but if some future refactor regresses, the activity
        // must NOT leave the bridge hanging. The launcher's `launch()` is
        // suspending on the bridge; failing to deliver here would leak the
        // coroutine.
        UcbResultBridge.reset()
        // Mirror the real launch sequence: reserve the bridge first, then
        // start the activity. The activity's missing-extras path should
        // deliver a Failed outcome to the reserved deferred so any awaiting
        // caller resumes.
        val pending = UcbResultBridge.reserve()
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            UcbPaymentSheetActivity::class.java,
        )
        // Intentionally omit EXTRA_CLIENT_SECRET — also omit other required
        // extras so we exercise the missing-extras early-exit.
        val controller: ActivityController<UcbPaymentSheetActivity> =
            Robolectric.buildActivity(UcbPaymentSheetActivity::class.java, intent).create()
        val activity = controller.get()

        assertThat(activity.isFinishing).isTrue()
        // The reserved deferred must be completed with a Failed outcome —
        // never left hanging.
        assertThat(pending.isCompleted).isTrue()
        val outcome = pending.getCompleted()
        assertThat(outcome).isInstanceOf(UcbPurchaseOutcome.Failed::class.java)
    }
}
