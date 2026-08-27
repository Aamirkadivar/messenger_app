package com.messenger.app.data.encryption.history

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 2 - pure core, JVM.
 *
 * Everything here runs off-device. [HistoryCrypto] must never reference VaultCrypto, whose
 * initialiser loads libsodium through JNA and throws UnsatisfiedLinkError under a plain JVM test.
 * If someone adds such a reference, these tests fail immediately - which is the point.
 *
 * All key material below is TEST-ONLY and deliberately non-random.
 */
class HistoryCryptoTest {

    private companion object {
        /** TEST-ONLY roots. Never production material. */
        val ROOT_A = ByteArray(32) { it.toByte() }
        val ROOT_B = ByteArray(32) { (it + 1).toByte() }

        const val USER = "u1"
        const val CHAT = "c1"
        const val MSG = "m1"
    }

    private fun ctx(
        userId: String = USER,
        chatId: String = CHAT,
        messageId: String = MSG,
        rootVersion: Int = 7,
        protocolVersion: Int = HistoryCrypto.PROTOCOL_VERSION,
    ) = HistoryContext(userId, chatId, messageId, rootVersion, protocolVersion)

    // ------------------------------------------------------------------ root generation

    @Test
    fun rootIsExactlyThirtyTwoBytes() {
        assertEquals(32, HistoryCrypto.newHistoryRoot().size)
    }

    @Test
    fun rootGenerationIsNotDeterministic() {
        // Not a randomness test - just proof the generator is not a constant or a counter.
        val seen = HashSet<String>()
        repeat(32) { seen.add(HistoryCrypto.newHistoryRoot().toHex()) }
        assertEquals("every generated root must be distinct", 32, seen.size)
    }

    // ------------------------------------------------------------------ HKDF correctness

