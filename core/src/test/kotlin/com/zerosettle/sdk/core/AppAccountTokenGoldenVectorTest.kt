package com.zerosettle.sdk.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Golden-vector parity. The same `(userId, packageName)` MUST yield the same UUID on
 * Android, iOS, and the backend. The expected values below were computed 2026-05-11 via:
 *
 *   cd backend && python -c \
 *     "import importlib.util; \
 *      s=importlib.util.spec_from_file_location('aat','api/services/appaccount_token.py'); \
 *      m=importlib.util.module_from_spec(s); s.loader.exec_module(m); \
 *      print(m.derive_app_account_token(user_id='alice', bundle_id='com.example.app'))"
 *
 * and cross-checked against ZeroSettleKit/Sources/ZeroSettleKit/StoreKit/AppAccountToken.swift
 * (same RFC-4122 §4.3 SHA-1 name-based UUID; same ROOT = uuid5(NAMESPACE_DNS,
 * "appaccounttoken.zerosettle.com") = 8d8bd607-2a41-5204-8430-f2c2a81203dd).
 *
 * If this test fails after a backend change, the contract broke — do NOT just update the
 * expected value; reconcile across all three implementations first.
 */
class AppAccountTokenGoldenVectorTest {

    private val ALICE_APP = "0a1581ab-fc60-50c0-8923-790e19340024"
    private val BOB_APP = "a19745d1-08a8-519c-aa8c-c32212a4de20"
    private val ALICE_OTHER = "7a9d8896-5434-599d-9479-c256cb0841dc"

    @Test fun rootNamespace_matchesBackend() {
        assertThat(AppAccountToken.ROOT_NAMESPACE.toString()).isEqualTo("8d8bd607-2a41-5204-8430-f2c2a81203dd")
    }

    @Test fun alice_app_matchesBackend() {
        assertThat(AppAccountToken.derive(userId = "alice", packageName = "com.example.app").toString()).isEqualTo(ALICE_APP)
    }

    @Test fun bob_app_matchesBackend() {
        assertThat(AppAccountToken.derive(userId = "bob", packageName = "com.example.app").toString()).isEqualTo(BOB_APP)
    }

    @Test fun alice_otherPackage_matchesBackend_andDiffersFromApp() {
        assertThat(AppAccountToken.derive(userId = "alice", packageName = "com.example.other").toString()).isEqualTo(ALICE_OTHER)
        assertThat(ALICE_OTHER).isNotEqualTo(ALICE_APP)
    }

    @Test fun uuidPassthrough_unchanged() {
        val uuid = "9d7a4f0e-7c3a-11ee-b962-0242ac120002"
        assertThat(AppAccountToken.derive(userId = uuid, packageName = "com.example.app").toString()).isEqualTo(uuid)
    }
}
