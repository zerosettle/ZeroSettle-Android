package com.zerosettle.sdk.models

/** What a successful [com.zerosettle.sdk.ZeroSettle.reconcile] moved. */
public data class ReconcileResult(
    /** Identifier of the reconciliation record. */
    val reconciliationId: Int,
    /** The id whose purchases were moved (typically the guest id). */
    val fromUserId: String,
    /** The id that received them (typically the account id). */
    val intoUserId: String,
    /** How many transactions moved. */
    val movedTransactions: Int,
    /** How many entitlements moved. */
    val movedEntitlements: Int,
)
