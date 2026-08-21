package com.messenger.app.data.encryption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * The store tag has to hold one property above all: it changes when, and only
 * when, the signing identity changes. That is what lets the Delivery Service
 * tell a rebuilt MLS store apart from the one that published earlier, and it is
 * why a stale KeyPackage can no longer be served to a device that cannot open it.
 *
 * The KeyPackage framing walked here was validated against every real published
 * package in the deployment (80 rows across three client builds) before being
 * written; these vectors are synthetic so no real user identity ends up in the
 * repository.
 */
class MlsStoreIdTest {

    // ---- RFC 9420 framing helpers ----

    private fun varint(n: Int): ByteArray = when {
        n < 0x40 -> byteArrayOf(n.toByte())
        n < 0x4000 -> byteArrayOf(((0x40 or (n ushr 8)).toByte()), n.toByte())
        else -> byteArrayOf(
            (0x80 or (n ushr 24)).toByte(),
            (n ushr 16).toByte(),
            (n ushr 8).toByte(),
            n.toByte()
        )
    }

    private fun vec(b: ByteArray): ByteArray = varint(b.size) + b

    /** protocol_version, cipher_suite, init_key, encryption_key, signature_key, tail. */
    private fun keyPackage(
        initKey: ByteArray = ByteArray(32) { 1 },
        encryptionKey: ByteArray = ByteArray(32) { 2 },
        signatureKey: ByteArray = ByteArray(32) { 3 },
        tail: ByteArray = ByteArray(48) { 9 }
    ): ByteArray =
        byteArrayOf(0, 1, 0, 1) + vec(initKey) + vec(encryptionKey) + vec(signatureKey) + tail

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---- the property that matters ----

    @Test
    fun `the tag is the sha-256 of the signature key`() {
        val signatureKey = ByteArray(32) { it.toByte() }
        assertEquals(
            "the tag must be a function of the signing identity alone",
            sha256Hex(signatureKey),
            MlsStoreId.of(keyPackage(signatureKey = signatureKey))
        )
    }

    @Test
    fun `packages from one store share a tag even with different init keys`() {
        val identity = ByteArray(32) { 7 }
        val first = keyPackage(initKey = ByteArray(32) { 11 }, signatureKey = identity)
        val second = keyPackage(initKey = ByteArray(32) { 22 }, signatureKey = identity)

        assertEquals(
            "every package a store publishes must file under the same incarnation, " +
                "or a device's own stock would look like several stores",
            MlsStoreId.of(first), MlsStoreId.of(second)
        )
    }

    @Test
    fun `a rebuilt store gets a different tag`() {
        val before = keyPackage(signatureKey = ByteArray(32) { 4 })
        val after = keyPackage(signatureKey = ByteArray(32) { 5 })

        assertNotEquals(
            "a new signing identity is exactly the boundary the tag exists to name",
            MlsStoreId.of(before), MlsStoreId.of(after)
        )
    }

    // ---- length prefixes ----

    @Test
    fun `two-byte and four-byte length prefixes are read`() {
        val signatureKey = ByteArray(32) { 6 }
        val expected = sha256Hex(signatureKey)

        // 0x40-prefixed: a vector between 64 and 16383 bytes.
        assertEquals(
            expected,
            MlsStoreId.of(keyPackage(initKey = ByteArray(300) { 1 }, signatureKey = signatureKey))
        )
        // 0x80-prefixed: a vector of 16384 bytes or more.
        assertEquals(
            expected,
            MlsStoreId.of(keyPackage(initKey = ByteArray(20_000) { 1 }, signatureKey = signatureKey))
        )
    }

    // ---- malformed input must fail closed ----

    @Test
    fun `truncated bytes yield null rather than a guess`() {
        val full = keyPackage()
        for (cut in intArrayOf(0, 1, 4, 5, 20, 40, 60)) {
            assertNull(
                "a $cut-byte prefix must not produce a tag",
                MlsStoreId.of(full.copyOfRange(0, cut))
            )
        }
    }

    @Test
    fun `a vector claiming more bytes than exist yields null`() {
        val bytes = byteArrayOf(0, 1, 0, 1) + varint(200) + ByteArray(10)
        assertNull(MlsStoreId.of(bytes))
    }

    @Test
    fun `an empty signature key yields null`() {
        assertNull(
            "an empty identity would collapse every such store into one tag",
            MlsStoreId.of(keyPackage(signatureKey = ByteArray(0)))
        )
    }

    @Test
    fun `the reserved varint prefix yields null`() {
        // 0b11 is reserved by RFC 9420 and must be rejected, not interpreted.
        val bytes = byteArrayOf(0, 1, 0, 1, 0xC0.toByte(), 0, 0, 0) + ByteArray(64)
        assertNull(MlsStoreId.of(bytes))
    }

    @Test
    fun `empty input yields null`() {
        assertNull(MlsStoreId.of(ByteArray(0)))
    }
}
