package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryCrypto
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Layer B is the only way back to a message whose MLS ratchet secret is spent: OpenMLS drops that
 * secret on the first successful decrypt, so the ciphertext on the row can never be reopened. Until
 * now the archive was written and never read - `MessageArchiver.open` had no production caller.
 *
 * These tests pin the two halves of the restore decision:
 *
 *  - [archiveIsRestorable], the gate that decides whether the fallback may run at all;
 *  - [MessageArchiver.open], the crypto that turns a sealed record back into text.
 *
 * The real AEAD is exercised on-device by `HistoryMessageCipherAeadTest`; a JVM test cannot load
 * libsodium, so the cipher here is a deliberately simple stand-in that still binds the key and the
 * associated data, which is what these cases are about.
 */
class ArchiveRestoreTest {

    private val user = "1a9713a1-0000-4000-8000-000000000001"
    private val chat = "027f8fe0-94ca-4f78-9852-a373a776ae06"
    private val message = "ca12d119-0000-4000-8000-000000000002"
    private val plaintext = "P22-WIN-TO-DROID-e7f8"

    // ---------------------------------------------------------------- the gate

    /** A sealed row carrying ciphertext is the one and only restorable shape. */
    @Test
    fun sealedRowWithCiphertextIsRestorable() {
        assertTrue(archiveIsRestorable(ArchiveState.SEALED.wire, "c2VhbGVk"))
    }

    /** Never archived: the message keeps its ordinary failure, and stays retryable. */
    @Test
    fun rowWithNoArchiveIsNotRestorable() {
        assertFalse(archiveIsRestorable(ArchiveState.NONE.wire, null))
        assertFalse(archiveIsRestorable(null, null))
        assertFalse(archiveIsRestorable(null, "c2VhbGVk"))
    }

    /**
     * A delete-for-everyone tombstone must never be reopened. This is the most damaging thing the
     * fallback could do, so it is asserted separately from the ordinary "no archive" case.
     */
    @Test
    fun retractedRowIsNeverRestorable() {
        assertFalse(archiveIsRestorable(ArchiveState.RETRACTED.wire, "c2VhbGVk"))
    }

    /** Sealed but empty carries nothing to open. */
    @Test
    fun sealedRowWithoutCiphertextIsNotRestorable() {
        assertFalse(archiveIsRestorable(ArchiveState.SEALED.wire, ""))
        assertFalse(archiveIsRestorable(ArchiveState.SEALED.wire, null))
    }

    /** PENDING means plaintext was known but never sealed; there is nothing to open. */
    @Test
    fun pendingRowIsNotRestorable() {
        assertFalse(archiveIsRestorable(ArchiveState.PENDING.wire, "c2VhbGVk"))
    }

    // ------------------------------------------------------------- the crypto

