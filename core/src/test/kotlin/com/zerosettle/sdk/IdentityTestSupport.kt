package com.zerosettle.sdk

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** Routes MockWebServer responses by URL path prefix; unmatched paths 404. */
internal fun routeBy(routes: Map<String, (RecordedRequest) -> MockResponse>): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        return routes.entries.firstOrNull { (prefix, _) -> path.startsWith(prefix) }?.value?.invoke(request)
            ?: MockResponse().setResponseCode(404).setBody("unmocked path: $path")
    }
}

/** Bootstrap routes: empty catalog and entitlements, UCB config 404. */
internal fun bootstrapRoutes(): Map<String, (RecordedRequest) -> MockResponse> = mapOf(
    "/v1/iap/products/" to { _ -> MockResponse().setBody("""{"products":[]}""") },
    "/v1/iap/entitlements/" to { _ -> MockResponse().setBody("""{"entitlements":[]}""") },
)

internal class RecordingLogger : com.zerosettle.sdk.core.ZeroSettleLogger {
    val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun verbose(tag: String, message: String, throwable: Throwable?) {}
    override fun debug(tag: String, message: String, throwable: Throwable?) {}
    override fun info(tag: String, message: String, throwable: Throwable?) {}
    override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
    override fun error(tag: String, message: String, throwable: Throwable?) {}
}
