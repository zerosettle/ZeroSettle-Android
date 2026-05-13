package com.zerosettle.sdk.offers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.zerosettle.sdk.internal.offerDismissalsDataStore
import kotlinx.coroutines.flow.first

/**
 * Persists per-userId offer dismissals. A dismissed offer makes [OfferManager] go
 * straight to `INELIGIBLE` for that user — across sessions. Mirrors iOS
 * `ZSOfferManager`'s dismissal persistence (`zerosettle.offer_dismissed.<userId>`).
 */
public class OfferDismissalStore(private val context: Context) {

    private fun key(userId: String) = booleanPreferencesKey("offer_dismissed_$userId")

    public suspend fun isDismissed(userId: String): Boolean =
        context.offerDismissalsDataStore.data.first()[key(userId)] ?: false

    public suspend fun dismiss(userId: String) {
        context.offerDismissalsDataStore.edit { it[key(userId)] = true }
    }

    /**
     * Clear the dismissal preference for a single user. Pairs with [dismiss] —
     * adopters who need a per-user "un-dismiss" call this rather than the
     * coarse [resetAll] (which clears every user's dismissal).
     */
    public suspend fun undismiss(userId: String) {
        context.offerDismissalsDataStore.edit { it.remove(key(userId)) }
    }

    /** Clear all dismissal flags (testing / debug). */
    public suspend fun resetAll() {
        context.offerDismissalsDataStore.edit { it.clear() }
    }
}
