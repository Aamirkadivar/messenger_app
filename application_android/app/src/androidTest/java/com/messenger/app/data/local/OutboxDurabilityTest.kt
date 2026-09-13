package com.messenger.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.local.dao.ScopedChatSyncStateDao
import com.messenger.app.data.local.dao.ScopedOutboxDao
import com.messenger.app.data.local.entity.ChatSyncStateEntity
import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.OutboxState
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * PHASE 71 - the durable outbox on a real device, in the real per-account Room database.
 *
 * Android criteria 11 (a pending message survives the ViewModel) and 12 (it survives an app or
 * process restart): outbox rows are written through the production [ScopedOutboxDao] into the
 * account's [AuthDatabase] file. "Process restart" is modelled the way AccountCacheIsolationTest
 * models it: the handle is closed and a brand-new [AccountCacheHolder] - the object graph a fresh
 * process builds - opens the same file. Plus the v8 -> v9 migration keeps every existing message.
 */
@RunWith(AndroidJUnit4::class)
class OutboxDurabilityTest {

    private companion object {
        const val ACCOUNT_A = "aaaaaaaa-7171-0000-0000-00000000000a"
        const val ACCOUNT_B = "bbbbbbbb-7171-0000-0000-00000000000b"
        const val CHAT = "chat-p71"
        const val TEST_DB = "outbox-migration-test.db"
    }

    private class FakeAuth : TokenManager by mockk(relaxed = true) {
        @Volatile var account: String? = null
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(account)
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var auth: FakeAuth
    private lateinit var holder: AccountCacheHolder

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuthDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Before
    fun setUp() {
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
        auth = FakeAuth().apply { account = ACCOUNT_A }
        holder = AccountCacheHolder(ctx, Provider { auth })
    }

    @After
    fun tearDown() = runBlocking {
        holder.deactivate()
        for (id in listOf(ACCOUNT_A, ACCOUNT_B)) ctx.deleteDatabase(AccountCacheNamespace.databaseNameFor(id))
    }

    /** A row exactly as ChatRepository.sendText writes it: sealed body, never plaintext. */
    private fun queued(cmid: String, createdAt: Long) = OutboxEntity(
        clientMessageId = cmid, chatId = CHAT, chatType = "direct",
        requestJson = """{"chat_id":"$CHAT","content":"9f8e7d6c5b4a","encrypted":true,"encryption_version":4,"client_message_id":"$cmid"}""",
        state = OutboxState.PENDING, createdAt = createdAt, attempts = 0, nextAttemptAt = createdAt,
        lastError = null, serverMessageId = null, acceptedAt = null
    )

    @Test
    fun pendingMessagesSurviveViewModelAndProcessRestart() = runBlocking {
        val first = queued("11111111-7171-4111-8111-111111111111", 1_000)
        val second = queued("22222222-7171-4222-8222-222222222222", 2_000)
        ScopedOutboxDao(holder).apply {
            insert(ACCOUNT_A, first)
            insert(ACCOUNT_A, second)
            markRetry(ACCOUNT_A, second.clientMessageId, 3, 5_000, "ConnectException")
        }

        // (11) A new consumer - what a recreated ViewModel reaches through the singleton repository.
        val afterRecreate = ScopedOutboxDao(holder).unacceptedForChat(CHAT)
        assertEquals(listOf(first.clientMessageId, second.clientMessageId), afterRecreate.map { it.clientMessageId })

        // (12) Process restart: every handle closed, a brand-new holder opens the same file.
        holder.deactivate()
        val restarted = AccountCacheHolder(ctx, Provider { auth })
        val outbox = ScopedOutboxDao(restarted)
        val rows = outbox.unacceptedForChat(CHAT)
        println("P71 A12(device): after restart ${rows.size} pending row(s): ${rows.map { it.state to it.attempts }}")
        assertEquals(2, rows.size)
        assertEquals("byte-identical request survives", first.requestJson, rows[0].requestJson)
        assertEquals(first.clientMessageId, rows[0].clientMessageId)
        assertEquals(3, rows[1].attempts)
        assertEquals(listOf(first.clientMessageId), outbox.due(4_000).map { it.clientMessageId })

        // ACCEPTED is terminal for the retry loop; a FAILED row can be explicitly re-armed.
        outbox.markAccepted(ACCOUNT_A, first.clientMessageId, "server-1", 6_000)
        outbox.markFailed(ACCOUNT_A, second.clientMessageId, 4, "HTTP 403")
        assertEquals(0, outbox.resetForRetry(ACCOUNT_A, first.clientMessageId, 7_000))
        assertEquals(1, outbox.resetForRetry(ACCOUNT_A, second.clientMessageId, 7_000))
        assertEquals(listOf(second.clientMessageId), outbox.due(7_000).map { it.clientMessageId })
        restarted.deactivate()
    }

