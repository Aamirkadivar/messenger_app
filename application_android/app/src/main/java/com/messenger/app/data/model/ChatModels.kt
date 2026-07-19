package com.messenger.app.data.model

import kotlinx.serialization.Serializable

/**
 * User model representing an app user
 */
@Serializable
data class User(
    val id: String,
    val name: String,
    val username: String,
    val email: String,
    val avatarUrl: String? = null,
    val status: UserStatus = UserStatus.OFFLINE,
    val lastSeen: Long? = null,
    val bio: String? = null,
    val publicKey: String? = null,
    val createdAt: Long? = null
)

/**
 * User online status
 */
enum class UserStatus {
    ONLINE,
    OFFLINE,
    AWAY,
    DO_NOT_DISTURB,
    TYPING
}

/**
 * Message type enum
 */
enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    VOICE_MESSAGE,
    SYSTEM,
    ENCRYPTED
}

/**
 * Message delivery status
 */
enum class MessageStatus {
    SENT,
    DELIVERED,
    READ,
    FAILED,
    PENDING
}

/**
 * Chat message model
 */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.PENDING,
    val timestamp: Long,
    val encryptedContent: String? = null,
    val encryptionKeyId: String? = null,
    val encryptionNonce: String? = null,
    val recipientId: String? = null,
    val replyTo: String? = null,
    val mentions: List<Mention> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val isEncrypted: Boolean = false
) {
    val isOwnMessage: Boolean = false
}

/**
 * Message request for sending
 */
@Serializable
data class SendMessageRequest(
    val conversationId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val encryptedContent: String? = null,
    val encryptionKeyId: String? = null,
    val encryptionNonce: String? = null,
    val replyTo: String? = null,
    val mentions: List<Mention> = emptyList()
)

/**
 * Message pagination request
 */
@Serializable
data class MessagePaginationRequest(
    val conversationId: String,
    val before: Long? = null,
    val after: Long? = null,
    val limit: Int = 20
)

/**
 * Message pagination response
 */
@Serializable
data class MessagePaginationResponse(
    val messages: List<Message>,
    val hasMore: Boolean,
    val cursor: String? = null
)

/**
 * Conversation/Chat model
 */
@Serializable
data class Conversation(
    val id: String,
    val type: ConversationType,
    val participants: List<User> = emptyList(),
    val name: String? = null,
    val avatarUrl: String? = null,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val typingUsers: List<String> = emptyList(),
    val lastUpdated: Long? = null,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false
)

/**
 * Conversation type
 */
enum class ConversationType {
    DIRECT,
    GROUP,
    SUPERGROUP
}

/**
 * Create group request
 */
@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val participantIds: List<String>
)

/**
 * Group member update
 */
@Serializable
data class UpdateGroupRequest(
    val participantIds: List<String>,
    val adminIds: List<String> = emptyList()
)

/**
 * Mention in message
 */
@Serializable
data class Mention(
    val userId: String,
    val userName: String,
    val start: Int,
    val end: Int
)

/**
 * Message attachment
 */
@Serializable
data class Attachment(
    val id: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val type: AttachmentType,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val fileName: String? = null
)

/**
 * Attachment type enum
 */
enum class AttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    VOICE_MESSAGE
}

/**
 * Typing indicator event
 */
data class TypingIndicator(
    val userId: String,
    val userName: String,
    val conversationId: String,
    val isTyping: Boolean,
    val timestamp: Long
)

/**
 * Online status update
 */
data class PresenceUpdate(
    val userId: String,
    val status: UserStatus,
    val lastSeen: Long? = null
)

/**
 * Notification payload from FCM
 */
@Serializable
data class PushNotification(
    val type: NotificationType,
    val title: String,
    val body: String,
    val data: NotificationData? = null,
    val timestamp: Long? = null
)

/**
 * Notification type
 */
enum class NotificationType {
    MESSAGE,
    GROUP_UPDATE,
    TYPING,
    PRESENCE,
    SYSTEM
}

/**
 * Notification data payload
 */
@Serializable
data class NotificationData(
    val conversationId: String? = null,
    val senderId: String? = null,
    val messageId: String? = null,
    val type: String? = null
)

/**
 * Search result model
 */
@Serializable
data class SearchResults(
    val users: List<User> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val messages: List<Message> = emptyList()
)

/**
 * Profile update request
 */
@Serializable
data class ProfileUpdateRequest(
    val name: String? = null,
    val bio: String? = null,
    val username: String? = null
)