package io.zerosettle.justone.sdk

import android.content.Context
import io.zerosettle.justone.SampleConfig
import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.ZeroSettleConfig

/**
 * (Re)configure the SDK against the backend env currently persisted in
 * [SampleConfig]. Called from [SampleApplication.onCreate] at launch and from the
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
            publishableKey = SampleConfig.PUBLISHABLE_KEY,
            baseUrlOverride = SampleConfig.resolveBaseUrlOverride(ctx),
            syncPlayPurchases = true,
            preloadCheckout = true,
        ),
    )
}
