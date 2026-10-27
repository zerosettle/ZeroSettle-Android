package com.zerosettle.sdk.internal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * Persists the anonymous UUID, active userId, and customer metadata.
 *
 * Anonymous id is generated on first read and remains stable until [clear]. New ids are
 * [ANONYMOUS_ID_PREFIX] plus a lowercase UUID; a stored bare UUID from an older install is
 * returned unchanged. A pending identity-link pair survives restarts until the server settles it.
 */
internal class IdentityStore(private val context: Context) {

    private val keyAnon = stringPreferencesKey("anonymous_session_uuid")
    private val keyUser = stringPreferencesKey("active_user_id")
    private val keyCustomerName = stringPreferencesKey("customer_name")
    private val keyCustomerEmail = stringPreferencesKey("customer_email")
    private val keyPendingLinkFrom = stringPreferencesKey("pending_identity_link_from")
    private val keyPendingLinkInto = stringPreferencesKey("pending_identity_link_into")

    suspend fun anonymousUuid(): String {
        // Read-or-create inside one edit so concurrent first calls agree on one UUID.
        return context.identityDataStore.edit { prefs ->
            if (prefs[keyAnon] == null) prefs[keyAnon] = ANONYMOUS_ID_PREFIX + UUID.randomUUID().toString().lowercase()
        }[keyAnon]!!
    }

    /** The stored anonymous id, or null when none was minted yet (never mints). */
    suspend fun storedAnonymousId(): String? = context.identityDataStore.data.first()[keyAnon]

    suspend fun pendingIdentityLink(): PendingIdentityLink? {
        val prefs = context.identityDataStore.data.first()
        val from = prefs[keyPendingLinkFrom] ?: return null
        val into = prefs[keyPendingLinkInto] ?: return null
        return PendingIdentityLink(from = from, into = into)
    }

    suspend fun setPendingIdentityLink(link: PendingIdentityLink) {
        context.identityDataStore.edit {
            it[keyPendingLinkFrom] = link.from
            it[keyPendingLinkInto] = link.into
        }
    }

    /** Drops the pending pair if it is still [link], and the stored anonymous id if it is still [link]'s `from`. */
    suspend fun settleIdentityLink(link: PendingIdentityLink) {
        context.identityDataStore.edit { prefs ->
            // Only the pair that was sent settles; a newer pair written meanwhile stays.
            if (prefs[keyPendingLinkFrom] == link.from && prefs[keyPendingLinkInto] == link.into) {
                prefs.remove(keyPendingLinkFrom)
                prefs.remove(keyPendingLinkInto)
            }
            if (prefs[keyAnon] == link.from) prefs.remove(keyAnon)
        }
    }

    suspend fun setActiveUserId(userId: String) {
        context.identityDataStore.edit { it[keyUser] = userId }
    }

    suspend fun activeUserId(): String? = context.identityDataStore.data.first()[keyUser]

    suspend fun setCustomer(name: String?, email: String?) {
        context.identityDataStore.edit { prefs ->
            if (name != null) prefs[keyCustomerName] = name else prefs.remove(keyCustomerName)
            if (email != null) prefs[keyCustomerEmail] = email else prefs.remove(keyCustomerEmail)
        }
    }

    suspend fun customerName(): String? = context.identityDataStore.data.first()[keyCustomerName]
    suspend fun customerEmail(): String? = context.identityDataStore.data.first()[keyCustomerEmail]

    suspend fun clear() {
        context.identityDataStore.edit { it.clear() }
    }

    companion object {
        const val ANONYMOUS_ID_PREFIX = "zs_anon_"

        fun hasAnonymousPrefix(id: String): Boolean = id.startsWith(ANONYMOUS_ID_PREFIX, ignoreCase = true)
    }
}

/** An anonymous id waiting to be folded into [into] by `POST /v1/iap/identity-link/`. */
internal data class PendingIdentityLink(val from: String, val into: String)
