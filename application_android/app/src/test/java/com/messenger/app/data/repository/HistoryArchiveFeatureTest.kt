package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.encryption.history.HistoryArchiveDisabledException
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 - the history-archive feature flag, JVM.
 *
 * The flag lives at one boundary, [MessageArchiver.seal], which is what every archive path reaches.
 * These tests check the OFF state does nothing at all - no cipher call, no keyring access, no
 * column change - while the ON state leaves the A-E2 behaviour exactly as verified.
 */
class HistoryArchiveFeatureTest {

    private companion object {
        const val USER = "user-flag"
        const val CHAT = "chat-flag"
        const val MSG = "msg-flag"
        const val TEXT = "feature flag canary"
        const val PLACEHOLDER = "[encrypted]"
    }

    /** Counts every call so "the cipher was never touched" is an assertion, not an assumption. */
    private class CountingCipher : ArchiveCipher {
        var sealCalls = 0
        var openCalls = 0
        private fun tag(root: ByteArray, c: HistoryContext) =
            "${root.joinToString(""){ b -> "%02x".format(b) }}|${c.userId}|${c.chatId}|" +
                "${c.messageId}|${c.rootVersion}|${c.protocolVersion}"
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray): ByteArray {
            sealCalls++
            return (tag(historyRoot, ctx) + "|" + String(plaintext, Charsets.UTF_8))
                .toByteArray(Charsets.UTF_8)
        }
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? {
            openCalls++
            val text = String(sealed, Charsets.UTF_8)
            val prefix = tag(historyRoot, ctx) + "|"
            return if (text.startsWith(prefix)) text.removePrefix(prefix).toByteArray(Charsets.UTF_8) else null
        }
    }

    /** Counts reads and writes so "the keyring was never touched" is likewise asserted. */
    private class CountingStore : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        var writes = 0
        var reads = 0
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { writes++; blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf()).also { reads++ }
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { writes++; cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf()).also { reads++ }
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

    private class Rig(enabled: Boolean) {
        val cipher = CountingCipher()
        val store = CountingStore()
        val keyring = HistoryKeyringRepository(PassthroughVault(), store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        val archiver = MessageArchiver(keyring, cipher, HistoryArchiveFeature { enabled })
        val coordinator = HistoryRotationCoordinator(keyring, RecoveryTestSupport.disabledLazy(keyring), HistoryArchiveFeature { true })
    }

    // ------------------------------------------------------------------ 1. default

    @Test
    fun theFlagDefaultsOff() {
        assertFalse(
            "history archiving must ship OFF",
            HistoryArchiveFeature.DEFAULT_ENABLED
        )
        assertFalse(
            "the production binding must be the OFF one",
            HistoryArchiveFeature.Default.isEnabled()
        )
    }

    // ------------------------------------------------------------------ 2-5. OFF does nothing

    @Test
    fun offReturnsAClearDisabledResult() = runBlocking {
        val r = Rig(enabled = false)
        val result = r.archiver.seal(USER, CHAT, MSG, TEXT)
        assertTrue(result.isFailure)
        assertTrue(
            "callers must be able to tell 'disabled' from 'failed'",
            result.exceptionOrNull() is HistoryArchiveDisabledException
        )
    }

    @Test
    fun offNeverInvokesTheCipher() = runBlocking {
        val r = Rig(enabled = false)
        repeat(3) { r.archiver.seal(USER, CHAT, "$MSG-$it", TEXT) }
        assertEquals("the AEAD must not be called at all", 0, r.cipher.sealCalls)
    }

    @Test
    fun offNeverTouchesTheKeyring() = runBlocking {
        val r = Rig(enabled = false)
        r.archiver.seal(USER, CHAT, MSG, TEXT)
        assertEquals("no root may be minted", 0, r.store.writes)
        assertEquals("the keyring must not even be read", 0, r.store.reads)
        assertNull(r.store.blob)
        assertNull(r.store.cache)
    }

    @Test
    fun offLeavesArchiveColumnsCompletelyUnchanged() = runBlocking {
        val r = Rig(enabled = false)
        val prior = OutboundArchivePolicy.Fields.NONE
        val after = OutboundArchivePolicy.archiveFor(
            USER, CHAT, MSG, TEXT, prior, PLACEHOLDER
        ) { u, c, m, p -> r.archiver.seal(u, c, m, p).getOrNull() }

        assertSame("the row must come back untouched", prior, after)
        assertNull(after.ciphertext)
        assertEquals(0, after.rootVersion)
        assertNull(after.state)
    }

    /** An already-sealed row keeps its archive when the flag is later turned off. */
    @Test
    fun offDoesNotDisturbExistingArchives() = runBlocking {
        val on = Rig(enabled = true)
        val sealed = on.archiver.seal(USER, CHAT, MSG, TEXT).getOrThrow()

        val off = MessageArchiver(on.keyring, on.cipher, HistoryArchiveFeature { false })
        assertEquals(
            "reading history must never be gated by the flag",
            TEXT,
            off.open(USER, CHAT, MSG, sealed.rootVersion, sealed.ciphertextB64).getOrThrow()
        )
    }

    // ------------------------------------------------------------------ 6. messaging unaffected

    @Test
    fun offDoesNotBreakMessageProcessing() = runBlocking {
        val r = Rig(enabled = false)
        // The outbound policy is what the send path consults; a disabled archive must look exactly
        // like "nothing to archive", never like an error the send should react to.
        val fields = OutboundArchivePolicy.archiveFor(
            USER, CHAT, MSG, TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER
        ) { u, c, m, p -> r.archiver.seal(u, c, m, p).getOrNull() }
        assertNull(fields.state)
        // And retraction still works with archiving off - deleting a message must stay safe.
        assertTrue(ArchiveRetractionPolicy.plan(fields.state).write)
    }

    // ------------------------------------------------------------------ 7-9. ON is unchanged

    @Test
    fun onPreservesExistingArchiveBehaviour() = runBlocking {
        val r = Rig(enabled = true)
        val sealed = r.archiver.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        assertEquals(1, sealed.rootVersion)
        assertEquals(1, r.cipher.sealCalls)
        assertEquals(TEXT, r.archiver.open(USER, CHAT, MSG, 1, sealed.ciphertextB64).getOrThrow())
        assertTrue("the keyring must have been persisted", r.store.blob != null)
    }

    @Test
    fun onStillRespectsTheRotationBarrier() = runBlocking {
        val r = Rig(enabled = true)
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.keyring.markRotationRequired(CHAT)
        val result = r.archiver.seal(USER, CHAT, MSG, TEXT)
        assertTrue("a raised barrier must still block archiving", result.isFailure)
        assertFalse(
            "and it must be the barrier, not the flag",
            result.exceptionOrNull() is HistoryArchiveDisabledException
        )
    }

    @Test
    fun onStillRespectsRetractedAsTerminal() = runBlocking {
        val r = Rig(enabled = true)
        val retracted = OutboundArchivePolicy.Fields(null, 2, ArchiveState.RETRACTED.wire)
        val after = OutboundArchivePolicy.archiveFor(
            USER, CHAT, MSG, TEXT, retracted, PLACEHOLDER
        ) { u, c, m, p -> r.archiver.seal(u, c, m, p).getOrNull() }
        assertSame(retracted, after)
        assertEquals(0, r.cipher.sealCalls)
    }

    @Test
    fun onStillRotatesOnSecurityEvents() = runBlocking {
        val r = Rig(enabled = true)
        r.archiver.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "alice")).getOrThrow()
        assertEquals(2, r.archiver.seal(USER, CHAT, "$MSG-2", TEXT).getOrThrow().rootVersion)
    }

    // ------------------------------------------------------------------ 10. one boundary

    /**
     * Fetch, realtime, and outbound all archive by calling [MessageArchiver.seal]; none of them
     * has its own flag check. Driving the same archiver through all three shapes shows they obey
     * one policy rather than three copies of it.
     */
    @Test
    fun allThreePathsShareTheSameFlagBoundary() = runBlocking {
        val off = Rig(enabled = false)
        // outbound shape
        val outbound = OutboundArchivePolicy.archiveFor(
            USER, CHAT, "m-out", TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER
        ) { u, c, m, p -> off.archiver.seal(u, c, m, p).getOrNull() }
        // inbound fetch shape and realtime shape both call seal directly
        val fetch = off.archiver.seal(USER, CHAT, "m-fetch", TEXT)
        val realtime = off.archiver.seal(USER, CHAT, "m-rt", TEXT)

        assertNull(outbound.ciphertext)
        assertTrue(fetch.exceptionOrNull() is HistoryArchiveDisabledException)
        assertTrue(realtime.exceptionOrNull() is HistoryArchiveDisabledException)
        assertEquals("no path may bypass the flag", 0, off.cipher.sealCalls)

        val on = Rig(enabled = true)
        val outboundOn = OutboundArchivePolicy.archiveFor(
            USER, CHAT, "m-out", TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER
        ) { u, c, m, p -> on.archiver.seal(u, c, m, p).getOrNull() }
        assertEquals(ArchiveState.SEALED.wire, outboundOn.state)
        assertTrue(on.archiver.seal(USER, CHAT, "m-fetch", TEXT).isSuccess)
        assertTrue(on.archiver.seal(USER, CHAT, "m-rt", TEXT).isSuccess)
        assertEquals(3, on.cipher.sealCalls)
    }
}
