package com.zerosettle.sdk.entitlements

import com.zerosettle.sdk.models.PendingAction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes the `pending_actions[]` array from `GET /v1/iap/entitlements/` into typed
 * [PendingAction]s. The wire shape uses a `type` discriminator
 * (`migration_completed_info` / `manual_play_cancel`); rows with an unknown `type`,
 * a missing `type`, or a missing required field are passed to [onUnknown] (for an INFO
 * log) and dropped — forward-compat, so the backend can add new action types without
 * breaking older SDKs.
 */
public object PendingActionParser {

    public fun parse(rows: List<JsonObject>, onUnknown: (String) -> Unit): List<PendingAction> =
        rows.mapNotNull { row -> decodeOne(row, onUnknown) }

    private fun JsonObject.str(key: String): String =
        strOrNull(key) ?: error("missing $key")

    private fun JsonObject.strOrNull(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        strOrNull(key)?.toDoubleOrNull()?.toInt()

    private fun JsonObject.objOrNull(key: String): JsonObject? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return v as? JsonObject
    }

    private fun decodeOne(row: JsonObject, onUnknown: (String) -> Unit): PendingAction? {
        val type = (row["type"] as? JsonPrimitive)?.content
        return try {
            when (type) {
                "migration_completed_info" -> {
                    val price = row.objOrNull("new_subscription_price")
                    PendingAction.MigrationCompletedInfo(
                        transactionId = row.str("transaction_id"),
                        userMessage = row.str("user_message"),
                        playAccessEndsAtIso = row.strOrNull("play_access_ends_at"),
                        newSubscriptionPriceCents = price?.intOrNull("amount_cents"),
                        newSubscriptionCurrency = price?.strOrNull("currency"),
                        newSubscriptionInterval = price?.strOrNull("billing_interval"),
                    )
                }
                "manual_play_cancel" -> PendingAction.ManualPlayCancel(
                    transactionId = row.str("transaction_id"),
                    userMessage = row.str("user_message"),
                    originalPlayPurchaseToken = row.str("original_play_purchase_token"),
                    expiresAtIso = row.strOrNull("expires_at"),
                    deepLink = row.str("deep_link"),
                )
                else -> {
                    onUnknown("Unknown pending_action type: $type — ignored (forward-compat)")
                    null
                }
            }
        } catch (e: Throwable) {
            onUnknown("Malformed pending_action (type=$type) dropped: ${e.message}")
            null
        }
    }
}
