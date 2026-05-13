package com.zerosettle.sdk.offers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnWebCheckoutSucceededOverloadTest {

    @Test
    fun `OfferManager exposes onWebCheckoutSucceeded(transactionId String?) overload`() {
        // Use Java reflection (no kotlin-reflect dep on the test classpath).
        // The suspend overload's JVM signature is `(String, Continuation)` while
        // the arg-less one is `(Continuation)` — so a method whose name matches
        // and whose parameter list contains a String discriminates the overload.
        val overload = OfferManager::class.java.methods.firstOrNull { method ->
            method.name == "onWebCheckoutSucceeded" &&
                method.parameterTypes.any { it == String::class.java }
        }
        assertThat(overload).isNotNull()
    }
}
