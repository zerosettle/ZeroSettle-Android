package com.zerosettle.sdk.internal

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityStoreTest {

    private val store = IdentityStore(ApplicationProvider.getApplicationContext())

    @Before fun reset() = runTest { store.clear() }

    @Test fun anonymousUuid_isStableAcrossReads() = runTest {
        val first = store.anonymousUuid()
        val second = store.anonymousUuid()
        assertThat(first).isEqualTo(second)
    }

    @Test fun setActiveUserId_andClear() = runTest {
        store.setActiveUserId("u1")
        assertThat(store.activeUserId()).isEqualTo("u1")
        store.clear()
        assertThat(store.activeUserId()).isNull()
    }

    @Test fun setCustomer_persistsNameAndEmail() = runTest {
        store.setCustomer(name = "Alice", email = "a@example.com")
        assertThat(store.customerName()).isEqualTo("Alice")
        assertThat(store.customerEmail()).isEqualTo("a@example.com")
    }

    @Test fun setCustomer_nullArgsClearKeys() = runTest {
        store.setCustomer(name = "Alice", email = "a@example.com")
        store.setCustomer(name = null, email = null)
        assertThat(store.customerName()).isNull()
        assertThat(store.customerEmail()).isNull()
    }

    @Test fun clear_clearsCustomer() = runTest {
        store.setCustomer(name = "Alice", email = "a@example.com")
        store.clear()
        assertThat(store.customerName()).isNull()
        assertThat(store.customerEmail()).isNull()
    }
}
