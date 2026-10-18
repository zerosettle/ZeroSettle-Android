package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZeroSettleFeatureCheckWiringTest {
    @After fun tearDown() { ZeroSettle.resetForTesting() }

    private fun configure(server: MockWebServer, key: String) = ZeroSettle.configure(
        ApplicationProvider.getApplicationContext(),
        ZeroSettleConfig(publishableKey = key, baseUrlOverride = server.url("/").toString().trimEnd('/')),
    )

    @Test fun checkedKeyIsReportedWithTheConfiguredKeyEvenAfterLogout() {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(204)); server.start()
        configure(server, "zs_pk_test_A")
        ZeroSettle.hasAccess("pro")
        ZeroSettle.logout()
        val req = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertThat(req.path).isEqualTo("/v1/iap/features/checked/")
        assertThat(req.getHeader("X-ZeroSettle-Key")).isEqualTo("zs_pk_test_A")
        assertThat(req.body.readUtf8()).isEqualTo("""{"keys":["pro"]}""")
        assertThat(server.requestCount).isEqualTo(1)
        server.shutdown()
    }

    @Test fun reconfigureWithAnotherKeyReportsAgain() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204)); server.start()
        configure(server, "zs_pk_test_A")
        ZeroSettle.hasAccess("pro")
        assertThat(server.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("X-ZeroSettle-Key")).isEqualTo("zs_pk_test_A")
        configure(server, "zs_pk_test_B")
        ZeroSettle.hasAccess("pro")
        assertThat(server.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("X-ZeroSettle-Key")).isEqualTo("zs_pk_test_B")
        server.shutdown()
    }
}
