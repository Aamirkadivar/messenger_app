package com.messenger.app.data.encryption.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android half of the Phase 30 cross-platform archive gate.
 *
 * Windows is gaining a Layer B adapter, and an archive sealed on one platform is worthless unless
 * the other can open it. Before any integration work, the deterministic halves of the protocol -
 * the per-message key and the AAD - must agree byte-for-byte.
 *
 * These values are asserted against an INDEPENDENT reference computed from RFC 5869 / RFC 2104
 * rather than against the Windows implementation, so the test proves both platforms are correct
 * rather than merely equal to each other. Windows asserts the same constants.
 *
 * Every value here is synthetic. The root is 00..1f, the identifiers are literals, and the
 * plaintext is a fixture string - nothing derived from a real account, message, or key.
 */
class CrossPlatformArchiveFixtureTest {

    private companion object {
        /** 00 01 02 … 1f. Synthetic, never a real history root. */
        val ROOT = ByteArray(32) { it.toByte() }
        const val USER = "fixture-user"
        const val CHAT = "fixture-chat"
        const val MESSAGE = "00000000-0000-0000-0000-000000000001"
        const val ROOT_VERSION = 1
        const val PLAINTEXT = "Phase30-cross-platform-archive-fixture"

        /** HKDF-SHA256(ikm=ROOT, salt="messenger/history-kdf-salt/v1", info=…, 32). */
        const val EXPECTED_KEY_HEX =
            "c9a0f84cdf1d4e4ca878343145486faa8544f5d2a4f154bc98ecf811cc342a29"

        const val EXPECTED_AAD_HEX =
            "000000186d657373656e6765722f686973746f72792d6161642f7631" +
                "00000001" +
                "0000000c666978747572652d75736572" +
                "0000000c666978747572652d63686174" +
                "00000024" +
                "30303030303030302d303030302d303030302d303030302d303030303030303030303031" +
                "00000001"
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun ctx() = HistoryContext(
        userId = USER,
        chatId = CHAT,
        messageId = MESSAGE,
        rootVersion = ROOT_VERSION,
    )

    /** The key Windows must derive from the same inputs. */
    @Test
    fun derivedMessageKeyMatchesTheCrossPlatformReference() {
        val key = HistoryCrypto.deriveMessageKey(ROOT, MESSAGE, ROOT_VERSION)

        assertEquals(32, key.size)
        assertEquals(EXPECTED_KEY_HEX, key.hex())
    }

    /** The AAD Windows must bind to the same archive. */
    @Test
    fun aadMatchesTheCrossPlatformReference() {
        val aad = HistoryCrypto.aad(ctx())

        assertEquals(108, aad.size)
        assertEquals(EXPECTED_AAD_HEX, aad.hex())
    }

    /**
     * The wire layout the two platforms must agree on, asserted through the encoding rules rather
     * than a magic number: 4-byte big-endian lengths, UTF-8, no trimming or normalisation.
     */
    @Test
    fun aadLayoutIsLengthPrefixedBigEndianUtf8() {
        val aad = HistoryCrypto.aad(ctx())

        // label
        assertEquals(0x18, aad[3].toInt())                       // u32be(24) = "messenger/history-aad/v1"
        assertEquals("messenger/history-aad/v1", String(aad.copyOfRange(4, 28), Charsets.UTF_8))
        // protocol version, big-endian
        assertEquals(listOf(0, 0, 0, 1), aad.copyOfRange(28, 32).map { it.toInt() })
        // userId, length-prefixed
        assertEquals(0x0c, aad[35].toInt())
        assertEquals(USER, String(aad.copyOfRange(36, 48), Charsets.UTF_8))
    }

    /** An empty field must still contribute its four zero length bytes, and nothing else. */
    @Test
    fun anEmptyFieldContributesOnlyItsLength() {
        val withEmptyChat = HistoryCrypto.aad(ctx().copy(chatId = ""))
        val withChat = HistoryCrypto.aad(ctx())

        assertEquals(withChat.size - CHAT.length, withEmptyChat.size)
    }

    /** Every bound field must change the AAD - this is what makes the binding meaningful. */
    @Test
    fun everyBoundFieldChangesTheAad() {
        val base = HistoryCrypto.aad(ctx()).hex()

        assertEquals(false, base == HistoryCrypto.aad(ctx().copy(userId = "other")).hex())
        assertEquals(false, base == HistoryCrypto.aad(ctx().copy(chatId = "other")).hex())
        assertEquals(false, base == HistoryCrypto.aad(ctx().copy(messageId = "other")).hex())
        assertEquals(false, base == HistoryCrypto.aad(ctx().copy(rootVersion = 2)).hex())
        assertEquals(false, base == HistoryCrypto.aad(ctx().copy(protocolVersion = 2)).hex())
    }

    /** A different root version must yield an unrelated key, not a related one. */
    @Test
    fun rootVersionIsBoundIntoTheKey() {
        val v1 = HistoryCrypto.deriveMessageKey(ROOT, MESSAGE, 1).hex()
        val v2 = HistoryCrypto.deriveMessageKey(ROOT, MESSAGE, 2).hex()

        assertEquals(false, v1 == v2)
    }

    /** Pinned so a change to the fixture plaintext cannot silently desynchronise the platforms. */
    @Test
    fun fixturePlaintextIsTheAgreedValue() {
        assertEquals(38, PLAINTEXT.toByteArray(Charsets.UTF_8).size)
        assertEquals("Phase30-cross-platform-archive-fixture", PLAINTEXT)
    }

    // ------------------------------------------------------------------ Phase 35 vector
    //
    // A second fixture, deliberately awkward where the first is plain ASCII: UUID-shaped
    // identifiers, and a message body carrying multibyte UTF-8, embedded NUL bytes and high-bit
    // bytes. Only the deterministic halves are asserted here - the AEAD lives in
    // HistoryMessageCipher, which needs libsodium and therefore a device - while Windows
    // (history_archive_seal_test.cpp) asserts these identical strings AND the ciphertext.
    //
    // Expected values come from an independent RFC 5869 computation, not from either client.

    private object P35 {
        const val USER = "9f1c0d4e-0000-4000-8000-0000000000a1"
        const val CHAT = "3b7e5f21-0000-4000-8000-0000000000c2"
        const val MESSAGE = "7d2a91b0-0000-4000-8000-0000000000e3"
        const val ROOT_VERSION = 1

        const val KEY_HEX = "38ee0d416f4dfdab44533f22636202927b122df19f7537f7b40ef463c8ca3899"

        const val AAD_HEX =
            "000000186d657373656e6765722f686973746f72792d6161642f7631" +
                "00000001" +
                "00000024" +
                "39663163306434652d303030302d343030302d383030302d303030303030303030306131" +
                "00000024" +
                "33623765356632312d303030302d343030302d383030302d303030303030303030306332" +
                "00000024" +
                "37643261393162302d303030302d343030302d383030302d303030303030303030306533" +
                "00000001"
    }

    private fun p35Ctx() = HistoryContext(
        userId = P35.USER,
        chatId = P35.CHAT,
        messageId = P35.MESSAGE,
        rootVersion = P35.ROOT_VERSION,
    )

    /** The key Windows must derive for the Phase 35 vector. */
    @Test
    fun phase35DerivedKeyMatchesTheCrossPlatformReference() {
        val key = HistoryCrypto.deriveMessageKey(ROOT, P35.MESSAGE, P35.ROOT_VERSION)

        assertEquals(32, key.size)
        assertEquals(P35.KEY_HEX, key.hex())
    }

    /** The AAD Windows must bind for the Phase 35 vector. */
    @Test
    fun phase35AadMatchesTheCrossPlatformReference() {
        val aad = HistoryCrypto.aad(p35Ctx())

        assertEquals(156, aad.size)
        assertEquals(P35.AAD_HEX, aad.hex())
    }

    /** UUID-shaped identifiers must be bound verbatim - never trimmed, case-folded, or normalised. */
    @Test
    fun phase35IdentifiersAreBoundVerbatim() {
        val base = HistoryCrypto.aad(p35Ctx()).hex()

        assertEquals(false, base == HistoryCrypto.aad(p35Ctx().copy(userId = P35.USER.uppercase())).hex())
        assertEquals(false, base == HistoryCrypto.aad(p35Ctx().copy(chatId = " " + P35.CHAT)).hex())
        assertEquals(false, base == HistoryCrypto.aad(p35Ctx().copy(messageId = P35.MESSAGE + " ")).hex())
    }
}
