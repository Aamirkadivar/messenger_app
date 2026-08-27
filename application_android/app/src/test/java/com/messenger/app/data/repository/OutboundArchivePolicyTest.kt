package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT D - outbound archiving, JVM.
 *
 * Two halves. The guard rules are tested against [OutboundArchivePolicy] directly, so no Retrofit
 * or Room is needed. The context binding is tested end-to-end through a real
 * [HistoryKeyringRepository] and [MessageArchiver] with an outbound-shaped context, which is what
 * proves an outbound archive cannot be opened as some other message.
 *
 * All key material is TEST-ONLY.
 */
class OutboundArchivePolicyTest {

    private companion object {
        const val USER = "user-out"
        const val CHAT = "chat-out"
        const val MSG = "server-assigned-id"
        const val TEXT = "checkpoint-d outbound canary"
        const val PLACEHOLDER = "[encrypted]"
    }

    // ------------------------------------------------------------------ guards

    private fun sealer(
        calls: MutableList<String> = mutableListOf(),
        result: SealedArchive? = SealedArchive("Y2lwaGVy", 4),
        throws: Boolean = false,
    ): suspend (String, String, String, String) -> SealedArchive? = { u, c, m, _ ->
        calls.add("$u|$c|$m")
        if (throws) throw IllegalStateException("vault is locked") else result
    }

    private suspend fun run(
        userId: String? = USER,
        chatId: String = CHAT,
        messageId: String = MSG,
        plaintext: String = TEXT,
        prior: OutboundArchivePolicy.Fields = OutboundArchivePolicy.Fields.NONE,
        seal: suspend (String, String, String, String) -> SealedArchive? = sealer(),
    ) = OutboundArchivePolicy.archiveFor(
        userId, chatId, messageId, plaintext, prior, PLACEHOLDER, seal
    )

    @Test
    fun aSuccessfulSendProducesASealedArchive() = runBlocking {
        val fields = run()
        assertEquals("Y2lwaGVy", fields.ciphertext)
        assertEquals(4, fields.rootVersion)
        assertEquals(ArchiveState.SEALED.wire, fields.state)
    }

    @Test
    fun theAuthoritativeMessageIdIsWhatGetsSealed() = runBlocking {
        val calls = mutableListOf<String>()
        run(seal = sealer(calls))
        assertEquals(listOf("$USER|$CHAT|$MSG"), calls)
    }

    /** A locked vault must cost the archive, never the send. */
    @Test
    fun aLockedVaultLeavesTheRowUnchanged() = runBlocking {
        val prior = OutboundArchivePolicy.Fields.NONE
        val fields = run(prior = prior, seal = sealer(result = null))
        assertSame("the row must come back untouched", prior, fields)
        assertNull(fields.ciphertext)
        assertNull(fields.state)
    }

    @Test
    fun aThrowingSealerLeavesTheRowUnchanged() = runBlocking {
        val fields = run(seal = sealer(throws = true))
        assertNull("an exception must not propagate into the send path", fields.ciphertext)
        assertEquals(0, fields.rootVersion)
    }

    @Test
    fun archivingIsSkippedWithoutAnOwningUser() = runBlocking {
        val calls = mutableListOf<String>()
        for (bad in listOf(null, "", "   ")) {
            assertNull(run(userId = bad, seal = sealer(calls)).ciphertext)
        }
        assertTrue("sealing must not even be attempted", calls.isEmpty())
    }

