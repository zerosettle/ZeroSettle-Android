package com.zerosettle.sdk.billing

import com.android.billingclient.api.UserChoiceBillingListener
import com.android.billingclient.api.UserChoiceDetails
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.core.LogcatLogger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * PlayBillingManager UCB-enablement contract (Phase 2 Chunk B, Task 2.3).
 *
 * The manager owns the `BillingClient.Builder` and must call
 * `enableUserChoiceBilling(listener)` / `enableAlternativeBillingOnly()` based
 * on the [UcbConfig] handed in at construction. We can't easily inspect the
 * builder's internal state via the public PBL surface — so this test instead
 * pins the precondition: when `isEnabled && !dmaAltBillingOnlyEea` we
 * **require** a non-null [UserChoiceBillingListener], because that's the only
 * way to receive the choice-screen callback. The three other quadrants
 * (UCB off; UCB on + DMA-only; UCB on + listener supplied) construct without
 * throwing.
 *
 * The fact that constructing the manager doesn't throw in those quadrants
 * is the integration guarantee — if the conditional branching in the builder
 * regresses, BillingClient construction will fail and surface here. Robolectric
 * doesn't fully provision Play Services, but the builder itself is a simple
 * value-object configurator that runs without any external dependency.
 */
@RunWith(RobolectricTestRunner::class)
class PlayBillingManagerUcbTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val logger = LogcatLogger

    private val noopOnPurchases: (List<com.android.billingclient.api.Purchase>) -> Unit = { _ -> }

    @Test fun ucbDisabled_constructsWithoutListener() {
        // Standard Play Billing — no UCB params required. This is the
        // default-arg path; existing call sites compile unchanged.
        val mgr = PlayBillingManager(
            context = context,
            obfuscatedAccountIdProvider = { null },
            onPurchases = noopOnPurchases,
            logger = logger,
        )
        // No assertion beyond "didn't throw" — the builder never invoked UCB
        // setters because ucbConfig defaults to UcbConfig.Disabled.
        assertThat(mgr).isNotNull()
    }

    @Test fun ucbEnabled_dmaOnly_constructsWithoutListener() {
        // EEA DMA tenants opt into alternative billing only — Google's choice
        // screen never fires (no GPB option shown), so no listener required.
        val mgr = PlayBillingManager(
            context = context,
            obfuscatedAccountIdProvider = { null },
            onPurchases = noopOnPurchases,
            logger = logger,
            ucbConfig = UcbConfig(isEnabled = true, dmaAltBillingOnlyEea = true),
            ucbChoiceListener = null,
        )
        assertThat(mgr).isNotNull()
    }

    @Test fun ucbEnabled_userChoice_constructsWithListener() {
        // The normal UCB path — Google shows the choice screen and routes the
        // alt-billing selection back to our listener.
        val listener = UserChoiceBillingListener { _: UserChoiceDetails -> /* no-op */ }
        val mgr = PlayBillingManager(
            context = context,
            obfuscatedAccountIdProvider = { null },
            onPurchases = noopOnPurchases,
            logger = logger,
            ucbConfig = UcbConfig(isEnabled = true, dmaAltBillingOnlyEea = false),
            ucbChoiceListener = listener,
        )
        assertThat(mgr).isNotNull()
    }

    @Test fun ucbEnabled_userChoice_missingListener_throws() {
        // The precondition we care most about: if the tenant has UCB enabled
        // in non-DMA mode, we MUST have a listener wired or there's no way to
        // collect the externalTransactionToken when the user picks our flow.
        // Failing fast at construction is much better than silently dropping
        // the callback at runtime.
        val ex = runCatching {
            PlayBillingManager(
                context = context,
                obfuscatedAccountIdProvider = { null },
                onPurchases = noopOnPurchases,
                logger = logger,
                ucbConfig = UcbConfig(isEnabled = true, dmaAltBillingOnlyEea = false),
                ucbChoiceListener = null,
            )
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }
}
