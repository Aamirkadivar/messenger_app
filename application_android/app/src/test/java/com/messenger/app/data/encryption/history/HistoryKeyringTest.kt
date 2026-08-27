package com.messenger.app.data.encryption.history

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 2 - keyring representation, JVM.
 *
 * Gate 2 defines only the representation. Sealing and persistence are Gate 3 concerns, so nothing
 * here touches the vault, Room, or the network. All roots below are TEST-ONLY.
 */
class HistoryKeyringTest {

    private companion object {
        /** TEST-ONLY roots. Never production material. */
        val ROOT_AA = ByteArray(32) { 0xAA.toByte() }
        val ROOT_BB = ByteArray(32) { 0xBB.toByte() }
    }

    private fun entry(chatId: String, version: Int, root: ByteArray = ROOT_AA) =
        HistoryRootEntry(chatId, version, root)

    private fun keyring(vararg e: HistoryRootEntry) = HistoryKeyring.of(e.toList())

    // ------------------------------------------------------------------ round trip

    @Test
    fun encodeDecodeRoundTrip() {
        val original = keyring(entry("c1", 1), entry("c2", 3, ROOT_BB)).getOrThrow()
        val decoded = HistoryKeyring.decode(original.encode()).getOrThrow()
        assertEquals(original, decoded)
        assertEquals(2, decoded.entries.size)
        assertArrayEquals(ROOT_AA, decoded.find("c1", 1)!!.root)
        assertArrayEquals(ROOT_BB, decoded.find("c2", 3)!!.root)
    }

    @Test
    fun serialisationIsCanonicalRegardlessOfInsertionOrder() {
        val a = keyring(entry("c2", 2), entry("c1", 5), entry("c1", 1)).getOrThrow()
        val b = keyring(entry("c1", 1), entry("c2", 2), entry("c1", 5)).getOrThrow()
        assertEquals(
            "same entries in any order must serialise identically",
            a.encode().toHex(),
            b.encode().toHex()
        )
    }

    /**
     * Canonical keyring vector, derived by hand from the documented layout:
     *
     *     u8    formatVersion = 1        -> 01
     *     u32be entryCount    = 1        -> 00000001
     *     u32be chatIdLen     = 2        -> 00000002
     *     bytes chatId        = "c1"     -> 6331
     *     u32be rootVersion   = 1        -> 00000001
     *     u8    rootLen       = 32       -> 20
     *     bytes root          = 0xAA x32 -> aa...aa
     */
    @Test
    fun encodingMatchesCanonicalVector() {
        val expected = "01" + "00000001" + "00000002" + "6331" + "00000001" + "20" + "aa".repeat(32)
        assertEquals(expected, keyring(entry("c1", 1)).getOrThrow().encode().toHex())
    }

    // ------------------------------------------------------------------ multiple versions

    @Test
    fun multipleVersionsPerChatAreSupported() {
        val k = keyring(entry("c1", 1, ROOT_AA), entry("c1", 2, ROOT_BB)).getOrThrow()
        assertEquals(2, k.entries.size)
        assertArrayEquals(ROOT_AA, k.find("c1", 1)!!.root)
        assertArrayEquals(ROOT_BB, k.find("c1", 2)!!.root)
    }

