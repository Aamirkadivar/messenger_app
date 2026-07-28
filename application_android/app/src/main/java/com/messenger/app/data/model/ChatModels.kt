package com.messenger.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * These models mirror the real backend contract (back-end/handlers/message.go,
 * back-end/models/models.go).
 */

@Serializable
data class ChatParticipantDto(
    val id: String,
    val email: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class DirectChatDto(
    val id: String,
    val type: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val participants: List<ChatParticipantDto> = emptyList()
)

@Serializable
data class DirectChatResponse(
    val data: DirectChatDto
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("sender_id") val senderId: String,
    val sender: ChatParticipantDto? = null,
    val content: String = "",
    val encrypted: Boolean = true,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("file_type") val fileType: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val type: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class MessagesResponse(
    val data: List<MessageDto> = emptyList(),
    val total: Long = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("chat_type") val chatType: String = "direct",
    val content: String,
    @SerialName("content_type") val contentType: String = "text",
    @SerialName("recipient_public_key") val recipientPublicKey: String = "",
    val encrypted: Boolean = false,
    /** Set for voice notes; content_type carries the kind. */
    @SerialName("file_url") val fileUrl: String = "",
    @SerialName("file_type") val fileType: String = "",
    /** Voice length in milliseconds, so the bubble can show it before playing. */
    @SerialName("duration_ms") val durationMs: Long = 0
)

@Serializable
data class SendMessageResponseData(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String? = null,
    val encrypted: Boolean = false,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class SendMessageResponse(
    val message: String? = null,
    val data: SendMessageResponseData
)

@Serializable
data class ChatListOtherUserDto(
    val id: String,
    val email: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("is_online") val isOnline: Boolean = false
)

@Serializable
data class LastMessageDto(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    val content: String = "",
    @SerialName("content_type") val contentType: String = "text",
    val encrypted: Boolean = false,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class ChatListItemDto(
    val id: String,
    val type: String,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("other_user_id") val otherUserId: String? = null,
    @SerialName("other_user") val otherUser: ChatListOtherUserDto? = null,
    @SerialName("last_message") val lastMessage: LastMessageDto? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("unread_count") val unreadCount: Long = 0,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class ChatsListResponse(
    val data: List<ChatListItemDto> = emptyList()
)

@Serializable
data class UserSearchResult(
    val id: String,
    val email: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class UserSearchResponse(
    val users: List<UserSearchResult> = emptyList()
)
