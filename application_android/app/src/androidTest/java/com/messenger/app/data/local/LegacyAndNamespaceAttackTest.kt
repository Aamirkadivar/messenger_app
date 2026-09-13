package com.messenger.app.data.local

import android.database.sqlite.SQLiteDatabase
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * GATE 21 - the legacy-adoption and namespace attacks, on a real device.
 *
 * The isolation claim is about FILES, so these assertions look at the actual
 * files the app creates and at a real, populated legacy database planted where
 * a pre-Gate-19 install would have left one.
 */
@RunWith(AndroidJUnit4::class)
class LegacyAndNamespaceAttackTest {

    private companion object {
        const val A = "aaaaaaaa-1111-1111-1111-11111111111a"
        const val B = "bbbbbbbb-2222-2222-2222-22222222222b"
        const val C = "cccccccc-3333-3333-3333-33333333333c"
    }

    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = null
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: FakeAuth
    private lateinit var holder: AccountCacheHolder

    @Before
    fun setUp() {
        for (id in listOf(A, B, C)) ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        ctx.deleteDatabase(AccountCacheNamespace.LEGACY_QUARANTINED_DB)
        auth = FakeAuth()
        holder = AccountCacheHolder(ctx, Provider { auth })
    }

    @After
    fun tearDown(): Unit = runBlocking {
        holder.deactivate()
        for (id in listOf(A, B, C)) ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        ctx.deleteDatabase(AccountCacheNamespace.LEGACY_QUARANTINED_DB)
    }

    /** Writes an unmistakable legacy database exactly where the old build kept one. */
    private fun plantLegacyDatabase() {
        val path = ctx.getDatabasePath(AccountCacheNamespace.LEGACY_QUARANTINED_DB)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY NOT NULL, type TEXT, name TEXT)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL," +
                " content TEXT, isEncrypted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS cached_chats (id TEXT PRIMARY KEY NOT NULL, rawJson TEXT, cachedAt INTEGER)")
        db.execSQL("INSERT OR REPLACE INTO conversations VALUES ('LEGACY_A','direct','legacy chat of A')")
        db.execSQL("INSERT OR REPLACE INTO messages VALUES ('LEGACY_A','LEGACY_A','LEGACY PLAINTEXT OF A',0)")
        db.execSQL("INSERT OR REPLACE INTO cached_chats VALUES ('LEGACY_A','{\"id\":\"LEGACY_A\"}',1)")
        db.close()
    }

    // ------------------------------------------------------------- Phase 11

    @Test
    fun aPopulatedLegacyDatabaseIsNeverAdoptedByAnyAccount() = runBlocking {
        plantLegacyDatabase()
        val legacyFile = ctx.getDatabasePath(AccountCacheNamespace.LEGACY_QUARANTINED_DB)
        assertTrue("precondition: the legacy database exists", legacyFile.exists())
        val sizeBefore = legacyFile.length()

        val messages = ScopedMessageDao(holder)
        val conversations = ScopedConversationDao(holder)
        val chats = ScopedCachedChatDao(holder)

        // Several accounts, several logout/login cycles, and a rebuilt holder
        // standing in for a restarted process - none may inherit those rows.
        for (round in 0 until 2) {
            for (id in listOf(A, B, C)) {
                auth.account = id
                assertNull("$id adopted a legacy message (round $round)",
                    messages.getMessageById("LEGACY_A"))
                assertNull("$id adopted a legacy conversation (round $round)",
                    conversations.getConversationById("LEGACY_A"))
                assertEquals("$id adopted the legacy chat list (round $round)",
                    emptyList<CachedChatEntity>(), chats.getAllCached())
                assertEquals("$id adopted legacy messages (round $round)",
                    emptyList<MessageEntity>(), messages.getAllMessagesByConversation("LEGACY_A"))
                auth.account = null
                holder.deactivate()
            }
        }

        // Restarted process.
        val restarted = AccountCacheHolder(ctx, Provider { FakeAuth().apply { account = A } })
        assertNull("a restarted process adopted the legacy database",
            ScopedMessageDao(restarted).getMessageById("LEGACY_A"))
        restarted.deactivate()

        assertTrue("the legacy file must be preserved, not deleted", legacyFile.exists())
        assertEquals("the legacy file must be left untouched", sizeBefore, legacyFile.length())
    }

    // ------------------------------------------------------------- Phase 12

    @Test
    fun eachAccountGetsItsOwnPhysicalDatabaseFile() = runBlocking {
        val messages = ScopedMessageDao(holder)
        val conversations = ScopedConversationDao(holder)

        for (id in listOf(A, B, C)) {
            auth.account = id
            conversations.insertConversation(id, ConversationEntity(id = "chat-$id", type = "direct"))
            messages.insertMessages(
                id,
                listOf(
                    MessageEntity(
                        id = "msg-$id", conversation_id = "chat-$id", senderId = "s",
                        content = "secret of $id", type = "text", status = "SENT", timestamp = 1
                    )
                )
            )
            auth.account = null
            holder.deactivate()
        }

        // Distinct physical paths that actually exist on disk.
        val paths = listOf(A, B, C).map { ctx.getDatabasePath(AccountCacheNamespace.databaseNameFor(it)) }
        assertEquals("each account must have its own file", 3, paths.map { it.absolutePath }.toSet().size)
        for (f in paths) assertTrue("expected a real database file at ${f.absolutePath}", f.exists())

        // No raw account identifier anywhere in the path, and no traversal.
        for ((id, f) in listOf(A, B, C).zip(paths)) {
            assertFalse("the raw account id must not appear in the path",
                f.absolutePath.contains(id))
            assertFalse("no traversal in the path", f.absolutePath.contains(".."))
            assertTrue("unexpected file name: ${f.name}",
                Regex("^messenger_cache_[0-9a-f]{32}$").matches(f.name))
        }

        // Each file holds only its own rows.
        for (id in listOf(A, B, C)) {
            auth.account = id
            assertEquals("secret of $id", messages.getMessageById("msg-$id")?.content)
            for (other in listOf(A, B, C).filter { it != id }) {
                assertNull("$id could read $other's message", messages.getMessageById("msg-$other"))
            }
            auth.account = null
            holder.deactivate()
        }
    }

    @Test
    fun namespacesCannotCollideThroughCaseOrWhitespace() {
        assertNotEquals(
            AccountCacheNamespace.databaseNameFor("user-ABC"),
            AccountCacheNamespace.databaseNameFor("user-abc")
        )
        assertEquals(
            AccountCacheNamespace.databaseNameFor(A),
            AccountCacheNamespace.databaseNameFor("  $A  ")
        )
        assertNotEquals(
            AccountCacheNamespace.databaseNameFor("a b"),
            AccountCacheNamespace.databaseNameFor("ab")
        )
    }
}
