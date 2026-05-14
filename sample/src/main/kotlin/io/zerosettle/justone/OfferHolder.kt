package io.zerosettle.justone

import com.zerosettle.sdk.ZeroSettle
import com.zerosettle.sdk.offers.OfferManager

/**
 * The sample needs ONE [OfferManager] shared across screens — `ZeroSettle.offerManager()`
 * mints a fresh instance per call, so the Home screen's [com.zerosettle.ui.ZeroSettleOfferTip]
 * and [com.zerosettle.ui.ZeroSettleCheckoutHost] (and the dedicated Offers tab) must all
 * observe the same instance, otherwise `pendingCheckoutUrl` set by Accept is invisible to
 * the host. The manager can only be constructed after `identify()` resolves (it throws
 * `UserNotIdentified` otherwise), so this is lazy and reset on logout.
 */
object OfferHolder {
    @Volatile private var instance: OfferManager? = null

    /** Construct (once) for the current identity; safe to call repeatedly. Requires an identified user. */
    fun get(): OfferManager = instance ?: synchronized(this) {
        instance ?: ZeroSettle.offerManager().also { instance = it }
    }

    fun getOrNull(): OfferManager? = instance

    /** Drop the cached manager (call on logout / identity switch). */
    fun reset() = synchronized(this) { instance = null }
}
