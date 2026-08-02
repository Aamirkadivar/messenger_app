package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for storing messages locally
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversation_id: String,
    val senderId: String,
    val content: String,
    val type: String, // Serialized MessageType
    val status: String, // Serialized MessageStatus
    val timestamp: Long,
    val encryptedContent: String? = null,
    val encryptionKeyId: String? = null,
    val encryptionNonce: String? = null,
    val recipientId: String? = null,
    val replyTo: String? = null,
    val mentions: String? = null, // JSON serialized
    val attachments: String? = null, // JSON serialized
    val isEncrypted: Boolean = false,
    val readAt: String? = null,
    // Voice note / generic file attachment. fileType carries the kind
    // ("audio", "image", "file"); fileName/fileSize are display metadata for
    // attachments, meaningless for voice.
    val fileUrl: String? = null,
    val fileType: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val durationMs: Long = 0,
    // Which of the sender's group Sender Key versions encrypted this message
    // (see ChatRepository's group E2EE). 0 outside a group chat.
    val keyVersion: Int = 0
)