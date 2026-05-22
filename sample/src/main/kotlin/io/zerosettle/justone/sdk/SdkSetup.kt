package io.zerosettle.justone.sdk

import android.content.Context
import io.zerosettle.justone.SampleConfig
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.ZeroSettleConfig

/**
 * (Re)configure the SDK against the backend env currently persisted in
 * [SampleConfig]. Called from [JustOneApplication.onCreate] at launch and from the
 * Sign-in screen whenever the env is changed. `ZeroSettle.configure` is safe to
 * call again — it tears down any prior Play coordinator / entitlement poller first.
 *
 * Switching env does NOT clear an existing identity, so callers should re-`identify`
 * afterward (the Sign-in screen does this naturally).
 */
internal fun configureSdk(ctx: Context) {
    ZeroSettle.configure(
        ctx.applicationContext,
        ZeroSettleConfig(
            publishableKey = SampleConfig.resolvePublishableKey(ctx),
            baseUrlOverride = SampleConfig.resolveBaseUrlOverride(ctx),
            syncPlayPurchases = true,
            preloadCheckout = true,
        ),
    )
    // Re-apply the persisted Switch & Save (ECL) testing override. `true` forces
    // the ECL availability check to pass so the offer tip surfaces on devices
    // not enrolled in Google's ECL program; `null` (toggle off) = real query.
    ZeroSettle.eclAvailabilityOverride = if (SampleConfig.loadEclOverride(ctx)) true else null
}
