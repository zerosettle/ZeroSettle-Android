package com.zerosettle.sdk

import com.zerosettle.sdk.core.LogcatLogger
import com.zerosettle.sdk.core.ZeroSettleLogger

/**
 * SDK-wide configuration passed to [ZeroSettle.configure]. Immutable; replace by
 * calling configure again.
 *
 * @property publishableKey `zs_pk_live_…` (production) or `zs_pk_test_…` (sandbox).
 *           Prefix is the canonical sandbox/live signal — there is no separate flag.
 * @property playLicenseKey Optional. PEM-encoded RSA key from Play Console used for
 *           advisory `Purchase.signature` checks before forwarding to backend. If
 *           omitted, signature verification happens server-side only.
 * @property syncPlayPurchases When true (default), the SDK installs a
 *           `PurchasesUpdatedListener` and forwards every purchase to
 *           `POST /v1/iap/play-store-transactions/`. Set false only if the host
 *           app fully owns Play Billing and explicitly opts out of sync.
 * @property preloadCheckout When true, eagerly fetch web checkout config after
 *           [ZeroSettle.identify] succeeds. Reduces perceived latency at the cost
 *           of one extra request per launch.
 * @property strictAck When true, the SDK never acknowledges a Play purchase
 *           without backend validation — risking Play's 3-day auto-refund if the
 *           backend stays down. Default false: defensive ack after 24h if sync
 *           keeps failing (per chunk-4 spec "Acknowledgement edge cases").
 * @property baseUrlOverride Dev-only; points the SDK at a local backend / ngrok
 *           tunnel. Production builds should leave this null.
 * @property logger Pluggable logger; defaults to [LogcatLogger].
 */
public data class ZeroSettleConfig(
    val publishableKey: String,
    val playLicenseKey: String? = null,
    val syncPlayPurchases: Boolean = true,
    val preloadCheckout: Boolean = false,
    val strictAck: Boolean = false,
    val baseUrlOverride: String? = null,
    val logger: ZeroSettleLogger = LogcatLogger,
) {
    init {
        require(publishableKey.startsWith(LIVE_PREFIX) || publishableKey.startsWith(TEST_PREFIX)) {
            "ZeroSettleConfig.publishableKey must start with $LIVE_PREFIX or $TEST_PREFIX"
        }
    }

    /** True when [publishableKey] starts with `zs_pk_test_`. */
    public val isSandbox: Boolean get() = publishableKey.startsWith(TEST_PREFIX)

    public companion object {
        public const val LIVE_PREFIX: String = "zs_pk_live_"
        public const val TEST_PREFIX: String = "zs_pk_test_"
    }
}
