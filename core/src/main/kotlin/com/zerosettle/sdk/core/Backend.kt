package com.zerosettle.sdk.core

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

    /** `POST /v1/iap/migration-converted/` — records that a migration offer converted. */
    suspend fun migrationConverted(userId: String, source: String): Result<Unit> {
        val body = """{"user_id":"${esc(userId)}","source":"${esc(source)}"}"""
        return http.post("/v1/iap/migration-converted/", body).map { }
    }

    /**
     * `POST /v1/iap/migration-actions/{id}/dismiss/` — dismisses a pending migration action.
     * [actionId] is the migration transaction id (the backend's `transaction_id` path param).
     */
    suspend fun dismissMigrationAction(actionId: String): Result<Unit> =
        http.post("/v1/iap/migration-actions/${enc(actionId)}/dismiss/", body = "{}").map { }

    /**
     * `POST /v1/iap/upgrade-offer/execute/` — server-side executes an accepted
     * in-app upgrade offer. The caller supplies the body; returns the raw JSON.
     */
    suspend fun executeUpgradeOffer(body: String): Result<String> =
        http.post("/v1/iap/upgrade-offer/execute/", body)

    /** `POST /v1/iap/claim-entitlement/` — explicit cross-account ownership transfer. */
    suspend fun claimEntitlement(
        userId: String,
        productId: String,
        originalTransactionId: String,
    ): Result<Unit> {
        val body =
            """{"user_id":"${esc(userId)}","product_id":"${esc(productId)}","original_transaction_id":"${esc(originalTransactionId)}"}"""
        return http.post("/v1/iap/claim-entitlement/", body).map { }
    }

    /** `GET /v1/iap/transaction-history/?user_id=…` — full transaction history (raw JSON). */
    suspend fun fetchTransactionHistory(userId: String): Result<String> =
        http.get("/v1/iap/transaction-history/?user_id=${enc(userId)}")

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

@Serializable
internal data class PlaySyncResponse(
    val owned: Boolean = false,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("entitlement_id") val entitlementId: String? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean = false,
    val conflict: Boolean = false,
    @SerialName("claim_available") val claimAvailable: Boolean = false,
    @SerialName("existing_owner_hint") val existingOwnerHint: String? = null,
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