    /**
     * RFC 5869 Appendix A.1, the published HKDF-SHA256 vector. This is an EXTERNAL vector, so it
     * proves the derivation is real HKDF rather than merely self-consistent.
     */
    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = HistoryCrypto.hkdfSha256(
            ikm = hex("0b".repeat(22)),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex()
        )
    }

    // ------------------------------------------------------------------ message-key derivation

    @Test
    fun messageKeyIsThirtyTwoBytes() {
        assertEquals(32, HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 1).size)
    }

    @Test
    fun messageKeyDerivationIsDeterministic() {
        assertArrayEquals(
            HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 3),
            HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 3)
        )
    }

    @Test
    fun differentRootsProduceDifferentMessageKeys() {
        assertNotEquals(
            HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 1).toHex(),
            HistoryCrypto.deriveMessageKey(ROOT_B, MSG, 1).toHex()
        )
    }

    @Test
    fun differentRootVersionsProduceDifferentMessageKeys() {
        assertNotEquals(
            "root version must be bound into the derivation",
            HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 1).toHex(),
            HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 2).toHex()
        )
    }

    @Test
    fun differentMessageIdsProduceDifferentMessageKeys() {
        assertNotEquals(
            HistoryCrypto.deriveMessageKey(ROOT_A, "m1", 1).toHex(),
            HistoryCrypto.deriveMessageKey(ROOT_A, "m2", 1).toHex()
        )
    }

    /**
     * Message ids are length-prefixed inside the HKDF info too, so ids that would concatenate
     * identically must still derive different keys.
     */
    @Test
    fun messageIdBoundariesAreUnambiguousInDerivation() {
        assertNotEquals(
            HistoryCrypto.deriveMessageKey(ROOT_A, "m1", 1).toHex(),
            HistoryCrypto.deriveMessageKey(ROOT_A, "m", 1).toHex()
        )
    }

    @Test
    fun derivationRejectsWrongRootLength() {
        for (badSize in intArrayOf(0, 16, 31, 33, 64)) {
            assertThrows(IllegalArgumentException::class.java) {
                HistoryCrypto.deriveMessageKey(ByteArray(badSize), MSG, 1)
            }
        }
    }

    // ------------------------------------------------------------------ AAD

    @Test
    fun aadIsDeterministic() {
        assertArrayEquals(HistoryCrypto.aad(ctx()), HistoryCrypto.aad(ctx()))
    }

    /**
     * Canonical AAD vector, derived by hand from the documented layout rather than captured from
     * the implementation:
     *
     *     lp("messenger/history-aad/v1") = 00000018 || 6d...31
     *     u32be(protocolVersion = 1)     = 00000001
     *     lp("u1")                       = 00000002 || 7531
     *     lp("c1")                       = 00000002 || 6331
     *     lp("m1")                       = 00000002 || 6d31
     *     u32be(rootVersion = 7)         = 00000007
     */
    @Test
    fun aadMatchesCanonicalVector() {
        val expected =
            "00000018" +
                "6d657373656e6765722f686973746f72792d6161642f7631" +
                "00000001" +
                "00000002" + "7531" +
                "00000002" + "6331" +
                "00000002" + "6d31" +
                "00000007"
        assertEquals(expected, HistoryCrypto.aad(ctx(rootVersion = 7)).toHex())
    }

    @Test
    fun aadChangesWhenAnyBoundFieldChanges() {
        val base = HistoryCrypto.aad(ctx()).toHex()
        assertNotEquals("userId must be bound", base, HistoryCrypto.aad(ctx(userId = "u2")).toHex())
        assertNotEquals("chatId must be bound", base, HistoryCrypto.aad(ctx(chatId = "c2")).toHex())
        assertNotEquals(
            "messageId must be bound", base, HistoryCrypto.aad(ctx(messageId = "m2")).toHex()
        )
        assertNotEquals(
            "rootVersion must be bound", base, HistoryCrypto.aad(ctx(rootVersion = 8)).toHex()
        )
        assertNotEquals(
            "protocolVersion must be bound", base, HistoryCrypto.aad(ctx(protocolVersion = 2)).toHex()
        )
    }

    /**
     * The reason this AAD is length-prefixed instead of following the repository's existing
     * pipe-delimited convention. Under a separator-joined encoding both rows below collapse to the
     * same string, so a chat id containing the separator could impersonate another message.
     */
    @Test
    fun aadFieldBoundariesCannotBeForgedBySeparatorInjection() {
        val split = HistoryCrypto.aad(ctx(chatId = "a|b", messageId = "c")).toHex()
        val other = HistoryCrypto.aad(ctx(chatId = "a", messageId = "b|c")).toHex()
        assertNotEquals("length prefixes must make the field split unforgeable", split, other)
    }

    @Test
    fun aadIsIndependentOfDefaultLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ROOT)
            val a = HistoryCrypto.aad(ctx()).toHex()
            // Turkish is the classic locale trap for text handling.
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            val b = HistoryCrypto.aad(ctx()).toHex()
            assertEquals("AAD must not depend on the default locale", a, b)
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // ------------------------------------------------------------------ structure

    /**
     * Verifies the composition documented on deriveMessageKey by rebuilding the HKDF info
     * independently here. If the derivation silently changed shape, this fails even though
     * determinism still holds.
     */
    @Test
    fun messageKeyComposesExactlyAsDocumented() {
        val label = "messenger/history-message-key/v1".toByteArray(Charsets.UTF_8)
        val msg = MSG.toByteArray(Charsets.UTF_8)
        val info = HistoryCrypto.u32be(label.size) + label +
            HistoryCrypto.u32be(msg.size) + msg +
            HistoryCrypto.u32be(5)
        val expected = HistoryCrypto.hkdfSha256(
            ikm = ROOT_A,
            salt = "messenger/history-kdf-salt/v1".toByteArray(Charsets.UTF_8),
            info = info,
            length = 32
        )
        assertArrayEquals(expected, HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 5))
    }

    @Test
    fun bestEffortWipeClearsTheArrayItWasGiven() {
        val k = HistoryCrypto.deriveMessageKey(ROOT_A, MSG, 1)
        assertTrue(k.any { it != 0.toByte() })
        HistoryCrypto.bestEffortWipe(k)
        // Asserts only that THIS array was overwritten. Not a claim that the JVM erased every
        // copy - see the KDoc on bestEffortWipe.
        assertTrue("wipe must zero the array it holds", k.all { it == 0.toByte() })
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
    ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
}
