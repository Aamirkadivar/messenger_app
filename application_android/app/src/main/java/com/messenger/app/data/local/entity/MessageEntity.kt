package com.messenger.app.data.local.entity

import androidx.room.ColumnInfo
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
    val keyVersion: Int = 0,
    val encryptionVersion: Int = 1,
    // Which of the sender's devices sealed this message. v3/v4 ratchet
    // sessions are keyed by "chatId|senderDeviceId"; a cached row without it
    // decrypts against the wrong session on reopen.
    val senderDeviceId: String = "",
    val isForwarded: Boolean = false,
    val forwardedFromName: String = "",
    val forwardedFromMessageId: String = "",
    // ---- Layer B recoverable history archive (added in schema v8) ----
    // Added additively: [content] above stays exactly as it was for this rollout, so these
    // columns coexist with the existing plaintext copy rather than replacing it.
    /**
     * XChaCha20-Poly1305 archive of this message, Base64, as produced by
     * `HistoryMessageCipher.seal` (nonce || ciphertext || tag). Null when the row has no archive.
     * Never holds plaintext: a failed seal leaves this null rather than writing anything.
     */
    val archiveCiphertext: String? = null,
    /**
     * Which per-chat history root version sealed [archiveCiphertext]. Bound into both the key
     * derivation and the AAD, so without it the archive cannot be opened after a rotation.
     * 0 means "no archive"; real versions start at 1.
     */
    @ColumnInfo(defaultValue = "0")
    val archiveRootVersion: Int = 0,
    /**
     * `ArchiveState.wire`. Null reads as `NONE`, which is what every pre-v8 row is.
     */
    val archiveState: String? = null
)