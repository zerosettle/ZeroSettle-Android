package com.zerosettle.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZeroSettleVersionTest {
    @Test fun sdkVersion_isExposedAndNonEmpty() {
        assertThat(ZeroSettle.sdkVersion).isEqualTo(BuildConfig.ZEROSETTLE_SDK_VERSION)
        assertThat(ZeroSettle.sdkVersion).matches("""\d+\.\d+\.\d+""")
    }
}
