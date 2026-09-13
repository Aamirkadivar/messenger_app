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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 25 found that a locked vault silently stops archiving: the seal fails, the archive columns
 * are left untouched, and the only evidence is a log line. The row stays eligible, so the existing
 * refresh-triggered pass retries it - but nothing above the repository could say so.
 *
 * [messageArchiveStatus] closes that gap by reading the columns the row already carries. It is
 * derived, never stored, so there is no second source of truth and no migration.
 *
 * The status shares [isArchivable] with the archiving pass itself, which is the property that makes
 * PENDING meaningful: a message reported PENDING is exactly a message the next pass will attempt.
 */
class MessageArchiveStatusTest {

    private val plaintext = "P26-eligible-text"
    private val ciphertextHex = "00010002ab".repeat(8)

    private fun status(
        isEncrypted: Boolean = false,
        content: String = plaintext,
        fileType: String? = "text",
        archiveState: String? = null,
        archiveCiphertext: String? = null,
    ) = messageArchiveStatus(isEncrypted, content, fileType, archiveState, archiveCiphertext)

    // ------------------------------------------------------- 1. not applicable

    /** An unreadable row cannot be archived: there is no plaintext to seal. */
    @Test
    fun encryptedMessageIsNotApplicable() {
        assertEquals(
            MessageArchiveStatus.NOT_APPLICABLE,
            status(isEncrypted = true, content = ciphertextHex)
        )
    }

    /** Media is carried by the attachment pipeline, not Layer B. */
    @Test
    fun mediaMessageIsNotApplicable() {
        for (t in listOf("audio", "image", "file", "video_note")) {
            assertEquals(
                "content type $t must not be reported as archivable",
                MessageArchiveStatus.NOT_APPLICABLE,
                status(fileType = t)
            )
        }
    }

    /** Blank content and the encrypted placeholder are not messages worth sealing. */
    @Test
    fun blankAndPlaceholderAreNotApplicable() {
        assertEquals(MessageArchiveStatus.NOT_APPLICABLE, status(content = ""))
        assertEquals(
            MessageArchiveStatus.NOT_APPLICABLE,
            status(content = ChatRepository.ENCRYPTED_PLACEHOLDER)
        )
    }

    /**
     * A delete-for-everyone tombstone is terminal. Reporting it pending would invite a later pass to
     * seal it again and resurrect a message the user erased for everyone.
     */
    @Test
    fun retractedIsNotApplicableEvenThoughTheRowIsReadable() {
        assertEquals(
            MessageArchiveStatus.NOT_APPLICABLE,
            status(archiveState = ArchiveState.RETRACTED.wire, archiveCiphertext = "c2VhbGVk")
        )
    }

    // ------------------------------------------------------------- 2. pending

    /** The gap this phase closes: eligible, readable, and not archived. */
    @Test
    fun eligibleTextWithNoArchiveIsPending() {
        assertEquals(MessageArchiveStatus.PENDING, status())
        assertEquals(MessageArchiveStatus.PENDING, status(archiveState = ArchiveState.NONE.wire))
    }

    /**
     * What a locked vault actually leaves behind. The seal failed, the columns were not touched, and
     * the row is still eligible - so it reports PENDING, not a sealed or failed state.
     */
    @Test
    fun aFailedSealLeavesTheMessagePending() {
        assertEquals(
            MessageArchiveStatus.PENDING,
            status(archiveState = null, archiveCiphertext = null)
        )
    }

    /** Sealed but carrying nothing is not archived, and must not claim to be. */
    @Test
    fun sealedWithoutCiphertextIsNotReportedSealed() {
        assertEquals(
            MessageArchiveStatus.PENDING,
            status(archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "")
        )
    }

    // -------------------------------------------------------------- 3. sealed

    @Test
    fun sealedWithCiphertextIsSealed() {
        assertEquals(
            MessageArchiveStatus.SEALED,
            status(archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk")
        )
    }

    /** Coverage is a property of the row, not of who sent it or which version encrypted it. */
    @Test
    fun statusDoesNotDependOnSender() {
        assertEquals(
            MessageArchiveStatus.SEALED,
            status(archiveState = ArchiveState.SEALED.wire, archiveCiphertext = "c2VhbGVk", content = "from a peer")
        )
    }

    // ------------------------------- 4-5. locked vault, then a later retry

    /**
     * The whole transition, end to end, against the real [MessageArchiver]:
     *
     *   locked vault -> seal fails -> plaintext untouched -> PENDING
     *   -> vault available -> the SAME eligibility gate -> seal succeeds -> SEALED
     *
     * No scheduler and no worker: the retry is simply the next pass re-evaluating an eligible row,
     * which is exactly what `cacheMessages` already does.
     */
    @Test
    fun lockedVaultLeavesPendingAndALaterRetrySeals() = runBlocking {
        val store = MemoryKeyringStore()
        val user = "1a9713a1-0000-4000-8000-000000000001"
        val chat = "027f8fe0-94ca-4f78-9852-a373a776ae06"
        val message = "aaaaaaaa-0000-4000-8000-000000000001"

        // --- vault locked
        val locked = MessageArchiver(
            HistoryKeyringRepository(LockedVault(), store, HistoryUserProvider { user }, HistoryArchiveFeature { true }),
            BindingCipher(),
            HistoryArchiveFeature { true },
        )
        val firstAttempt = locked.seal(user, chat, message, plaintext)
        assertTrue("a locked vault must fail sealing", firstAttempt.isFailure)

        // The row is untouched by the failure: still readable, still unarchived.
        assertEquals(MessageArchiveStatus.PENDING, status(archiveState = null, archiveCiphertext = null))

        // --- vault available; same eligibility gate, same row
        val unlocked = MessageArchiver(
            HistoryKeyringRepository(PassthroughVault(), store, HistoryUserProvider { user }, HistoryArchiveFeature { true }),
            BindingCipher(),
            HistoryArchiveFeature { true },
        )
        val retry = unlocked.seal(user, chat, message, plaintext).getOrThrow()

        assertEquals(
            MessageArchiveStatus.SEALED,
            status(archiveState = ArchiveState.SEALED.wire, archiveCiphertext = retry.ciphertextB64)
        )

        // --- 6. and the restore path still opens what was sealed
        assertEquals(
            plaintext,
            unlocked.open(user, chat, message, retry.rootVersion, retry.ciphertextB64).getOrNull()
        )
    }

    /** Archiving disabled by flag is a policy state, and must not be mistaken for a fault. */
    @Test
    fun archivingDisabledStillReportsPendingNotSealed() = runBlocking {
        val archiver = MessageArchiver(
            HistoryKeyringRepository(
                PassthroughVault(), MemoryKeyringStore(),
                HistoryUserProvider { "u" }, HistoryArchiveFeature { true },
            ),
            BindingCipher(),
            HistoryArchiveFeature { false },
        )
        val outcome = archiver.seal("u", "c", "m", plaintext)
        assertTrue(outcome.isFailure)
        assertEquals(MessageArchiveStatus.PENDING, status())
    }

    // ------------------------------------------------------------- test rig

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
}
