package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 3 CHECKPOINT C - the shared archive use-case, JVM.
 *
 * The real AEAD is proven in Gate 2 against libsodium. The fake here is context-sensitive on
 * purpose: it records the exact root and [HistoryContext] used to seal and refuses to open under
 * anything different. That means these tests check what this class is actually responsible for -
 * selecting the right root and binding the right context - rather than re-testing XChaCha20.
 *
 * All key material is TEST-ONLY.
 */
class MessageArchiverTest {

    /**
     * Reversible, context-checking stand-in. Sealed form is
     * `root|user|chat|message|rootVersion|protocol|plaintext`, so opening under any different
     * context or root fails exactly as a real AEAD would.
     */
    private class ContextCheckingCipher : ArchiveCipher {
        var sealCalls = 0
        var failSealing = false

        private fun tag(root: ByteArray, c: HistoryContext) =
            "${root.joinToString(""){ b -> "%02x".format(b) }}|${c.userId}|${c.chatId}|" +
                "${c.messageId}|${c.rootVersion}|${c.protocolVersion}"

        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray): ByteArray? {
            sealCalls++
            if (failSealing) return null
            return (tag(historyRoot, ctx) + "|" + String(plaintext, Charsets.UTF_8))
                .toByteArray(Charsets.UTF_8)
        }

        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? {
            val text = String(sealed, Charsets.UTF_8)
            val prefix = tag(historyRoot, ctx) + "|"
            if (!text.startsWith(prefix)) return null // wrong root or wrong context
            return text.removePrefix(prefix).toByteArray(Charsets.UTF_8)
        }
    }

    private class PassthroughVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x7F) + plaintext.copyOf())

        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x7F.toByte()) {
                Result.success(sealed.copyOfRange(1, sealed.size))
            } else {
                Result.failure(IllegalStateException("not sealed"))
            }
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

    private class LockedVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("vault is locked"))
        override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("vault is locked"))
    }

    private companion object {
        const val USER = "user-1"
        const val CHAT = "chat-1"
        const val MSG = "msg-1"
        const val TEXT = "checkpoint-c archive canary"
    }

    private fun archiver(
        cipher: ArchiveCipher = ContextCheckingCipher(),
        vault: HistoryKeyringVault = PassthroughVault(),
        store: HistoryKeyringStore = MemoryStore(),
    ): Pair<MessageArchiver, HistoryKeyringRepository> {
        val keyring = HistoryKeyringRepository(vault, store, com.messenger.app.data.encryption.history.HistoryUserProvider { "test-user" }, HistoryArchiveFeature { true })
        return MessageArchiver(keyring, cipher, com.messenger.app.data.encryption.history.HistoryArchiveFeature.Enabled) to keyring
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun sealThenOpenReturnsTheOriginalText() = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        assertEquals(1, sealed.rootVersion)
        assertTrue(sealed.ciphertextB64.isNotBlank())

        val opened = a.open(USER, CHAT, MSG, sealed.rootVersion, sealed.ciphertextB64).getOrThrow()
        assertEquals(TEXT, opened)
    }

    /**
     * The explicit `: Unit` is load-bearing. Without it Kotlin infers the trailing decode's
     * ByteArray as the return type and JUnit rejects the entire class with "should be void" before
     * running anything - it compiles either way, so only running the tests catches it.
     */
    @Test
    fun ciphertextIsBase64AndNotThePlaintext(): Unit = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        assertTrue(
            "must not store plaintext in the archive field",
            !sealed.ciphertextB64.contains(TEXT)
        )
        // Throws IllegalArgumentException if the field is not valid Base64.
        assertTrue(java.util.Base64.getDecoder().decode(sealed.ciphertextB64).isNotEmpty())
    }

    // ------------------------------------------------------------------ context binding

    @Test
    fun openingUnderADifferentUserFails() = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        assertTrue(a.open("user-2", CHAT, MSG, sealed.rootVersion, sealed.ciphertextB64).isFailure)
    }

    @Test
    fun openingUnderADifferentChatFails() = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        // Different chat also means a different root, so this fails twice over.
        assertTrue(a.open(USER, "chat-2", MSG, sealed.rootVersion, sealed.ciphertextB64).isFailure)
    }

    @Test
    fun openingUnderADifferentMessageIdFails() = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        assertTrue(a.open(USER, CHAT, "msg-2", sealed.rootVersion, sealed.ciphertextB64).isFailure)
    }

    @Test
    fun openingUnderADifferentRootVersionFails() = runBlocking {
        val (a, keyring) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        keyring.rotate(CHAT).getOrThrow() // version 2 now exists and is holdable
        assertTrue(
            "root version is bound into key and AAD",
            a.open(USER, CHAT, MSG, 2, sealed.ciphertextB64).isFailure
        )
    }

    @Test
    fun openingWithAVersionThisDeviceLacksFails() = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        val result = a.open(USER, CHAT, MSG, 99, sealed.ciphertextB64)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("does not hold root version"))
    }

    @Test
    fun tamperedCiphertextFails() = runBlocking {
        val (a, _) = archiver()
        val sealed = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        val raw = java.util.Base64.getDecoder().decode(sealed.ciphertextB64)
        raw[0] = (raw[0].toInt() xor 0xFF).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)
        assertTrue(a.open(USER, CHAT, MSG, sealed.rootVersion, tampered).isFailure)
    }

    @Test
    fun malformedBase64Fails() = runBlocking {
        val (a, _) = archiver()
        a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        val result = a.open(USER, CHAT, MSG, 1, "!!!not base64!!!")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Base64"))
    }

    // ------------------------------------------------------------------ root isolation

    @Test
    fun differentChatsUseDifferentRoots() = runBlocking {
        val cipher = ContextCheckingCipher()
        val (a, _) = archiver(cipher = cipher)
        val one = a.seal(USER, "chat-a", MSG, TEXT).getOrThrow()
        val two = a.seal(USER, "chat-b", MSG, TEXT).getOrThrow()
        assertNotEquals(
            "the same text in two chats must not produce the same archive",
            one.ciphertextB64,
            two.ciphertextB64
        )
    }

    @Test
    fun messagesSealedAfterRotationRecordTheNewVersion() = runBlocking {
        val (a, keyring) = archiver()
        val before = a.seal(USER, CHAT, "m-before", TEXT).getOrThrow()
        keyring.rotate(CHAT).getOrThrow()
        val after = a.seal(USER, CHAT, "m-after", TEXT).getOrThrow()

        assertEquals(1, before.rootVersion)
        assertEquals(2, after.rootVersion)

        // Both remain openable: rotation adds a version, it does not remove the old one.
        assertEquals(TEXT, a.open(USER, CHAT, "m-before", 1, before.ciphertextB64).getOrThrow())
        assertEquals(TEXT, a.open(USER, CHAT, "m-after", 2, after.ciphertextB64).getOrThrow())
    }

    // ------------------------------------------------------------------ failure policy

    @Test
    fun aLockedVaultMeansNoArchiveAndNoPlaintext() = runBlocking {
        val cipher = ContextCheckingCipher()
        val (a, _) = archiver(cipher = cipher, vault = LockedVault())
        val result = a.seal(USER, CHAT, MSG, TEXT)
        assertTrue(result.isFailure)
        assertEquals("sealing must not even be attempted without a root", 0, cipher.sealCalls)
    }

    @Test
    fun aFailedSealSurfacesAsFailureNeverAsPlaintext() = runBlocking {
        val cipher = ContextCheckingCipher().also { it.failSealing = true }
        val (a, _) = archiver(cipher = cipher)
        val result = a.seal(USER, CHAT, MSG, TEXT)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("archive sealing failed"))
    }

    @Test
    fun emptyIdentifiersAreRejected() {
        val (a, _) = archiver()
        for (bad in listOf(
            Triple("", CHAT, MSG),
            Triple(USER, "", MSG),
            Triple(USER, CHAT, ""),
        )) {
            var threw = false
            try {
                runBlocking { a.seal(bad.first, bad.second, bad.third, TEXT) }
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("must reject empty identifiers: $bad", threw)
        }
    }

    @Test
    fun sealingIsRepeatableForTheSameMessage() = runBlocking {
        // The archiver itself is stateless; de-duplication is the caller's job (ChatRepository
        // checks ArchiveState). Sealing twice must at least stay consistent and openable.
        val (a, _) = archiver()
        val first = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        val second = a.seal(USER, CHAT, MSG, TEXT).getOrThrow()
        assertEquals(first.rootVersion, second.rootVersion)
        assertEquals(TEXT, a.open(USER, CHAT, MSG, second.rootVersion, second.ciphertextB64).getOrThrow())
    }
}
