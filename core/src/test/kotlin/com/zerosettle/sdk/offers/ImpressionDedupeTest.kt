package com.zerosettle.sdk.offers
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class ImpressionDedupeTest {
    @Test fun reportsOncePerKey() {
        val d = ImpressionDedupe()
        assertThat(d.shouldReport("s1:-1")).isTrue()
        assertThat(d.shouldReport("s1:-1")).isFalse()
        assertThat(d.shouldReport("s1:7")).isTrue()
    }
}
