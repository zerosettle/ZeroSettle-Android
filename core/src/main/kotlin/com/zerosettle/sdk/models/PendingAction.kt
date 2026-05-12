package com.zerosettle.sdk.models

/**
 * A backend-initiated user prompt surfaced via the `pending_actions[]` array on
 * `GET /v1/iap/entitlements/` (chunk 3). The Android SDK is the first consumer; iOS
 * adopts the pattern later.
 *
 * Decoding happens in [com.zerosettle.sdk.entitlements.PendingActionParser]; unknown
 * `type` values (and malformed rows) are logged at INFO and dropped (forward-compat —
 * the backend can add new action types without breaking older SDKs).
 *
 * Dates are kept as ISO-8601 strings (no `java.util.Date` dependency in the model
 * layer); the `:ui` artifact / host app formats them. The wire shape keys actions by
 * `transaction_id` (the migration transaction); that is the identity used for
 * dismissal (`POST /v1/iap/migration-actions/<transaction_id>/dismiss/`) and for
 * de-duplicating "shown" events.
 */
public sealed class PendingAction {
    /** The migration transaction id this action relates to; also the dismissal key. */
    public abstract val transactionId: String

    /** Localized human-readable copy for the banner, supplied by the backend. */
    public abstract val userMessage: String

    /**
     * A one-time info banner shown after a successful Play→web Switch & Save: the
     * backend cancelled the old Play subscription, but Play access continues until the
     * previously-paid period ends. Type: `migration_completed_info`. Dismissed with
     * `action_type = "info_banner_dismissed"`.
     */
    public data class MigrationCompletedInfo(
        override val transactionId: String,
        override val userMessage: String,
        /** When Play Store access ends (ISO-8601), or `null` if the backend couldn't determine it. */
        val playAccessEndsAtIso: String?,
        /** First-period price of the new web subscription, or `null` if unavailable. */
        val newSubscriptionPriceCents: Int?,
        val newSubscriptionCurrency: String?,
        val newSubscriptionInterval: String?,
    ) : PendingAction()

    /**
     * The backend's programmatic Play cancel failed after all retries; the user must
     * cancel the old Play subscription themselves. Type: `manual_play_cancel`. The
     * `:ui` banner / host app deep-links to [deepLink] (the Play Store subscriptions
     * page); once cancelled, Play sends a `SUBSCRIPTION_CANCELED` RTDN, the backend
     * reconciles, and the action disappears from the next poll. Dismissed with
     * `action_type = "manual_play_cancel_completed"`.
     */
    public data class ManualPlayCancel(
        override val transactionId: String,
        override val userMessage: String,
        val originalPlayPurchaseToken: String,
        /** When the old Play subscription expires (ISO-8601), or `null` if unknown. */
        val expiresAtIso: String?,
        val deepLink: String,
    ) : PendingAction()
}
