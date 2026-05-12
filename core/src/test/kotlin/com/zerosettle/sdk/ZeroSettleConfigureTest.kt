package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleConfigureTest {

    @After fun tearDown() { ZeroSettle.resetForTesting() }

    @Test fun configure_setsIsConfigured() {
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(publishableKey = "zs_pk_test_abc"),
        )
        assertThat(ZeroSettle.isConfigured.value).isTrue()
    }

    @Test fun beforeConfigure_isConfiguredFalse() {
        assertThat(ZeroSettle.isConfigured.value).isFalse()
    }

    @Test fun identifyWithoutConfigure_returnsFailureNotConfigured() = runTest {
        val result = ZeroSettle.identify(Identity.User(id = "u1"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ZeroSettleError.NotConfigured::class.java)
    }

    @Test fun identifyAfterConfigure_succeeds() = runTest {
        ZeroSettle.configure(
            ApplicationProvider.getApplicationContext(),
            ZeroSettleConfig(publishableKey = "zs_pk_test_abc"),
        )
        val result = ZeroSettle.identify(Identity.Deferred)
        assertThat(result.isSuccess).isTrue()
    }
}
