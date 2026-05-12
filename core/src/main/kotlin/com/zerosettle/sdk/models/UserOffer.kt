package com.zerosettle.sdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical eligibility decision returned by `GET /v1/iap/user-offer/`.
 *
 * Per chunk 3, the backend extends `user-offer` (NOT a parallel `migration-offer`)
 * with a `source` field discriminating StoreKit vs Play migration source.
 */
public object UserOffer {

    @Serializable
    public data class Response(
        @SerialName("is_eligible") val isEligible: Boolean,
        val source: Offer.SourceStorefront? = null,
        val offer: Offer.OfferData? = null,
        @SerialName("disclosure_text") val disclosureText: String? = null,
    )
}
