package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Raw JSON snapshot of a chat-list entry (ChatListItemDto), so the chat list
 * can render offline before the network refresh completes - mirrors the
 * windows_app MessageCache "chats" table (id, raw_json, cached_at).
 */
@Entity(tableName = "cached_chats")
data class CachedChatEntity(
    @PrimaryKey val id: String,
    val rawJson: String,
    val cachedAt: Long
)
