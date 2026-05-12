package com.zerosettle.sdk.core

import com.zerosettle.sdk.models.ZeroSettleError
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin OkHttp wrapper for backend `/v1/iap/` calls.
 *
 * Responsibilities:
 *  - Inject `X-ZeroSettle-Key`, `X-ZS-SDK-Version`, `X-ZS-SDK-Platform` headers.
 *  - 1 retry on 5xx / IO error with 200ms backoff (Play / web hiccups during launch).
 *  - Map 2xx → `Result.success(body)`; 4xx/5xx → `Result.failure(BackendError)`; IO → `NetworkError`.
 *
 * Callers (the [Backend] wrapper in Task 17) handle JSON (de)serialization.
 */
internal class HttpClient(
    private val baseUrl: String,
    private val publishableKey: String,
    private val sdkVersion: String,
    private val ok: OkHttpClient = OkHttpClient.Builder().build(),
) {

    private val jsonMt = "application/json; charset=utf-8".toMediaType()

    suspend fun get(path: String): Result<String> = doRequest(buildGet(path))

    suspend fun post(path: String, body: String): Result<String> =
        doRequest(buildPost(path, body))

    private fun buildGet(path: String): Request = Request.Builder()
        .url(baseUrl + path)
        .get()
        .headers()
        .build()

    private fun buildPost(path: String, body: String): Request = Request.Builder()
        .url(baseUrl + path)
        .post(body.toRequestBody(jsonMt))
        .headers()
        .build()

    private fun Request.Builder.headers(): Request.Builder = apply {
        addHeader("X-ZeroSettle-Key", publishableKey)
        addHeader("X-ZS-SDK-Version", sdkVersion)
        addHeader("X-ZS-SDK-Platform", "android")
        addHeader("Accept", "application/json")
    }

    private suspend fun doRequest(req: Request): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                ok.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return@withContext Result.success(body)
                    if (resp.code in 500..599 && attempt == 0) {
                        delay(200)
                        return@repeat
                    }
                    return@withContext Result.failure(
                        ZeroSettleError.BackendError(statusCode = resp.code, body = body),
                    )
                }
            } catch (io: IOException) {
                lastError = io
                if (attempt == 0) { delay(200); return@repeat }
            }
        }
        Result.failure(ZeroSettleError.NetworkError(lastError ?: RuntimeException("unknown")))
    }
}
