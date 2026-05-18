package io.zerosettle.justone

import android.content.Context
import com.zerosettle.sdk.Identity

/**
 * Test-harness config. Fill [PUBLISHABLE_KEY] with a sandbox key (`zs_pk_test_…`)
 * from the ZeroSettle dashboard. The backend environment is chosen at runtime on
 * the Sign-in screen (and persisted) — see [Env] / [resolveBaseUrlOverride].
 *
 * The placeholder key below satisfies `ZeroSettleConfig` (only the `zs_pk_test_`
 * prefix is validated) but every backend call 401s until you replace it.
 */
internal object SampleConfig {
    const val PUBLISHABLE_KEY = "zs_pk_test_55cc0bdc80d2a3274238e00f74ca78f668e83f0ad8a46b48"
    const val TEST_USER_ID = "sample-user-1"

    /**
     * Backend environments selectable from the Sign-in screen.
     * `baseUrl == null` → the SDK's built-in default (production, `https://api.zerosettle.io`).
     * For [CUSTOM] the URL comes from the persisted custom-URL field instead.
     */
    enum class Env(val label: String, val baseUrl: String?) {
        PRODUCTION("Production (live API)", "https://api.zerosettle.io"),
        // Adjust if your staging host differs (Render service URL, custom domain, etc.).
        STAGING("Staging", "https://api-staging.zerosettle.io"),
        // The Android emulator's loopback alias for the host machine's localhost.
        // Requires android:usesCleartextTraffic="true" in the manifest (already set).
        LOCAL_EMULATOR("Local — emulator (api.zerosettle.ngrok.app)", "https://api.zerosettle.ngrok.app"),
        // Free-form: paste an ngrok tunnel, a LAN IP (http://192.168.x.x:8000), etc.
        CUSTOM("Custom…", null),
    }

    private const val PREFS = "zerosettle_sample_prefs"
    private const val KEY_ENV = "backend_env"
    private const val KEY_CUSTOM_URL = "backend_custom_url"
    private const val KEY_IDENTITY_TYPE = "identity_type"
    private const val KEY_IDENTITY_USER_ID = "identity_user_id"
    private const val KEY_IDENTITY_NAME = "identity_name"
    private const val KEY_IDENTITY_EMAIL = "identity_email"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persisted env (defaults to STAGING — that's where in-flight backend changes live). */
    fun loadEnv(ctx: Context): Env =
        runCatching { Env.valueOf(prefs(ctx).getString(KEY_ENV, Env.STAGING.name)!!) }
            .getOrDefault(Env.STAGING)

    fun saveEnv(ctx: Context, env: Env) {
        prefs(ctx).edit().putString(KEY_ENV, env.name).apply()
    }

    fun loadCustomUrl(ctx: Context): String = prefs(ctx).getString(KEY_CUSTOM_URL, "") ?: ""

    fun saveCustomUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_CUSTOM_URL, url.trim()).apply()
    }

    /** The `baseUrlOverride` to pass to `ZeroSettleConfig`, given the persisted env. */
    fun resolveBaseUrlOverride(ctx: Context): String? = when (val e = loadEnv(ctx)) {
        Env.PRODUCTION -> null
        Env.STAGING -> e.baseUrl
        Env.LOCAL_EMULATOR -> e.baseUrl
        Env.CUSTOM -> loadCustomUrl(ctx).ifBlank { null }
    }

    /** Human-readable effective base URL (for display). */
    fun effectiveBaseUrl(ctx: Context): String =
        resolveBaseUrlOverride(ctx) ?: "https://api.zerosettle.io (SDK default)"

    // ── Identity persistence ────────────────────────────────────────────
    //
    // Mirrors the JustOne iOS sample: the last identity the user signed
    // in with is replayed on next launch so they aren't re-prompted every
    // cold start. The SDK's own state isn't persisted across process
    // restarts — that's the host app's job; the sample acts as the
    // reference implementation. Cleared on explicit logout + on env
    // switch (since an identity bound to staging won't be valid against
    // production).

    fun saveIdentity(ctx: Context, identity: Identity) {
        val edit = prefs(ctx).edit()
        when (identity) {
            is Identity.User -> {
                edit.putString(KEY_IDENTITY_TYPE, "user")
                edit.putString(KEY_IDENTITY_USER_ID, identity.id)
                edit.putString(KEY_IDENTITY_NAME, identity.name)
                edit.putString(KEY_IDENTITY_EMAIL, identity.email)
            }
            Identity.Anonymous -> {
                edit.putString(KEY_IDENTITY_TYPE, "anonymous")
                edit.remove(KEY_IDENTITY_USER_ID)
                edit.remove(KEY_IDENTITY_NAME)
                edit.remove(KEY_IDENTITY_EMAIL)
            }
            Identity.Deferred -> {
                edit.putString(KEY_IDENTITY_TYPE, "deferred")
                edit.remove(KEY_IDENTITY_USER_ID)
                edit.remove(KEY_IDENTITY_NAME)
                edit.remove(KEY_IDENTITY_EMAIL)
            }
        }
        edit.apply()
    }

    fun loadIdentity(ctx: Context): Identity? {
        val p = prefs(ctx)
        return when (p.getString(KEY_IDENTITY_TYPE, null)) {
            "user" -> {
                val id = p.getString(KEY_IDENTITY_USER_ID, null) ?: return null
                Identity.User(
                    id = id,
                    name = p.getString(KEY_IDENTITY_NAME, null),
                    email = p.getString(KEY_IDENTITY_EMAIL, null),
                )
            }
            "anonymous" -> Identity.Anonymous
            "deferred" -> Identity.Deferred
            else -> null
        }
    }

    fun clearIdentity(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_IDENTITY_TYPE)
            .remove(KEY_IDENTITY_USER_ID)
            .remove(KEY_IDENTITY_NAME)
            .remove(KEY_IDENTITY_EMAIL)
            .apply()
    }
}
