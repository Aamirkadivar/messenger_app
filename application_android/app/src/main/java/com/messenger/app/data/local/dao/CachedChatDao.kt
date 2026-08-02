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

    /**
     * Drops one chat's cached list entry. Without this a deleted chat would
     * reappear from the cache on the next cold start, before the network
     * refresh had a chance to correct it.
     */
    @Query("DELETE FROM cached_chats WHERE id = :chatId")
    suspend fun deleteCached(chatId: String)
}
