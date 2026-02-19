package com.zerosettle.sdk.internal

import com.zerosettle.sdk.core.HttpClient
import com.zerosettle.sdk.core.HttpError
import com.zerosettle.sdk.core.ZSLogger
import com.zerosettle.sdk.error.APIErrorDetail
import com.zerosettle.sdk.error.PaymentSheetError
import com.zerosettle.sdk.error.ZSError
import com.zerosettle.sdk.model.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Internal API client for ZeroSettle IAP endpoints.
 * Maps to iOS `Backend`.
 */
internal class Backend(
    private val baseUrl: String,
    private val publishableKey: String,
) {
    private val httpClient = HttpClient()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val authHeaders: Map<String, String>
        get() = mapOf("X-ZeroSettle-Key" to publishableKey)

    // -- Products --

    suspend fun fetchProducts(userId: String? = null): ProductCatalog {
        val urlBuilder = StringBuilder(apiUrl("iap/products/"))
        if (userId != null) {
            urlBuilder.append("?user_id=$userId")
        }

        val response: ProductsResponse = try {
            httpClient.get(
                url = urlBuilder.toString(),
                headers = authHeaders,
                deserializer = ProductsResponse.serializer(),
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }

        val remoteConfig = response.config?.let { parseRemoteConfig(it) }
        return ProductCatalog(products = response.products, config = remoteConfig)
    }

    // -- Checkout Sessions --

    suspend fun createCheckoutSession(
        productId: String,
        userId: String? = null,
        externalUserId: String? = null,
        rcAppUserId: String? = null,
    ): CheckoutSession {
        val body = json.encodeToString(
            CreateCheckoutSessionRequest.serializer(),
            CreateCheckoutSessionRequest(productId, userId, externalUserId, rcAppUserId)
        )
        return httpClient.post(
            url = apiUrl("iap/checkout-sessions/"),
            body = body,
            headers = authHeaders,
            deserializer = CheckoutSession.serializer(),
        )
    }

    // -- Payment Intents --

    suspend fun createPaymentIntent(
        productId: String,
        userId: String? = null,
        freeTrialDays: Int,
    ): PaymentIntentResponse {
        val body = json.encodeToString(
            CreatePaymentIntentRequest.serializer(),
            CreatePaymentIntentRequest(productId, userId, freeTrialDays)
        )
        return try {
            httpClient.post(
                url = apiUrl("iap/payment-intents/"),
                body = body,
                headers = authHeaders,
                deserializer = PaymentIntentResponse.serializer(),
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    // -- Transactions --

    suspend fun getTransaction(transactionId: String): ZSTransaction {
        return try {
            httpClient.get(
                url = apiUrl("iap/transactions/$transactionId/"),
                headers = authHeaders,
                deserializer = ZSTransaction.serializer(),
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    // -- Transaction Verification --

    suspend fun verifyTransaction(
        transactionId: String,
        maxAttempts: Int = 6,
        pollIntervalMs: Long = 2_000,
    ): ZSTransaction {
        // Initial delay for webhook processing
        delay(1_500)

        var lastTransaction: ZSTransaction? = null
        for (attempt in 1..maxAttempts) {
            val transaction = getTransaction(transactionId)
            lastTransaction = transaction

            when (transaction.status) {
                ZSTransaction.Status.COMPLETED -> return transaction
                ZSTransaction.Status.PROCESSING -> {
                    if (attempt < maxAttempts) {
                        delay(pollIntervalMs)
                        continue
                    }
                    // Final attempt still processing — don't treat as completed
                    throw PaymentSheetError.VerificationFailed(
                        "Transaction still processing after $maxAttempts attempts"
                    )
                }
                else -> {
                    // pending/failed — user didn't complete payment
                    throw PaymentSheetError.Cancelled
                }
            }
        }
        throw PaymentSheetError.VerificationFailed("Verification timed out")
    }

    // -- Entitlements --

    suspend fun getEntitlements(userId: String): List<Entitlement> {
        val url = apiUrl("iap/entitlements/") + "?user_id=$userId"
        val response: EntitlementsResponse = try {
            httpClient.get(
                url = url,
                headers = authHeaders,
                deserializer = EntitlementsResponse.serializer(),
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
        return response.entitlements
    }

    // -- Migration Tracking --

    suspend fun trackMigrationConversion(userId: String) {
        val body = json.encodeToString(
            MigrationConversionRequest.serializer(),
            MigrationConversionRequest(userId)
        )
        try {
            httpClient.postVoid(
                url = apiUrl("iap/migration-converted/"),
                body = body,
                headers = authHeaders,
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    // -- Customer Portal --

    suspend fun createCustomerPortalSession(userId: String): CustomerPortalSession {
        val body = json.encodeToString(
            CreateCustomerPortalSessionRequest.serializer(),
            CreateCustomerPortalSessionRequest(userId)
        )
        return try {
            httpClient.post(
                url = apiUrl("iap/customer-portal-sessions/"),
                body = body,
                headers = authHeaders,
                deserializer = CustomerPortalSession.serializer(),
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    // -- Play Store Transaction Sync --

    suspend fun syncPlayStoreTransaction(purchaseToken: String, userId: String) {
        val body = json.encodeToString(
            SyncPlayStoreTransactionRequest.serializer(),
            SyncPlayStoreTransactionRequest(purchaseToken, userId)
        )
        try {
            httpClient.postVoid(
                url = apiUrl("iap/play-store-transactions/"),
                body = body,
                headers = authHeaders,
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    // -- Cancel Flow --

    suspend fun fetchCancelFlow(): CancelFlowConfig {
        return try {
            httpClient.get(
                url = apiUrl("iap/cancel-flow/"),
                headers = authHeaders,
                deserializer = CancelFlowConfig.serializer(),
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    suspend fun submitCancelFlowResponse(payload: CancelFlowResponsePayload) {
        val body = json.encodeToString(
            CancelFlowResponsePayload.serializer(),
            payload
        )
        try {
            httpClient.postVoid(
                url = apiUrl("iap/cancel-flow/respond/"),
                body = body,
                headers = authHeaders,
            )
        } catch (e: Exception) {
            throw wrapError(e)
        }
    }

    // -- Error Wrapping --

    companion object {
        fun wrapError(error: Throwable): ZSError {
            if (error is ZSError) return error
            val detail = parseAPIErrorDetail(error)
            return ZSError.ApiError(detail)
        }

        private fun parseAPIErrorDetail(error: Throwable): APIErrorDetail {
            if (error !is HttpError) {
                return APIErrorDetail(
                    statusCode = null,
                    serverMessage = null,
                    serverCode = null,
                    underlyingError = error,
                )
            }

            return when (error) {
                is HttpError.HttpErrorResponse -> {
                    var serverMessage: String? = null
                    var serverCode: String? = null

                    if (error.body != null) {
                        try {
                            val jsonStr = String(error.body)
                            val jsonObj = Json.parseToJsonElement(jsonStr)
                            if (jsonObj is JsonObject) {
                                serverMessage = jsonObj["error"]?.jsonPrimitive?.content
                                    ?: jsonObj["message"]?.jsonPrimitive?.content
                                    ?: jsonObj["detail"]?.jsonPrimitive?.content
                                serverCode = jsonObj["code"]?.jsonPrimitive?.content
                            }
                        } catch (_: Exception) {
                            // Body not JSON — try to extract readable text from HTML
                            error.bodyString?.let { bodyText ->
                                val titleMatch = Regex("<title>(.*?)</title>").find(bodyText)
                                if (titleMatch != null) {
                                    serverMessage = titleMatch.groupValues[1]
                                }
                            }
                        }
                    }

                    APIErrorDetail(
                        statusCode = error.statusCode,
                        serverMessage = serverMessage,
                        serverCode = serverCode,
                        underlyingError = error,
                    )
                }

                is HttpError.NetworkError -> APIErrorDetail(
                    statusCode = null,
                    serverMessage = null,
                    serverCode = null,
                    underlyingError = error,
                )

                else -> APIErrorDetail(
                    statusCode = null,
                    serverMessage = null,
                    serverCode = null,
                    underlyingError = error,
                )
            }
        }
    }

    // -- Remote Config Parsing --

    private fun parseRemoteConfig(configResponse: ConfigResponse): RemoteConfig {
        val checkoutType = CheckoutType.fromWireValue(configResponse.checkout.sheetType)
            ?: CheckoutType.EXTERNAL_BROWSER

        val jurisdictions = mutableMapOf<Jurisdiction, JurisdictionCheckoutConfig>()
        configResponse.checkout.jurisdictions?.forEach { (key, value) ->
            val jurisdiction = try {
                Jurisdiction.valueOf(key.uppercase())
            } catch (_: Exception) {
                null
            }
            val sheetType = CheckoutType.fromWireValue(value.sheetType)

            if (jurisdiction != null && sheetType != null) {
                jurisdictions[jurisdiction] = JurisdictionCheckoutConfig(
                    sheetType = sheetType,
                    isEnabled = value.isEnabled,
                )
            }
        }

        val checkoutConfig = CheckoutConfig(
            sheetType = checkoutType,
            isEnabled = configResponse.checkout.isEnabled,
            jurisdictions = jurisdictions,
        )

        val migration = configResponse.migration?.let { migrationResponse ->
            if (migrationResponse.shouldShow &&
                migrationResponse.productId != null &&
                migrationResponse.discountPercent != null &&
                migrationResponse.title != null &&
                migrationResponse.message != null
            ) {
                MigrationPrompt(
                    productId = migrationResponse.productId,
                    discountPercent = migrationResponse.discountPercent,
                    title = migrationResponse.title,
                    message = migrationResponse.message,
                    ctaText = migrationResponse.ctaText ?: "Save ${migrationResponse.discountPercent}% Forever",
                )
            } else {
                null
            }
        }

        return RemoteConfig(checkout = checkoutConfig, migration = migration)
    }

    // -- Helpers --

    private fun apiUrl(path: String): String = "$baseUrl/$path"
}
