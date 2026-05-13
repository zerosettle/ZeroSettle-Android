package com.zerosettle.sdk

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

class CurrentUserIdExposureTest {

    @Test
    fun `currentUserId is a public StateFlow on ZeroSettle`() {
        val flow: StateFlow<String?> = ZeroSettle.currentUserId
        assertThat(flow.value).isNull()  // pre-identify state
    }
}
