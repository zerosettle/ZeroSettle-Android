package com.zerosettle.sdk.error

import com.zerosettle.sdk.model.Entitlement
import com.zerosettle.sdk.model.Jurisdiction

/**
 * Unified error type for the ZeroSettle IAP SDK.
 * Maps to iOS `ZSError` enum.
 */
sealed class ZSError : Exception() {

    /** The SDK has not been configured. Call `ZeroSettle.configure()` first. */
    data object NotConfigured : ZSError() {
        override val message: String get() = "ZeroSettle is not configured. Call configure() first."
    }

    /** The publishable key format is invalid. */
    data object InvalidPublishableKey : ZSError() {
        override val message: String get() = "Invalid publishable key. Check your ZeroSettle dashboard."
    }

    /** No product found with the given identifier. */
    data class ProductNotFound(val productId: String) : ZSError() {
        override val message: String get() = "Product not found: $productId"
    }

    /** The checkout flow failed for a specific reason. */
    data class CheckoutFailed(val reason: CheckoutFailure) : ZSError() {
        override val message: String
            get() = when (reason) {
                is CheckoutFailure.ProductNotFound ->
                    "Checkout failed: product not found."
                is CheckoutFailure.MerchantNotOnboarded ->
                    "Checkout failed: merchant has not completed payment setup."
                is CheckoutFailure.StripeError ->
                    "Payment error: ${reason.message}"
                is CheckoutFailure.ServerError ->
                    "Checkout failed: server error (${reason.statusCode})${reason.message?.let { " — $it" } ?: ""}"
                is CheckoutFailure.NetworkUnavailable ->
                    "Checkout failed: no network connection."
                is CheckoutFailure.Other ->
                    "Checkout failed: ${reason.message}"
            }
    }

    /** Transaction verification failed after checkout. */
    data class TransactionVerificationFailed(val detail: String) : ZSError() {
        override val message: String get() = "Transaction verification failed: $detail"
    }

    /** An API or network error occurred. */
    data class ApiError(val detail: APIErrorDetail) : ZSError() {
        override val message: String get() = detail.message ?: "Unknown API error"
    }

    /** The checkout callback URL could not be parsed. */
    data object InvalidCallbackUrl : ZSError() {
        override val message: String get() = "Invalid checkout callback URL."
    }

    /** Web checkout is disabled for the user's jurisdiction. */
    data class WebCheckoutDisabledForJurisdiction(val jurisdiction: Jurisdiction) : ZSError() {
        override val message: String
            get() = "Web checkout is disabled for the ${jurisdiction.name} jurisdiction. Use Play Store instead."
    }

    /** A userId is required for this product type (subscriptions, non-consumables). */
    data class UserIdRequired(val productId: String) : ZSError() {
        override val message: String
            get() = "A userId is required to purchase $productId. Subscriptions and non-consumable products require a user identity for entitlement tracking."
    }

    /** Entitlement restoration partially failed. Check partialEntitlements for what was recovered. */
    data class RestoreEntitlementsFailed(
        val partialEntitlements: List<Entitlement>,
        val underlyingError: Throwable,
    ) : ZSError() {
        override val message: String
            get() = "Failed to restore entitlements: ${underlyingError.message}"
    }

    /** The user cancelled the purchase. */
    data object Cancelled : ZSError() {
        override val message: String get() = "Purchase was cancelled."
    }

    /** The purchase is pending approval (e.g., parental controls). */
    data object PurchasePending : ZSError() {
        override val message: String get() = "Purchase is pending approval."
    }

    /** A Play Store transaction failed verification. */
    data class PlayStoreVerificationFailed(val underlyingError: Throwable) : ZSError() {
        override val message: String
            get() = "Play Store verification failed: ${underlyingError.message}"
    }
}