    /**
     * Binds key and AAD the way the real cipher does, so wrong-key and wrong-context cases fail
     * here for the same reason they fail on-device.
     */
    private class BindingCipher : ArchiveCipher {
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) =
            HistoryCrypto.aad(ctx) + byteArrayOf(0) + historyRoot + byteArrayOf(0) + plaintext

        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? {
            val prefix = HistoryCrypto.aad(ctx) + byteArrayOf(0) + historyRoot + byteArrayOf(0)
            if (sealed.size < prefix.size) return null
            if (!sealed.copyOfRange(0, prefix.size).contentEquals(prefix)) return null
            return sealed.copyOfRange(prefix.size, sealed.size)
        }
    }

    private fun archiver(): MessageArchiver {
        val keyring = HistoryKeyringRepository(
            PassthroughVault(),
            MemoryKeyringStore(),
            HistoryUserProvider { user },
            HistoryArchiveFeature { true },
        )
        return MessageArchiver(keyring, BindingCipher(), HistoryArchiveFeature { true })
    }

    /** The decisive property: seal then open returns exactly the original text. */
    @Test
    fun sealedArchiveReopensToTheOriginalPlaintext() = runBlocking {
        val a = archiver()
        val sealed = a.seal(user, chat, message, plaintext).getOrThrow()

        val reopened = a.open(user, chat, message, sealed.rootVersion, sealed.ciphertextB64)

        assertEquals(plaintext, reopened.getOrNull())
    }

    /** A flipped bit must fail authentication, not return damaged text. */
    @Test
    fun tamperedArchiveIsRejected() = runBlocking {
        val a = archiver()
        val sealed = a.seal(user, chat, message, plaintext).getOrThrow()
        val raw = Base64.getDecoder().decode(sealed.ciphertextB64)
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x01).toByte()
        val tampered = Base64.getEncoder().encodeToString(raw)

        val reopened = a.open(user, chat, message, sealed.rootVersion, tampered)

        assertTrue("a tampered archive must not open", reopened.isFailure)
        assertEquals(null, reopened.getOrNull())
    }

    /** A record addressed to another message must not open under this one's context. */
    @Test
    fun archiveOfAnotherMessageDoesNotOpen() = runBlocking {
        val a = archiver()
        val sealed = a.seal(user, chat, message, plaintext).getOrThrow()

        val reopened = a.open(user, chat, "99999999-0000-4000-8000-00000000000f", sealed.rootVersion, sealed.ciphertextB64)

        assertTrue(reopened.isFailure)
    }

    /** A root version this device does not hold fails cleanly rather than guessing. */
    @Test
    fun unknownRootVersionFailsCleanly() = runBlocking {
        val a = archiver()
        val sealed = a.seal(user, chat, message, plaintext).getOrThrow()

        val reopened = a.open(user, chat, message, sealed.rootVersion + 41, sealed.ciphertextB64)

        assertTrue(reopened.isFailure)
    }

    /** Garbage in the ciphertext column is a clean failure, not a crash. */
    @Test
    fun malformedCiphertextFailsCleanly() = runBlocking {
        val a = archiver()
        a.seal(user, chat, message, plaintext).getOrThrow()

        val reopened = a.open(user, chat, message, 1, "not-base-64!!")

        assertTrue(reopened.isFailure)
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

    /** Exactly what a signed-in device with a locked vault looks like to the archive layer. */
    private class LockedVault : HistoryKeyringVault {
        override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("history keyring cannot be sealed: vault is locked"))
        override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("vault is locked"))
    }

    private class MemoryKeyringStore : HistoryKeyringStore {
        private var blob: ByteArray? = null
        private var cache: ByteArray? = null
        private var generation: Long? = null
        override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring(owner: String) = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring(owner: String) = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(owner: String, plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache(owner: String) = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache(owner: String) = Result.success(Unit).also { cache = null }
        override suspend fun saveHistoryKeyringGeneration(owner: String, generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration(owner: String) = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration(owner: String) =
            Result.success(Unit).also { generation = null }
    }

    // ------------------------------------------- vault-locked observability

    /**
     * A locked vault must not look like a successful archive. Before this phase the reason was
     * discarded inside `sealAndUpload`, so archiving appeared enabled while nothing was stored.
     */
    @Test
    fun aLockedVaultFailsSealingWithAStatedReason() = runBlocking {
        val keyring = HistoryKeyringRepository(
            LockedVault(), MemoryKeyringStore(), HistoryUserProvider { user }, HistoryArchiveFeature { true },
        )
        val a = MessageArchiver(keyring, BindingCipher(), HistoryArchiveFeature { true })

        val outcome = a.seal(user, chat, message, plaintext)

        assertTrue("a locked vault must fail sealing", outcome.isFailure)
        val why = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue("the reason must name the locked vault, got: $why", why.contains("locked"))
        assertFalse("the reason must never carry the message text", why.contains(plaintext))
    }

    /** The same rig with an unlocked vault seals and reopens, so the test above is not vacuous. */
    @Test
    fun anUnlockedVaultSealsAndReopens() = runBlocking {
        val a = archiver()
        val sealed = a.seal(user, chat, message, plaintext)
        assertTrue(sealed.isSuccess)
        assertEquals(plaintext, a.open(user, chat, message, sealed.getOrThrow().rootVersion, sealed.getOrThrow().ciphertextB64).getOrNull())
    }
}
