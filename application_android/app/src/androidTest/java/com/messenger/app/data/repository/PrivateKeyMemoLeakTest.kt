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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider

/**
 * GATE 24 - does account A's E2EE PRIVATE KEY survive a logout inside the
 * singleton repository?
 *
 * ChatRepository memoises the signed-in account's private key:
 *
 *     private suspend fun myPriv(): String? {
 *         myPrivateHex?.let { return it }      // short-circuits before any identity check
 *         ...
 *     }
 *
 * `myPrivateHex` is a @Volatile field on a @Singleton and is never reset on
 * logout. The observable consequence tested here: after switching to B, a crypto
 * path that needs "my private key" never asks the TokenManager for B's key at
 * all - because it already holds A's.
 */
@RunWith(AndroidJUnit4::class)
class PrivateKeyMemoLeakTest {

    private companion object {
        const val ACCOUNT_A = "7777aaaa-0000-0000-0000-0000000077aa"
        const val ACCOUNT_B = "8888bbbb-0000-0000-0000-0000000088bb"
        const val GROUP_CHAT = "group-chat-shared-by-both"
        const val TOKEN = "test-token"
    }

    /** Records which account each key lookup was made for. */
    private class RecordingAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = ACCOUNT_A
        val privateKeyLookups = CopyOnWriteArrayList<String>()

        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)

        override suspend fun getE2EEPrivateKey(userId: String): Result<String?> {
            privateKeyLookups += userId
            return Result.success("PRIVATE_KEY_OF_$userId")
        }

        override suspend fun getE2EEPublicKey(userId: String): Result<String?> =
            Result.success("PUBLIC_KEY_OF_$userId")
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: RecordingAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
        auth = RecordingAuth()
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

    @Test
    fun accountBMustNeverUseAccountAsMemoisedPrivateKey() = runBlocking {
        // ---- A signs in and its private key is memoised on the singleton. ----
        //
        // This must be an operation that resolves the key THROUGH myPriv().
        // ensureKeysPublished calls tokenManager.getE2EEPrivateKey directly, so
        // it satisfied this precondition without ever populating the memo - and
        // a reintroduced memo then slipped past the assertion below, because
        // there was nothing cached for B to inherit.
        auth.account = ACCOUNT_A
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP_CHAT) }
        assertTrue(
            "precondition: A's private key was resolved through myPriv()",
            auth.privateKeyLookups.contains(ACCOUNT_A)
        )

        // ---- A logs out, B logs in. No process death: the supported flow. ----
        auth.account = null
        holder.deactivate()
        auth.privateKeyLookups.clear()
        auth.account = ACCOUNT_B

        // ---- B performs an operation that needs "my private key". ----
        // fetchGroupSenderKeys resolves the account private key as its very
        // first act, so it exercises myPriv() directly rather than through the
        // MLS/Sender-Key branches a full send would take.
        runCatching { repo.fetchGroupSenderKeys(TOKEN, GROUP_CHAT) }

        assertTrue(
            "PROVEN BROKEN: account B ran a private-key operation without ever " +
                "requesting its OWN key (lookups since the switch: " +
                "${auth.privateKeyLookups}). ChatRepository.myPriv() short-circuits " +
                "on the memoised @Volatile myPrivateHex, which still holds account " +
                "A's E2EE private key because nothing resets it on logout.",
            auth.privateKeyLookups.contains(ACCOUNT_B)
        )
        assertTrue(
            "PROVEN BROKEN: account A's key was consulted while B was signed in",
            !auth.privateKeyLookups.contains(ACCOUNT_A)
        )
    }
}
