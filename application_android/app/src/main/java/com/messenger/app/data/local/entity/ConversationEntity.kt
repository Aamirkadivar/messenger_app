package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.messenger.app.data.model.ConversationType

/**
 * Room entity for conversations/chats
 */
@Entity(
    tableName = "conversations",
    indices = [Index("id", unique = true)]
)
data class ConversationEntity(
    val id: String,
    val type: String, // Serialized ConversationType
    val name: String? = null,
    val avatarUrl: String? = null,
    val lastMessage: String? = null, // JSON serialized MessageEntity
    val unreadCount: Int = 0,
    val lastUpdated: Long? = null,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false
) {
    fun getConversationType(): ConversationType {
        return try {
            ConversationType.valueOf(type)
        } catch (e: Exception) {
            ConversationType.DIRECT
        }
    }
}