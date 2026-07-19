package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.messenger.app.data.model.MessageType
import com.messenger.app.data.model.MessageStatus

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
    val id: String,
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
    val isEncrypted: Boolean = false
) {
    fun getMessageType(): MessageType {
        return try {
            MessageType.valueOf(type)
        } catch (e: Exception) {
            MessageType.TEXT
        }
    }

    fun getMessageStatus(): MessageStatus {
        return try {
            MessageStatus.valueOf(status)
        } catch (e: Exception) {
            MessageStatus.PENDING
        }
    }
}