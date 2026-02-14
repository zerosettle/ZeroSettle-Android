package com.zerosettle.sdk.error

/**
 * Structured detail for API/HTTP errors at the product boundary.
 * Maps to iOS `APIErrorDetail`.
 */
data class APIErrorDetail(
    val statusCode: Int?,
    val serverMessage: String?,
    val serverCode: String?,
    val underlyingError: Throwable?,
) {
    val message: String
        get() {
            if (serverMessage != null) return serverMessage
            if (statusCode != null) return "Server error ($statusCode)"
            return underlyingError?.message ?: "Unknown API error"
        }
}
