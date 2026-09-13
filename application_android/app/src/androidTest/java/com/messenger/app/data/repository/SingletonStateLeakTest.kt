package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.AccountCacheHolder
import com.messenger.app.data.local.AccountCacheNamespace
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * GATE 23 - does account-sensitive state survive a logout inside the SINGLETON
 * repository, even though the Room namespace is correctly isolated?
 *
 * ChatRepository is a @Singleton holding eleven process-level maps keyed by
 * chatId with no account component, plus a memoised `myUserId`. Nothing clears
 * them on logout. This drives the real repository across an account switch with
 * no process death - the supported flow - and asks whether B observes state that
 * only A ever established.
 *
 * chatTypeFor is the cheapest observable instance. The same retention applies to
 * maps holding key material; see the Gate 23 report.
 */
@RunWith(AndroidJUnit4::class)
class SingletonStateLeakTest {

    private companion object {
        const val ACCOUNT_A = "5555aaaa-0000-0000-0000-0000000055aa"
        const val ACCOUNT_B = "6666bbbb-0000-0000-0000-0000000066bb"
        const val GROUP_CHAT = "group-chat-only-A-knows-about"
        const val TOKEN = "test-token"
    }

    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = ACCOUNT_A
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: FakeAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
        auth = FakeAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        repo = ChatRepository(
            chatApiService = mockk<ChatApiService>(relaxed = true),
            messageDao = ScopedMessageDao(holder),
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

    /**
     * A learns that a conversation is a GROUP. B - a different account, on the
     * same process, after a logout - must not inherit that knowledge.
     */
    @Test
    fun singletonChatMetadataDoesNotSurviveAnAccountSwitch() = runBlocking {
        auth.account = ACCOUNT_A
        // sendMessage records the chat type in the singleton's chatTypeMap before
        // it does anything else. The network call is mocked and irrelevant here.
        runCatching { repo.sendMessage(TOKEN, GROUP_CHAT, "group", "hello from A") }
        assertEquals(
            "precondition: A learned this chat is a group",
            "group", repo.chatTypeFor(GROUP_CHAT)
        )

        // ---- A logs out, B logs in. No process death: the supported flow. ----
        auth.account = null
        holder.deactivate()
        auth.account = ACCOUNT_B

        assertEquals(
            "PROVEN BROKEN: account B inherited account A's in-memory knowledge of " +
                "a conversation it has never seen. The Room namespace is isolated, " +
                "but ChatRepository is a @Singleton whose chatId-keyed maps are " +
                "never cleared on logout.",
            "direct", repo.chatTypeFor(GROUP_CHAT)
        )
    }
}
