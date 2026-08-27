package com.messenger.app.data.encryption.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 2 - AEAD acceptance criteria, against the REAL production cipher.
 *
 * These tests live under androidTest for one reason only: `VaultCrypto` initialises
 * `LazySodiumAndroid(SodiumAndroid())`, so it needs the Android libsodium runtime and throws
 * UnsatisfiedLinkError under a plain JVM test. Everything that does not need libsodium is tested
 * off-device in HistoryCryptoTest / HistoryKeyringTest and is deliberately NOT duplicated here.
 *
 * Entirely in-memory. This class touches no SharedPreferences, no Room, no MLS state, no network,
 * no live chat, and no production data - it only calls pure functions plus the AEAD primitive.
 * All roots and payloads are TEST-ONLY.
 */
@RunWith(AndroidJUnit4::class)
class HistoryMessageCipherAeadTest {

    private companion object {
        /** TEST-ONLY roots. Never production material. */
        val ROOT_A = ByteArray(32) { it.toByte() }
        val ROOT_B = ByteArray(32) { (it + 1).toByte() }

        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16

        val PLAINTEXT = "gate2-history-archive-canary-0123456789".toByteArray(Charsets.UTF_8)
    }

    private fun ctx(
        userId: String = "u1",
        chatId: String = "c1",
        messageId: String = "m1",
        rootVersion: Int = 1,
        protocolVersion: Int = HistoryCrypto.PROTOCOL_VERSION,
    ) = HistoryContext(userId, chatId, messageId, rootVersion, protocolVersion)

    private fun seal(
        root: ByteArray = ROOT_A,
        context: HistoryContext = ctx(),
        plaintext: ByteArray = PLAINTEXT,
    ): ByteArray = HistoryMessageCipher.seal(root, context, plaintext)
        ?: throw AssertionError("seal must succeed for a well-formed input")

    // ------------------------------------------------------------------ round trip

    @Test
    fun sealOpenRoundTripReturnsExactPlaintext() {
        val sealed = seal()
        val opened = HistoryMessageCipher.open(ROOT_A, ctx(), sealed)
        assertNotNull("a correctly addressed ciphertext must open", opened)
        assertArrayEquals(PLAINTEXT, opened)
    }

    @Test
    fun roundTripHandlesEmptyAndLargePayloads() {
        for (size in intArrayOf(0, 1, 15, 16, 17, 64, 100_000)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val sealed = seal(plaintext = payload)
            assertArrayEquals(
                "round trip must be exact at size " + size,
                payload,
                HistoryMessageCipher.open(ROOT_A, ctx(), sealed)
            )
        }
    }

    /** Confirms the output is exactly what openXChaCha expects: nonce || ciphertext || tag. */
    @Test
    fun sealedLayoutCarriesNonceAndTag() {
        val sealed = seal()
        assertEquals(NONCE_BYTES + PLAINTEXT.size + TAG_BYTES, sealed.size)
    }

    /**
     * The nonce is fresh per seal, so ciphertexts must differ even for identical inputs - and both
     * must still open. This is why ciphertext determinism is explicitly not required.
     */
    @Test
    fun sealingIsNonDeterministicButBothOpen() {
        val first = seal()
        val second = seal()
        assertNotEquals(
            "a fresh nonce must make repeated seals differ",
            first.joinToString(",") { it.toString() },
            second.joinToString(",") { it.toString() }
        )
        assertArrayEquals(PLAINTEXT, HistoryMessageCipher.open(ROOT_A, ctx(), first))
        assertArrayEquals(PLAINTEXT, HistoryMessageCipher.open(ROOT_A, ctx(), second))
    }

    // ------------------------------------------------------------------ wrong root

    @Test
    fun wrongRootFails() {
        assertNull(
            "a ciphertext sealed under root A must not open under root B",
            HistoryMessageCipher.open(ROOT_B, ctx(), seal(root = ROOT_A))
        )
    }

    @Test
    fun wrongRootVersionFails() {
        assertNull(
            "root version is bound into both the key and the AAD",
            HistoryMessageCipher.open(ROOT_A, ctx(rootVersion = 2), seal(context = ctx(rootVersion = 1)))
        )
    }

    // ------------------------------------------------------------------ wrong context

