package com.zerosettle.sdk

/**
 * Who the host app is making requests on behalf of.
 *
 * Mirrors iOS `Identity` — three variants:
 *
 *  - [User] — authenticated user. [id] is any non-empty string (need not be a UUID;
 *    the SDK derives a deterministic UUIDv5 `appAccountToken` from it). Optional [name] /
 *    [email] are stored on the Stripe Customer.
 *  - [Anonymous] — no auth yet. The SDK generates a stable per-install UUID, persisted
 *    in DataStore. Same UUID across launches until [ZeroSettle.logout] or app data clear.
 *  - [Deferred] — suppresses the "no identity declared" runtime warning for apps where
 *    auth happens later in the lifecycle.
 */
public sealed class Identity {
    public data class User(
        val id: String,
        val name: String? = null,
        val email: String? = null,
    ) : Identity() {
        init { require(id.isNotEmpty()) { "Identity.User.id must be non-empty" } }
    }

    public data object Anonymous : Identity()
    public data object Deferred : Identity()
}
