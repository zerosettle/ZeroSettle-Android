package com.zerosettle.sdk.error

/**
 * Classifies checkout failures into actionable categories.
 * Maps to iOS `CheckoutFailure` enum.
 */
sealed class CheckoutFailure {
    /** The requested product was not found on the server. */
    data object ProductNotFound : CheckoutFailure()

    /** The merchant has not completed Stripe onboarding. */
    data object MerchantNotOnboarded : CheckoutFailure()

    /** Stripe returned an error (e.g., card declined, insufficient funds). */
    data class StripeError(val code: String?, val message: String) : CheckoutFailure()

    /** The server returned a non-2xx response. */
    data class ServerError(val statusCode: Int, val message: String?) : CheckoutFailure()

    /** The device appears to have no network connectivity. */
    data object NetworkUnavailable : CheckoutFailure()

    /** An unclassified error occurred. */
    data class Other(val message: String) : CheckoutFailure()
}
