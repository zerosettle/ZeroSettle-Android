package com.zerosettle.sdk.models

/**
 * Sealed exception hierarchy. Mirrors iOS `ZeroSettleError` (case-specific so callers
 * pattern-match recovery rather than parsing error strings).
 *
 * Public SDK methods return `kotlin.Result<T>` — internal code may `throw` these,
 * but the boundary always wraps with `Result.failure(...)`.
 */
public sealed class ZeroSettleError : Exception() {
    public data object NotConfigured : ZeroSettleError() {
        private fun readResolve(): Any = NotConfigured
        override val message: String = "ZeroSettle.configure(...) was not called."
    }
    public data object UserNotIdentified : ZeroSettleError() {
        private fun readResolve(): Any = UserNotIdentified
        override val message: String = "ZeroSettle.identify(...) must be called before this API."
    }
    public data object UserIdRequired : ZeroSettleError() {
        private fun readResolve(): Any = UserIdRequired
        override val message: String = "Identity.User.id is required for this operation."
    }
    public data class InvalidUserId(val reason: String) : ZeroSettleError() {
        override val message: String get() = "Invalid userId: $reason"
    }
    public data class CheckoutFailed(val reason: String) : ZeroSettleError() {
        override val message: String get() = "Checkout failed: $reason"
    }
    public data object PurchaseCancelled : ZeroSettleError() {
        private fun readResolve(): Any = PurchaseCancelled
        override val message: String = "Purchase cancelled by user."
    }
    /**
     * The caller invoked `purchase()` while a previous `purchase()` call was
     * still awaiting its deep-link return. The high-level API serializes one
     * checkout at a time — fail-fast rather than queue. The first call will
     * still resolve normally when its callback arrives.
     */
    public data object CheckoutInFlight : ZeroSettleError() {
        private fun readResolve(): Any = CheckoutInFlight
        override val message: String = "A web checkout is already in progress."
    }
    public data object PurchasePending : ZeroSettleError() {
        private fun readResolve(): Any = PurchasePending
        override val message: String = "Purchase is pending Google's review (parental approval, etc)."
    }
    public data class PlayBillingError(
        val responseCode: Int,
        val debugMessage: String,
    ) : ZeroSettleError() {
        override val message: String get() = "Play Billing error $responseCode: $debugMessage"
    }
    public data class BackendError(
        val statusCode: Int,
        val body: String,
    ) : ZeroSettleError() {
        override val message: String get() = "Backend error HTTP $statusCode"
    }
    public data class NetworkError(override val cause: Throwable) : ZeroSettleError() {
        override val message: String get() = "Network error: ${cause.message ?: cause::class.simpleName}"
    }
    public data object PlayApiUnreachable : ZeroSettleError() {
        private fun readResolve(): Any = PlayApiUnreachable
        override val message: String = "Play Developer API is temporarily unreachable. Try again shortly."
    }
    public data class ProductNotFound(val productId: String) : ZeroSettleError() {
        override val message: String get() = "Product not found: $productId"
    }
    public data object NotBootstrapped : ZeroSettleError() {
        private fun readResolve(): Any = NotBootstrapped
        override val message: String = "SDK not yet bootstrapped — call identify(...) first."
    }
    public data class NoActiveSubscription(val productId: String) : ZeroSettleError() {
        override val message: String get() = "No active subscription for product $productId."
    }
    public data class AlreadyMigrated(val productId: String) : ZeroSettleError() {
        override val message: String get() = "Product $productId is already migrated to web checkout."
    }
    public data object OfferIneligible : ZeroSettleError() {
        private fun readResolve(): Any = OfferIneligible
        override val message: String = "User is not currently eligible for any offer."
    }
    public data object MerchantNotOnboarded : ZeroSettleError() {
        private fun readResolve(): Any = MerchantNotOnboarded
        override val message: String = "This merchant has not completed Stripe onboarding."
    }
    public data class JurisdictionBlocked(val country: String) : ZeroSettleError() {
        override val message: String get() = "Web checkout unavailable in jurisdiction: $country"
    }
}
