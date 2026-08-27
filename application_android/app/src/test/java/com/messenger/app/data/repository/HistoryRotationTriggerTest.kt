package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT E2 - the history-specific rotation trigger, JVM.
 *
 * Covers event -> chat mapping, the barrier that makes rotation a real security boundary,
 * idempotency under duplicate and concurrent events, and fail-closed behaviour.
 *
 * All key material is TEST-ONLY.
 */
class HistoryRotationTriggerTest {

    private companion object {
        const val USER = "user-e2"
        const val CHAT = "chat-e2"
        const val TEXT = "checkpoint-e2 canary"
    }

    private class MemoryStore : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        var saveFails = false
        override suspend fun saveHistoryKeyring(sealed: ByteArray): Result<Unit> =
            if (saveFails) Result.failure(IllegalStateException("storage failure"))
            else Result.success(Unit).also { blob = sealed.copyOf() }
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

    private class Vault(var locked: Boolean = false) : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> =
            if (locked) Result.failure(IllegalStateException("vault is locked"))
            else Result.success(byteArrayOf(0x7F) + plaintext.copyOf())
        override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> =
            if (locked) Result.failure(IllegalStateException("vault is locked"))
            else if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte())
                Result.success(sealed.copyOfRange(1, sealed.size))
            else Result.failure(IllegalStateException("not sealed"))
    }

    private class Cipher : ArchiveCipher {
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

    private class Rig {
        val store = MemoryStore()
        val vault = Vault()
        val keyring = HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val archiver = MessageArchiver(keyring, Cipher(), com.messenger.app.data.encryption.history.HistoryArchiveFeature.Enabled)
        val coordinator = HistoryRotationCoordinator(keyring, RecoveryTestSupport.disabledLazy(keyring), HistoryArchiveFeature { true })
    }

    // ------------------------------------------------------------------ event mapping

    @Test
    fun everyChatScopedEventRotatesExactlyThatChat() = runBlocking {
        val events = listOf(
            SecurityEvent.GroupMembersAdded("c1", listOf("m1")),
            SecurityEvent.GroupMemberRemoved("c2", "m1"),
            SecurityEvent.GroupLeft("c3"),
            SecurityEvent.DirectPeerIdentityAccepted("c4", "fp"),
        )
        for (e in events) {
            val chats = HistoryRotationPolicy.affectedChats(e, listOf("other-a", "other-b"))
            assertEquals("$e must touch exactly one chat", 1, chats.size)
            assertFalse("must not touch unrelated chats", chats.contains("other-a"))
        }
    }

    /** A revoked device may hold roots for any chat, so all of them rotate. */
    @Test
    fun deviceRevocationRotatesEveryKnownChat() {
        val all = listOf("c1", "c2", "c3")
        assertEquals(
            all.toSet(),
            HistoryRotationPolicy.affectedChats(SecurityEvent.DeviceRevoked("d1"), all)
        )
    }

    @Test
    fun blankIdentifiersRotateNothing() {
        assertTrue(HistoryRotationPolicy.affectedChats(SecurityEvent.GroupLeft(""), emptyList()).isEmpty())
        assertTrue(
            HistoryRotationPolicy.affectedChats(SecurityEvent.DeviceRevoked("d"), listOf("", " ".trim()))
                .isEmpty()
        )
    }

    // ------------------------------------------------------------------ basic rotation

    @Test
    fun firstRootIsVersionOneAndEachEventAddsOne() = runBlocking {
        val r = Rig()
        assertEquals(1, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)

        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "alice")).getOrThrow()
        assertEquals(2, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)

        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "bob")).getOrThrow()
        assertEquals(3, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)
    }

    @Test
    fun versionsNeverDecreaseAndOldRootsStayReadable() = runBlocking {
        val r = Rig()
        val v1 = r.keyring.ensureRoot(CHAT).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "a")).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "b")).getOrThrow()

        val loaded = r.keyring.load().getOrThrow()
        assertEquals(3, loaded.entries.count { it.chatId == CHAT })
        assertArrayEquals("version 1 must be untouched", v1.root, loaded.find(CHAT, 1)!!.root)
        assertEquals(3, loaded.latest(CHAT)!!.rootVersion)
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    fun aDuplicateEventDoesNotCreateASecondRoot() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        val e = SecurityEvent.GroupMemberRemoved(CHAT, "alice")

        assertFalse(r.coordinator.onSecurityEvent(e).getOrThrow().duplicate)
        val second = r.coordinator.onSecurityEvent(e).getOrThrow()
        assertTrue("the repeat must be recognised as a duplicate", second.duplicate)
        assertTrue(second.rotated.isEmpty())
        assertEquals(2, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)
    }

    /** Removing Alice and then Bob are DIFFERENT boundaries and must both rotate. */
    @Test
    fun distinctEventsEachRotate() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "alice")).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "bob")).getOrThrow()
        assertEquals(3, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)
    }

    @Test
    fun duplicateRevocationAndResetEventsAreDeduplicated() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot("c1").getOrThrow()
        val revoke = SecurityEvent.DeviceRevoked("device-9")
        r.coordinator.onSecurityEvent(revoke, listOf("c1")).getOrThrow()
        assertTrue(r.coordinator.onSecurityEvent(revoke, listOf("c1")).getOrThrow().duplicate)
        assertEquals(2, r.keyring.ensureRoot("c1").getOrThrow().rootVersion)
    }

    @Test
    fun concurrentIdenticalEventsRotateOnce() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        val e = SecurityEvent.GroupLeft(CHAT)
        val results = (1..8).map { async { r.coordinator.onSecurityEvent(e) } }.awaitAll()
        assertEquals("exactly one call may do the work", 1, results.count { !it.getOrThrow().duplicate })
        assertEquals(2, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)
    }

    @Test
    fun concurrentDistinctEventsEachRotateExactlyOnce() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        (1..5).map { i ->
            async { r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "m$i")) }
        }.awaitAll()
        assertEquals(6, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)
    }

    // ------------------------------------------------------------------ the barrier

    /**
     * The core security property: after a security event whose rotation FAILED, nothing may be
     * archived under the old root.
     */
    @Test
    fun aFailedRotationBlocksArchivingRatherThanUsingTheOldRoot() = runBlocking {
        val r = Rig()
        val v1 = r.keyring.ensureRoot(CHAT).getOrThrow()
        assertEquals(1, v1.rootVersion)

        r.vault.locked = true
        val result = r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "mallory"))
        assertTrue("rotation must report failure", result.isFailure)

        assertTrue("the barrier must be raised", r.keyring.isRotationRequired(CHAT))
        assertTrue(
            "archiving must refuse rather than fall back to root 1",
            r.archiver.seal(USER, CHAT, "m-after", TEXT).isFailure
        )
        assertTrue(r.keyring.ensureRoot(CHAT).isFailure)
    }

    @Test
    fun theBarrierLiftsOnlyAfterASuccessfulRotation() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.vault.locked = true
        r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft(CHAT))
        assertTrue(r.keyring.isRotationRequired(CHAT))

        // Vault comes back; the same event may be retried because it never completed.
        r.vault.locked = false
        r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft(CHAT)).getOrThrow()

        assertFalse(r.keyring.isRotationRequired(CHAT))
        assertEquals(2, r.keyring.ensureRoot(CHAT).getOrThrow().rootVersion)
        assertTrue(r.archiver.seal(USER, CHAT, "m", TEXT).isSuccess)
    }

    @Test
    fun storageFailureDuringRotationAlsoFailsClosed() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.store.saveFails = true
        assertTrue(r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft(CHAT)).isFailure)
        assertTrue(r.keyring.isRotationRequired(CHAT))
        assertTrue(r.archiver.seal(USER, CHAT, "m", TEXT).isFailure)
    }

    @Test
    fun aFailedRotationDoesNotCreateAPhantomVersion() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.store.saveFails = true
        r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft(CHAT))

        r.store.saveFails = false
        r.keyring.clearCache()
        val loaded = r.keyring.load().getOrThrow()
        assertEquals("only the durable root may exist", 1, loaded.entries.count { it.chatId == CHAT })
    }

    /** An unrelated chat must stay archivable while another chat is blocked. */
    @Test
    fun theBarrierIsPerChat() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot("blocked").getOrThrow()
        r.keyring.ensureRoot("fine").getOrThrow()
        r.vault.locked = true
        r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft("blocked"))
        r.vault.locked = false

        assertTrue(r.archiver.seal(USER, "blocked", "m", TEXT).isFailure)
        assertTrue(r.archiver.seal(USER, "fine", "m", TEXT).isSuccess)
    }

    // ------------------------------------------------------------------ archive interaction

    @Test
    fun archiveRotateArchiveKeepsBothReadableUnderTheirOwnVersions() = runBlocking {
        val r = Rig()
        val old = r.archiver.seal(USER, CHAT, "m-old", TEXT).getOrThrow()
        assertEquals(1, old.rootVersion)

        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "alice")).getOrThrow()

        val fresh = r.archiver.seal(USER, CHAT, "m-new", TEXT).getOrThrow()
        assertEquals("a post-event message must use the new root", 2, fresh.rootVersion)
        assertNotEquals(old.ciphertextB64, fresh.ciphertextB64)

        assertEquals(TEXT, r.archiver.open(USER, CHAT, "m-old", 1, old.ciphertextB64).getOrThrow())
        assertEquals(TEXT, r.archiver.open(USER, CHAT, "m-new", 2, fresh.ciphertextB64).getOrThrow())
        assertTrue(
            "the new message must not open under the retired root",
            r.archiver.open(USER, CHAT, "m-new", 1, fresh.ciphertextB64).isFailure
        )
    }

    @Test
    fun rotationImmediatelyAfterASendDoesNotDisturbTheJustSealedArchive() = runBlocking {
        val r = Rig()
        val sent = r.archiver.seal(USER, CHAT, "m1", TEXT).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft(CHAT)).getOrThrow()
        assertEquals(TEXT, r.archiver.open(USER, CHAT, "m1", sent.rootVersion, sent.ciphertextB64).getOrThrow())
    }

    @Test
    fun rotationImmediatelyBeforeASendMakesTheSendUseTheNewRoot() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupLeft(CHAT)).getOrThrow()
        assertEquals(2, r.archiver.seal(USER, CHAT, "m1", TEXT).getOrThrow().rootVersion)
    }

    @Test
    fun multipleDistinctEventsInSequenceEachAdvanceTheRoot() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        val archives = mutableListOf<Pair<String, SealedArchive>>()
        listOf(
            SecurityEvent.GroupMembersAdded(CHAT, listOf("x")),
            SecurityEvent.GroupMemberRemoved(CHAT, "y"),
            SecurityEvent.DirectPeerIdentityAccepted(CHAT, "fp-1"),
        ).forEachIndexed { i, e ->
            r.coordinator.onSecurityEvent(e).getOrThrow()
            archives.add("m$i" to r.archiver.seal(USER, CHAT, "m$i", "$TEXT-$i").getOrThrow())
        }
        assertEquals(listOf(2, 3, 4), archives.map { it.second.rootVersion })
        archives.forEachIndexed { i, (id, s) ->
            assertEquals("$TEXT-$i", r.archiver.open(USER, CHAT, id, s.rootVersion, s.ciphertextB64).getOrThrow())
        }
    }

    @Test
    fun revocationRotatesAllChatsAndEachStaysIndependentlyReadable() = runBlocking {
        val r = Rig()
        val before = listOf("c1", "c2").associateWith { c ->
            r.archiver.seal(USER, c, "m-$c", TEXT).getOrThrow()
        }
        r.coordinator.onSecurityEvent(SecurityEvent.DeviceRevoked("d1"), listOf("c1", "c2")).getOrThrow()

        for ((c, sealed) in before) {
            assertEquals(1, sealed.rootVersion)
            assertEquals(TEXT, r.archiver.open(USER, c, "m-$c", 1, sealed.ciphertextB64).getOrThrow())
            assertEquals(2, r.archiver.seal(USER, c, "m2-$c", TEXT).getOrThrow().rootVersion)
        }
    }
}
