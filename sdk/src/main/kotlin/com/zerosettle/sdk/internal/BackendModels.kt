package com.zerosettle.sdk.internal

import com.zerosettle.sdk.model.Entitlement
import com.zerosettle.sdk.model.ZSProduct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- Request DTOs --

@Serializable
internal data class CreateCheckoutSessionRequest(
    @SerialName("product_id") val productId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("external_user_id") val externalUserId: String? = null,
    @SerialName("rc_app_user_id") val rcAppUserId: String? = null,
    val platform: String = "android",
)

@Serializable
internal data class CreatePaymentIntentRequest(
    @SerialName("product_id") val productId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("free_trial_days") val freeTrialDays: Int,
    val platform: String = "android",
)

@Serializable
internal data class MigrationConversionRequest(
    @SerialName("user_id") val userId: String,
)

@Serializable
internal data class CreateCustomerPortalSessionRequest(
    @SerialName("user_id") val userId: String,
)

@Serializable
internal data class SyncPlayStoreTransactionRequest(
    @SerialName("purchase_token") val purchaseToken: String,
    @SerialName("user_id") val userId: String,
)

// -- Response DTOs --

@Serializable
internal data class ProductsResponse(
    val products: List<ZSProduct>,
    val config: ConfigResponse? = null,
)

@Serializable
internal data class ConfigResponse(
    val checkout: CheckoutConfigResponse,
    val migration: MigrationPromptResponse? = null,
)

@Serializable
internal data class CheckoutConfigResponse(
    @SerialName("sheet_type") val sheetType: String,
    @SerialName("is_enabled") val isEnabled: Boolean,
    val jurisdictions: Map<String, JurisdictionConfigResponse>? = null,
)

@Serializable
internal data class JurisdictionConfigResponse(
    @SerialName("sheet_type") val sheetType: String,
    @SerialName("is_enabled") val isEnabled: Boolean,
)

@Serializable
internal data class MigrationPromptResponse(
    @SerialName("should_show") val shouldShow: Boolean,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("discount_percent") val discountPercent: Int? = null,
    val title: String? = null,
    val message: String? = null,
)

@Serializable
internal data class EntitlementsResponse(
    val entitlements: List<Entitlement>,
)

@Serializable
internal data class CheckoutSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("checkout_url") val checkoutUrl: String,
    @SerialName("transaction_id") val transactionId: String? = null,
)

@Serializable
internal data class PaymentIntentResponse(
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("transaction_id") val transactionId: String,
    val amount: Int,
    val currency: String,
    @SerialName("product_name") val productName: String,
    @SerialName("original_amount") val originalAmount: Int? = null,
    @SerialName("callback_url") val callbackUrl: String,
    @SerialName("publishable_key") val publishableKey: String,
    @SerialName("checkout_url") val checkoutUrl: String,
)

@Serializable
internal data class CustomerPortalSession(
    @SerialName("portal_url") val portalUrl: String,
)

// -- Cancel Flow --

@Serializable
internal data class CancelFlowResponsePayload(
    @SerialName("user_id") val userId: String,
    @SerialName("product_id") val productId: String,
    val outcome: String,
    @SerialName("offer_shown") val offerShown: Boolean,
    @SerialName("offer_accepted") val offerAccepted: Boolean,
    @SerialName("last_step_seen") val lastStepSeen: Int,
    val answers: List<CancelFlowAnswerPayload>,
)

@Serializable
internal data class CancelFlowAnswerPayload(
    @SerialName("question_id") val questionId: Int,
    @SerialName("selected_option_id") val selectedOptionId: Int? = null,
    @SerialName("free_text") val freeText: String? = null,
)
