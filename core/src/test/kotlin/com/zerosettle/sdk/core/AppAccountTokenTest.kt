package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test

/**
 * Golden-vector parity test. The same `(packageName, userId)` MUST produce the
 * same UUID on iOS, Android, and the backend Python implementation.
 *
 * To regenerate vectors:
 *  - Backend: `python -c "from api.services.appaccount_token import derive_app_account_token as d; print(d(user_id='USER', bundle_id='PKG'))"`
 *  - iOS playground using `AppAccountToken.derive(userId:bundleId:)`
 * and confirm the Kotlin implementation produces the same UUID before checking in.
 *
 * The GOLDEN_* vectors below were captured 2026-05-11 by running the backend
 * Python implementation at backend/api/services/appaccount_token.py.
 */
class AppAccountTokenTest {

    private companion object {
        // Captured from backend Python `derive_app_account_token`, 2026-05-11.
        const val GOLDEN_ROOT = "8d8bd607-2a41-5204-8430-f2c2a81203dd"
        const val GOLDEN_ALICE_APP = "0a1581ab-fc60-50c0-8923-790e19340024"
        const val GOLDEN_BOB_APP = "a19745d1-08a8-519c-aa8c-c32212a4de20"
        const val GOLDEN_ALICE_OTHER = "7a9d8896-5434-599d-9479-c256cb0841dc"
        const val GOLDEN_USER123_APP = "48eb50db-3bf0-5acd-8ca7-777309ad4b6e"
    }

    @Test fun root_matchesNamespaceDnsDerivation() {
        // ROOT = uuid5(NAMESPACE_DNS, "appaccounttoken.zerosettle.com")
        val expected = AppAccountToken.uuidV5(
            namespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
            name = "appaccounttoken.zerosettle.com",
        )
        assertThat(AppAccountToken.ROOT_NAMESPACE).isEqualTo(expected)
    }

    @Test fun goldenVectors_matchBackendPython() {
        assertThat(AppAccountToken.ROOT_NAMESPACE.toString()).isEqualTo(GOLDEN_ROOT)
        assertThat(AppAccountToken.derive(userId = "alice", packageName = "com.example.app").toString())
            .isEqualTo(GOLDEN_ALICE_APP)
        assertThat(AppAccountToken.derive(userId = "bob", packageName = "com.example.app").toString())
            .isEqualTo(GOLDEN_BOB_APP)
        assertThat(AppAccountToken.derive(userId = "alice", packageName = "com.example.other").toString())
            .isEqualTo(GOLDEN_ALICE_OTHER)
        assertThat(AppAccountToken.derive(userId = "user-123", packageName = "com.example.app").toString())
            .isEqualTo(GOLDEN_USER123_APP)
    }

    @Test fun userIdThatIsAlreadyUUID_passesThroughUnchanged() {
        val uuid = "9d7a4f0e-7c3a-11ee-b962-0242ac120002"
        val result = AppAccountToken.derive(userId = uuid, packageName = "com.example.app")
        assertThat(result.toString()).isEqualTo(uuid)
    }

    @Test fun userIdAndPackage_deriveDeterministically() {
        val a1 = AppAccountToken.derive(userId = "alice", packageName = "com.example.app")
        val a2 = AppAccountToken.derive(userId = "alice", packageName = "com.example.app")
        val b = AppAccountToken.derive(userId = "bob", packageName = "com.example.app")
        val a3 = AppAccountToken.derive(userId = "alice", packageName = "com.example.other")
        assertThat(a1).isEqualTo(a2)
        assertThat(a1).isNotEqualTo(b)
        assertThat(a1).isNotEqualTo(a3)
    }

    @Test fun emptyUserId_throws() {
        try {
            AppAccountToken.derive(userId = "", packageName = "com.app")
            error("expected throw")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("userId")
        }
    }

    @Test fun emptyPackage_throws() {
        try {
            AppAccountToken.derive(userId = "alice", packageName = "")
            error("expected throw")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("packageName")
        }
    }

    @Test fun version_andVariant_setCorrectly() {
        val derived = AppAccountToken.derive(userId = "alice", packageName = "com.example.app")
        // Version 5: byte 6 high nibble must be 0x5
        val bytes = AppAccountToken.uuidToBytes(derived)
        assertThat(bytes[6].toInt() and 0xF0).isEqualTo(0x50)
        // RFC 4122 variant: byte 8 high two bits must be 10
        assertThat(bytes[8].toInt() and 0xC0).isEqualTo(0x80)
    }
}
