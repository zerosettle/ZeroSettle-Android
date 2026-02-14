package com.zerosettle.sdk

import com.zerosettle.sdk.model.Entitlement
import com.zerosettle.sdk.model.ZSTransaction

/**
 * Delegate interface to receive callbacks for ZeroSettle IAP events.
 * All methods have default empty implementations — only implement what you need.
 * Maps to iOS `ZeroSettleDelegate`.
 */
interface ZeroSettleDelegate {

    // -- Checkout Events --

    /** Called when a web checkout begins (Custom Tab / browser is opening). */
    fun zeroSettleCheckoutDidBegin(productId: String) {}

    /** Called when a web checkout completes successfully. */
    fun zeroSettleCheckoutDidComplete(transaction: ZSTransaction) {}

    /** Called when the user cancels the web checkout (returns without purchasing). */
    fun zeroSettleCheckoutDidCancel(productId: String) {}

    /** Called when a web checkout fails. */
    fun zeroSettleCheckoutDidFail(productId: String, error: Throwable) {}

    // -- Entitlement Events --

    /** Called when the user's entitlements are updated (from either source). */
    fun zeroSettleEntitlementsDidUpdate(entitlements: List<Entitlement>) {}

    // -- Play Store Sync Events --

    /** Called when a Play Store transaction is successfully synced to ZeroSettle. */
    fun zeroSettleDidSyncPlayStoreTransaction(productId: String, purchaseToken: String) {}

    /** Called when syncing a Play Store transaction to ZeroSettle fails. */
    fun zeroSettlePlayStoreSyncFailed(error: Throwable) {}
}
