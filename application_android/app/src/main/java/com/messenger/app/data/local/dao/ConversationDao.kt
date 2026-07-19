package com.messenger.app.data.local.dao

import androidx.room.*
import com.messenger.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY lastUpdated DESC")
    fun getAllConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastUpdated DESC")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :convId")
    suspend fun getConversationById(convId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :convId")
    fun getConversationFlow(convId: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :convId")
    suspend fun deleteConversation(convId: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :convId")
    suspend fun clearUnreadCount(convId: String)

    @Query("UPDATE conversations SET isMuted = :isMuted WHERE id = :convId")
    suspend fun toggleMute(convId: String, isMuted: Boolean)

    @Query("UPDATE conversations SET isArchived = :isArchived WHERE id = :convId")
    suspend fun toggleArchive(convId: String, isArchived: Boolean)

    @Query("SELECT * FROM conversations WHERE type = 'DIRECT' ORDER BY lastUpdated DESC")
    fun getDirectConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE type = 'GROUP' ORDER BY lastUpdated DESC")
    fun getGroupConversationsFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE lastUpdated > :since ORDER BY lastUpdated DESC")
    fun getUpdatedConversations(since: Long): Flow<List<ConversationEntity>>
}