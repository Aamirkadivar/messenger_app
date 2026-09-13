package com.messenger.app.data.repository

import com.messenger.app.data.local.dao.ScopedOutboxDao
import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.OutboxState
import com.messenger.app.data.model.SendMessageRequest
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.di.AppModule
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 71 - Android criterion 11 at the repository boundary.
 *
 * What a (re)created ChatViewModel shows for a chat's unsent messages comes from
 * [ChatRepository.pendingOutbox], i.e. from the durable outbox rows - not from anything a previous
 * ViewModel held. A repository that never saw these messages being composed (as after a process
 * restart) still returns every one of them, in order, with its state; only the text is absent,
 * because the outbox deliberately never stores plaintext.
 *
 * Real persistence across a process restart is proven on-device by OutboxDurabilityTest.
 */
class OutboxRehydrateTest {

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getCurrentUserId(): Result<String?> = Result.success("owner-id")
        override suspend fun getOrCreateDeviceId(): Result<String> = Result.success("my-device")
    }

    private companion object {
        const val CHAT = "chat-under-test"
    }

    private val json = AppModule.provideJson()

    private fun row(cmid: String, state: String, createdAt: Long, replyTo: String = "") = OutboxEntity(
        clientMessageId = cmid, chatId = CHAT, chatType = "direct",
        requestJson = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(chatId = CHAT, content = "a1b2c3", encrypted = true, encryptionVersion = 4,
                replyToId = replyTo, clientMessageId = cmid)
        ),
        state = state, createdAt = createdAt, attempts = if (state == OutboxState.FAILED) 1 else 3,
        nextAttemptAt = createdAt, lastError = if (state == OutboxState.FAILED) "HTTP 403" else "ConnectException",
        serverMessageId = null, acceptedAt = null
    )

    @Test
    fun aFreshConsumerRehydratesQueuedMessagesFromTheDurableOutbox() = runBlocking {
        val dao = mockk<ScopedOutboxDao>()
        coEvery { dao.unacceptedForChat(CHAT) } returns listOf(
            row("11111111-1111-4111-8111-111111111111", OutboxState.PENDING, 1_000, replyTo = "parent-1"),
            row("22222222-2222-4222-8222-222222222222", OutboxState.FAILED, 2_000)
        )
        // A repository that never composed these messages - the post-restart situation.
        val repo = ChatRepository(
            chatApiService = mockk(relaxed = true),
            messageDao = mockk(relaxed = true),
            conversationDao = mockk(relaxed = true),
            cachedChatDao = mockk(relaxed = true),
            webSocketManager = mockk<WebSocketManager>(relaxed = true),
            tokenManager = Tokens(),
            groupRepository = mockk(relaxed = true),
            json = json,
            outboxDao = dao
        )
        val pending = repo.pendingOutbox(CHAT)
        println("P71 A11: rehydrated ${pending.size} queued message(s): ${pending.map { it.failed }}")
        assertEquals(2, pending.size)
        assertEquals("11111111-1111-4111-8111-111111111111", pending[0].clientMessageId)
        assertFalse(pending[0].failed)
        assertEquals("parent-1", pending[0].replyToId)
        assertTrue(pending[1].failed)
        assertEquals("HTTP 403", pending[1].error)
        // Never held by this process, and never stored: the UI shows a placeholder, not plaintext.
        assertNull(pending[0].text)
        assertNull(pending[1].text)
    }
}
