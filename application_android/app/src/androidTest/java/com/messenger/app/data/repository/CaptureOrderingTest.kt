package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.AccountCacheHolder
import com.messenger.app.data.local.AccountCacheNamespace
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.model.MessageDto
import com.messenger.app.data.model.MessagesResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import javax.inject.Provider

/**
 * GATE 22.1 - ownership must be captured when the operation STARTS.
 *
 * The other tests hand an owner to the DAO directly, which proves the check but
 * not the capture ORDER. Here the account switches while the network call is in
 * flight, driven through the real ChatRepository. If the repository captured the
 * owner after the response instead of before the request, it would capture B and
 * write A's messages into B's cache - and this test would see them.
 */
@RunWith(AndroidJUnit4::class)
class CaptureOrderingTest {

    private companion object {
        const val ACCOUNT_A = "1111aaaa-0000-0000-0000-0000000011aa"
        const val ACCOUNT_B = "2222bbbb-0000-0000-0000-0000000022bb"
        const val CHAT = "chat-fetched-as-A"
        const val MSG = "message-fetched-as-A"
        const val SECRET = "A private: fetched while A was signed in"
        const val TOKEN = "test-token"
    }

    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = ACCOUNT_A
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: FakeAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var messages: ScopedMessageDao
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
        auth = FakeAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        messages = ScopedMessageDao(holder)

        val api = mockk<ChatApiService>(relaxed = true)
        // The account switch happens WHILE the request is in flight - exactly the
        // window the owner capture exists to survive.
        coEvery { api.getMessages(any(), any(), any(), any()) } answers {
            auth.account = ACCOUNT_B
            Response.success(
                MessagesResponse(
                    data = listOf(
                        MessageDto(
                            id = MSG, chatId = CHAT, senderId = "s",
                            content = SECRET, encrypted = false,
                            createdAt = "2026-01-01T00:00:00Z"
                        )
                    )
                )
            )
        }

        repo = ChatRepository(
            chatApiService = api,
            messageDao = messages,
            conversationDao = ScopedConversationDao(holder),
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
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
    }

    @Test
    fun aFetchStartedAsAIsNotCachedIntoBWhenTheAccountSwitchesMidFlight() = runBlocking {
        // Starts as A; the fake API flips the account to B before returning.
        runCatching { repo.getMessages(TOKEN, CHAT) }

        assertEquals("precondition: the switch happened during the call", ACCOUNT_B, auth.account)
        assertNull(
            "PROVEN BROKEN: messages fetched as A were cached into B - the owner " +
                "must be captured before the request, not after the response",
            messages.getMessageById(MSG)
        )

        auth.account = ACCOUNT_A
        assertNull(
            "the refused write must be dropped, not diverted into A behind B's back",
            messages.getMessageById(MSG)
        )
    }

    @Test
    fun aFetchThatStaysWithinOneAccountStillCaches() = runBlocking {
        val api = mockk<ChatApiService>(relaxed = true)
        coEvery { api.getMessages(any(), any(), any(), any()) } returns Response.success(
            MessagesResponse(
                data = listOf(
                    MessageDto(
                        id = MSG, chatId = CHAT, senderId = "s",
                        content = SECRET, encrypted = false,
                        createdAt = "2026-01-01T00:00:00Z"
                    )
                )
            )
        )
        val stable = ChatRepository(
            chatApiService = api,
            messageDao = messages,
            conversationDao = ScopedConversationDao(holder),
            cachedChatDao = ScopedCachedChatDao(holder),
            webSocketManager = mockk<WebSocketManager>(relaxed = true),
            tokenManager = auth,
            groupRepository = mockk(relaxed = true),
            json = Json { ignoreUnknownKeys = true }
        )
        auth.account = ACCOUNT_A

        runCatching { stable.getMessages(TOKEN, CHAT) }

        assertEquals(
            "an operation that never crosses an account boundary must still cache",
            SECRET, messages.getMessageById(MSG)?.content
        )
    }
}
