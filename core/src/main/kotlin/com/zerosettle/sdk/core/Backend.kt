package com.zerosettle.sdk.core

import com.zerosettle.sdk.billing.UcbConfig
import com.zerosettle.sdk.models.Entitlement
import com.zerosettle.sdk.models.PendingClaim
import com.zerosettle.sdk.models.ProductCatalog
import com.zerosettle.sdk.models.UserOffer
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Typed wrapper over `/v1/iap/`. Owns JSON (de)serialization; throws no exceptions
 * on the boundary — every method returns `kotlin.Result<T>`.
 *
 * Mirrors iOS `Backend`. The HTTP layer ([HttpClient]) translates non-2xx / network
 * failures into `Result.failure(ZeroSettleError.…)`; this class additionally maps
 * JSON-decode failures into [ZeroSettleError.BackendError].
 */
internal class Backend(
    baseUrl: String,
    publishableKey: String,
    sdkVersion: String,
    private val http: HttpClient = HttpClient(baseUrl, publishableKey, sdkVersion),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /** `GET /v1/iap/products/?user_id=…` — full canonical catalog. */
    suspend fun fetchProducts(userId: String): Result<ProductCatalog> =
        http.get("/v1/iap/products/?user_id=${enc(userId)}")
            .mapDecode(ProductCatalog.serializer())

    /**
     * `GET /v1/iap/play-billing-config/` — tenant-level UCB configuration. Fetched
     * once at SDK bootstrap and cached in [com.zerosettle.sdk.billing.UcbConfigRepository].
     * Returns [UcbConfig.Disabled]-equivalent defaults if a tenant hasn't opted in.
     */
    suspend fun fetchUcbConfig(): Result<UcbConfig> =
        http.get("/v1/iap/play-billing-config/")
            .mapDecode(UcbConfig.serializer())

    /**
     * `GET /v1/iap/entitlements/?user_id=…` — active entitlements plus the raw
     * `pending_actions[]` list. The `PendingAction` consumer is Phase 7; until then
     * actions are surfaced here as [JsonObject]s. TODO(phase 7): swap for a typed
     * `PendingAction` model.
     */
    suspend fun fetchEntitlements(userId: String): Result<EntitlementsResponse> =
        http.get("/v1/iap/entitlements/?user_id=${enc(userId)}")
            .mapDecode(EntitlementsResponse.serializer())

    /** `GET /v1/iap/user-offer/?user_id=…[&product_id=…]` — unified offer eligibility. */
    suspend fun fetchUserOffer(userId: String, productId: String? = null): Result<UserOffer.Response> {
        val path = buildString {
            append("/v1/iap/user-offer/?user_id=").append(enc(userId))
            productId?.let { append("&product_id=").append(enc(it)) }
        }
        return http.get(path).mapDecode(UserOffer.Response.serializer())
    }

    /**
     * `POST /v1/iap/checkout-configs/` — creates a checkout session. The request body
     * is passed verbatim by the caller; for a Play→web migration it must include
     * `play_purchase_token`. Returns the raw JSON response (checkout URL etc.) — Phase
     * 4/5 owns the typed response shape.
     */
    suspend fun createCheckoutConfig(body: String): Result<String> =
        http.post("/v1/iap/checkout-configs/", body)

    /**
     * `POST /v1/iap/checkout-configs/` — typed variant. For a Play→web migration
     * pass [playPurchaseToken]; otherwise null. Maps a `503 PLAY_API_UNREACHABLE`
     * to [ZeroSettleError.PlayApiUnreachable] so callers can surface a retry hint.
     */
    suspend fun createWebCheckout(
        userId: String,
        productId: String,
        playPurchaseToken: String?,
        customerName: String?,
        customerEmail: String?,
    ): Result<CheckoutConfigResponse> {
        val req = CheckoutConfigRequest(
            userId = userId, productId = productId,
            playPurchaseToken = playPurchaseToken,
            customerName = customerName, customerEmail = customerEmail,
        )
        val body = json.encodeToString(CheckoutConfigRequest.serializer(), req)
        return http.post("/v1/iap/checkout-configs/", body).mapCheckoutBody()
    }

    private fun Result<String>.mapCheckoutBody(): Result<CheckoutConfigResponse> = fold(
        onSuccess = {
            try {
                Result.success(json.decodeFromString(CheckoutConfigResponse.serializer(), it))
            } catch (e: Throwable) {
                Result.failure(ZeroSettleError.BackendError(statusCode = 200, body = "decode_failed: ${e.message}"))
            }
        },
        onFailure = { err ->
            if (err is ZeroSettleError.BackendError && err.statusCode == 503 && err.body.contains("PLAY_API_UNREACHABLE")) {
                Result.failure(ZeroSettleError.PlayApiUnreachable)
            } else {
                Result.failure(err)
            }
        },
    )

    /**
     * `POST /v1/iap/migration-converted/` — records that a migration offer converted.
     * [source] is `"play_store"` for a Play→web migration, `"store_kit"` otherwise
     * (chunk-3 contract). Mirrors iOS `Backend.trackMigrationConversion`.
     */
    suspend fun trackMigrationConversion(userId: String, source: String): Result<Unit> {
        val body = """{"user_id":"${esc(userId)}","source":"${esc(source)}"}"""
        return http.post("/v1/iap/migration-converted/", body).map { }
    }

    // ─── Subscription management ───────────────────────────────────────────

    /** `POST /v1/iap/subscriptions/cancel/` — cancels a web-checkout subscription. */
    suspend fun cancelSubscription(userId: String, productId: String, immediate: Boolean): Result<Unit> {
        val body = """{"user_id":"${esc(userId)}","product_id":"${esc(productId)}","immediate":$immediate}"""
        return http.post("/v1/iap/subscriptions/cancel/", body).map { }
    }

    /** `POST /v1/iap/subscriptions/pause/` — pauses a web-checkout subscription; returns the resume date. */
    suspend fun pauseSubscription(userId: String, productId: String, pauseDurationDays: Int?): Result<PauseResponse> {
        val days = pauseDurationDays?.toString() ?: "null"
        val body = """{"user_id":"${esc(userId)}","product_id":"${esc(productId)}","pause_duration_days":$days}"""
        return http.post("/v1/iap/subscriptions/pause/", body).mapDecode(PauseResponse.serializer())
    }

    /** `POST /v1/iap/subscriptions/resume/` — resumes a paused web-checkout subscription. */
    suspend fun resumeSubscription(userId: String, productId: String): Result<Unit> {
        val body = """{"user_id":"${esc(userId)}","product_id":"${esc(productId)}"}"""
        return http.post("/v1/iap/subscriptions/resume/", body).map { }
    }

    // ─── Cancel flow ───────────────────────────────────────────────────────

    /** `GET /v1/iap/cancel-flow/?user_id=…` — server-driven retention survey + save offer. */
    suspend fun fetchCancelFlowConfig(userId: String): Result<com.zerosettle.sdk.models.CancelFlow.Config> =
        http.get("/v1/iap/cancel-flow/?user_id=${enc(userId)}")
            .mapDecode(com.zerosettle.sdk.models.CancelFlow.Config.serializer())

    /** `POST /v1/iap/cancel-flow/accept-offer/` — accepts the in-cancel-flow save offer. */
    suspend fun acceptSaveOffer(userId: String, productId: String): Result<com.zerosettle.sdk.models.CancelFlow.SaveOfferResult> {
        val body = """{"user_id":"${esc(userId)}","product_id":"${esc(productId)}"}"""
        return http.post("/v1/iap/cancel-flow/accept-offer/", body)
            .mapDecode(com.zerosettle.sdk.models.CancelFlow.SaveOfferResult.serializer())
    }

    /**
     * `POST /v1/iap/cancel-flow/respond/` — records the user's cancel-flow outcome
     * for retention analytics. [outcome] is one of `cancelled` / `retained` /
     * `dismissed` / `paused`. Optional [variantId] is the experiment variant id the
     * SDK received from the GET (echoed back so impressions/conversions line up).
     */
    suspend fun submitCancelFlowResponse(
        userId: String,
        outcome: String,
        productId: String? = null,
        variantId: Int? = null,
    ): Result<Unit> {
        val req = CancelFlowResponseRequest(
            userId = userId, outcome = outcome, productId = productId, variantId = variantId,
        )
        val body = json.encodeToString(CancelFlowResponseRequest.serializer(), req)
        return http.post("/v1/iap/cancel-flow/respond/", body).map { }
    }

    // ─── Upgrade offer ─────────────────────────────────────────────────────

    /** `GET /v1/iap/upgrade-offer/?user_id=…[&product_id=…]` — in-app plan-switch offer config. */
    suspend fun fetchUpgradeOfferConfig(userId: String, productId: String?): Result<com.zerosettle.sdk.models.UpgradeOffer.Config> {
        val path = buildString {
            append("/v1/iap/upgrade-offer/?user_id=").append(enc(userId))
            productId?.let { append("&product_id=").append(enc(it)) }
        }
        return http.get(path).mapDecode(com.zerosettle.sdk.models.UpgradeOffer.Config.serializer())
    }

    /**
     * `POST /v1/iap/upgrade-offer/respond/` — records a declined/dismissed upgrade
     * offer. [outcome] is `declined` or `dismissed`.
     */
    suspend fun respondUpgradeOffer(
        userId: String,
        currentProductId: String,
        targetProductId: String,
        outcome: String,
    ): Result<Unit> {
        val req = UpgradeOfferResponseRequest(
            userId = userId, currentProductId = currentProductId,
            targetProductId = targetProductId, outcome = outcome,
        )
        val body = json.encodeToString(UpgradeOfferResponseRequest.serializer(), req)
        return http.post("/v1/iap/upgrade-offer/respond/", body).map { }
    }

    /**
     * `POST /v1/iap/migration-actions/{transaction_id}/dismiss/` — dismisses a pending
     * migration action (a `pending_actions[]` entry). [transactionId] is the migration
     * transaction id (the path param). The body carries the identified [userId] (the
     * backend 403s on a mismatch) and an [actionType] discriminator
     * (`"info_banner_dismissed"` for a `migration_completed_info`,
     * `"manual_play_cancel_completed"` for a `manual_play_cancel`).
     */
    suspend fun dismissMigrationAction(transactionId: String, userId: String, actionType: String): Result<Unit> {
        val body = """{"user_id":"${esc(userId)}","action_type":"${esc(actionType)}"}"""
        return http.post("/v1/iap/migration-actions/${enc(transactionId)}/dismiss/", body).map { }
    }

    /**
     * `POST /v1/iap/upgrade-offer/execute/` — server-side executes an accepted
     * web→web plan switch (Stripe `modify` — no WebView), or returns the metadata
     * for a StoreKit→web upgrade checkout. Mirrors iOS `Backend.executeUpgradeOffer`.
     *
     * Wire body field names are `current_product_id` / `target_product_id` (the
     * backend handler reads exactly those — `from_*`/`to_*` were never accepted).
     */
    suspend fun executeUpgradeOffer(userId: String, currentProductId: String, targetProductId: String): Result<ExecuteUpgradeResponse> {
        val req = ExecuteUpgradeRequest(userId = userId, currentProductId = currentProductId, targetProductId = targetProductId)
        val body = json.encodeToString(ExecuteUpgradeRequest.serializer(), req)
        return http.post("/v1/iap/upgrade-offer/execute/", body).mapDecode(ExecuteUpgradeResponse.serializer())
    }

    /**
     * `POST /v1/iap/claim-entitlement/` — explicit cross-account ownership transfer.
     *
     * **BACKEND GAP (audit task #115):** the backend handler today requires a
     * `jws_representation` (an Apple-signed StoreKit JWS) and validates it
     * before transferring. There is no Play-side analogue, so this method
     * cannot fulfill a Play claim — the backend will 400 on a missing JWS.
     * A separate ``claim-play-entitlement/`` endpoint (or a polymorphic body)
     * is needed before [com.zerosettle.sdk.ZeroSettle.transferPlayOwnershipToCurrentUser]
     * is functional end-to-end on Android.
     */
    suspend fun claimEntitlement(
        userId: String,
        productId: String,
        originalTransactionId: String,
    ): Result<Unit> {
        val body =
            """{"user_id":"${esc(userId)}","product_id":"${esc(productId)}","original_transaction_id":"${esc(originalTransactionId)}"}"""
        return http.post("/v1/iap/claim-entitlement/", body).map { }
    }

    /**
     * `GET /v1/iap/transaction-history/?user_id=…` — full transaction history
     * for the identified user. Decoded into a typed list at the boundary;
     * decode failures surface as [ZeroSettleError.BackendError]. Mirrors iOS
     * Kit's `fetchTransactionHistory() -> [CheckoutTransaction]`.
     */
    suspend fun fetchTransactionHistory(userId: String): Result<List<com.zerosettle.sdk.models.CheckoutTransaction>> =
        http.get("/v1/iap/transaction-history/?user_id=${enc(userId)}")
            .mapDecode(TransactionHistoryResponse.serializer())
            .map { it.transactions }

    /**
     * `GET /v1/iap/transactions/<transactionId>/` — hydrated single-transaction record.
     * Used by [ZeroSettle.purchase] after the web-checkout deep-link returns so the
     * caller gets a [CheckoutTransaction] (not just an opaque id). Returns a
     * `404`→[ZeroSettleError.BackendError] if the id is unknown to the backend.
     */
    suspend fun fetchTransaction(transactionId: String): Result<com.zerosettle.sdk.models.CheckoutTransaction> =
        http.get("/v1/iap/transactions/${enc(transactionId)}/")
            .mapDecode(com.zerosettle.sdk.models.CheckoutTransaction.serializer())

    /**
     * `POST /v1/iap/play-ucb/initiate/` — fires User Choice Billing alternative-
     * payment checkout. The Play Billing UCB choice screen handed us a one-shot
     * [externalTransactionToken]; the backend exchanges it (server-side) with
     * Google's `externaltransactions.createexternaltransactiontoken`, mints a
     * Stripe PaymentIntent on the merchant's connected account (Stripe Tax
     * enabled), and returns the PaymentIntent `client_secret` + acct id so the
     * SDK can present `PaymentSheet`.
     *
     * On error: backend can return 422 with `{"error":"stripe_tax_not_configured"}`
     * (merchant hasn't enabled Stripe Tax) — caller surfaces this to the developer
     * as a configuration failure. All other errors flow through the standard
     * [ZeroSettleError.BackendError] / [ZeroSettleError.NetworkError] paths.
     */
    suspend fun initiatePlayUcb(
        externalTransactionToken: String,
        productId: String,
        userId: String,
    ): Result<UcbInitiateResponse> {
        val req = UcbInitiateRequest(
            externalTransactionToken = externalTransactionToken,
            productId = productId,
            userId = userId,
        )
        val body = json.encodeToString(UcbInitiateRequest.serializer(), req)
        return http.post("/v1/iap/play-ucb/initiate/", body).mapDecode(UcbInitiateResponse.serializer())
    }

    /**
     * `POST /v1/iap/play-store-transactions/` — syncs a Play Billing purchase to the
     * backend (creates a Transaction + entitlement, or surfaces a cross-account
     * conflict).
     */
    suspend fun syncPlayPurchase(
        userId: String,
        purchaseToken: String,
        productId: String,
        packageName: String,
        orderId: String?,
        purchaseState: Int,
        isAcknowledged: Boolean,
        signature: String,
        originalJson: String,
        willAutoRenew: Boolean,
        customerName: String?,
        customerEmail: String?,
    ): Result<PlaySyncResponse> {
        val req = PlaySyncRequest(
            userId = userId, purchaseToken = purchaseToken, productId = productId,
            packageName = packageName, orderId = orderId, purchaseState = purchaseState,
            isAcknowledged = isAcknowledged, signature = signature, originalJson = originalJson,
            willAutoRenew = willAutoRenew, customerName = customerName, customerEmail = customerEmail,
        )
        val body = json.encodeToString(PlaySyncRequest.serializer(), req)
        return http.post("/v1/iap/play-store-transactions/", body)
            .mapDecode(PlaySyncResponse.serializer())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Decode helper that converts JSON-parse failures into [ZeroSettleError.BackendError]. */
    private fun <T> Result<String>.mapDecode(serializer: KSerializer<T>): Result<T> =
        fold(
            onSuccess = {
                try {
                    Result.success(json.decodeFromString(serializer, it))
                } catch (e: Throwable) {
                    Result.failure(
                        ZeroSettleError.BackendError(statusCode = 200, body = "decode_failed: ${e.message}"),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )
}

@Serializable
internal data class PauseResponse(
    val success: Boolean = false,
    val status: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("resumes_at") val resumesAt: String? = null,
)

/**
 * Response from `POST /v1/iap/upgrade-offer/execute/`. The handler returns one of
 * two shapes keyed by `upgrade_type`: `web_to_web` carries [entitlement];
 * `storekit_to_web` carries [targetProductId] + [cancelInstructions] + [metadata]
 * (the SDK then drives a checkout for `target_product_id`).
 */
@Serializable
internal data class ExecuteUpgradeResponse(
    val success: Boolean = false,
    @SerialName("upgrade_type") val upgradeType: String? = null,
    val entitlement: UpgradeEntitlement? = null,
    @SerialName("target_product_id") val targetProductId: String? = null,
    @SerialName("cancel_instructions") val cancelInstructions: String? = null,
    val metadata: JsonObject? = null,
) {
    /** The product id the user ended up on (web→web) or should check out for (storekit→web). */
    val newProductId: String? get() = entitlement?.productId ?: targetProductId
}

@Serializable
internal data class UpgradeEntitlement(
    @SerialName("product_id") val productId: String? = null,
    val status: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
internal data class ExecuteUpgradeRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("current_product_id") val currentProductId: String,
    @SerialName("target_product_id") val targetProductId: String,
)

@Serializable
internal data class CancelFlowResponseRequest(
    @SerialName("user_id") val userId: String,
    val outcome: String,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("variant_id") val variantId: Int? = null,
)

@Serializable
internal data class UpgradeOfferResponseRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("current_product_id") val currentProductId: String,
    @SerialName("target_product_id") val targetProductId: String,
    val outcome: String,
)

@Serializable
internal data class CheckoutConfigRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("play_purchase_token") val playPurchaseToken: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_email") val customerEmail: String? = null,
)

@Serializable
internal data class CheckoutConfigResponse(
    @SerialName("checkout_url") val checkoutUrl: String,
    @SerialName("checkout_presentation") val checkoutPresentation: com.zerosettle.sdk.checkout.CheckoutPresentation? = null,
    @SerialName("stripe_customer_id") val stripeCustomerId: String? = null,
)

@Serializable
internal data class PlaySyncRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("purchase_token") val purchaseToken: String,
    @SerialName("product_id") val productId: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("order_id") val orderId: String?,
    @SerialName("purchase_state") val purchaseState: Int,
    @SerialName("is_acknowledged") val isAcknowledged: Boolean,
    val signature: String,
    @SerialName("original_json") val originalJson: String,
    @SerialName("will_auto_renew") val willAutoRenew: Boolean,
    @SerialName("customer_name") val customerName: String?,
    @SerialName("customer_email") val customerEmail: String?,
)

/**
 * Response from `POST /v1/iap/play-store-transactions/`.
 *
 * The endpoint is **fully synchronous**: it processes the Play purchase
 * inline and returns HTTP 200 with the complete body
 * `{status, owned, transaction_id, entitlement_id, conflict,
 * claim_available, existing_owner_hint, is_sandbox}`. The SDK's purchase
 * flow reads `owned`/`conflict`/`claimAvailable` directly off this response
 * (see `PurchaseSyncProcessor.process`) to resolve the deferred-bridge in
 * `PlayBillingCoordinator` (`onPurchaseSynced` / `onPurchaseFailed`).
 *
 * **Wire types:** `transaction_id` and `entitlement_id` are Django model
 * PKs — JSON **integers** (`BigAutoField` → [Long]), or `null`. They are
 * NOT strings: the SDK's strict [Json] config (no `isLenient`, no
 * `coerceInputValues`) throws `JsonDecodingException` if an unquoted JSON
 * number is decoded into a `String` property, so these MUST stay [Long].
 * Consumable purchases return `entitlement_id: null` (no entitlement row),
 * which [Long]? accepts. Adapt to `String` at call sites with `?.toString()`.
 */
@Serializable
internal data class PlaySyncResponse(
    val owned: Boolean = false,
    @SerialName("transaction_id") val transactionId: Long? = null,
    // Canonical `txn_*` string id of the synced transaction. This is the id
    // the backend's `GET /v1/iap/transactions/{id}/` resolves on — the integer
    // `transactionId` PK 404s there. Always prefer this for any downstream
    // transaction-id use (post-purchase fetch, events, pending claims).
    // Nullable so an older backend that doesn't emit it still decodes.
    @SerialName("transaction_ref") val transactionRef: String? = null,
    @SerialName("entitlement_id") val entitlementId: Long? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean = false,
    val conflict: Boolean = false,
    @SerialName("claim_available") val claimAvailable: Boolean = false,
    @SerialName("existing_owner_hint") val existingOwnerHint: String? = null,
)

@Serializable
internal data class TransactionHistoryResponse(
    val transactions: List<com.zerosettle.sdk.models.CheckoutTransaction> = emptyList(),
)

/**
 * Request body for `POST /v1/iap/play-ucb/initiate/`. Mirrors the backend
 * test fixtures' wire shape: snake_case field names.
 */
@Serializable
internal data class UcbInitiateRequest(
    @SerialName("external_transaction_token") val externalTransactionToken: String,
    @SerialName("product_id") val productId: String,
    @SerialName("user_id") val userId: String,
)

/**
 * Response body for `POST /v1/iap/play-ucb/initiate/`. The backend always
 * returns [clientSecret] and [externalTransactionId]; [stripeAccount],
 * [merchantCountry], and [transactionId] are nullable to match the
 * fixtures' edge-cases (e.g., platform-managed merchants with no Connect
 * account; sub-period merchants without a transaction id yet).
 */
@Serializable
internal data class UcbInitiateResponse(
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("stripe_account") val stripeAccount: String? = null,
    @SerialName("merchant_country") val merchantCountry: String? = null,
    @SerialName("external_transaction_id") val externalTransactionId: String,
    @SerialName("transaction_id") val transactionId: Long? = null,
    // FB-2: the canonical `ucb_*` string id of the materialised transaction.
    // `GET /v1/iap/transactions/{id}/` resolves on this; the integer
    // `transaction_id` PK 404s there. Nullable — graceful for an older
    // backend that predates this field (same pattern as
    // [PlaySyncResponse.transactionRef]).
    @SerialName("transaction_ref") val transactionRef: String? = null,
)

@Serializable
internal data class EntitlementsResponse(
    val entitlements: List<Entitlement> = emptyList(),
    // TODO(phase 7): replace JsonObject with a typed PendingAction model.
    @SerialName("pending_actions") val pendingActions: List<JsonObject> = emptyList(),
    // The entitlements endpoint does not currently emit `pending_claims` — kept here
    // (defaults to `[]`) because phases 6/7 (`OfferManager`, the `ZeroSettle` facade)
    // consume `pendingClaims` and the backend is expected to surface it. Conflicts are
    // currently surfaced via [PlaySyncResponse.conflict] / .claimAvailable instead.
    @SerialName("pending_claims") val pendingClaims: List<PendingClaim> = emptyList(),
)
