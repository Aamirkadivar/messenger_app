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
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    /** A round video's poster frame, shown while the video itself downloads. */
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    /** Which of the sender's group Sender Key versions encrypted this message. Meaningless outside a group. */
    @SerialName("key_version") val keyVersion: Int = 0,
    @SerialName("encryption_version") val encryptionVersion: Int = 1,
    @SerialName("sender_device_id") val senderDeviceId: String = "",
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("is_forwarded") val isForwarded: Boolean = false,
    @SerialName("forwarded_from_name") val forwardedFromName: String = "",
    @SerialName("forwarded_from_message_id") val forwardedFromMessageId: String? = null,
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
    /** Set for voice notes and attachments; content_type carries the kind. */
    @SerialName("file_url") val fileUrl: String = "",
    @SerialName("file_type") val fileType: String = "",
    /** Original filename/size for an attachment - display metadata only, not secret. */
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    /** Voice/round-video length in milliseconds, so the bubble can show it before playing. */
    @SerialName("duration_ms") val durationMs: Long = 0,
    /** A round video's poster frame, so its bubble fills in before the video lands. */
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    /** Only meaningful (and only ever non-zero) for a group message - see MessageDto.keyVersion. */
    @SerialName("key_version") val keyVersion: Int = 0,
    @SerialName("encryption_version") val encryptionVersion: Int = 1,
    @SerialName("reply_to_id") val replyToId: String = "",
    @SerialName("is_forwarded") val isForwarded: Boolean = false,
    @SerialName("forwarded_from_name") val forwardedFromName: String = "",
    @SerialName("forwarded_from_message_id") val forwardedFromMessageId: String = ""
)

/** Display-only forward attribution stamped on a newly sent message. */
data class ForwardMeta(
    val isForwarded: Boolean = false,
    val fromName: String = "",
    val fromMessageId: String = ""
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
    @SerialName("file_name") val fileName: String = "",
    val encrypted: Boolean = false,
    @SerialName("key_version") val keyVersion: Int = 0,
    @SerialName("encryption_version") val encryptionVersion: Int = 1,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class ChatListItemDto(
    val id: String,
    val type: String,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** Bumped by the server on every group membership change - the Sender Key rotation signal. */
    @SerialName("key_epoch") val keyEpoch: Int = 0,
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

@Serializable
data class BlockedUserDto(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("blocked_at") val blockedAt: String? = null
)

@Serializable
data class BlockedUsersResponse(
    val users: List<BlockedUserDto> = emptyList()
)

// ==================== Calls ====================
// Mirrors back-end/handlers/calls.go's GetIceServers/GetCallHistory.

@Serializable
data class IceServerDto(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class IceServersResponse(
    @SerialName("ice_servers") val iceServers: List<IceServerDto> = emptyList()
)

@Serializable
data class CallLogDto(
    val id: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("is_incoming") val isIncoming: Boolean,
    val status: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_sec") val durationSec: Int = 0
)

@Serializable
data class CallHistoryResponse(
    val data: List<CallLogDto> = emptyList()
)

// ==================== E2EE vault (opaque to server) ====================

@Serializable
data class E2EEVaultDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("vault_version") val vaultVersion: Int = 0,
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    val suite: String = "",
    @SerialName("vault_ciphertext_b64") val vaultCiphertextB64: String = "",
    @SerialName("pw_kdf") val pwKdf: String = "",
    @SerialName("pw_salt_b64") val pwSaltB64: String = "",
    @SerialName("pw_params") val pwParams: String = "",
    @SerialName("pw_wrapped_master_b64") val pwWrappedMasterB64: String = "",
    @SerialName("rk_kdf") val rkKdf: String = "",
    @SerialName("rk_salt_b64") val rkSaltB64: String = "",
    @SerialName("rk_wrapped_master_b64") val rkWrappedMasterB64: String = ""
)

@Serializable
data class E2EEVaultPutRequest(
    @SerialName("vault_version") val vaultVersion: Int,
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    val suite: String,
    @SerialName("vault_ciphertext_b64") val vaultCiphertextB64: String,
    @SerialName("pw_kdf") val pwKdf: String,
    @SerialName("pw_salt_b64") val pwSaltB64: String,
    @SerialName("pw_params") val pwParams: String,
    @SerialName("pw_wrapped_master_b64") val pwWrappedMasterB64: String,
    @SerialName("rk_kdf") val rkKdf: String = "",
    @SerialName("rk_salt_b64") val rkSaltB64: String = "",
    @SerialName("rk_wrapped_master_b64") val rkWrappedMasterB64: String = "",
    @SerialName("expected_version") val expectedVersion: Int = 0
)

@Serializable
data class E2EEDeviceRegisterRequest(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val platform: String,
    @SerialName("public_key") val publicKey: String
)

@Serializable
data class E2EEDeviceDto(
    val id: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val name: String = "",
    val platform: String = "",
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class E2EEDevicesResponse(
    val devices: List<E2EEDeviceDto> = emptyList()
)

@Serializable
data class ChatE2EEDeviceDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val platform: String = ""
)

@Serializable
data class ChatE2EEDevicesResponse(
    val devices: List<ChatE2EEDeviceDto> = emptyList()
)

@Serializable
data class E2EEPairingCreateRequest(
    @SerialName("ephemeral_pub_hex") val ephemeralPubHex: String,
    @SerialName("device_id") val deviceId: String = ""
)

@Serializable
data class E2EEPairingCreateResponse(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("pairing_string") val pairingString: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    val status: String = ""
)

@Serializable
data class E2EEPairingCompleteRequest(
    @SerialName("payload_b64") val payloadB64: String,
    @SerialName("sender_pub_hex") val senderPubHex: String
)

@Serializable
data class E2EEPairingPayloadDto(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("payload_b64") val payloadB64: String = "",
    @SerialName("sender_pub_hex") val senderPubHex: String = ""
)