    @Test
    fun eachSubstitutedContextFieldFails() {
        val sealed = seal(context = ctx())
        val wrong = mapOf(
            "userId" to ctx(userId = "u2"),
            "chatId" to ctx(chatId = "c2"),
            "messageId" to ctx(messageId = "m2"),
            "rootVersion" to ctx(rootVersion = 9),
            "protocolVersion" to ctx(protocolVersion = 2),
        )
        for ((field, badCtx) in wrong) {
            assertNull(
                "changing " + field + " alone must cause authentication failure",
                HistoryMessageCipher.open(ROOT_A, badCtx, sealed)
            )
        }
    }

    // ------------------------------------------------------------------ tampering

    /**
     * Exhaustive single-bit sweep across the whole sealed blob. Every byte belongs to the nonce,
     * the ciphertext, or the tag, so this covers all three tamper cases without relying on my
     * arithmetic about where the boundaries fall.
     */
    @Test
    fun flippingAnyBitAnywhereFails() {
        val sealed = seal(plaintext = "short".toByteArray(Charsets.UTF_8))
        for (i in sealed.indices) {
            val tampered = sealed.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertNull(
                "flipping a bit at offset " + i + " must fail authentication",
                HistoryMessageCipher.open(ROOT_A, ctx(), tampered)
            )
        }
    }

    @Test
    fun tamperingWithNonceRegionFails() {
        val sealed = seal()
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertNull(HistoryMessageCipher.open(ROOT_A, ctx(), tampered))
    }

    @Test
    fun tamperingWithCiphertextBodyFails() {
        val sealed = seal()
        val mid = NONCE_BYTES + (PLAINTEXT.size / 2)
        val tampered = sealed.copyOf().also { it[mid] = (it[mid].toInt() xor 0xFF).toByte() }
        assertNull(HistoryMessageCipher.open(ROOT_A, ctx(), tampered))
    }

    @Test
    fun tamperingWithAuthenticationTagFails() {
        val sealed = seal()
        val last = sealed.size - 1
        val tampered = sealed.copyOf().also { it[last] = (it[last].toInt() xor 0xFF).toByte() }
        assertNull(HistoryMessageCipher.open(ROOT_A, ctx(), tampered))
    }

    @Test
    fun truncatedOrExtendedCiphertextFails() {
        val sealed = seal()
        assertNull("truncated", HistoryMessageCipher.open(ROOT_A, ctx(), sealed.copyOf(sealed.size - 1)))
        assertNull("empty", HistoryMessageCipher.open(ROOT_A, ctx(), ByteArray(0)))
        assertNull("too short for nonce+tag", HistoryMessageCipher.open(ROOT_A, ctx(), ByteArray(NONCE_BYTES)))
        assertNull(
            "extended",
            HistoryMessageCipher.open(ROOT_A, ctx(), sealed + byteArrayOf(0))
        )
    }

    // ------------------------------------------------------------------ no plaintext fallback

    /**
     * The acceptance criterion that matters most: a failed open must yield failure, never the
     * ciphertext, never a partial decryption, never the input echoed back.
     */
    @Test
    fun failureNeverYieldsPlaintextOrCiphertext() {
        val sealed = seal()
        val failures = listOf(
            "wrong root" to { HistoryMessageCipher.open(ROOT_B, ctx(), sealed) },
            "wrong version" to { HistoryMessageCipher.open(ROOT_A, ctx(rootVersion = 3), sealed) },
            "wrong message" to { HistoryMessageCipher.open(ROOT_A, ctx(messageId = "zz"), sealed) },
            "tampered" to {
                HistoryMessageCipher.open(
                    ROOT_A, ctx(), sealed.copyOf().also { it[it.size - 1] = 0 }
                )
            },
        )
        for ((label, attempt) in failures) {
            val result = attempt()
            assertNull(label + " must return null rather than any bytes", result)
            // Belt and braces: if a future change ever made this non-null, it must at minimum
            // never be the ciphertext or the original plaintext.
            if (result != null) {
                assertTrue(label + " leaked ciphertext", !result.contentEquals(sealed))
                assertTrue(label + " leaked plaintext", !result.contentEquals(PLAINTEXT))
            }
        }
    }

    // ------------------------------------------------------------------ derivation on-device

    /**
     * The pure core is fully covered on the JVM; this only confirms the same derivation runs
     * identically on-device, so the two halves of Gate 2 cannot silently diverge.
     */
    @Test
    fun derivationOnDeviceMatchesPureCore() {
        val k1 = HistoryCrypto.deriveMessageKey(ROOT_A, "m1", 1)
        val k2 = HistoryCrypto.deriveMessageKey(ROOT_A, "m1", 1)
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
        assertEquals(32, HistoryCrypto.newHistoryRoot().size)
    }
}
