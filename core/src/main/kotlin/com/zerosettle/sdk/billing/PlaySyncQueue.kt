package com.zerosettle.sdk.billing

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zerosettle.sdk.internal.syncQueueDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistent retry queue for failed Play purchase syncs. The Android analog of iOS
 * `StoreKitSyncQueue`. Backed by Preferences DataStore (one JSON-encoded array under
 * a single key) so pending syncs survive process death.
 *
 * Retry policy mirrors iOS exactly:
 *  - max **5** attempts ([MAX_ATTEMPTS])
 *  - exponential backoff `[1s, 5s, 30s, 5m]` for attempts 0-3 ([backoffDelayMillis])
 *  - abandon after attempt 4 — the [PurchaseSyncProcessor] then logs + emits a
 *    terminal `ZeroSettleEvent.SyncFailed`
 *
 * Cleared on `ZeroSettle.logout()` so the previous user's pending syncs never run
 * under a new user's identity.
 */
public class PlaySyncQueue(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("pending_play_syncs_json")

    public suspend fun pending(): List<PendingPurchaseSync> {
        val raw = context.syncQueueDataStore.data.first()[key] ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PendingPurchaseSync.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    /** Add a row, or — if [PendingPurchaseSync.purchaseToken] is already queued — leave it (dedup). */
    public suspend fun enqueue(item: PendingPurchaseSync) {
        mutate { current ->
            if (current.any { it.purchaseToken == item.purchaseToken }) current else current + item
        }
    }

    /** Increment the attempt count + stamp the last-attempt time for [purchaseToken]. */
    public suspend fun recordFailure(purchaseToken: String, atMillis: Long = System.currentTimeMillis()) {
        mutate { current ->
            current.map {
                if (it.purchaseToken == purchaseToken) it.copy(attemptCount = it.attemptCount + 1, lastAttemptAtMillis = atMillis)
                else it
            }
        }
    }

    public suspend fun remove(purchaseToken: String) {
        mutate { current -> current.filterNot { it.purchaseToken == purchaseToken } }
    }

    public suspend fun clear() {
        context.syncQueueDataStore.edit { it.remove(key) }
    }

    private suspend fun mutate(transform: (List<PendingPurchaseSync>) -> List<PendingPurchaseSync>) {
        context.syncQueueDataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString(ListSerializer(PendingPurchaseSync.serializer()), it) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[key] = json.encodeToString(ListSerializer(PendingPurchaseSync.serializer()), transform(current))
        }
    }

    public companion object {
        public const val MAX_ATTEMPTS: Int = 5

        /** Backoff for attempts 0-3 (millis); attempt 4+ → abandoned. Matches iOS `StoreKitSyncQueue.backoffDelays`. */
        private val BACKOFF_MILLIS: LongArray = longArrayOf(1_000L, 5_000L, 30_000L, 300_000L)

        public fun backoffDelayMillis(attemptCount: Int): Long? =
            if (attemptCount in BACKOFF_MILLIS.indices) BACKOFF_MILLIS[attemptCount] else null

        public fun isAbandoned(item: PendingPurchaseSync): Boolean = item.attemptCount >= MAX_ATTEMPTS
    }
}
