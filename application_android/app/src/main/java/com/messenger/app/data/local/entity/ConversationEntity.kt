package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for conversations/chats
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val lastMessage: String? = null,
    val unreadCount: Int = 0,
    val lastUpdated: Long? = null,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false
)