package com.zerosettle.sdk.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * Caches PaymentIntent results (checkout URL + transaction ID) so re-opens
 * skip the network call entirely. Thread-safe via ConcurrentHashMap.
 * Maps to iOS `CheckoutCache`.
 */
internal object CheckoutCache {
    private data class Entry(
        val checkoutUrl: String,
        val transactionId: String,
        val timestamp: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 5 * 60 * 1000L // 5 minutes

    fun get(productId: String, userId: String?): Pair<String, String>? {
        val key = "$productId:${userId ?: ""}"
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.checkoutUrl to entry.transactionId
    }

    fun set(productId: String, userId: String?, checkoutUrl: String, transactionId: String) {
        val key = "$productId:${userId ?: ""}"
        entries[key] = Entry(checkoutUrl, transactionId, System.currentTimeMillis())
    }

    fun invalidate(productId: String, userId: String?) {
        entries.remove("$productId:${userId ?: ""}")
    }
}
