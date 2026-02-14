package com.zerosettle.sdk.core

/**
 * Sealed class representing HTTP errors from the networking layer.
 * Maps to iOS `HTTPError` enum.
 */
internal sealed class HttpError : Exception() {

    /** The URL was malformed or could not be constructed. */
    data class InvalidUrl(val url: String) : HttpError() {
        override val message: String get() = "Invalid URL: $url"
    }

    /** The server returned an unexpected response format. */
    data object InvalidResponse : HttpError() {
        override val message: String get() = "Invalid HTTP response"
    }

    /** The server returned a non-2xx status code. */
    data class HttpErrorResponse(val statusCode: Int, val body: ByteArray?) : HttpError() {
        override val message: String get() = "HTTP error: $statusCode"

        /** Decode body as UTF-8 string (for logging). Returns null if empty or not decodable. */
        val bodyString: String?
            get() = body?.let {
                try { String(it, Charsets.UTF_8).take(500) } catch (_: Exception) { null }
            }

        override fun toString(): String {
            val preview = bodyString?.take(200)?.let { " body=\"$it\"" } ?: ""
            return "HttpErrorResponse(statusCode=$statusCode$preview)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HttpErrorResponse) return false
            return statusCode == other.statusCode && body.contentEquals(other.body)
        }

        override fun hashCode(): Int = 31 * statusCode + (body?.contentHashCode() ?: 0)
    }

    /** JSON decoding failed. */
    data class DecodingFailed(override val cause: Throwable) : HttpError() {
        override val message: String get() = "Decoding failed: ${cause.message}"
    }

    /** A network-level error occurred (no connectivity, timeout, etc.). */
    data class NetworkError(override val cause: Throwable) : HttpError() {
        override val message: String get() = "Network error: ${cause.message}"
    }
}
