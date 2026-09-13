package com.messenger.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.local.entity.CachedChatEntity
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * GATE 19 - account isolation of the local cache, against real Room databases.
 *
 * Runs on a device because the isolation is physical: two accounts resolve to
 * two SQLite files. An in-memory fake would prove nothing about that, which is
 * the whole claim.
 *
 * The auth boundary is NOT mocked away: the scoped DAOs under test are the real
 * production facades, over the real AccountCacheHolder, over real Room
 * databases. Only the account-id source is a stand-in, because "who is signed
 * in" is what the test needs to vary.
 */
@RunWith(AndroidJUnit4::class)
class AccountCacheIsolationTest {

    private companion object {
        const val ACCOUNT_A = "aaaaaaaa-0000-0000-0000-00000000000a"
        const val ACCOUNT_B = "bbbbbbbb-0000-0000-0000-00000000000b"
        const val CHAT_A = "conversation-belonging-to-A"
        const val CHAT_B = "conversation-belonging-to-B"
        const val MSG_A = "message-belonging-to-A"
        const val MSG_B = "message-belonging-to-B"
        const val SECRET_A = "A private: the merger closes on Tuesday"
        const val SECRET_B = "B private: unrelated"
    }

    /** Stands in for the signed-in account; everything else is production code. */
    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = null
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private lateinit var auth: FakeAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var messages: ScopedMessageDao
    private lateinit var conversations: ScopedConversationDao
    private lateinit var chats: ScopedCachedChatDao

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
        auth = FakeAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
        messages = ScopedMessageDao(holder)
        conversations = ScopedConversationDao(holder)
        chats = ScopedCachedChatDao(holder)
    }

    @After
    fun tearDown() = runBlocking {
        holder.deactivate()
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
    }

    /** login */
    private fun signIn(account: String) { auth.account = account }

    /** logout: credentials gone, cache handle released, cache files untouched */
    private suspend fun signOut() { auth.account = null; holder.deactivate() }

    private suspend fun seed(chatId: String, msgId: String, body: String) {
        // The seeding account IS the owner: these writes model work started and
        // finished while that account was signed in.
        val owner = auth.account.orEmpty()
        conversations.insertConversation(owner, ConversationEntity(id = chatId, type = "direct", name = chatId))
        messages.insertMessages(
            owner,
            listOf(
                MessageEntity(
                    id = msgId, conversation_id = chatId, senderId = "s",
                    content = body, type = "text", status = "SENT", timestamp = 1
                )
            )
        )
        chats.insertAll(owner, listOf(CachedChatEntity(id = chatId, rawJson = """{"id":"$chatId"}""", cachedAt = 1)))
    }

    // ------------------------------------------------------ 3E: account switch

    @Test
    fun accountBCannotObserveAnythingBelongingToAccountA() = runBlocking {
        signIn(ACCOUNT_A)
        seed(CHAT_A, MSG_A, SECRET_A)
        signOut()

        signIn(ACCOUNT_B)
        assertEquals("chat list must not show A's chats", emptyList<CachedChatEntity>(), chats.getAllCached())
        assertEquals("enumeration must not reach A", emptyList<ConversationEntity>(), conversations.getAllConversations())
        assertNull("direct conversation lookup must not reach A", conversations.getConversationById(CHAT_A))
        assertEquals("A's messages must be unreachable", emptyList<MessageEntity>(),
            messages.getAllMessagesByConversation(CHAT_A))
        assertNull("direct message lookup must not reach A", messages.getMessageById(MSG_A))
        assertNull("latest-message probe must not reach A", messages.getLatestMessage(CHAT_A))
    }

    @Test
    fun eachAccountKeepsItsOwnHistoryAcrossSwitches() = runBlocking {
        signIn(ACCOUNT_A); seed(CHAT_A, MSG_A, SECRET_A); signOut()
        signIn(ACCOUNT_B); seed(CHAT_B, MSG_B, SECRET_B); signOut()

        signIn(ACCOUNT_A)
        assertEquals(SECRET_A, messages.getMessageById(MSG_A)?.content)
        assertNull("A must never see B's message", messages.getMessageById(MSG_B))
        assertEquals(listOf(CHAT_A), chats.getAllCached().map { it.id })

        signOut(); signIn(ACCOUNT_B)
        assertEquals(SECRET_B, messages.getMessageById(MSG_B)?.content)
        assertNull("B must never see A's message", messages.getMessageById(MSG_A))
        assertEquals(listOf(CHAT_B), chats.getAllCached().map { it.id })
    }

    /** Logout must not destroy the account's offline history. */
    @Test
    fun logoutPreservesTheAccountsOwnCache() = runBlocking {
        signIn(ACCOUNT_A); seed(CHAT_A, MSG_A, SECRET_A); signOut()
        signIn(ACCOUNT_A)
        assertEquals("A's history must survive its own logout", SECRET_A, messages.getMessageById(MSG_A)?.content)
    }

    /** Signed out, the cache reads as empty rather than as the last account. */
    @Test
    fun withNoAccountTheCacheReadsEmptyAndRefusesWrites() = runBlocking {
        signIn(ACCOUNT_A); seed(CHAT_A, MSG_A, SECRET_A); signOut()

        assertEquals(emptyList<CachedChatEntity>(), chats.getAllCached())
        assertNull(messages.getMessageById(MSG_A))
        assertEquals(emptyList<ConversationEntity>(), conversations.getAllConversations())
        assertTrue(
            "an unattributable write must fail rather than land in someone's cache",
            runCatching {
                conversations.insertConversation(ACCOUNT_A, ConversationEntity(id = "x", type = "direct"))
            }.isFailure
        )
    }

    /**
     * A switch with NO deactivate() in between.
     *
     * The tests above all log out through a helper that calls deactivate(), which
     * hides whether the holder can protect itself. Production must not depend on
     * that: AuthRepository's holder reference is optional, and logout could fail
     * before releasing. current() re-resolves the account on every access, and
     * THAT is the layer under test here.
     */
    @Test
    fun switchingAccountWithoutDeactivateStillIsolates() = runBlocking {
        auth.account = ACCOUNT_A
        seed(CHAT_A, MSG_A, SECRET_A)
        assertEquals(SECRET_A, messages.getMessageById(MSG_A)?.content)

        auth.account = ACCOUNT_B          // straight to B, holder never told to close

        assertNull("a switch without deactivate leaked A's message",
            messages.getMessageById(MSG_A))
        assertEquals("a switch without deactivate leaked A's chat list",
            emptyList<CachedChatEntity>(), chats.getAllCached())
        assertEquals("a switch without deactivate leaked A's conversations",
            emptyList<ConversationEntity>(), conversations.getAllConversations())
    }

    /**
     * deactivate() must actually release the handle, not merely stop being asked.
     *
     * Isolation does not depend on this - current() re-resolves on every access -
     * but a handle left open after logout is exactly the sort of thing that
     * becomes reachable later, so the release is asserted rather than assumed.
     */
    @Test
    fun deactivateReleasesTheOpenNamespace() = runBlocking {
        auth.account = ACCOUNT_A
        seed(CHAT_A, MSG_A, SECRET_A)
        assertEquals(
            AccountCacheNamespace.databaseNameFor(ACCOUNT_A),
            holder.openNamespaceOrNull()
        )

        holder.deactivate()

        assertNull("logout must release the open namespace", holder.openNamespaceOrNull())
    }

    /** Signing out without deactivate() must still make the cache unreadable. */
    @Test
    fun clearingTheAccountWithoutDeactivateReadsEmpty() = runBlocking {
        auth.account = ACCOUNT_A
        seed(CHAT_A, MSG_A, SECRET_A)
        assertEquals(SECRET_A, messages.getMessageById(MSG_A)?.content)

        auth.account = null               // logged out, holder never told to close

        assertNull("the cache stayed readable after the account was cleared",
            messages.getMessageById(MSG_A))
        assertEquals(emptyList<CachedChatEntity>(), chats.getAllCached())
        assertEquals(emptyList<ConversationEntity>(), conversations.getAllConversations())
        assertTrue(
            "writes must not be attributable once the account is gone",
            runCatching {
                conversations.insertConversation(ACCOUNT_A, ConversationEntity(id = "x", type = "direct"))
            }.isFailure
        )
    }

    // ------------------------------------------------------ 3F: stale handles

    /**
     * The stale-reference attack. DAO facades captured while A was signed in are
     * invoked after the switch to B: they must resolve to B, never to A.
     */
    @Test
    fun staleDaoReferencesCannotReadThePreviousAccount() = runBlocking {
        signIn(ACCOUNT_A)
        seed(CHAT_A, MSG_A, SECRET_A)
        val staleMessages = messages          // captured under A
        val staleConversations = conversations
        val staleChats = chats
        signOut()

        signIn(ACCOUNT_B)
        assertNull("stale message DAO leaked A", staleMessages.getMessageById(MSG_A))
        assertNull("stale conversation DAO leaked A", staleConversations.getConversationById(CHAT_A))
        assertEquals("stale chat-list DAO leaked A", emptyList<CachedChatEntity>(), staleChats.getAllCached())
    }

    /** A stale Flow must not keep streaming the previous account's rows. */
    @Test
    fun staleFlowsDoNotStreamThePreviousAccount() = runBlocking {
        signIn(ACCOUNT_A)
        seed(CHAT_A, MSG_A, SECRET_A)
        val staleFlow = messages.getAllMessagesByConversationFlow(CHAT_A) // built under A
        signOut()

        signIn(ACCOUNT_B)
        // Collection happens now, under B: the flow resolves the account at
        // collection time, so it must see B's (empty) database.
        val first = staleFlow.first()
        assertEquals("a stale Flow leaked the previous account", emptyList<MessageEntity>(), first)
    }

    // ------------------------------------------------------- 3G: chat list

    @Test
    fun chatListsAreIsolatedInBothDirections() = runBlocking {
        signIn(ACCOUNT_A); seed(CHAT_A, MSG_A, SECRET_A); signOut()
        signIn(ACCOUNT_B); seed(CHAT_B, MSG_B, SECRET_B)

        assertEquals(listOf(CHAT_B), chats.getAllCached().map { it.id })
        assertTrue("B's list must not contain A's preview JSON",
            chats.getAllCached().none { it.rawJson.contains(CHAT_A) })

        signOut(); signIn(ACCOUNT_A)
        assertEquals(listOf(CHAT_A), chats.getAllCached().map { it.id })
        assertTrue("A's list must not contain B's preview JSON",
            chats.getAllCached().none { it.rawJson.contains(CHAT_B) })
    }

    // --------------------------------------------- 3I: process-death analogue

    /**
     * Closing every handle and rebuilding the holder from scratch is the honest
     * in-process analogue of the app being killed and restarted: nothing but the
     * files and the stored account id survives.
     */
    @Test
    fun restartRestoresOnlyTheAuthenticatedAccountsCache() = runBlocking {
        signIn(ACCOUNT_A); seed(CHAT_A, MSG_A, SECRET_A); signOut()
        signIn(ACCOUNT_B); seed(CHAT_B, MSG_B, SECRET_B)

        // "process death"
        holder.deactivate()
        val restartedAuth = FakeAuth().apply { account = ACCOUNT_B }
        val restarted = AccountCacheHolder(ctx, Provider { restartedAuth })
        val m2 = ScopedMessageDao(restarted)
        val c2 = ScopedCachedChatDao(restarted)

        assertEquals("B's own history must survive restart", SECRET_B, m2.getMessageById(MSG_B)?.content)
        assertNull("restart must not expose A to B", m2.getMessageById(MSG_A))
        assertEquals(listOf(CHAT_B), c2.getAllCached().map { it.id })
        restarted.deactivate()
    }

    // ------------------------------------------------- 3K: legacy quarantine

    /**
     * The pre-Gate-19 database must be unreachable through every production
     * path. It is not deleted - it is simply no longer nameable.
     */
    @Test
    fun theLegacyDatabaseCanNeverBeOpened() = runBlocking {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B, "anything-at-all")) {
            assertNotEquals(
                AccountCacheNamespace.LEGACY_QUARANTINED_DB,
                AccountCacheNamespace.databaseNameFor(id)
            )
        }
        assertTrue(
            "opening the legacy database by name must be refused",
            runCatching {
                AuthDatabase.getDatabase(ctx, AccountCacheNamespace.LEGACY_QUARANTINED_DB)
            }.isFailure
        )
        signIn(ACCOUNT_A)
        assertTrue(
            "an authenticated account must open only its own namespace",
            holder.current() != null &&
                holder.openNamespaceOrNull() == AccountCacheNamespace.databaseNameFor(ACCOUNT_A)
        )
    }
}
