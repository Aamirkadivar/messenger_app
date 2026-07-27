package com.messenger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.messenger.app.data.local.entity.CachedChatEntity

@Dao
interface CachedChatDao {

    @Query("SELECT * FROM cached_chats ORDER BY cachedAt DESC")
    suspend fun getAllCached(): List<CachedChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chats: List<CachedChatEntity>)
}
