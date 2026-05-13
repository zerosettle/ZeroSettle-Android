package com.zerosettle.sdk

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ZeroSettleError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the [ZeroSettle.dismissPendingAction] String-arg overload that the
 * Flutter plugin's pending-actions MethodChannel routes calls to. The plugin
 * receives a transactionId on the wire but not the full [PendingAction]
 * sealed-class variant, so the SDK resolves the variant from
 * [ZeroSettle.pendingActions] and forwards to the typed overload.
 *
 * Also verifies the [ZeroSettleError.NotFound] sealed-class variant the
 * overload returns when the transactionId isn't currently in the active
 * pending list.
 */
@RunWith(RobolectricTestRunner::class)
class DismissPendingActionOverloadTest {

    @Before fun setUp() = runTest {
        ZeroSettle.configure(
            context = ApplicationProvider.getApplicationContext(),
            config = ZeroSettleConfig(
                publishableKey = "zs_pk_test_xxx",
                syncPlayPurchases = false,
            ),
        )
    }

    @After fun tearDown() {
        ZeroSettle.resetForTesting()
    }

    @Test fun `dismissPendingAction(transactionId) returns NotFound when id is not in pending list`() = runTest {
        val result = ZeroSettle.dismissPendingAction("nonexistent_txn_123")
        assertThat(result.isFailure).isTrue()
        val err = result.exceptionOrNull()
        assertThat(err).isInstanceOf(ZeroSettleError.NotFound::class.java)
        assertThat((err as ZeroSettleError.NotFound).message).contains("nonexistent_txn_123")
    }

    @Test fun `ZeroSettleError sealed class has a NotFound variant`() {
        val instance: ZeroSettleError = ZeroSettleError.NotFound("test")
        assertThat(instance).isInstanceOf(ZeroSettleError.NotFound::class.java)
        assertThat((instance as ZeroSettleError.NotFound).message).isEqualTo("test")
    }
}