    @Test
    fun aClientMessageIdIsNeverOverwritten() = runBlocking {
        val item = queued("33333333-7171-4333-8333-333333333333", 1_000)
        val dao = ScopedOutboxDao(holder)
        dao.insert(ACCOUNT_A, item)
        try {
            dao.insert(ACCOUNT_A, item.copy(requestJson = "{}"))
            fail("a second insert under the same client_message_id must be refused")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
        }
        assertEquals(item.requestJson, dao.get(item.clientMessageId)!!.requestJson)
    }

    @Test
    fun anOutboxWriteFromTheWrongAccountIsRefused() = runBlocking {
        val dao = ScopedOutboxDao(holder)
        auth.account = ACCOUNT_B
        try {
            dao.insert(ACCOUNT_A, queued("44444444-7171-4444-8444-444444444444", 1_000))
            fail("a write owned by A must not land while B is signed in")
        } catch (expected: CacheOwnershipViolation) {
        }
        assertTrue(dao.unacceptedForChat(CHAT).isEmpty())
    }

    @Test
    fun syncPointsPersistPerAccount() = runBlocking {
        ScopedChatSyncStateDao(holder).upsert(ACCOUNT_A, ChatSyncStateEntity(CHAT, 237, 101, 1))
        holder.deactivate()
        val restarted = AccountCacheHolder(ctx, Provider { auth })
        val point = ScopedChatSyncStateDao(restarted).get(CHAT)
        assertNotNull(point)
        assertEquals(237L, point!!.lastSeq)
        assertEquals(101L, point.backfillBefore)
        auth.account = ACCOUNT_B
        assertNull("another account never sees A's sync point", ScopedChatSyncStateDao(restarted).get(CHAT))
        restarted.deactivate()
    }

    /** v8 -> v9 is additive: existing messages survive; the new tables exist and are writable. */
    @Test
    fun v8OpensAsV9WithMessagesIntact() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, unreadCount, isMuted, isArchived) VALUES ('$CHAT', 'direct', 0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, senderId, content, type, status, timestamp, isEncrypted, " +
                    "fileSize, durationMs, keyVersion, encryptionVersion, senderDeviceId, isForwarded, " +
                    "forwardedFromName, forwardedFromMessageId, archiveRootVersion) VALUES ('m-1', '$CHAT', " +
                    "'peer', 'p71-canary', 'text', 'SENT', 1700000000000, 0, 0, 0, 0, 1, 'd', 0, '', '', 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AuthDatabase.MIGRATION_8_9)
        db.query("SELECT content FROM messages WHERE id = 'm-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("p71-canary", c.getString(0))
        }
        db.execSQL(
            "INSERT INTO outbox_messages (clientMessageId, chatId, chatType, requestJson, state, createdAt, " +
                "attempts, nextAttemptAt) VALUES ('c', '$CHAT', 'direct', '{}', 'PENDING', 1, 0, 1)"
        )
        db.execSQL("INSERT INTO chat_sync_state (chatId, lastSeq, updatedAt) VALUES ('$CHAT', 5, 1)")
        db.query("SELECT count(*) FROM outbox_messages").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        println("P71 A-migration(device): v8 -> v9 kept the message, new tables writable")
    }
}
