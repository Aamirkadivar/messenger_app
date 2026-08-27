package com.messenger.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 3 CHECKPOINT B - the v7 -> v8 migration against real SQLite.
 *
 * Runs on a device because MigrationTestHelper needs an actual SQLite instance. The JVM-side
 * ArchiveSchemaV8Test already proves the statements are additive and that nullability/defaults
 * match the exported schema; what can only be proven here is that a POPULATED v7 database survives
 * the migration with its rows intact.
 *
 * Uses its own temporary database file, so it never touches the app's real `messenger_database`.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveMigration7to8Test {

    private companion object {
        const val TEST_DB = "archive-migration-test.db"
        const val CONV_ID = "conv-under-test"
        const val MSG_ID = "msg-under-test"
        const val PLAINTEXT = "gate3-checkpoint-b-canary-plaintext"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuthDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * A v7 database with one conversation and one message must open as v8 with the row unchanged
     * and the three archive columns present and empty.
     */
    @Test
    fun v7OpensAsV8WithExistingMessagesIntact() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, unreadCount, isMuted, isArchived) " +
                    "VALUES ('$CONV_ID', 'direct', 0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, senderId, content, type, status, " +
                    "timestamp, isEncrypted, fileSize, durationMs, keyVersion, encryptionVersion, " +
                    "senderDeviceId, isForwarded, forwardedFromName, forwardedFromMessageId) " +
                    "VALUES ('$MSG_ID', '$CONV_ID', 'sender-1', '$PLAINTEXT', 'text', 'SENT', " +
                    "1700000000000, 0, 0, 0, 0, 1, 'device-1', 0, '', '')"
            )
        }

        // validateDroppedTables = true: a rebuild that left a stale table behind would fail here.
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AuthDatabase.MIGRATION_7_8)

        db.query("SELECT * FROM messages WHERE id = '$MSG_ID'").use { c ->
            assertTrue("the pre-existing row must survive the migration", c.moveToFirst())
            assertEquals(1, c.count)
            assertEquals(
                "existing plaintext content must be untouched",
                PLAINTEXT,
                c.getString(c.getColumnIndexOrThrow("content"))
            )
            assertEquals("sender-1", c.getString(c.getColumnIndexOrThrow("senderId")))
            assertEquals(1700000000000L, c.getLong(c.getColumnIndexOrThrow("timestamp")))

            // New columns exist and default to "never archived".
            assertNull(c.getString(c.getColumnIndexOrThrow("archiveCiphertext")))
            assertNull(c.getString(c.getColumnIndexOrThrow("archiveState")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("archiveRootVersion")))
        }
    }

    /** An empty v7 database must migrate too - the common case for a fresh install. */
    @Test
    fun emptyV7Migrates() {
        helper.createDatabase(TEST_DB, 7).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AuthDatabase.MIGRATION_7_8)
        db.query("SELECT COUNT(*) FROM messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    /**
     * Row count is preserved exactly. A destructive migration would silently pass the column
     * assertions above while emptying the table, so this checks the thing that would actually be
     * lost.
     */
    @Test
    fun migrationIsNonDestructiveAcrossManyRows() {
        val rows = 25
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, unreadCount, isMuted, isArchived) " +
                    "VALUES ('$CONV_ID', 'direct', 0, 0, 0)"
            )
            repeat(rows) { i ->
                db.execSQL(
                    "INSERT INTO messages (id, conversation_id, senderId, content, type, status, " +
                        "timestamp, isEncrypted, fileSize, durationMs, keyVersion, " +
                        "encryptionVersion, senderDeviceId, isForwarded, forwardedFromName, " +
                        "forwardedFromMessageId) " +
                        "VALUES ('m$i', '$CONV_ID', 'sender-1', 'body-$i', 'text', 'SENT', " +
                        "${1700000000000L + i}, 0, 0, 0, 0, 1, 'device-1', 0, '', '')"
                )
            }
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AuthDatabase.MIGRATION_7_8)
        db.query("SELECT COUNT(*) FROM messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("no row may be lost", rows, c.getInt(0))
        }
        db.query("SELECT content FROM messages WHERE id = 'm7'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("body-7", c.getString(0))
        }
    }

    /** The new columns must be writable after migrating, which is what Checkpoints C-E rely on. */
    @Test
    fun archiveColumnsAreUsableAfterMigration() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, unreadCount, isMuted, isArchived) " +
                    "VALUES ('$CONV_ID', 'direct', 0, 0, 0)"
            )
            db.execSQL(
                "INSERT INTO messages (id, conversation_id, senderId, content, type, status, " +
                    "timestamp, isEncrypted, fileSize, durationMs, keyVersion, encryptionVersion, " +
                    "senderDeviceId, isForwarded, forwardedFromName, forwardedFromMessageId) " +
                    "VALUES ('$MSG_ID', '$CONV_ID', 'sender-1', '$PLAINTEXT', 'text', 'SENT', " +
                    "1700000000000, 0, 0, 0, 0, 1, 'device-1', 0, '', '')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AuthDatabase.MIGRATION_7_8)
        db.execSQL(
            "UPDATE messages SET archiveCiphertext = 'c2VhbGVk', archiveRootVersion = 3, " +
                "archiveState = 'sealed' WHERE id = '$MSG_ID'"
        )
        db.query("SELECT archiveCiphertext, archiveRootVersion, archiveState FROM messages " +
            "WHERE id = '$MSG_ID'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("c2VhbGVk", c.getString(0))
            assertEquals(3, c.getInt(1))
            assertEquals("sealed", c.getString(2))
        }
    }
}
