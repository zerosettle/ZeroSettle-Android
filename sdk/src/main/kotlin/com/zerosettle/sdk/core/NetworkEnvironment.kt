package com.zerosettle.sdk.core

/**
 * Network environment configuration (production/development).
 * Maps to iOS `NetworkEnvironment`.
 */
internal enum class NetworkEnvironment(val backendUrl: String) {
    PRODUCTION("https://api.zerosettle.io/v1"),
    DEVELOPMENT("https://api.zerosettle.io/v1"),
}