    @Test
    fun latestReturnsHighestVersionForChat() {
        val k = keyring(entry("c1", 1), entry("c1", 9, ROOT_BB), entry("c1", 4)).getOrThrow()
        assertEquals(9, k.latest("c1")!!.rootVersion)
        assertNull("unknown chat has no root", k.latest("nope"))
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun rejectsDuplicateChatAndVersion() {
        val r = keyring(entry("c1", 1, ROOT_AA), entry("c1", 1, ROOT_BB))
        assertFailsWith(r, "duplicate")
    }

    @Test
    fun rejectsTruncatedRoot() {
        assertFailsWith(keyring(entry("c1", 1, ByteArray(31))), "exactly 32 bytes")
    }

    @Test
    fun rejectsOversizedRoot() {
        assertFailsWith(keyring(entry("c1", 1, ByteArray(33))), "exactly 32 bytes")
    }

    @Test
    fun rejectsEmptyRoot() {
        assertFailsWith(keyring(entry("c1", 1, ByteArray(0))), "exactly 32 bytes")
    }

    @Test
    fun rejectsEmptyChatId() {
        assertFailsWith(keyring(entry("", 1)), "chatId must not be empty")
    }

    @Test
    fun rejectsInvalidRootVersion() {
        assertFailsWith(keyring(entry("c1", 0)), "rootVersion must be >= 1")
        assertFailsWith(keyring(entry("c1", -3)), "rootVersion must be >= 1")
    }

    // ------------------------------------------------------------------ decode hardening

    @Test
    fun rejectsUnsupportedFormatVersion() {
        val valid = keyring(entry("c1", 1)).getOrThrow().encode()
        val bumped = valid.copyOf().also { it[0] = 2 }
        assertFailsWith(HistoryKeyring.decode(bumped), "unsupported keyring format version 2")
    }

    /**
     * Every prefix of a valid encoding must be rejected. The exact reason differs by cut point -
     * an early cut trips the allocation guard, a later one trips the reader - so this asserts
     * rejection rather than a particular message.
     */
    @Test
    fun rejectsTruncatedSerialisation() {
        val valid = keyring(entry("c1", 1)).getOrThrow().encode()
        for (cut in 0 until valid.size) {
            assertTrue(
                "a " + cut + "-byte prefix of a valid keyring must not decode",
                HistoryKeyring.decode(valid.copyOf(cut)).isFailure
            )
        }
        assertTrue("the full encoding must still decode", HistoryKeyring.decode(valid).isSuccess)
    }

    @Test
    fun rejectsTrailingBytes() {
        val valid = keyring(entry("c1", 1)).getOrThrow().encode()
        assertFailsWith(HistoryKeyring.decode(valid + byteArrayOf(0)), "trailing bytes")
    }

    @Test
    fun rejectsEmptyInput() {
        assertFailsWith(HistoryKeyring.decode(ByteArray(0)), "truncated")
    }

    @Test
    fun rejectsGarbage() {
        assertTrue(HistoryKeyring.decode(hex("deadbeefcafe")).isFailure)
    }

    @Test
    fun rejectsNegativeEntryCount() {
        assertFailsWith(HistoryKeyring.decode(hex("01ffffffff")), "negative entry count")
    }

    /** A corrupt length must not be allowed to drive a huge allocation before it is rejected. */
    @Test
    fun rejectsAbsurdEntryCount() {
        assertFailsWith(HistoryKeyring.decode(hex("017fffffff")), "exceeds available bytes")
    }

    /** Duplicates must be rejected on the decode path too, not just when constructed in memory. */
    @Test
    fun rejectsDuplicateEntriesOnDecode() {
        val dup = hex(
            "01" + "00000002" +
                "00000002" + "6331" + "00000001" + "20" + "aa".repeat(32) +
                "00000002" + "6331" + "00000001" + "20" + "bb".repeat(32)
        )
        assertFailsWith(HistoryKeyring.decode(dup), "duplicate")
    }

    /** A declared root length other than 32 must be rejected even if the buffer supplies it. */
    @Test
    fun rejectsDeclaredRootLengthOtherThanThirtyTwo() {
        val badLen = hex(
            "01" + "00000001" + "00000002" + "6331" + "00000001" + "10" + "aa".repeat(16)
        )
        assertFailsWith(HistoryKeyring.decode(badLen), "exactly 32 bytes")
    }

    // ------------------------------------------------------------------ hygiene

    @Test
    fun toStringDoesNotLeakRootMaterial() {
        val s = entry("c1", 1, ROOT_AA).toString()
        assertTrue("root must be redacted in toString", s.contains("redacted"))
        assertTrue("root bytes must not appear", !s.contains("aaaaaa"))
        assertTrue("chat id is not secret and stays visible", s.contains("c1"))
    }

    @Test
    fun emptyKeyringRoundTrips() {
        val empty = HistoryKeyring.of(emptyList()).getOrThrow()
        assertEquals(empty, HistoryKeyring.decode(empty.encode()).getOrThrow())
        assertEquals(0, empty.entries.size)
    }

    private fun assertFailsWith(result: Result<HistoryKeyring>, fragment: String) {
        assertTrue("expected failure but succeeded", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertNotNull(message)
        assertTrue(
            "expected message containing \"" + fragment + "\" but was \"" + message + "\"",
            message.contains(fragment)
        )
    }
}
