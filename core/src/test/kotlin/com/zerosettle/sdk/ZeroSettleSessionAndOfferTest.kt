package com.zerosettle.sdk

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.offers.ResolvedOffer
import org.junit.Test

class ZeroSettleSessionAndOfferTest {
    @Test fun sessionIdStableNonBlank() {
        assertThat(ZeroSettle.sessionId).isNotEmpty()
        assertThat(ZeroSettle.sessionId).isEqualTo(ZeroSettle.sessionId)
    }

    @Test fun currentOfferRoundTrips() {
        ZeroSettle.setCurrentOffer(ResolvedOffer("p", 7, "migration"))
        assertThat(ZeroSettle.currentOffer.value?.productId).isEqualTo("p")
        ZeroSettle.setCurrentOffer(null)
        assertThat(ZeroSettle.currentOffer.value).isNull()
    }
}
