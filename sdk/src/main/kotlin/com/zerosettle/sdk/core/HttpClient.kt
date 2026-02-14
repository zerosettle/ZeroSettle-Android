package com.zerosettle.sdk.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp wrapper with suspend GET/POST and kotlinx.serialization JSON decoding.
 * Maps to iOS `HTTPClient`.
 */
internal class HttpClient(
    private val json: Json = defaultJson,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Constants.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(Constants.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    /**
     * Perform a GET request and decode the response.
     */
    suspend fun <T> get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        deserializer: KSerializer<T>,
    ): T {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        return execute(request, deserializer)
    }

    /**
     * Perform a POST request with a JSON body and decode the response.
     */
    suspend fun <T> post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        deserializer: KSerializer<T>,
    ): T {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        return execute(request, deserializer)
    }

    /**
     * Perform a POST request with a JSON body, expecting no meaningful response body.
     */
    suspend fun postVoid(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        executeVoid(request)
    }

    /**
     * Execute a request and decode the response body.
     */
    private suspend fun <T> execute(
        request: Request,
        deserializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val response = executeRaw(request)
        val responseBody = response.body?.string()
            ?: throw HttpError.InvalidResponse

        val statusCode = response.code
        if (statusCode !in 200..299) {
            logApiError(statusCode, request.url.toString(), responseBody)
            throw HttpError.HttpErrorResponse(statusCode, responseBody.toByteArray())
        }

        try {
            json.decodeFromString(deserializer, responseBody)
        } catch (e: Exception) {
            ZSLogger.error("Decoding failed: $e", ZSLogger.Category.NETWORK)
            throw HttpError.DecodingFailed(e)
        }
    }

    /**
     * Execute a request expecting no meaningful response body (e.g. 204).
     */
    private suspend fun executeVoid(request: Request) = withContext(Dispatchers.IO) {
        val response = executeRaw(request)
        val statusCode = response.code

        if (statusCode !in 200..299) {
            val responseBody = response.body?.string() ?: ""
            logApiError(statusCode, request.url.toString(), responseBody)
            throw HttpError.HttpErrorResponse(statusCode, responseBody.toByteArray())
        }
    }

    /**
     * Execute a raw OkHttp request with cancellation support.
     */
    private suspend fun executeRaw(request: Request): Response {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(HttpError.NetworkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }
    }

    /**
     * Parse and log API error responses with debug info for developers.
     */
    private fun logApiError(statusCode: Int, url: String, body: String) {
        try {
            val jsonObj = json.parseToJsonElement(body)
            val map = jsonObj as? kotlinx.serialization.json.JsonObject ?: run {
                ZSLogger.error("[ZeroSettle] HTTP $statusCode from $url", ZSLogger.Category.NETWORK)
                return
            }

            val errorMessage = map["error"]?.toString()?.trim('"') ?: "Unknown error"
            val errorCode = map["code"]?.toString()?.trim('"') ?: "unknown"

            ZSLogger.error(
                "[ZeroSettle] API Error: $errorMessage (code: $errorCode, status: $statusCode)",
                ZSLogger.Category.NETWORK
            )

            val debug = map["debug"] as? kotlinx.serialization.json.JsonObject ?: return
            ZSLogger.error("[ZeroSettle] Debug Info:", ZSLogger.Category.NETWORK)

            debug["reason"]?.let {
                ZSLogger.error("  → Reason: ${it.toString().trim('"')}", ZSLogger.Category.NETWORK)
            }
            debug["action"]?.let {
                ZSLogger.error("  → Action: ${it.toString().trim('"')}", ZSLogger.Category.NETWORK)
            }
            debug["docs"]?.let {
                ZSLogger.error("  → Docs: ${it.toString().trim('"')}", ZSLogger.Category.NETWORK)
            }
            debug["stripe_error"]?.let {
                ZSLogger.error("  → Stripe Error: ${it.toString().trim('"')}", ZSLogger.Category.NETWORK)
            }
            debug["stripe_error_code"]?.let {
                ZSLogger.error("  → Stripe Code: ${it.toString().trim('"')}", ZSLogger.Category.NETWORK)
            }
        } catch (_: Exception) {
            ZSLogger.error("[ZeroSettle] HTTP $statusCode from $url", ZSLogger.Category.NETWORK)
        }
    }
}
