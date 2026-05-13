package com.zerosettle.sdk.core

import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic UUIDv5 derivation for the `appAccountToken` passed to Play Billing
 * as `setObfuscatedAccountId`.
 *
 * Algorithm (must match iOS `AppAccountToken.swift` and backend
 * `api/services/appaccount_token.py` byte-for-byte):
 *
 * ```
 * ROOT       = uuid5(NAMESPACE_DNS, "appaccounttoken.zerosettle.com")
 * namespace  = uuid5(ROOT, packageName)
 * derived    = uuid5(namespace, userId)
 * ```
 *
 * If `userId` is already a valid UUID, the literal value is returned as-is
 * (RevenueCat-style passthrough).
 *
 * NOTE for future maintainers: when adding a new golden vector, generate it on
 * BOTH platforms:
 *  - Backend: `python -c "from api.services.appaccount_token import derive_app_account_token as d; print(d(user_id='USER', bundle_id='PKG'))"`
 *  - iOS playground using `AppAccountToken.derive(userId:bundleId:)`
 * and confirm the Kotlin implementation produces the same UUID before checking in.
 */
public object AppAccountToken {

    /** RFC 4122 §C: NAMESPACE_DNS. */
    private val NAMESPACE_DNS: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    /**
     * Root namespace: `uuid5(NAMESPACE_DNS, "appaccounttoken.zerosettle.com")`.
     *
     * NOTE: the `.com` TLD here is **intentional** and must NOT be changed
     * to `.io` — this string is the UUIDv5 namespace input, not a real DNS
     * hostname. It must remain byte-identical to iOS Kit's
     * `AppAccountToken.swift` (`"appaccounttoken.zerosettle.com"`) and the
     * backend's `api/services/appaccount_token.py` to produce matching
     * tokens cross-platform. The runtime API base URL is `.io` (see
     * [ZeroSettle.DEFAULT_BASE_URL]); only this namespace constant uses
     * the legacy `.com` string.
     */
    internal val ROOT_NAMESPACE: UUID by lazy {
        uuidV5(NAMESPACE_DNS, "appaccounttoken.zerosettle.com")
    }

    /** Derive the canonical token. */
    public fun derive(userId: String, packageName: String): UUID {
        require(userId.isNotEmpty()) { "userId must be non-empty" }
        require(packageName.isNotEmpty()) { "packageName must be non-empty" }
        runCatching { UUID.fromString(userId) }.getOrNull()?.let { return it }
        val tenantNs = uuidV5(ROOT_NAMESPACE, packageName)
        return uuidV5(tenantNs, userId)
    }

    /**
     * Compute a name-based UUID per RFC 4122 §4.3 using SHA-1.
     *
     * `internal` so the parity test can call it directly with NAMESPACE_DNS.
     */
    internal fun uuidV5(namespace: UUID, name: String): UUID {
        val nsBytes = uuidToBytes(namespace)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nsBytes)
        sha1.update(nameBytes)
        val digest = sha1.digest()

        // First 16 bytes; set version to 5, variant to RFC 4122.
        digest[6] = ((digest[6].toInt() and 0x0F) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3F) or 0x80).toByte()

        return bytesToUuid(digest)
    }

    /** Byte representation of a UUID (big-endian, 16 bytes). */
    internal fun uuidToBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val out = ByteArray(16)
        for (i in 0..7) out[i] = ((msb shr (56 - i * 8)) and 0xFF).toByte()
        for (i in 0..7) out[i + 8] = ((lsb shr (56 - i * 8)) and 0xFF).toByte()
        return out
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        require(bytes.size >= 16)
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8..15) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}
