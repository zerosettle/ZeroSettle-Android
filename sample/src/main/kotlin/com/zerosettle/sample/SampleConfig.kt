package com.zerosettle.sample

import android.content.Context

/**
 * Test-harness config. Fill [PUBLISHABLE_KEY] with a sandbox key (`zs_pk_test_…`)
 * from the ZeroSettle dashboard. The backend environment is chosen at runtime on
 * the Sign-in screen (and persisted) — see [Env] / [resolveBaseUrlOverride].
 *
 * The placeholder key below satisfies `ZeroSettleConfig` (only the `zs_pk_test_`
 * prefix is validated) but every backend call 401s until you replace it.
 */
internal object SampleConfig {
    const val PUBLISHABLE_KEY = "zs_pk_test_REPLACE_ME"
    const val TEST_USER_ID = "sample-user-1"

    /**
     * Backend environments selectable from the Sign-in screen.
     * `baseUrl == null` → the SDK's built-in default (production, `https://api.zerosettle.io`).
     * For [CUSTOM] the URL comes from the persisted custom-URL field instead.
     */
    enum class Env(val label: String, val baseUrl: String?) {
        PRODUCTION("Production (live API)", null),
        // Adjust if your staging host differs (Render service URL, custom domain, etc.).
        STAGING("Staging", "https://api-staging.zerosettle.io"),
        // The Android emulator's loopback alias for the host machine's localhost.
        // Requires android:usesCleartextTraffic="true" in the manifest (already set).
        LOCAL_EMULATOR("Local — emulator (10.0.2.2:8000)", "http://10.0.2.2:8000"),
        // Free-form: paste an ngrok tunnel, a LAN IP (http://192.168.x.x:8000), etc.
        CUSTOM("Custom…", null),
    }

    private const val PREFS = "zerosettle_sample_prefs"
    private const val KEY_ENV = "backend_env"
    private const val KEY_CUSTOM_URL = "backend_custom_url"

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
}
