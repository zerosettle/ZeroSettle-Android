package io.zerosettle.justone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "justone_user")

/**
 * Wraps DataStore Preferences for app identity + reminder + paywall state.
 * `userId` presence gates the Onboarding vs Main route tree.
 */
class UserPrefs(private val ctx: Context) {

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val EMAIL = stringPreferencesKey("email")
        val REMINDER_HOUR = intPreferencesKey("reminder_time_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_time_minute")
        val PAYWALL_DISMISSED_AT = longPreferencesKey("launch_paywall_dismissed_at")
        val SEEDED = booleanPreferencesKey("seeded")
    }

    data class Identity(val userId: String, val displayName: String, val email: String)
    data class ReminderTime(val hour: Int, val minute: Int)

    val identity: Flow<Identity?> = ctx.dataStore.data.map { p ->
        val id = p[Keys.USER_ID] ?: return@map null
        Identity(id, p[Keys.DISPLAY_NAME].orEmpty(), p[Keys.EMAIL].orEmpty())
    }

    val reminderTime: Flow<ReminderTime?> = ctx.dataStore.data.map { p ->
        val h = p[Keys.REMINDER_HOUR] ?: return@map null
        ReminderTime(h, p[Keys.REMINDER_MINUTE] ?: 0)
    }

    val paywallDismissedAt: Flow<Long?> = ctx.dataStore.data.map { it[Keys.PAYWALL_DISMISSED_AT] }
    val seeded: Flow<Boolean> = ctx.dataStore.data.map { it[Keys.SEEDED] ?: false }

    suspend fun saveIdentity(userId: String, displayName: String, email: String) {
        ctx.dataStore.edit {
            it[Keys.USER_ID] = userId
            it[Keys.DISPLAY_NAME] = displayName
            it[Keys.EMAIL] = email
        }
    }

    suspend fun clearIdentity() {
        ctx.dataStore.edit {
            it.remove(Keys.USER_ID); it.remove(Keys.DISPLAY_NAME); it.remove(Keys.EMAIL)
        }
    }

    suspend fun setReminderTime(time: ReminderTime?) {
        ctx.dataStore.edit {
            if (time == null) { it.remove(Keys.REMINDER_HOUR); it.remove(Keys.REMINDER_MINUTE) }
            else { it[Keys.REMINDER_HOUR] = time.hour; it[Keys.REMINDER_MINUTE] = time.minute }
        }
    }

    suspend fun setPaywallDismissedAt(epochMillis: Long?) {
        ctx.dataStore.edit {
            if (epochMillis == null) it.remove(Keys.PAYWALL_DISMISSED_AT)
            else it[Keys.PAYWALL_DISMISSED_AT] = epochMillis
        }
    }

    suspend fun setSeeded(value: Boolean) {
        ctx.dataStore.edit { it[Keys.SEEDED] = value }
    }
}
