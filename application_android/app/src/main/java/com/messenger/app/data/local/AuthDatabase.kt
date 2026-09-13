package com.messenger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.messenger.app.data.local.dao.CachedChatDao
import com.messenger.app.data.local.dao.ChatSyncStateDao
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.OutboxDao
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.local.entity.CachedChatEntity
import com.messenger.app.data.local.entity.ChatSyncStateEntity
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.UserEntity

/**
 * Room database for the messenger app
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        UserEntity::class,
        CachedChatEntity::class,
        OutboxEntity::class,
        ChatSyncStateEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AuthDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userDao(): UserDao
    abstract fun cachedChatDao(): CachedChatDao
    abstract fun outboxDao(): OutboxDao
    abstract fun chatSyncStateDao(): ChatSyncStateDao

    companion object {
        /**
         * v7 -> v8: adds the Layer B archive columns to `messages`.
         *
         * Strictly additive - three ADD COLUMN statements, no table rebuild, no data rewrite, no
         * existing column touched. Every pre-existing row keeps its `content` exactly as it was and
         * simply reads as "never archived": archiveCiphertext NULL, archiveRootVersion 0,
         * archiveState NULL (which `ArchiveState.fromWire` maps to NONE).
         *
         * archiveRootVersion carries `DEFAULT 0` because SQLite requires a default when adding a
         * NOT NULL column, and the entity declares `@ColumnInfo(defaultValue = "0")` so Room's
         * expected schema matches what this statement actually produces. The two nullable TEXT
         * columns deliberately have no DEFAULT, matching a plain nullable Room column.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ArchiveSchemaV8.ADD_COLUMNS.forEach(db::execSQL)
            }
        }

        /**
         * v8 -> v9: the durable outbox and the per-chat sync point (Phase 71).
         *
         * Strictly additive: two new tables, nothing existing touched. Column order, types,
         * nullability and index names match what Room generates for [OutboxEntity] and
         * [ChatSyncStateEntity] (see schemas/9.json), so the post-migration validation passes.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox_messages` (`clientMessageId` TEXT NOT NULL, " +
                        "`chatId` TEXT NOT NULL, `chatType` TEXT NOT NULL, `requestJson` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
                        "`nextAttemptAt` INTEGER NOT NULL, `lastError` TEXT, `serverMessageId` TEXT, " +
                        "`acceptedAt` INTEGER, PRIMARY KEY(`clientMessageId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_messages_chatId` ON `outbox_messages` (`chatId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_messages_state` ON `outbox_messages` (`state`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_sync_state` (`chatId` TEXT NOT NULL, " +
                        "`lastSeq` INTEGER NOT NULL, `backfillBefore` INTEGER, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`))"
                )
            }
        }

        /**
         * Opens the cache database called [databaseName].
         *
         * Gate 19: the name comes from [AccountCacheHolder] and is derived from
         * the authenticated account, because the physical database is now the
         * account boundary. There is deliberately no account-blind overload and
         * no process-wide INSTANCE - either would hand one shared cache to every
         * account and undo the isolation. The holder owns both the lifecycle and
         * the caching of this handle.
         *
         * The pre-Gate-19 database was named
         * [AccountCacheNamespace.LEGACY_QUARANTINED_DB]. Nothing passes that name
         * any more, and the guard below refuses it, so it can no longer be opened
         * by any code path.
         */
        fun getDatabase(context: Context, databaseName: String): AuthDatabase {
            require(databaseName.startsWith(AccountCacheNamespace.PREFIX)) {
                "refusing to open a cache database outside the account namespace"
            }
            return Room.databaseBuilder(
                context.applicationContext,
                AuthDatabase::class.java,
                databaseName
            )
                // Destructive fallback ONLY when going backwards (a debug
                // build older than the installed data). A forward schema
                // bump must never wipe this database: it holds the only
                // readable copy of our own MLS group messages, since an MLS
                // sender cannot decrypt its own ciphertext and the server
                // copy is therefore unrecoverable. Losing it is permanent
                // data loss, not a cache miss. Add a Migration for each
                // version bump instead.
                .fallbackToDestructiveMigrationOnDowngrade()
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                .build()
        }
    }
}