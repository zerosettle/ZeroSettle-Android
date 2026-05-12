package com.zerosettle.sdk.internal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * Persists the anonymous UUID, active userId, and customer metadata.
 *
 * Anonymous UUID is generated on first read and remains stable until [clear].
 */
internal class IdentityStore(private val context: Context) {

    private val keyAnon = stringPreferencesKey("anonymous_session_uuid")
    private val keyUser = stringPreferencesKey("active_user_id")
    private val keyCustomerName = stringPreferencesKey("customer_name")
    private val keyCustomerEmail = stringPreferencesKey("customer_email")

    suspend fun anonymousUuid(): String {
        val prefs = context.identityDataStore.data.first()
        prefs[keyAnon]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        context.identityDataStore.edit { it[keyAnon] = fresh }
        return fresh
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
}
