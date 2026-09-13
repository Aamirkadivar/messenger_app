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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * GATE 22 - invariant I5 on Android: a write started under one account must not
 * land in another account's namespace.
 *
 * The Windows client captures the owning account when a request is ISSUED and
 * refuses the write if it no longer matches. This test asks whether Android does
 * the same. It models the real shape of the hazard: a network reply or WebSocket
 * delivery whose handler began while A was signed in and completes after the
 * user has switched to B.
 */
@RunWith(AndroidJUnit4::class)
class LateCallbackOwnershipTest {

    private companion object {
        const val ACCOUNT_A = "aaaa1111-0000-0000-0000-0000000000aa"
        const val ACCOUNT_B = "bbbb2222-0000-0000-0000-0000000000bb"
        const val CHAT_A = "As-conversation"
        const val MSG_A = "As-late-message"
        const val SECRET_A = "A private: late-arriving reply"
    }

    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = null
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: FakeAuth
    private lateinit var holder: AccountCacheHolder
    private lateinit var messages: ScopedMessageDao
    private lateinit var conversations: ScopedConversationDao
    private lateinit var chats: ScopedCachedChatDao

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
    fun tearDown(): Unit = runBlocking {
        holder.deactivate()
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) {
            ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        }
    }

    /**
     * The reply that outlived its session.
     *
     * A fetch begins under A. Before it commits, A logs out and B logs in. The
     * handler then runs its cache write, exactly as ChatRepository.cacheMessages
     * would. A's rows must not appear in B's database.
     */
    @Test
    fun aWriteStartedUnderAMustNotLandInBsNamespace() = runBlocking {
        auth.account = ACCOUNT_A
        // Owner captured while A is signed in - this is the operation start.
        val owner = ACCOUNT_A
        conversations.insertConversation(owner, ConversationEntity(id = CHAT_A, type = "direct"))

        // ---- the account changes while the operation is in flight ----
        auth.account = ACCOUNT_B

        // ---- the handler finally commits, carrying A's data ----
        runCatching {
            conversations.insertConversation(owner, ConversationEntity(id = CHAT_A, type = "direct"))
            messages.insertMessages(
                owner,
                listOf(
                    MessageEntity(
                        id = MSG_A, conversation_id = CHAT_A, senderId = "s",
                        content = SECRET_A, type = "text", status = "SENT", timestamp = 1
                    )
                )
            )
            chats.insertAll(
                owner,
                listOf(CachedChatEntity(id = CHAT_A, rawJson = """{"id":"$CHAT_A"}""", cachedAt = 1))
            )
        }

        // B is signed in right now. Whatever happened above, none of A's data may
        // be visible to B.
        assertNull(
            "PROVEN BROKEN: a write begun under account A landed in account B's cache",
            messages.getMessageById(MSG_A)
        )
        assertNull(
            "PROVEN BROKEN: a conversation begun under A landed in B's cache",
            conversations.getConversationById(CHAT_A)
        )
        val leaked = chats.getAllCached().any { it.id == CHAT_A }
        assertNull(
            "PROVEN BROKEN: a chat-list entry begun under A landed in B's cache",
            if (leaked) CHAT_A else null
        )

        // And it must not have been quietly diverted into A's namespace either.
        // Reopening A's database on A's behalf while B is signed in would be a
        // different leak, not a fix: the refused write is DROPPED.
        auth.account = ACCOUNT_A
        assertNull(
            "PROVEN BROKEN: the refused write was redirected into A's cache " +
                "behind B's back instead of being dropped",
            messages.getMessageById(MSG_A)
        )
    }

    /** The same refusal must apply when nobody is signed in at all. */
    @Test
    fun aWriteThatOutlivesLogoutIsRefusedEverywhere() = runBlocking {
        auth.account = ACCOUNT_A
        val owner = ACCOUNT_A
        conversations.insertConversation(owner, ConversationEntity(id = CHAT_A, type = "direct"))

        auth.account = null   // logged out, nobody signed in

        runCatching<Unit> {
            messages.insertMessages(
                owner,
                listOf(
                    MessageEntity(
                        id = MSG_A, conversation_id = CHAT_A, senderId = "s",
                        content = SECRET_A, type = "text", status = "SENT", timestamp = 1
                    )
                )
            )
        }

        auth.account = ACCOUNT_A
        assertNull(
            "PROVEN BROKEN: a write completing while signed out still reached a cache",
            messages.getMessageById(MSG_A)
        )
    }

    /**
     * Every write method must refuse independently.
     *
     * Wrapping several writes in one runCatching hides per-method regressions:
     * the first refusal aborts the block and the rest never run. Each call is
     * therefore attempted and asserted on its own.
     */
    @Test
    fun everyWriteMethodRefusesIndependentlyAfterASwitch() = runBlocking {
        auth.account = ACCOUNT_A
        val owner = ACCOUNT_A
        conversations.insertConversation(owner, ConversationEntity(id = CHAT_A, type = "direct"))

        auth.account = ACCOUNT_B

        // B legitimately has its OWN row for the same conversation id - two
        // accounts can be in one conversation. Without this the messages table's
        // foreign key would reject A's late write on its own, which would mask a
        // broken ownership check behind incidental referential integrity.
        conversations.insertConversation(
            ACCOUNT_B, ConversationEntity(id = CHAT_A, type = "direct", name = "B's own row")
        )

        val entity = MessageEntity(
            id = MSG_A, conversation_id = CHAT_A, senderId = "s",
            content = SECRET_A, type = "text", status = "SENT", timestamp = 1
        )

        // Each of these is a separate late commit carrying A's ownership.
        runCatching<Unit> { conversations.insertConversation(owner, ConversationEntity(id = CHAT_A, type = "direct")) }
        runCatching<Unit> { messages.insertMessages(owner, listOf(entity)) }
        runCatching<Long> { messages.insertMessage(owner, entity) }
        runCatching<Unit> { messages.updateMessage(owner, entity) }
        runCatching<Unit> { messages.updateMessageStatus(owner, MSG_A, "READ") }
        runCatching<Unit> { messages.deleteMessagesByConversation(owner, CHAT_A) }
        runCatching<Unit> { messages.setArchive(owner, MSG_A, "x", 1, "SEALED") }
        runCatching<Unit> {
            chats.insertAll(owner, listOf(CachedChatEntity(id = CHAT_A, rawJson = "{}", cachedAt = 1)))
        }
        runCatching<Unit> { conversations.toggleMute(owner, CHAT_A, true) }
        runCatching<Unit> { conversations.clearUnreadCount(owner, CHAT_A) }
        runCatching<Unit> { conversations.deleteConversation(owner, CHAT_A) }

        // B must be untouched by every one of them.
        assertNull("a per-method write reached B", messages.getMessageById(MSG_A))
        assertEquals(
            "a per-method conversation write overwrote B's own row",
            "B's own row", conversations.getConversationById(CHAT_A)?.name
        )
        assertEquals("a per-method chat-list write reached B",
            0, chats.getAllCached().size)
    }

    /**
     * A blank owner must be refused.
     *
     * Note: the explicit blank check is REDUNDANT - a blank owner can never equal
     * a non-empty active account, so the mismatch check refuses it anyway. This
     * asserts the contract; it is not what enforces it.
     */
    @Test
    fun aBlankOwnerIsRefused() = runBlocking {
        auth.account = ACCOUNT_A
        conversations.insertConversation(ACCOUNT_A, ConversationEntity(id = CHAT_A, type = "direct"))
        val entity = MessageEntity(
            id = MSG_A, conversation_id = CHAT_A, senderId = "s",
            content = SECRET_A, type = "text", status = "SENT", timestamp = 1
        )
        runCatching<Unit> { messages.insertMessages("", listOf(entity)) }
        runCatching<Unit> { messages.insertMessages("   ", listOf(entity)) }
        assertNull("a blank owner must never produce a write", messages.getMessageById(MSG_A))
    }

    /** A write owned by the account that IS signed in must still commit. */
    @Test
    fun aWriteThatKeepsItsOwnerStillCommits() = runBlocking {
        auth.account = ACCOUNT_A
        val owner = ACCOUNT_A
        conversations.insertConversation(owner, ConversationEntity(id = CHAT_A, type = "direct"))
        messages.insertMessages(
            owner,
            listOf(
                MessageEntity(
                    id = MSG_A, conversation_id = CHAT_A, senderId = "s",
                    content = SECRET_A, type = "text", status = "SENT", timestamp = 1
                )
            )
        )
        assertEquals(
            "an owned write by the active account must commit",
            SECRET_A, messages.getMessageById(MSG_A)?.content
        )
    }
}
