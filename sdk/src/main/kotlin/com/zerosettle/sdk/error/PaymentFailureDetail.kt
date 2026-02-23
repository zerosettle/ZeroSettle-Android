package com.zerosettle.sdk.error

/**
 * Structured detail for payment failures within the payment sheet.
 * Maps to iOS `PaymentFailureDetail`.
 */
internal data class PaymentFailureDetail(
    val kind: Kind,
    val message: String,
) {
    /**
     * The category of payment failure.
     */
    enum class Kind {
        /** The card was declined by the payment processor. */
        CARD_DECLINED,

        /** A network error prevented the payment from completing. */
        NETWORK_ERROR,

        /** The server returned a non-2xx response. */
        SERVER_ERROR,

        /** An error occurred during the checkout flow. */
        CHECKOUT_ERROR,

        /** An unclassified failure. */
        UNKNOWN,
    }
}
