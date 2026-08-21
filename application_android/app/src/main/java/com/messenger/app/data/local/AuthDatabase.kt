package com.messenger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 7,
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
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}