    @Test
    fun archivingIsSkippedWithoutAnAuthoritativeId() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(run(messageId = "", seal = sealer(calls)).ciphertext)
        assertNull(run(chatId = "", seal = sealer(calls)).ciphertext)
        assertTrue("no invented id may ever be sealed", calls.isEmpty())
    }

    @Test
    fun placeholderAndBlankTextAreNotArchived() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(run(plaintext = "", seal = sealer(calls)).ciphertext)
        assertNull(run(plaintext = "   ", seal = sealer(calls)).ciphertext)
        assertNull(run(plaintext = PLACEHOLDER, seal = sealer(calls)).ciphertext)
        assertTrue(calls.isEmpty())
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    fun anAlreadySealedRowIsNeverResealed() = runBlocking {
        val calls = mutableListOf<String>()
        val prior = OutboundArchivePolicy.Fields("existing", 2, ArchiveState.SEALED.wire)
        val fields = run(prior = prior, seal = sealer(calls))
        assertSame(prior, fields)
        assertEquals("existing", fields.ciphertext)
        assertEquals("the original root version must not rotate", 2, fields.rootVersion)
        assertTrue("no second seal may occur", calls.isEmpty())
    }

    /** The resurrection guard: a retracted row must never regain an archive. */
    @Test
    fun aRetractedRowIsNeverResealed() = runBlocking {
        val calls = mutableListOf<String>()
        val prior = OutboundArchivePolicy.Fields(null, 3, ArchiveState.RETRACTED.wire)
        val fields = run(prior = prior, seal = sealer(calls))
        assertSame(prior, fields)
        assertEquals(ArchiveState.RETRACTED.wire, fields.state)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun repeatedInvocationIsStable() = runBlocking {
        val first = run()
        val second = run(prior = first)
        assertSame("a second pass must be a no-op", first, second)
    }

    @Test
    fun aPendingRowIsStillArchivable() = runBlocking {
        val prior = OutboundArchivePolicy.Fields(null, 0, ArchiveState.PENDING.wire)
        val fields = run(prior = prior)
        assertEquals(ArchiveState.SEALED.wire, fields.state)
    }

    // ------------------------------------------------------------------ real binding

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

    private fun realArchiver(): MessageArchiver =
        MessageArchiver(
            HistoryKeyringRepository(PassthroughVault(), MemoryStore(), com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true }),
            ContextCheckingCipher(),
            com.messenger.app.data.encryption.history.HistoryArchiveFeature.Enabled
        )

    /** An outbound archive must open only under the exact context it was sent with. */
    @Test
    fun anOutboundArchiveIsBoundToItsFullContext() = runBlocking {
        val a = realArchiver()
        val fields = OutboundArchivePolicy.archiveFor(
            USER, CHAT, MSG, TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER
        ) { u, c, m, p -> a.seal(u, c, m, p).getOrNull() }

        assertEquals(ArchiveState.SEALED.wire, fields.state)
        val ct = fields.ciphertext!!
        val v = fields.rootVersion

        assertEquals(TEXT, a.open(USER, CHAT, MSG, v, ct).getOrThrow())
        assertTrue("wrong message id", a.open(USER, CHAT, "other-id", v, ct).isFailure)
        assertTrue("wrong user", a.open("someone-else", CHAT, MSG, v, ct).isFailure)
        assertTrue("wrong chat", a.open(USER, "other-chat", MSG, v, ct).isFailure)
        assertTrue("wrong root version", a.open(USER, CHAT, MSG, v + 1, ct).isFailure)
    }

    @Test
    fun theArchiveNeverContainsThePlaintext() = runBlocking {
        val a = realArchiver()
        val fields = OutboundArchivePolicy.archiveFor(
            USER, CHAT, MSG, TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER
        ) { u, c, m, p -> a.seal(u, c, m, p).getOrNull() }
        val decoded = String(java.util.Base64.getDecoder().decode(fields.ciphertext!!), Charsets.UTF_8)
        // The stand-in cipher is not real encryption, so assert on the STORED field, which is what
        // reaches Room. Real confidentiality is proven in Gate 2 against libsodium.
        assertTrue(
            "the archive column must not hold readable plaintext",
            !fields.ciphertext!!.contains(TEXT)
        )
        assertTrue("sanity: the fake round-trips", decoded.endsWith(TEXT))
    }

    @Test
    fun twoOutboundMessagesInTheSameChatShareARootButNotACiphertext() = runBlocking {
        val a = realArchiver()
        val seal: suspend (String, String, String, String) -> SealedArchive? =
            { u, c, m, p -> a.seal(u, c, m, p).getOrNull() }
        val one = OutboundArchivePolicy.archiveFor(
            USER, CHAT, "id-1", TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER, seal
        )
        val two = OutboundArchivePolicy.archiveFor(
            USER, CHAT, "id-2", TEXT, OutboundArchivePolicy.Fields.NONE, PLACEHOLDER, seal
        )
        assertEquals("same chat, same root version", one.rootVersion, two.rootVersion)
        assertNotEquals(
            "identical text under different message ids must not collide",
            one.ciphertext,
            two.ciphertext
        )
    }
}
