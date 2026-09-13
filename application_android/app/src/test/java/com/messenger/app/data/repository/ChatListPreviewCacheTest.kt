package com.messenger.app.data.repository

import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat list renders the newest message of every chat on each refresh. Doing
 * that by decrypting the `last_message` ciphertext again cannot work for MLS: the
 * ratchet secret was consumed by the decrypt that first displayed the message, so
 * OpenMLS answers SecretReuseError and the row shows the encrypted placeholder
 * even while the chat itself renders the text perfectly from Room.
 *
 * How each branch is observed:
 *
 *  - Cache hit: `previewFor` returns the stored text and touches nothing else.
 *    Zero interactions with [TokenManager] prove `decryptFor` was never entered,
 *    so no ratchet secret could have been requested.
 *
 *  - Fallback: the decrypt path reaches `E2ECrypto`, whose JNA/libsodium binding
 *    has no native library under a JVM unit test and fails to initialise. That
 *    failure is exactly the evidence wanted here - it can only be reached by
 *    entering `openFor`, so it proves the cache branch did NOT short-circuit.
 *    The real decrypt is exercised on-device in the runtime checks instead.
 */
class ChatListPreviewCacheTest {

    private val chatId = "027f8fe0-94ca-4f78-9852-a373a776ae06"
    private val messageId = "2f7728cb-0000-4000-8000-000000000001"
    private val plaintext = "p18-droid2win-c9a2"
    private val ciphertextHex = "00010002ab".repeat(8)

    private val messageDao: ScopedMessageDao = mockk(relaxed = true)
    private val tokenManager: TokenManager = mockk(relaxed = true)

    private fun row(isEncrypted: Boolean, content: String) = MessageEntity(
        id = messageId,
        conversation_id = chatId,
        senderId = "ecfb93c5-0000-4000-8000-000000000002",
        content = content,
        type = "text",
        status = "SENT",
        timestamp = 1_756_000_000_000L,
        isEncrypted = isEncrypted,
        encryptionVersion = 6,
        senderDeviceId = "ead6306e-0000-4000-8000-000000000003"
    )

    private fun repository(): ChatRepository {
        coEvery { tokenManager.getCurrentUserId() } returns Result.success("owner-id")
        coEvery { tokenManager.getOrCreateDeviceId() } returns Result.success("my-device")
        return ChatRepository(
            chatApiService = mockk(relaxed = true),
            messageDao = messageDao,
            conversationDao = mockk<ScopedConversationDao>(relaxed = true),
            cachedChatDao = mockk<ScopedCachedChatDao>(relaxed = true),
            webSocketManager = mockk<WebSocketManager>(relaxed = true),
            tokenManager = tokenManager,
            groupRepository = mockk(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        )
    }

    private fun preview(repo: ChatRepository, id: String = messageId) = runBlocking {
        repo.previewFor(
            chatId = chatId,
            messageId = id,
            content = ciphertextHex,
            encrypted = true,
            senderId = "ecfb93c5-0000-4000-8000-000000000002",
            keyVersion = 0,
            encryptionVersion = 6,
            senderDeviceId = "ead6306e-0000-4000-8000-000000000003"
        )
    }

    /** True when the call got as far as the crypto layer, i.e. did not use the cache. */
    private fun reachedDecryptLayer(id: String = messageId): Boolean {
        val outcome = runCatching { preview(repository(), id) }
        val e = outcome.exceptionOrNull() ?: return false
        val trace = generateSequence(e) { it.cause }.joinToString { it.toString() }
        return trace.contains("E2ECrypto") || trace.contains("UnsatisfiedLinkError") ||
            trace.contains("jnidispatch")
    }

    /** 1. A readable cached row is returned as-is and MLS is never asked. */
    @Test
    fun readableCachedLastMessageSkipsMls() {
        coEvery { messageDao.getMessageById(messageId) } returns row(false, plaintext)

        val result = preview(repository())

        assertEquals(plaintext, result)
        coVerify(exactly = 0) { tokenManager.getCurrentUserId() }
        coVerify(exactly = 0) { tokenManager.getOrCreateDeviceId() }
    }

    /** 2. A ciphertext-only row must still take the normal decrypt path. */
    @Test
    fun encryptedCachedLastMessageFallsBackToMls() {
        coEvery { messageDao.getMessageById(messageId) } returns row(true, ciphertextHex)

        assertTrue(
            "an encrypted row must keep falling through to the decrypt path",
            reachedDecryptLayer()
        )
    }

    /** 3. No cached row at all: unchanged behaviour. */
    @Test
    fun missingCachedLastMessageFallsBackToMls() {
        coEvery { messageDao.getMessageById(messageId) } returns null

        assertTrue("a missing row must not be treated as readable", reachedDecryptLayer())
    }

    /**
     * 4. A row marked decrypted but holding no text is not readable. Treating it as
     *    readable would paint an empty preview AND suppress the only decrypt that
     *    could still recover the message.
     */
    @Test
    fun emptyCachedPlaintextDoesNotPretendReadable() {
        coEvery { messageDao.getMessageById(messageId) } returns row(false, "")

        assertTrue("blank content is not readable text", reachedDecryptLayer())
    }

    /** A blank message id cannot address a row, so it must not short-circuit. */
    @Test
    fun blankMessageIdFallsBackToMls() {
        assertTrue(reachedDecryptLayer(id = ""))
        coVerify(exactly = 0) { messageDao.getMessageById(any()) }
    }

    /**
     * Readability is a property of the row, not of who sent it: a peer's message
     * and a sibling device's message are resolved identically. The sender-device
     * question stays with [isOwnDeviceMlsMessage], which this must not duplicate.
     */
    @Test
    fun cacheHitDoesNotDependOnSender() {
        coEvery { messageDao.getMessageById(messageId) } returns
            row(false, plaintext).copy(senderDeviceId = "a-sibling-device")

        assertEquals(plaintext, preview(repository()))
        coVerify(exactly = 0) { tokenManager.getOrCreateDeviceId() }
    }
}
