package com.zerosettle.sdk.core

/**
 * Structured analytics events emitted by the SDK. Host apps subscribe to
 * [com.zerosettle.sdk.ZeroSettle.events] and forward to their analytics SDK.
 *
 * Adding a new event is non-breaking (sealed class consumers must handle unknowns
 * via `else`, or accept the compile warning). Renaming or removing is breaking.
 */
public sealed class ZeroSettleEvent {
    public data class OfferShown(val productId: String) : ZeroSettleEvent()
    public data class OfferAccepted(val productId: String) : ZeroSettleEvent()
    public data class OfferDismissed(val productId: String) : ZeroSettleEvent()
    /** The `GET /v1/iap/user-offer/` call failed (transport / decode error) — distinct from a quiet "no offer". */
    public data class OfferEvaluationFailed(val reason: String) : ZeroSettleEvent()
    public data class PurchaseSucceeded(val productId: String, val transactionId: String) : ZeroSettleEvent()
    public data class PurchaseFailed(val productId: String, val reason: String) : ZeroSettleEvent()
    public data class MigrationCompleted(val productId: String) : ZeroSettleEvent()
    public data class SyncFailed(val purchaseToken: String, val attempts: Int, val terminal: Boolean) : ZeroSettleEvent()
    public data class EntitlementsRefreshed(val count: Int) : ZeroSettleEvent()
    public data class PendingActionShown(val actionType: String) : ZeroSettleEvent()
}
