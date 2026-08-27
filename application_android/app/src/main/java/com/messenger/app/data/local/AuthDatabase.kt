package com.messenger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.messenger.app.data.local.dao.CachedChatDao
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.local.entity.CachedChatEntity
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.local.entity.UserEntity

/**
 * Room database for the messenger app
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        UserEntity::class,
        CachedChatEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AuthDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userDao(): UserDao
    abstract fun cachedChatDao(): CachedChatDao

    companion object {
        @Volatile
        private var INSTANCE: AuthDatabase? = null

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

        fun getDatabase(context: Context): AuthDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuthDatabase::class.java,
                    "messenger_database"
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
                    .addMigrations(MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}