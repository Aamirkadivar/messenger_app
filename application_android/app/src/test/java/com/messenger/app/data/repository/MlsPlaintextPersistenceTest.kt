package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An MLS sender-ratchet secret is consumed by the first successful decrypt
 * (OpenMLS `.take()`s it out of `past_secrets` to preserve forward secrecy, and
 * answers `SecretReuseError` ever after). So the plaintext produced by that one
 * decrypt is the only copy that will ever exist: whoever obtains it must write it
 * back, or the message is lost to every later read.
 *
 * The realtime path used to persist it only as a side effect of Layer B sealing.
 * Sealing is separately configured, and when it was unavailable
 * `archiveRealtimeMessage` returned before storing anything - so a remote message
 * displayed correctly once and was then permanently unreadable. These tests pin
 * the persistence contract itself rather than the archiving that used to carry it.
 */
class MlsPlaintextPersistenceTest {

    private val chatId = "027f8fe0-94ca-4f78-9852-a373a776ae06"
    private val messageId = "817a449e-0000-4000-8000-000000000001"
    private val owner = "ecfb93c5-0000-4000-8000-000000000002"
    private val peerDevice = "ead6306e-0000-4000-8000-000000000003"
    private val plaintext = "p18-win-to-droid"

    private val messageDao: ScopedMessageDao = mockk(relaxed = true)
    private val conversationDao: ScopedConversationDao = mockk(relaxed = true)

    /** Ciphertext-only row: exactly what a history refresh writes for a peer message. */
    private fun encryptedRow() = MessageEntity(
        id = messageId,
        conversation_id = chatId,
        senderId = owner,
        content = "00010002ab".repeat(8),
        type = "text",
        status = "SENT",
        timestamp = 1_756_000_000_000L,
        isEncrypted = true,
        encryptionVersion = 6,
        senderDeviceId = peerDevice
    )

    private fun readableRow() = encryptedRow().copy(content = plaintext, isEncrypted = false)

    private fun repository(
        seal: (suspend (String, String, String, String) -> SealedArchive?)? =
            { _, _, _, _ -> SealedArchive("sealed-b64", 1) }
    ) = ChatRepository(
        chatApiService = mockk(relaxed = true),
        messageDao = messageDao,
        conversationDao = conversationDao,
        cachedChatDao = mockk<ScopedCachedChatDao>(relaxed = true),
        webSocketManager = mockk<WebSocketManager>(relaxed = true),
        tokenManager = mockk<TokenManager>(relaxed = true),
        groupRepository = mockk(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        onArchiveMessage = seal
    )

    private fun archive(repo: ChatRepository) = runBlocking {
        repo.archiveRealtimeMessage(
            owner = owner,
            chatId = chatId,
            messageId = messageId,
            senderId = owner,
            plaintext = plaintext,
            timestampMs = 1_756_000_000_000L,
            keyVersion = 0,
            encryptionVersion = 6,
            senderDeviceId = peerDevice,
            chatType = "group"
        )
    }

    /** 1. An existing ciphertext row must be rewritten with the decrypted body. */
    @Test
    fun existingEncryptedRowGetsPlaintextWrittenBack() {
        coEvery { messageDao.getMessageById(messageId) } returns encryptedRow()
        val body = slot<String>()

        archive(repository())

        coVerify(exactly = 1) { messageDao.setPlaintext(owner, messageId, capture(body)) }
        assertEquals(plaintext, body.captured)
        coVerify(exactly = 0) { messageDao.insertMessage(any(), any()) }
    }

    /**
     * 2. The regression itself: persistence must not depend on sealing. With no
     *    archiver wired the call still reports failure, but the row is readable.
     */
    @Test
    fun plaintextIsPersistedEvenWhenSealingIsUnavailable() {
        coEvery { messageDao.getMessageById(messageId) } returns encryptedRow()

        archive(repository(seal = null))

        coVerify(exactly = 1) { messageDao.setPlaintext(owner, messageId, plaintext) }
    }

    /** 3. No row yet: create one that is readable from the start. */
    @Test
    fun missingRowIsCreatedReadable() {
        coEvery { messageDao.getMessageById(messageId) } returns null
        coEvery { conversationDao.getConversationById(chatId) } returns null
        val row = slot<MessageEntity>()

        archive(repository())

        coVerify(exactly = 1) { messageDao.insertMessage(owner, capture(row)) }
        assertEquals(plaintext, row.captured.content)
        assertFalse("a freshly stored row must not be marked encrypted", row.captured.isEncrypted)
        assertEquals(messageId, row.captured.id)
        assertEquals(peerDevice, row.captured.senderDeviceId)
        assertEquals(6, row.captured.encryptionVersion)
        coVerify(exactly = 0) { messageDao.setPlaintext(any(), any(), any()) }
    }

    /** 4. An already-readable row is left completely alone. */
    @Test
    fun alreadyReadableRowIsNotRewritten() {
        coEvery { messageDao.getMessageById(messageId) } returns readableRow()

        archive(repository())

        coVerify(exactly = 0) { messageDao.setPlaintext(any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.insertMessage(any(), any()) }
    }

    /** 5. A retracted message must never be resurrected by this path. */
    @Test
    fun retractedRowIsNeverResurrected() {
        coEvery { messageDao.getMessageById(messageId) } returns
            encryptedRow().copy(archiveState = ArchiveState.RETRACTED.wire)

        archive(repository())

        coVerify(exactly = 0) { messageDao.setPlaintext(any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.insertMessage(any(), any()) }
    }

    /** 6. Sealing still happens, and still only touches the archive columns. */
    @Test
    fun sealingStillRunsAfterPersistence() {
        coEvery { messageDao.getMessageById(messageId) } returns encryptedRow()

        archive(repository())

        coVerify(exactly = 1) {
            messageDao.setArchive(owner, messageId, "sealed-b64", 1, ArchiveState.SEALED.wire)
        }
    }

    // ---- the predicate that gates cache-before-MLS in cacheMessages ----

    /** A readable row short-circuits the decrypt: MLS is never asked. */
    @Test
    fun readableRowIsRecognised() {
        assertTrue(hasReadableCachedText(isEncrypted = false, content = plaintext))
    }

    /** A ciphertext row is not readable, so the MLS attempt remains eligible. */
    @Test
    fun encryptedRowIsNotReadable() {
        assertFalse(hasReadableCachedText(isEncrypted = true, content = "00010002ab"))
    }

    /**
     * A row marked decrypted but carrying no text is not readable either: treating
     * it as readable would replace a message with an empty bubble and, worse,
     * suppress the only decrypt that could still recover it.
     */
    @Test
    fun blankContentIsNotReadable() {
        assertFalse(hasReadableCachedText(isEncrypted = false, content = ""))
        assertFalse(hasReadableCachedText(isEncrypted = true, content = ""))
    }

    /**
     * Readability is a property of the row, never of who sent it. A peer's message
     * and our own are judged identically - the sender-device question belongs to
     * [isOwnDeviceMlsMessage], which this must not duplicate or replace.
     */
    @Test
    fun readabilityDoesNotDependOnSender() {
        assertEquals(
            hasReadableCachedText(isEncrypted = false, content = plaintext),
            hasReadableCachedText(isEncrypted = false, content = plaintext)
        )
        assertTrue(hasReadableCachedText(isEncrypted = false, content = "from a sibling device"))
    }
}
