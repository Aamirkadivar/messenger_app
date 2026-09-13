package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.AccountCacheHolder
import com.messenger.app.data.local.AccountCacheNamespace
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.model.ChatListItemDto
import com.messenger.app.data.model.ChatListOtherUserDto
import com.messenger.app.data.model.ChatsListResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import javax.inject.Provider

/**
 * INDEPENDENT AUDIT - ownership boundary of ChatRepository's in-memory state.
 *
 * learnChatMeta validates the operation owner ONCE, at its top, and then calls
 * applyPeerIdentity, which SUSPENDS on tokenManager.getKnownPublicKey before it
 * writes pendingPeerPubs, pendingSecurityNotices and chatOtherPub.
 *
 * The account can change inside that suspension. Nothing re-validates after it,
 * so the writes land in a singleton that now belongs to somebody else.
 *
 * This test pauses the fake exactly inside that window.
 */
@RunWith(AndroidJUnit4::class)
class AuditRaceProofTest {

    private companion object {
        const val ACCOUNT_A = "1111aaaa-0000-0000-0000-00000000a111"
        const val ACCOUNT_B = "2222bbbb-0000-0000-0000-00000000b222"
        const val CHAT = "chat-both-accounts-can-open"
        const val TOKEN = "test-token"
        val PINNED_PUB = "aa".repeat(32)
        val SERVER_PUB = "bb".repeat(32)
    }

    /** Lets the test stop the coroutine inside applyPeerIdentity's first suspend. */
    private class GatedAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = ACCOUNT_A
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        @Volatile var gate = false

        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)

        override suspend fun getKnownPublicKey(owner: String, chatId: String): Result<String?> {
            if (gate) {
                gate = false
                entered.complete(Unit)
                release.await()
            }
            // A pinned key that DISAGREES with the server's value: the branch
            // that records a pending peer key and a security notice.
            return Result.success(PINNED_PUB)
        }

        override suspend fun getE2EEPrivateKey(userId: String): Result<String?> =
            Result.success("cc".repeat(32))

        override suspend fun getE2EEPublicKey(userId: String): Result<String?> =
            Result.success("dd".repeat(32))

        override suspend fun getOrCreateDeviceId(): Result<String> = Result.success("dev")
        override suspend fun getAccessToken(): Result<String?> = Result.success(null)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: GatedAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
        auth = GatedAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        val api = mockk<ChatApiService>(relaxed = true)
        coEvery { api.getChats(any()) } returns Response.success(
            ChatsListResponse(
                data = listOf(
                    ChatListItemDto(
                        id = CHAT,
                        type = "direct",
                        otherUser = ChatListOtherUserDto(
                            id = "peer", email = "p@e.test", username = "p",
                            publicKey = SERVER_PUB
                        )
                    )
                )
            )
        )
        repo = ChatRepository(
            chatApiService = api,
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

    @Test
    fun aPeerIdentityWriteCannotLandAfterTheAccountChanged() = runBlocking {
        auth.account = ACCOUNT_A
        auth.gate = true

        val job = launch(Dispatchers.IO) { runCatching { repo.getChats(TOKEN) } }

        // Park inside applyPeerIdentity, AFTER learnChatMeta validated owner A.
        withTimeout(10_000) { auth.entered.await() }

        // Exactly what AuthRepository.clearAuthData does on logout, then B signs in.
        auth.account = null
        repo.clearAccountScopedState()
        auth.account = ACCOUNT_B

        auth.release.complete(Unit)
        job.join()

        assertFalse(
            "PROVEN BROKEN: account A's PENDING PEER PUBLIC KEY was written into " +
                "the singleton after logout, while account B is signed in. " +
                "acceptPeerKeyChange() would then let B pin A's pending key as " +
                "the trusted identity for this chat.",
            repo.hasPendingPeerKeyChange(CHAT)
        )
        assertFalse(
            "PROVEN BROKEN: account A's SECURITY NOTICE was written into the " +
                "singleton after logout. Account B sees (and consumes) a " +
                "peer-key-change warning raised in account A's session.",
            repo.takePendingSecurityNotice(CHAT)
        )
        // The third value applyPeerIdentity writes after the suspension is
        // chatOtherPub. It has no getter, but it is exactly what decides whether
        // a direct message can be sealed - so ask that question instead.
        //
        // (This replaces an assertion that could not fail: it compared
        // chatTypeFor against "direct", which is both the type this chat has and
        // the value returned when nothing is known. It never distinguished a
        // leak from a clean state, and only went unnoticed because the first
        // assertion failed before reaching it.)
        assertFalse(
            "PROVEN BROKEN: account A's peer identity was installed into the " +
                "singleton after the switch, so account B can seal traffic under " +
                "a peer key that account A pinned and B never saw.",
            repo.encryptFor(CHAT, "hello").encrypted
        )
    }
}
