package io.zerosettle.justone

import android.content.Context
import com.zerosettle.sdk.Identity

/**
 * Test-harness config. Each [Env] carries its own sandbox publishable key
 * (`zs_pk_test_…`, issued under this sample app's applicationId) alongside its
 * base URL — switching env on the Sign-in screen switches both. Resolve the
 * active env's key with [resolvePublishableKey].
 */
internal object SampleConfig {

    /**
     * Backend environments selectable from the Sign-in screen.
     * `baseUrl == null` → the SDK's built-in default (production, `https://api.zerosettle.io`).
     * For [CUSTOM] the URL comes from the persisted custom-URL field instead.
     */
    enum class Env(
        val label: String,
        val baseUrl: String?,
        /** Per-app sandbox publishable key for this env. Empty when one has
         *  not been issued yet — [resolvePublishableKey] supplies a fallback. */
        val publishableKey: String,
    ) {
        // Prod publishable key not issued yet — empty; resolvePublishableKey
        // falls back so configure() never sees a blank key.
        PRODUCTION("Production (live API)", "https://api.zerosettle.io", ""),
        // Adjust if your staging host differs (Render service URL, custom domain, etc.).
        STAGING(
            "Staging",
            "https://api-staging.zerosettle.io",
            "zs_pk_test_bded1f5dddde6f79ac538bff33b70737244a3557555d863c",
        ),
        // The Android emulator's loopback alias for the host machine's localhost.
        // Requires android:usesCleartextTraffic="true" in the manifest (already set).
        LOCAL_EMULATOR(
            "Local — emulator (api.zerosettle.ngrok.app)",
            "https://api.zerosettle.ngrok.app",
            "zs_pk_test_55cc0bdc80d2a3274238e00f74ca78f668e83f0ad8a46b48",
        ),
        // Free-form: paste an ngrok tunnel, a LAN IP (http://192.168.x.x:8000), etc.
        // Reuses the localhost key — custom URLs in dev are usually local/ngrok.
        CUSTOM(
            "Custom…",
            null,
            "zs_pk_test_55cc0bdc80d2a3274238e00f74ca78f668e83f0ad8a46b48",
        ),
    }

    private const val PREFS = "zerosettle_sample_prefs"
    private const val KEY_ENV = "backend_env"
    private const val KEY_CUSTOM_URL = "backend_custom_url"
    private const val KEY_ECL_OVERRIDE = "ecl_availability_override"
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

    // ── Switch & Save (ECL) testing override ────────────────────────────
    //
    // When enabled, the sample sets `ZeroSettle.eclAvailabilityOverride = true`
    // so the Switch & Save offer tip surfaces even on devices/accounts not
    // enrolled in Google's External Content Link program. Persisted so the
    // choice survives a cold start; re-applied by `configureSdk`.

    /** Whether the ECL availability override is enabled (defaults to off). */
    fun loadEclOverride(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ECL_OVERRIDE, false)

    fun saveEclOverride(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ECL_OVERRIDE, enabled).apply()
    }

    /** The `baseUrlOverride` to pass to `ZeroSettleConfig`, given the persisted env. */
    fun resolveBaseUrlOverride(ctx: Context): String? = when (val e = loadEnv(ctx)) {
        Env.PRODUCTION -> null
        Env.STAGING -> e.baseUrl
        Env.LOCAL_EMULATOR -> e.baseUrl
        Env.CUSTOM -> loadCustomUrl(ctx).ifBlank { null }
    }

    /** The publishable key for the persisted env. Falls back to the staging
     *  key when the env's own key has not been issued yet (e.g. production),
     *  so `ZeroSettleConfig` never receives a blank key. */
    fun resolvePublishableKey(ctx: Context): String =
        loadEnv(ctx).publishableKey.ifBlank { Env.STAGING.publishableKey }

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
