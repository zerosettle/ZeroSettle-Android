package com.zerosettle.sdk

import android.content.Context
import com.zerosettle.sdk.internal.ZeroSettleScope
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SDK entry point. Mirrors iOS `ZeroSettle.shared`.
 *
 * Lifecycle:
 *
 *  1. `ZeroSettle.configure(context, config)` — at `Application.onCreate()`.
 *  2. `ZeroSettle.identify(Identity.User(id = "u1"))` — when the host app knows
 *     who the user is.
 *  3. Subsequent calls (`products()`, `purchase()`, `entitlements`, …) resolve
 *     against the identified user — NO `userId` parameter overloads exist.
 *
 * State exposed as `StateFlow` (current value). Methods on the public surface
 * return `kotlin.Result<T>` — callers
 * `.onSuccess { … }.onFailure { it is ZeroSettleError.NotConfigured }`.
 */
public object ZeroSettle {

    // ─── Configuration ──────────────────────────────────────────────────────

    private val _isConfigured = MutableStateFlow(false)
    public val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    @Volatile internal var appContext: Context? = null
        private set
    @Volatile internal var config: ZeroSettleConfig? = null
        private set

    internal val scope: ZeroSettleScope = ZeroSettleScope()

    /** Call once at `Application.onCreate()`. Safe to call again to swap config. */
    public fun configure(context: Context, config: ZeroSettleConfig) {
        this.appContext = context.applicationContext
        this.config = config
        _isConfigured.value = true
    }

    // ─── Identity (Task 14 fills this in) ───────────────────────────────────

    private val _isBootstrapped = MutableStateFlow(false)
    public val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()

    /**
     * Declare who the host app is acting on behalf of and bootstrap the SDK.
     *
     * Behaviour:
     *  - [Identity.User] — full bootstrap (sync Play purchases if enabled, fetch
     *    products, restore entitlements).
     *  - [Identity.Anonymous] — generates a stable per-install UUID, bootstraps.
     *  - [Identity.Deferred] — records intent only; suppresses the
     *    "no identity declared" warning. Returns `Result.success(null)`.
     *
     * Returns `Result.success(...)` on success; `Result.failure(ZeroSettleError.*)`
     * otherwise. Bootstrap pipeline filled in by Task 14.
     */
    public suspend fun identify(identity: Identity): Result<Any?> {
        if (!_isConfigured.value) return Result.failure(ZeroSettleError.NotConfigured)
        // Task 14 fills in the bootstrap pipeline.
        return Result.success(null)
    }

    /** Clear identity, customer metadata, sync queue. Cancels in-flight background work. */
    public fun logout() {
        scope.reset()
        _isBootstrapped.value = false
        // Task 14 / Task 28 fill in identity + sync-queue clears.
    }

    // ─── Test surface ───────────────────────────────────────────────────────

    /** Test-only: reset all state. */
    internal fun resetForTesting() {
        scope.reset()
        _isConfigured.value = false
        _isBootstrapped.value = false
        appContext = null
        config = null
    }
}
