package com.zerosettle.sdk.model

/**
 * The result of fetching the product catalog from the ZeroSettle backend.
 */
data class ProductCatalog(
    val products: List<ZSProduct>,
    val config: RemoteConfig?,
)
