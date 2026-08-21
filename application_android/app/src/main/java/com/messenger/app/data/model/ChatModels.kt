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

// ==================== MLS (RFC 9420) Delivery Service ====================
// Every payload below is opaque protocol material, base64 on the wire. The
// server stores and relays it without parsing; see back-end/handlers/mls.go.

@Serializable
data class MlsKeyPackageItem(
    @SerialName("device_id") val deviceId: String,
    @SerialName("cipher_suite") val cipherSuite: Int,
    @SerialName("key_package_b64") val keyPackageB64: String,
    @SerialName("ref_hash") val refHash: String,
    /**
     * Which incarnation of the publisher's MLS store made this package. Opaque
     * to the server; blank means "unattributed", which is what a client that
     * cannot name its store must send rather than inventing a value.
     */
    @SerialName("store_id") val storeId: String
)

@Serializable
data class MlsPublishKeyPackagesRequest(
    @SerialName("key_packages") val keyPackages: List<MlsKeyPackageItem>
)

@Serializable
data class MlsPublishKeyPackagesResponse(val stored: Int = 0)

@Serializable
data class MlsKeyPackageCountResponse(val available: Int = 0)

@Serializable
data class MlsClaimKeyPackageRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("cipher_suite") val cipherSuite: Int = 0
)

@Serializable
data class MlsClaimKeyPackageResponse(
    @SerialName("key_package_b64") val keyPackageB64: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("cipher_suite") val cipherSuite: Int = 0
)

@Serializable
data class MlsCreateGroupRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("group_id_b64") val groupIdB64: String,
    @SerialName("cipher_suite") val cipherSuite: Int
)

/**
 * Abandons a chat's MLS group and starts a fresh incarnation under the same
 * chat_id.
 *
 * [expectedInstanceId] is a compare-and-swap: the server only recreates while
 * that incarnation is still current. Two devices deciding to recover at the same
 * moment therefore yield exactly one new group - the loser gets 409 with the
 * winner's identity and adopts it.
 */
@Serializable
data class MlsRecreateGroupRequest(
    @SerialName("group_id_b64") val groupIdB64: String,
    @SerialName("cipher_suite") val cipherSuite: Int,
    @SerialName("expected_instance_id") val expectedInstanceId: String = ""
)

/** Response to a recreate attempt. Also the 409 body, carrying the winner. */
@Serializable
data class MlsRecreateGroupResponse(
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("instance_id") val instanceId: String = "",
    @SerialName("group_id_b64") val groupIdB64: String = "",
    val epoch: Long = 0,
    val error: String = ""
)

@Serializable
data class MlsGroupDto(
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("group_id_b64") val groupIdB64: String = "",
    @SerialName("cipher_suite") val cipherSuite: Int = 0,
    val epoch: Long = 0,
    /** Identifies this incarnation: a recreated group reuses the chat_id. */
    @SerialName("instance_id") val instanceId: String = ""
)

@Serializable
data class MlsCoverageDeviceDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    val claimable: Boolean = false
)

/**
 * DS-visible join progress. The server cannot read the ratchet tree, but it
 * does know which devices have acked a Welcome. Clients send MLS only when
 * every other live device is in [ackedDeviceIds].
 */
@Serializable
data class MlsCoverageDto(
    val exists: Boolean = false,
    val epoch: Long = 0,
    @SerialName("instance_id") val instanceId: String = "",
    @SerialName("live_devices") val liveDevices: List<MlsCoverageDeviceDto> = emptyList(),
    @SerialName("claimable_device_ids") val claimableDeviceIds: List<String> = emptyList(),
    @SerialName("acked_device_ids") val ackedDeviceIds: List<String> = emptyList(),
    @SerialName("pending_device_ids") val pendingDeviceIds: List<String> = emptyList()
)

@Serializable
data class MlsWelcomeItem(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("welcome_b64") val welcomeB64: String
)

@Serializable
data class MlsCommitRequest(
    // The epoch this client last observed; the server rejects a stale commit
    // with 409 so two members cannot fork the group.
    @SerialName("expected_epoch") val expectedEpoch: Long,
    @SerialName("commit_b64") val commitB64: String,
    @SerialName("sender_device_id") val senderDeviceId: String = "",
    val welcomes: List<MlsWelcomeItem> = emptyList()
)

@Serializable
data class MlsCommitResponse(val epoch: Long = 0)

@Serializable
data class MlsHandshakeDto(
    val epoch: Long = 0,
    val kind: String = "",
    @SerialName("sender_user_id") val senderUserId: String = "",
    @SerialName("sender_device_id") val senderDeviceId: String = "",
    @SerialName("payload_b64") val payloadB64: String = ""
)

@Serializable
data class MlsHandshakesResponse(val handshakes: List<MlsHandshakeDto> = emptyList())

@Serializable
data class MlsPendingWelcomeDto(
    val id: String = "",
    @SerialName("chat_id") val chatId: String = "",
    val epoch: Long = 0,
    @SerialName("welcome_b64") val welcomeB64: String = ""
)

@Serializable
data class MlsWelcomesResponse(val welcomes: List<MlsPendingWelcomeDto> = emptyList())

/** Retires Welcomes only after the join actually succeeded. */
@Serializable
data class MlsWelcomeAckRequest(val ids: List<String>)

@Serializable
data class MlsPutGroupInfoRequest(
    val epoch: Long,
    @SerialName("group_info_b64") val groupInfoB64: String
)

@Serializable
data class MlsGroupInfoDto(
    @SerialName("group_info_b64") val groupInfoB64: String = "",
    @SerialName("group_info_epoch") val groupInfoEpoch: Long = 0,
    val epoch: Long = 0,
    /** False when the published GroupInfo is stale; joining against it fails. */
    @SerialName("is_current") val isCurrent: Boolean = false
)

/**
 * Approves a WhatsApp-style QR sign-in shown on another device.
 *
 * [scanSecret] exists only inside the scanned QR image - sending it is what
 * proves this device physically scanned the code rather than merely knowing a
 * session id. See back-end/handlers/qrlogin.go.
 */
@Serializable
data class QrLoginApproveRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("scan_secret") val scanSecret: String
)
