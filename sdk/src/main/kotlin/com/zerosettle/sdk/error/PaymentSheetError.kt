package com.zerosettle.sdk.error

/**
 * Errors specific to the payment sheet UI.
 * Maps to iOS `PaymentSheetError`.
 */
internal sealed class PaymentSheetError : Exception() {
    data object Cancelled : PaymentSheetError() {
        override val message: String get() = "Payment was cancelled"
    }

    data object NotConfigured : PaymentSheetError() {
        override val message: String get() = "ZeroSettle is not configured"
    }

    data class PaymentFailed(val detail: PaymentFailureDetail) : PaymentSheetError() {
        override val message: String get() = detail.message
    }

    data class VerificationFailed(val detail: String) : PaymentSheetError() {
        override val message: String get() = "Verification failed: $detail"
    }

    data class PreloadFailed(val detail: APIErrorDetail) : PaymentSheetError() {
        override val message: String get() = detail.message
    }

    data object UserIdRequired : PaymentSheetError() {
        override val message: String
            get() = "A userId is required for subscriptions and non-consumable products."
    }
}
