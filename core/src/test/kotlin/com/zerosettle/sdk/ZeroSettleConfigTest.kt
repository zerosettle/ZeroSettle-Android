package com.zerosettle.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZeroSettleConfigTest {
    @Test fun livePrefix_isSandboxFalse() {
        val cfg = ZeroSettleConfig(publishableKey = "zs_pk_live_abc")
        assertThat(cfg.isSandbox).isFalse()
    }

    @Test fun testPrefix_isSandboxTrue() {
        val cfg = ZeroSettleConfig(publishableKey = "zs_pk_test_abc")
        assertThat(cfg.isSandbox).isTrue()
    }

    @Test fun otherPrefix_throws() {
        try {
            ZeroSettleConfig(publishableKey = "junk_abc")
            error("expected throw")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("zs_pk_live_")
        }
    }

    @Test fun defaults() {
        val cfg = ZeroSettleConfig(publishableKey = "zs_pk_test_abc")
        assertThat(cfg.playLicenseKey).isNull()
        assertThat(cfg.syncPlayPurchases).isTrue()
        assertThat(cfg.preloadCheckout).isFalse()
        assertThat(cfg.strictAck).isFalse()
        assertThat(cfg.baseUrlOverride).isNull()
    }
}
