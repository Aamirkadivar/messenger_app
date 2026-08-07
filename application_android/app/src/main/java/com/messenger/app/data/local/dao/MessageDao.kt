package com.messenger.app.data.local.dao

import androidx.room.*
import com.messenger.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for messages
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getMessagesByConversation(convId: String, limit: Int = 20, offset: Int = 0): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :convId AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    fun getMessagesBefore(convId: String, beforeTimestamp: Long, limit: Int = 20): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :convId AND timestamp > :afterTimestamp ORDER BY timestamp ASC LIMIT :limit")
    fun getMessagesAfter(convId: String, afterTimestamp: Long, limit: Int = 20): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY timestamp DESC")
    fun getAllMessagesByConversationFlow(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY timestamp DESC")
    suspend fun getAllMessagesByConversation(convId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(convId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :convId")
    fun getMessageCount(convId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversation_id = :convId")
    suspend fun deleteMessagesByConversation(convId: String)

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' OR conversation_id IN (SELECT id FROM conversations WHERE name LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchMessages(query: String): Flow<List<MessageEntity>>
}