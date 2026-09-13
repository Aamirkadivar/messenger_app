package com.messenger.app.data.encryption.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android half of the Phase 32 cross-platform keyring gate.
 *
 * Windows has gained a history keyring, and the same keyring bytes are sealed once and opened on
 * either platform - so the two serialisations must agree byte-for-byte before anything is archived
 * against them. A silent disagreement would not surface until a real message could not be recovered.
 *
 * The fixture hex below was computed independently from the written format specification in
 * [HistoryKeyring], not by serialising either implementation. The Windows test
 * (windows_app/tests/history_keyring_test.cpp) asserts the identical string, so both platforms are
 * checked against the same third value rather than against each other.
 *
 * Everything here is synthetic: a counting-pattern root and literal identifiers. No production
 * account, chat, or root appears.
 */
class CrossPlatformKeyringFixtureTest {

    private companion object {
        const val CHAT = "00000000-0000-0000-0000-000000000001"
        const val ROOT_VERSION = 1

        /** 00 01 02 … 1f. Synthetic, never a real history root. */
        val ROOT = ByteArray(32) { it.toByte() }

        /**
         * u8 formatVersion=1 | u32be count=1 | u32be chatIdLen=0x24 | chatId
         * | u32be rootVersion=1 | u8 rootLen=0x20 | root
         */
        const val FIXTURE_HEX =
            "01" +
                "00000001" +
                "00000024" +
                "30303030303030302d303030302d303030302d303030302d303030303030303030303031" +
                "00000001" +
                "20" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun fixtureBytes(): ByteArray =
        ByteArray(FIXTURE_HEX.length / 2) {
            FIXTURE_HEX.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }

    private fun oneEntryKeyring(): HistoryKeyring =
        HistoryKeyring.of(listOf(HistoryRootEntry(CHAT, ROOT_VERSION, ROOT))).getOrThrow()

    /** The bytes Windows must produce from the same entry. */
    @Test
    fun serialisationMatchesTheCrossPlatformFixture() {
        assertEquals(FIXTURE_HEX, oneEntryKeyring().encode().hex())
    }

    /** The fixture Windows also serialises must decode here to the same semantic object. */
    @Test
    fun deserialisationOfTheFixtureYieldsTheExpectedEntry() {
        val decoded = HistoryKeyring.decode(fixtureBytes()).getOrThrow()

        assertEquals(1, decoded.entries.size)
        val entry = decoded.latest(CHAT)
        assertNotNull(entry)
        assertEquals(CHAT, entry!!.chatId)
        assertEquals(ROOT_VERSION, entry.rootVersion)
        assertEquals(ROOT.hex(), entry.root.hex())
    }

    /** Decode then re-encode must be byte-stable, or the two platforms could drift over a round trip. */
    @Test
    fun decodeThenEncodeIsByteStable() {
        val decoded = HistoryKeyring.decode(fixtureBytes()).getOrThrow()

        assertEquals(FIXTURE_HEX, decoded.encode().hex())
    }

    /**
     * Serialisation must be canonical. Windows sorts with QString's comparison and Android with
     * Kotlin's; both are UTF-16 code-unit order, and this pins that the result does not depend on
     * insertion order on either side.
     */
    @Test
    fun serialisationIsCanonicalNotInsertionOrdered() {
        val a = ByteArray(32) { 'A'.code.toByte() }
        val b = ByteArray(32) { 'B'.code.toByte() }
        val c = ByteArray(32) { 'C'.code.toByte() }

        val one = HistoryKeyring.of(
            listOf(
                HistoryRootEntry("b-chat", 1, b),
                HistoryRootEntry("a-chat", 2, c),
                HistoryRootEntry("a-chat", 1, a),
            )
        ).getOrThrow()
        val two = HistoryKeyring.of(
            listOf(
                HistoryRootEntry("a-chat", 1, a),
                HistoryRootEntry("b-chat", 1, b),
                HistoryRootEntry("a-chat", 2, c),
            )
        ).getOrThrow()

        assertEquals(one.encode().hex(), two.encode().hex())
        assertEquals("a-chat", one.entries.first().chatId)
        assertEquals(1, one.entries.first().rootVersion)
    }

    /** Rotation keeps older versions addressable; both platforms must agree on that. */
    @Test
    fun olderVersionsRemainAddressable() {
        val v1 = ByteArray(32) { 1 }
        val v2 = ByteArray(32) { 2 }
        val k = HistoryKeyring.of(
            listOf(HistoryRootEntry(CHAT, 1, v1), HistoryRootEntry(CHAT, 2, v2))
        ).getOrThrow()

        assertEquals(2, k.latest(CHAT)!!.rootVersion)
        assertEquals(v1.hex(), k.find(CHAT, 1)!!.root.hex())
        assertNull(k.find(CHAT, 3))
    }

    /**
     * The rejections Windows must also make. An accepting parser on one platform and a rejecting
     * one on the other is a divergence even when no valid keyring is involved.
     */
    @Test
    fun malformedInputIsRejectedTheSameWayOnBothPlatforms() {
        val f = fixtureBytes()

        assertTrue(HistoryKeyring.decode(ByteArray(0)).isFailure)
        assertTrue(HistoryKeyring.decode(f.copyOf(f.size - 1)).isFailure)
        assertTrue(HistoryKeyring.decode(f + byteArrayOf(0x78)).isFailure)

        val badVersion = f.copyOf().also { it[0] = 0 }
        assertTrue(HistoryKeyring.decode(badVersion).isFailure)
        val futureVersion = f.copyOf().also { it[0] = 2 }
        assertTrue(HistoryKeyring.decode(futureVersion).isFailure)

        // rootVersion field sits at offset 45 in this fixture.
        val zeroVersion = f.copyOf().also { for (i in 45..48) it[i] = 0 }
        assertTrue(HistoryKeyring.decode(zeroVersion).isFailure)
        val negativeVersion = f.copyOf().also { for (i in 45..48) it[i] = 0xFF.toByte() }
        assertTrue(HistoryKeyring.decode(negativeVersion).isFailure)

        val absurdCount = f.copyOf().also {
            it[1] = 0x7F; it[2] = 0xFF.toByte(); it[3] = 0xFF.toByte(); it[4] = 0xFF.toByte()
        }
        assertTrue(HistoryKeyring.decode(absurdCount).isFailure)
    }

    /** Structural rules, enforced at construction as well as at decode - same list as Windows. */
    @Test
    fun structuralRulesRejectTheSameEntries() {
        assertTrue(HistoryKeyring.of(listOf(HistoryRootEntry("", 1, ROOT))).isFailure)
        assertTrue(HistoryKeyring.of(listOf(HistoryRootEntry(CHAT, 0, ROOT))).isFailure)
        assertTrue(HistoryKeyring.of(listOf(HistoryRootEntry(CHAT, -1, ROOT))).isFailure)
        assertTrue(HistoryKeyring.of(listOf(HistoryRootEntry(CHAT, 1, ByteArray(31)))).isFailure)
        assertTrue(HistoryKeyring.of(listOf(HistoryRootEntry(CHAT, 1, ByteArray(33)))).isFailure)
        assertTrue(HistoryKeyring.of(listOf(HistoryRootEntry(CHAT, 1, ByteArray(0)))).isFailure)
        assertTrue(
            HistoryKeyring.of(
                listOf(HistoryRootEntry(CHAT, 1, ROOT), HistoryRootEntry(CHAT, 1, ByteArray(32)))
            ).isFailure
        )
        assertTrue(
            HistoryKeyring.of(
                listOf(HistoryRootEntry(CHAT, 1, ROOT), HistoryRootEntry(CHAT, 2, ByteArray(32)))
            ).isSuccess
        )
    }

    /** An empty keyring is a legitimate value and must encode to the header alone. */
    @Test
    fun anEmptyKeyringEncodesToJustTheHeader() {
        val empty = HistoryKeyring.of(emptyList()).getOrThrow()

        assertEquals("0100000000", empty.encode().hex())
        assertEquals(empty.encode().hex(), HistoryKeyring.decode(empty.encode()).getOrThrow().encode().hex())
    }

    /** A root must never be rendered, including through logging or a debugger. */
    @Test
    fun entryToStringNeverRevealsRootMaterial() {
        val rendered = HistoryRootEntry(CHAT, 1, ROOT).toString()

        assertTrue(rendered.contains("redacted"))
        assertTrue(!rendered.contains("000102"))
    }
}
