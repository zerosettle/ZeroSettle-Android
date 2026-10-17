package com.zerosettle.sdk.access

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zerosettle.sdk.core.EntitlementsResponse
import com.zerosettle.sdk.internal.accessDataStore
import com.zerosettle.sdk.models.Access
import com.zerosettle.sdk.models.Balance
import com.zerosettle.sdk.models.FeatureValue
import com.zerosettle.sdk.models.Plan
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * On-device copy of the last present [Access], one entry per environment and user,
 * so features work before the first network read and while offline.
 *
 * The entry is stored in the same shape the backend sends, so reading it back reuses
 * the tolerant decoder. The user id is hashed; it never appears in the key.
 */
internal class AccessStore(private val context: Context, private val environment: String) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun key(userId: String) =
        stringPreferencesKey("access.v1.$environment.${hashPrefix(userId)}")

    suspend fun load(userId: String): Access? {
        val raw = context.accessDataStore.data.first()[key(userId)] ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(raw) as JsonObject
            EntitlementsResponse(
                plan = obj["plan"], addOns = obj["add_ons"],
                features = obj["features"], balances = obj["balances"],
            ).access()
        }.getOrNull()?.takeIf { it.isPresent }
    }

    suspend fun save(userId: String, access: Access) {
        if (!access.isPresent) return
        val encoded = buildJsonObject {
            access.plan?.let { put("plan", json.encodeToJsonElement(Plan.serializer(), it)) }
            put("add_ons", json.encodeToJsonElement(ListSerializer(Plan.serializer()), access.addOns))
            put("features", JsonObject(access.features.mapValues { (_, v) ->
                when (v) {
                    is FeatureValue.Bool -> JsonPrimitive(v.value)
                    is FeatureValue.Text -> JsonPrimitive(v.value)
                }
            }))
            put("balances", JsonObject(access.balances.mapValues { (_, v) ->
                json.encodeToJsonElement(Balance.serializer(), v)
            }))
        }
        context.accessDataStore.edit { it[key(userId)] = encoded.toString() }
    }

    suspend fun remove(userId: String) {
        context.accessDataStore.edit { it.remove(key(userId)) }
    }

    private fun hashPrefix(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
