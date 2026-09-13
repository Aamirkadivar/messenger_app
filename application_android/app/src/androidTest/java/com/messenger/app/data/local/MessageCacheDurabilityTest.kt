package com.messenger.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The original product defect: previously loaded messages were not available
 * after reopening a conversation, and were refetched instead.
 *
 * Runs on a device because only real SQLite enforces `ON DELETE CASCADE`, and
 * because the durability claim is about a real file surviving the database
 * being closed and reopened - neither of which an in-memory fake can show.
 *
 * Uses its own database file, so it never touches the app's `messenger_database`.
 */
@RunWith(AndroidJUnit4::class)
class MessageCacheDurabilityTest {

    private companion object {
        const val TEST_DB = "message-cache-durability-test.db"
        const val CHAT = "chat-under-test"
        const val OTHER_CHAT = "unrelated-chat"
        const val COUNT = 50
    }

    private lateinit var db: AuthDatabase

    private fun open(): AuthDatabase =
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AuthDatabase::class.java,
            TEST_DB
        ).build()

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
    }

    private suspend fun seed(chatId: String, count: Int = COUNT) {
        db.conversationDao().insertConversation(ConversationEntity(id = chatId, type = "direct"))
        db.messageDao().insertMessages(
            (0 until count).map { i ->
                MessageEntity(
                    id = "$chatId-m$i",
                    conversation_id = chatId,
                    senderId = "sender",
                    content = "message $i",
                    type = "text",
                    status = "SENT",
                    timestamp = 1_000L + i
                )
            }
        )
    }

    /**
     * THE regression. Resuming a direct chat used to REPLACE the conversation
     * row; SQLite implements that as delete-then-insert, which cascaded every
     * cached message away. The fix updates the row instead.
     */
    @Test
    fun resumingAChatKeepsItsCachedMessages() = runBlocking {
        seed(CHAT)
        assertEquals(COUNT, db.messageDao().getAllMessagesByConversation(CHAT).size)

        // What getOrCreateDirectChat now does for an EXISTING conversation.
        db.conversationDao().updateConversationMeta(CHAT, "direct", "Alice", "http://avatar")

        assertEquals(
            "cached messages must survive resuming the conversation",
            COUNT, db.messageDao().getAllMessagesByConversation(CHAT).size
        )
        val row = db.conversationDao().getConversationById(CHAT)
        assertEquals("Alice", row?.name)
        assertEquals("http://avatar", row?.avatarUrl)
    }

    /** The mechanism the fix avoids, pinned so nobody reintroduces it. */
    @Test
    fun replacingTheConversationRowStillCascades() = runBlocking {
        seed(CHAT)
        db.conversationDao().insertConversation(
            ConversationEntity(id = CHAT, type = "direct", name = "Alice")
        )
        assertEquals(
            "INSERT OR REPLACE on the parent must be understood to cascade - " +
                "this is why the repository guards it",
            0, db.messageDao().getAllMessagesByConversation(CHAT).size
        )
    }

    /** Locally-owned columns are not collateral damage either. */
    @Test
    fun resumingAChatPreservesLocalOnlyState() = runBlocking {
        seed(CHAT)
        db.conversationDao().toggleMute(CHAT, true)
        db.conversationDao().toggleArchive(CHAT, true)

        db.conversationDao().updateConversationMeta(CHAT, "direct", "Alice", null)

        val row = db.conversationDao().getConversationById(CHAT)
        assertEquals("mute must survive a metadata refresh", true, row?.isMuted)
        assertEquals("archive must survive a metadata refresh", true, row?.isArchived)
    }

    /**
     * Process death: the cache is a file, so closing and reopening the database
     * is the honest analogue of the process being destroyed and restarted.
     */
    @Test
    fun cachedMessagesSurviveDatabaseCloseAndReopen() = runBlocking {
        seed(CHAT)
        db.conversationDao().updateConversationMeta(CHAT, "direct", "Alice", null)
        db.close()

        db = open()
        val recovered = db.messageDao().getAllMessagesByConversation(CHAT)
        assertEquals("messages must survive process death", COUNT, recovered.size)
        assertEquals("message 0", recovered.last().content)
        assertNotNull(db.messageDao().getMessageById("$CHAT-m0"))
    }

    /** Reopening must not need the network: everything is already local. */
    @Test
    fun everyCachedMessageIsReadableOffline() = runBlocking {
        seed(CHAT)
        db.close()
        db = open()
        val all = db.messageDao().getAllMessagesByConversation(CHAT)
        assertEquals(COUNT, all.size)
        assertEquals("no message may be lost", COUNT, all.map { it.id }.toSet().size)
    }

    /** Re-inserting the same server page must not duplicate rows. */
    @Test
    fun reCachingTheSameMessagesIsIdempotent() = runBlocking {
        seed(CHAT)
        seed(CHAT) // same ids again, as a duplicate server response would
        assertEquals(COUNT, db.messageDao().getAllMessagesByConversation(CHAT).size)
    }

    /** One conversation's lifecycle must never touch another's cache. */
    @Test
    fun conversationsAreIsolatedFromEachOther() = runBlocking {
        seed(CHAT)
        seed(OTHER_CHAT)

        db.conversationDao().updateConversationMeta(CHAT, "direct", "Alice", null)
        assertEquals(COUNT, db.messageDao().getAllMessagesByConversation(OTHER_CHAT).size)

        // Deleting one chat clears only its own messages.
        db.messageDao().deleteMessagesByConversation(CHAT)
        db.conversationDao().deleteConversation(CHAT)
        assertEquals(0, db.messageDao().getAllMessagesByConversation(CHAT).size)
        assertEquals(
            "an unrelated conversation must keep its cache",
            COUNT, db.messageDao().getAllMessagesByConversation(OTHER_CHAT).size
        )
        assertNull(db.conversationDao().getConversationById(CHAT))
        assertNotNull(db.conversationDao().getConversationById(OTHER_CHAT))
    }

    /** Pagination must add older messages without evicting newer ones. */
    @Test
    fun loadingAnOlderPageDoesNotEvictNewerMessages() = runBlocking {
        seed(CHAT) // timestamps 1000..1049
        db.messageDao().insertMessages(
            (0 until 20).map { i ->
                MessageEntity(
                    id = "$CHAT-old$i",
                    conversation_id = CHAT,
                    senderId = "sender",
                    content = "older $i",
                    type = "text",
                    status = "SENT",
                    timestamp = 500L + i
                )
            }
        )
        val all = db.messageDao().getAllMessagesByConversation(CHAT)
        assertEquals("older page must be additive", COUNT + 20, all.size)
        assertEquals("newest first", "message 49", all.first().content)
    }
}
