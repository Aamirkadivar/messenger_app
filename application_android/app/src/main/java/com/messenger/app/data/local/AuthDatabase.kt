package com.messenger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.UserDao
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
        UserEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AuthDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AuthDatabase? = null

        fun getDatabase(
            context: Context,
            tokenManager: com.messenger.app.security.TokenManager
        ): AuthDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuthDatabase::class.java,
                    "messenger_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}