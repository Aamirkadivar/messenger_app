package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.AccountCacheHolder
import com.messenger.app.data.local.AccountCacheNamespace
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.model.DirectChatDto
import com.messenger.app.data.model.DirectChatResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import javax.inject.Provider

/**
 * GATE 21 - the ORIGINAL user-visible bug, driven through the real repository.
 *
 * Gate 18 found that resuming a direct chat called insertConversation (REPLACE),
 * which SQLite implements as delete-then-insert, firing the messages table's
 * ON DELETE CASCADE and destroying the whole cached history. The reload that
 * followed made it look as though nothing had ever been cached.
 *
 * The DAO-level tests pin the cascade mechanism, but only this one exercises
 * ChatRepository.getOrCreateDirectChat - the path a user actually takes. Gate 18
 * showed what happens when a test asserts a reimplementation instead of
 * production code, so the repository here is the real one; only the NETWORK is
 * faked, which is not the boundary under test.
 */
@RunWith(AndroidJUnit4::class)
class ResumeDirectChatCacheTest {

    private companion object {
        const val ACCOUNT = "cafe0000-0000-0000-0000-00000000cafe"
        const val CHAT = "direct-chat-under-test"
        const val TOKEN = "test-token"
        const val CONTACT = "the-other-person"
    }

    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = ACCOUNT
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var holder: AccountCacheHolder
    private lateinit var messages: ScopedMessageDao
    private lateinit var conversations: ScopedConversationDao
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(ACCOUNT))
        val auth = FakeAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        messages = ScopedMessageDao(holder)
        conversations = ScopedConversationDao(holder)

        val api = mockk<ChatApiService>(relaxed = true)
        coEvery { api.getOrCreateDirectChat(any(), any()) } returns Response.success(
            DirectChatResponse(
                data = DirectChatDto(id = CHAT, type = "direct", name = "Someone", avatarUrl = null)
            )
        )

        repo = ChatRepository(
            chatApiService = api,
            messageDao = messages,
            conversationDao = conversations,
            cachedChatDao = ScopedCachedChatDao(holder),
            webSocketManager = mockk<WebSocketManager>(relaxed = true),
            tokenManager = auth,
            groupRepository = mockk(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        )
    }

    @After
    fun tearDown(): Unit = runBlocking {
        holder.deactivate()
        ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(ACCOUNT))
    }

    private suspend fun seedHistory(count: Int) {
        conversations.insertConversation(ACCOUNT, ConversationEntity(id = CHAT, type = "direct"))
        messages.insertMessages(
            ACCOUNT,
            (0 until count).map { i ->
                MessageEntity(
                    id = "$CHAT-m$i", conversation_id = CHAT, senderId = "s",
                    content = "cached message $i", type = "text", status = "SENT",
                    timestamp = 1_000L + i
                )
            }
        )
    }

    /** THE regression: resuming a direct chat must not destroy its history. */
    @Test
    fun resumingADirectChatThroughTheRepositoryKeepsCachedHistory() = runBlocking {
        seedHistory(40)
        assertEquals(40, messages.getAllMessagesByConversation(CHAT).size)

        // The user taps the contact and resumes the conversation.
        val result = repo.getOrCreateDirectChat(TOKEN, CONTACT)
        assertTrue("resuming the chat should succeed", result.isSuccess)

        assertEquals(
            "PROVEN BROKEN: resuming the conversation destroyed its cached history",
            40, messages.getAllMessagesByConversation(CHAT).size
        )
        assertNotNull("the conversation row must survive", conversations.getConversationById(CHAT))
        assertEquals("server-owned metadata should still be refreshed",
            "Someone", conversations.getConversationById(CHAT)?.name)
    }

    /** Repeated reopening must stay stable, not decay. */
    @Test
    fun reopeningRepeatedlyNeverErodesTheCache() = runBlocking {
        seedHistory(25)
        repeat(5) {
            val r = repo.getOrCreateDirectChat(TOKEN, CONTACT)
            assertTrue(r.isSuccess)
            assertEquals(
                "cached history shrank on reopen #$it",
                25, messages.getAllMessagesByConversation(CHAT).size
            )
        }
    }

    /** Locally-owned state must not be collateral damage either. */
    @Test
    fun resumingADirectChatPreservesLocalOnlyConversationState() = runBlocking {
        seedHistory(5)
        conversations.toggleMute(ACCOUNT, CHAT, true)
        conversations.toggleArchive(ACCOUNT, CHAT, true)

        repo.getOrCreateDirectChat(TOKEN, CONTACT)

        val row = conversations.getConversationById(CHAT)
        assertEquals("mute must survive resuming the chat", true, row?.isMuted)
        assertEquals("archive must survive resuming the chat", true, row?.isArchived)
    }
}
