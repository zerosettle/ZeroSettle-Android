package com.zerosettle.sdk.models

/** Placeholder — full sealed-class definition in Phase 7. */
public sealed class PendingAction {
    public abstract val actionId: String
    public abstract val transactionId: String
    public abstract val userMessage: String
}
