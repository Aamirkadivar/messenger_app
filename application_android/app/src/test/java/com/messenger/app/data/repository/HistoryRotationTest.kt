package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT E - rotation behaviour, JVM.
 *
 * Covers the rotation MECHANICS end to end: version monotonicity, old roots surviving, archives
 * before and after a rotation each opening under their own version.
 *
 * It does NOT cover a rotation TRIGGER, because there is no usable one - see the checkpoint report.
 * `Chat.KeyEpoch` advances only on group membership changes, never on device revocation or the
 * manual E2EE reset, and the client observes it lazily rather than as an event. Wiring rotation to
 * it would under-rotate exactly where it matters most, so the trigger is deliberately absent
 * pending a product decision.
 *
 * All key material is TEST-ONLY.
 */
class HistoryRotationTest {

    private companion object {
        const val USER = "user-rot"
        const val CHAT = "chat-rot"
        const val TEXT = "checkpoint-e rotation canary"
    }

    private class MemoryStore : HistoryKeyringStore {
        private var blob: ByteArray? = null
        private var cache: ByteArray? = null
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() = Result.success(Unit).also { cache = null }

        private var generation: Long? = null
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    private class PassthroughVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x7F) + plaintext.copyOf())
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte()) {
                Result.success(sealed.copyOfRange(1, sealed.size))
            } else Result.failure(IllegalStateException("not sealed"))
    }

    private class ContextCheckingCipher : ArchiveCipher {
        private fun tag(root: ByteArray, c: HistoryContext) =
            "${root.joinToString(""){ b -> "%02x".format(b) }}|${c.userId}|${c.chatId}|" +
                "${c.messageId}|${c.rootVersion}|${c.protocolVersion}"
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) =
            (tag(historyRoot, ctx) + "|" + String(plaintext, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? {
            val text = String(sealed, Charsets.UTF_8)
            val prefix = tag(historyRoot, ctx) + "|"
            return if (text.startsWith(prefix)) text.removePrefix(prefix).toByteArray(Charsets.UTF_8) else null
        }
    }

    private fun setup(): Pair<MessageArchiver, HistoryKeyringRepository> {
        val keyring = HistoryKeyringRepository(PassthroughVault(), MemoryStore(), com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        return MessageArchiver(keyring, ContextCheckingCipher(), com.messenger.app.data.encryption.history.HistoryArchiveFeature.Enabled) to keyring
    }

    // ------------------------------------------------------------------ versions

    @Test
    fun firstRootIsVersionOne() = runBlocking {
        val (_, k) = setup()
        assertEquals(1, k.ensureRoot(CHAT).getOrThrow().rootVersion)
    }

    @Test
    fun rotationCreatesExactlyOneNewVersion() = runBlocking {
        val (_, k) = setup()
        k.ensureRoot(CHAT).getOrThrow()
        assertEquals(2, k.rotate(CHAT).getOrThrow().rootVersion)
        assertEquals(2, k.load().getOrThrow().entries.count { it.chatId == CHAT })
    }

    @Test
    fun versionsAreMonotonicAcrossManyRotations() = runBlocking {
        val (_, k) = setup()
        k.ensureRoot(CHAT).getOrThrow()
        val seen = mutableListOf(1)
        repeat(6) { seen.add(k.rotate(CHAT).getOrThrow().rootVersion) }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), seen)
        assertEquals(seen.sorted(), seen)
        assertEquals("no version may repeat", seen.size, seen.toSet().size)
    }

    /** Rotation is a deliberate act: each call adds a version rather than being deduplicated. */
    @Test
    fun repeatedRotationKeepsAddingVersionsAndNeverLosesOldOnes() = runBlocking {
        val (_, k) = setup()
        k.ensureRoot(CHAT).getOrThrow()
        k.rotate(CHAT).getOrThrow()
        k.rotate(CHAT).getOrThrow()
        val loaded = k.load().getOrThrow()
        assertEquals(3, loaded.entries.count { it.chatId == CHAT })
        for (v in 1..3) assertNotNull("version $v must survive", loaded.find(CHAT, v))
    }

    @Test
    fun rotationNeverDeletesAnOldRoot() = runBlocking {
        val (_, k) = setup()
        val v1 = k.ensureRoot(CHAT).getOrThrow()
        k.rotate(CHAT).getOrThrow()
        assertArrayEquals(
            "the old root must remain byte-identical",
            v1.root,
            k.find(CHAT, 1).getOrThrow()!!.root
        )
    }

    @Test
    fun rotationIsPerChat() = runBlocking {
        val (_, k) = setup()
        k.ensureRoot("chat-a").getOrThrow()
        k.ensureRoot("chat-b").getOrThrow()
        k.rotate("chat-a").getOrThrow()
        assertEquals(2, k.load().getOrThrow().latest("chat-a")!!.rootVersion)
        assertEquals("an unrelated chat must not rotate", 1, k.load().getOrThrow().latest("chat-b")!!.rootVersion)
    }

    // ------------------------------------------------------------------ archives across rotation

    @Test
    fun rotateThenArchiveThenDecrypt() = runBlocking {
        val (a, k) = setup()
        k.ensureRoot(CHAT).getOrThrow()
        k.rotate(CHAT).getOrThrow()
        val sealed = a.seal(USER, CHAT, "m1", TEXT).getOrThrow()
        assertEquals("a new message must use the new root", 2, sealed.rootVersion)
        assertEquals(TEXT, a.open(USER, CHAT, "m1", 2, sealed.ciphertextB64).getOrThrow())
    }

    @Test
    fun archiveThenRotateThenDecryptTheOldArchive() = runBlocking {
        val (a, k) = setup()
        val old = a.seal(USER, CHAT, "m-old", TEXT).getOrThrow()
        assertEquals(1, old.rootVersion)
        k.rotate(CHAT).getOrThrow()
        assertEquals(
            "an archive sealed before rotation must still open afterwards",
            TEXT,
            a.open(USER, CHAT, "m-old", old.rootVersion, old.ciphertextB64).getOrThrow()
        )
    }

    @Test
    fun messagesEitherSideOfARotationUseDifferentRoots() = runBlocking {
        val (a, k) = setup()
        val before = a.seal(USER, CHAT, "m-before", TEXT).getOrThrow()
        k.rotate(CHAT).getOrThrow()
        val after = a.seal(USER, CHAT, "m-after", TEXT).getOrThrow()

        assertEquals(1, before.rootVersion)
        assertEquals(2, after.rootVersion)
        assertNotEquals(before.ciphertextB64, after.ciphertextB64)
        // Neither opens under the other's version.
        assertTrue(a.open(USER, CHAT, "m-before", 2, before.ciphertextB64).isFailure)
        assertTrue(a.open(USER, CHAT, "m-after", 1, after.ciphertextB64).isFailure)
    }

    @Test
    fun historicalArchivesSurviveManyRotations() = runBlocking {
        val (a, k) = setup()
        val archives = mutableListOf<Pair<String, SealedArchive>>()
        repeat(5) { i ->
            val id = "m$i"
            archives.add(id to a.seal(USER, CHAT, id, "$TEXT-$i").getOrThrow())
            k.rotate(CHAT).getOrThrow()
        }
        // Every archive still opens under the version it recorded, five rotations later.
        archives.forEachIndexed { i, (id, sealed) ->
            assertEquals(i + 1, sealed.rootVersion)
            assertEquals(
                "$TEXT-$i",
                a.open(USER, CHAT, id, sealed.rootVersion, sealed.ciphertextB64).getOrThrow()
            )
        }
        assertEquals(6, k.load().getOrThrow().entries.count { it.chatId == CHAT })
    }

    /** A device that never held a version cannot fabricate it. */
    @Test
    fun aVersionThisDeviceNeverHeldCannotOpenAnything() = runBlocking {
        val (a, _) = setup()
        val sealed = a.seal(USER, CHAT, "m1", TEXT).getOrThrow()
        assertTrue(a.open(USER, CHAT, "m1", 99, sealed.ciphertextB64).isFailure)
        assertNull(setup().second.find(CHAT, 99).getOrThrow())
    }

    // ------------------------------------------------------------------ combined with retraction

    @Test
    fun retractThenRotateThenRefreshStaysRetracted() = runBlocking {
        val (a, k) = setup()
        var row = OutboundArchivePolicy.Fields(null, 0, null)
        row = OutboundArchivePolicy.archiveFor(USER, CHAT, "m1", TEXT, row, "[encrypted]") { u, c, m, p ->
            a.seal(u, c, m, p).getOrNull()
        }
        // Retract, then rotate, then let a refresh pass run.
        row = OutboundArchivePolicy.Fields(null, row.rootVersion, "retracted")
        k.rotate(CHAT).getOrThrow()
        val afterRefresh = OutboundArchivePolicy.archiveFor(
            USER, CHAT, "m1", TEXT, row, "[encrypted]"
        ) { u, c, m, p -> a.seal(u, c, m, p).getOrNull() }

        assertEquals("rotation must not clear the tombstone", "retracted", afterRefresh.state)
        assertNull(afterRefresh.ciphertext)
    }
}
