package com.zerosettle.sdk.billing

import com.zerosettle.sdk.core.Backend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches and in-memory caches the tenant's [UcbConfig] for the session.
 *
 * `refresh()` is called once at bootstrap. The cached value is exposed via
 * [config] as a [StateFlow] so consumers (e.g., `UcbBillingClient`) read it
 * synchronously without re-fetching on every purchase. If the backend
 * returns an error (e.g., endpoint not yet shipped), the cached value
 * stays at [UcbConfig.Disabled] — the SDK treats it as opt-out so the
 * standard Play Billing flow keeps working.
 */
internal class UcbConfigRepository(
    private val backend: Backend,
) {
    private val _config = MutableStateFlow(UcbConfig.Disabled)
    val config: StateFlow<UcbConfig> = _config.asStateFlow()

    private val refreshMutex = Mutex()

    @Volatile
    private var _hasFetchedOnce: Boolean = false

    /** Fetch the latest config from the backend. Idempotent + concurrency-safe. */
    suspend fun refresh(): Result<UcbConfig> = refreshMutex.withLock {
        val result = backend.fetchUcbConfig()
        result.onSuccess { _config.value = it }
        _hasFetchedOnce = true
        result
    }

    /** True once the first refresh() has run, regardless of outcome. Useful for tests + observability. */
    fun hasFetchedOnce(): Boolean = _hasFetchedOnce
}
