package com.messenger.app.data.remote.api

import com.messenger.app.data.model.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Matches back-end/main.go's /api/v1/auth endpoints exactly.
 */
interface AuthApiService {
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/2fa/verify")
    suspend fun verify2FA(@Body request: Verify2FARequest): Response<AuthResponse>

    @POST("auth/password-reset/start")
    suspend fun startPasswordReset(@Body request: PasswordResetStartRequest): Response<PasswordResetStartResponse>

    @POST("auth/password-reset/complete")
    suspend fun completePasswordReset(@Body request: PasswordResetCompleteRequest): Response<PasswordResetCompleteResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<AuthResponse>
}

/**
 * Matches back-end/main.go's protected /api/v1 routes (users, chats, messages, crypto).
 */
interface ChatApiService {
    @GET("users/me")
    // The backend wraps this one: {"user": {...}}. Declaring it as a bare
    // UserDto made every response fail to deserialize.
    suspend fun getCurrentUser(@Header("Authorization") token: String): Response<MeResponse>

    @POST("users/me/password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body body: ChangePasswordRequest
    ): Response<Map<String, String>>

    @GET("users/search")
    suspend fun searchUsers(
        @Header("Authorization") token: String,
        @Query("q") query: String
    ): Response<UserSearchResponse>

    /** Multipart profile picture upload. Returns {"avatar_url": "/uploads/..."}. */
    @Multipart
    @POST("users/me/avatar")
    suspend fun uploadMyAvatar(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part
    ): Response<Map<String, String>>

    /** Group picture upload. Admins only. */
    @Multipart
    @POST("groups/{chatId}/avatar")
    suspend fun uploadGroupAvatar(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Part file: MultipartBody.Part
    ): Response<Map<String, String>>

    @GET("chats")
    suspend fun getChats(@Header("Authorization") token: String): Response<ChatsListResponse>

    @POST("chats/direct/{contactId}")
    suspend fun getOrCreateDirectChat(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String
    ): Response<DirectChatResponse>

    /**
     * Removes a chat from the caller's list only - the backend stamps left_at
     * on their own participant row rather than deleting anything, so the other
     * participant and the message history are untouched.
     */
    @DELETE("chats/{chatId}")
    suspend fun deleteChat(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<Unit>

    /** One-way block. Also hides any direct chat with this user from the caller's list. */
    @POST("users/{userId}/block")
    suspend fun blockUser(
        @Header("Authorization") token: String,
        @Path("userId") userId: String
    ): Response<Unit>

    @DELETE("users/{userId}/block")
    suspend fun unblockUser(
        @Header("Authorization") token: String,
        @Path("userId") userId: String
    ): Response<Unit>

    @GET("users/me/blocks")
    suspend fun listBlockedUsers(
        @Header("Authorization") token: String
    ): Response<BlockedUsersResponse>

    /**
     * Removes a message. forEveryone retracts it for both sides and the
     * server only permits it on your own messages; otherwise the server
     * records this account in the message's deleted_for list, so it
     * disappears here while the other participant keeps their copy.
     */
    @DELETE("messages/{chatId}/{messageId}")
    suspend fun deleteMessage(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Path("messageId") messageId: String,
        @Query("for_everyone") forEveryone: Boolean
    ): Response<Unit>

    @POST("chats/{chatId}/read")
    suspend fun markAsRead(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<Unit>

    @POST("messages")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Body request: SendMessageRequest
    ): Response<SendMessageResponse>

    /** Opaque (usually encrypted) voice payload. Returns {"file_url": ...}. */
    @Multipart
    @POST("messages/voice")
    suspend fun uploadVoice(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part
    ): Response<Map<String, kotlinx.serialization.json.JsonElement>>

    /** Opaque (usually encrypted) file/image attachment. Returns {"file_url": ..., "file_size": ...}. */
    @Multipart
    @POST("messages/attachment")
    suspend fun uploadAttachment(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part
    ): Response<Map<String, kotlinx.serialization.json.JsonElement>>

    /** Opaque (usually encrypted) round video message. Returns {"file_url": ..., "file_size": ...}. */
    @Multipart
    @POST("messages/video-note")
    suspend fun uploadVideoNote(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part
    ): Response<Map<String, kotlinx.serialization.json.JsonElement>>

    /** A round video's poster frame. Returns {"thumbnail_url": ...}. */
    @Multipart
    @POST("messages/video-thumb")
    suspend fun uploadVideoThumb(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part
    ): Response<Map<String, kotlinx.serialization.json.JsonElement>>

    @GET("messages/{chatId}")
    suspend fun getMessages(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null
    ): Response<MessagesResponse>

    // ==================== Groups ====================
    // Trailing slashes match the backend's groupRoutes.Post("/") registration.
    // Member search when *creating* a group uses users/search above; the
    // groups/{id}/search-users endpoint requires an existing group id.

    @POST("groups/")
    suspend fun createGroup(
        @Header("Authorization") token: String,
        @Body request: CreateGroupRequest
    ): Response<GroupResponse>

    @GET("groups/")
    suspend fun getGroups(
        @Header("Authorization") token: String
    ): Response<GroupsListResponse>

    @GET("groups/{chatId}")
    suspend fun getGroupInfo(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<GroupResponse>

    @PUT("groups/{chatId}")
    suspend fun updateGroup(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Body request: UpdateGroupRequest
    ): Response<Unit>

    @POST("groups/{chatId}/members")
    suspend fun addGroupMembers(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Body request: AddMembersRequest
    ): Response<Unit>

    @DELETE("groups/{chatId}/members/{memberId}")
    suspend fun removeGroupMember(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Path("memberId") memberId: String
    ): Response<Unit>

    @PUT("groups/{chatId}/members/{memberId}/role")
    suspend fun updateMemberRole(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Path("memberId") memberId: String,
        @Body request: UpdateMemberRoleRequest
    ): Response<Unit>

    /** Owner only - deletes the group for everyone. */
    @DELETE("groups/{chatId}")
    suspend fun deleteGroup(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<Unit>

    @POST("groups/{chatId}/leave")
    suspend fun leaveGroup(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<Unit>

    /** Distributes this device's encrypted-per-recipient copies of its current group Sender Key. */
    @POST("groups/{chatId}/sender-key")
    suspend fun publishSenderKey(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Body request: PublishSenderKeyRequest
    ): Response<Unit>

    /** Every Sender Key distributed to the caller across this group's members. */
    @GET("groups/{chatId}/sender-keys")
    suspend fun getSenderKeys(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<SenderKeysResponse>

    @POST("crypto/public-key")
    suspend fun savePublicKey(
        @Header("Authorization") token: String,
        @Body request: Map<String, String>
    ): Response<Unit>

    @GET("crypto/public-key/{userId}")
    suspend fun getPublicKey(
        @Header("Authorization") token: String,
        @Path("userId") userId: String
    ): Response<Map<String, String>>

    /**
     * The *current* user's registered public key. Used before generating a
     * keypair to detect that this account already has one on another device.
     */
    @GET("crypto/public-key")
    suspend fun getMyPublicKey(
        @Header("Authorization") token: String
    ): Response<Map<String, String>>

    // ==================== E2EE vault ====================

    @GET("e2ee/vault")
    suspend fun getE2EEVault(
        @Header("Authorization") token: String
    ): Response<E2EEVaultDto>

    @PUT("e2ee/vault")
    suspend fun putE2EEVault(
        @Header("Authorization") token: String,
        @Body body: E2EEVaultPutRequest
    ): Response<E2EEVaultDto>

    @POST("e2ee/devices")
    suspend fun registerE2EEDevice(
        @Header("Authorization") token: String,
        @Body body: E2EEDeviceRegisterRequest
    ): Response<Unit>

    @GET("e2ee/devices")
    suspend fun listE2EEDevices(
        @Header("Authorization") token: String
    ): Response<E2EEDevicesResponse>

    @GET("e2ee/chats/{chatId}/devices")
    suspend fun listChatE2EEDevices(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<ChatE2EEDevicesResponse>

    @POST("e2ee/devices/{deviceId}/revoke")
    suspend fun revokeE2EEDevice(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): Response<Unit>

    @POST("e2ee/pairing")
    suspend fun createE2EEPairing(
        @Header("Authorization") token: String,
        @Body body: E2EEPairingCreateRequest
    ): Response<E2EEPairingCreateResponse>

    @GET("e2ee/pairing/{sessionId}/payload")
    suspend fun takeE2EEPairingPayload(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String
    ): Response<E2EEPairingPayloadDto>

    // ==================== MLS (RFC 9420) Delivery Service ====================
    // The server relays opaque bytes and orders commits; see handlers/mls.go.

    // Approves a QR sign-in displayed on another device. Requires this device
    // to be authenticated - that session is what authorises the new one.
    @POST("auth/qr/approve")
    suspend fun approveQrLogin(
        @Header("Authorization") token: String,
        @Body body: QrLoginApproveRequest
    ): Response<Map<String, String>>

    @POST("e2ee/mls/keypackages")
    suspend fun publishMlsKeyPackages(
        @Header("Authorization") token: String,
        @Body body: MlsPublishKeyPackagesRequest
    ): Response<MlsPublishKeyPackagesResponse>

    // The count is scoped per device: a KeyPackage commits to one device's init
    // key, so a device must know its OWN stock, not the account's. It is scoped
    // per store incarnation too, because a rebuilt store cannot open anything
    // the previous one published - counting those would report a full stock and
    // suppress the republish the device needs. Null omits the filter, which is
    // the pre-store_id behaviour.
    @GET("e2ee/mls/keypackages/count")
    suspend fun countMlsKeyPackages(
        @Header("Authorization") token: String,
        @Header("X-Device-Id") deviceId: String,
        @Query("store_id") storeId: String? = null
    ): Response<MlsKeyPackageCountResponse>

    @POST("e2ee/mls/keypackages/claim")
    suspend fun claimMlsKeyPackage(
        @Header("Authorization") token: String,
        @Body body: MlsClaimKeyPackageRequest
    ): Response<MlsClaimKeyPackageResponse>

    @POST("e2ee/mls/groups")
    suspend fun createMlsGroup(
        @Header("Authorization") token: String,
        @Body body: MlsCreateGroupRequest
    ): Response<MlsGroupDto>

    @GET("e2ee/mls/groups/{chatId}")
    suspend fun getMlsGroup(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<MlsGroupDto>

    /** Abandons the chat's MLS group and starts a fresh incarnation. */
    @POST("e2ee/mls/groups/{chatId}/recreate")
    suspend fun recreateMlsGroup(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Body body: MlsRecreateGroupRequest
    ): Response<MlsRecreateGroupResponse>

    @GET("e2ee/mls/groups/{chatId}/coverage")
    suspend fun getMlsCoverage(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<MlsCoverageDto>

    @POST("e2ee/mls/groups/{chatId}/commit")
    suspend fun submitMlsCommit(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Body body: MlsCommitRequest
    ): Response<MlsCommitResponse>

    @POST("e2ee/mls/groups/{chatId}/group-info")
    suspend fun putMlsGroupInfo(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Body body: MlsPutGroupInfoRequest
    ): Response<Map<String, Long>>

    @GET("e2ee/mls/groups/{chatId}/group-info")
    suspend fun getMlsGroupInfo(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<MlsGroupInfoDto>

    @GET("e2ee/mls/groups/{chatId}/handshakes")
    suspend fun getMlsHandshakes(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String,
        @Query("since_epoch") sinceEpoch: Long
    ): Response<MlsHandshakesResponse>

    @GET("e2ee/mls/welcomes")
    suspend fun getMlsWelcomes(
        @Header("Authorization") token: String,
        @Header("X-Device-Id") deviceId: String
    ): Response<MlsWelcomesResponse>

    // Only called after joinFromWelcome succeeded; an unacked Welcome stays
    // pending so a failed join can be retried instead of locking the device out.
    @POST("e2ee/mls/welcomes/ack")
    suspend fun ackMlsWelcomes(
        @Header("Authorization") token: String,
        @Body body: MlsWelcomeAckRequest
    ): Response<Unit>

    @POST("e2ee/pairing/{sessionId}/complete")
    suspend fun completeE2EEPairing(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Body body: E2EEPairingCompleteRequest
    ): Response<Map<String, String>>

    @GET("auth/2fa/status")
    suspend fun totpStatus(@Header("Authorization") token: String): Response<TotpStatusDto>

    @POST("auth/2fa/totp/setup")
    suspend fun totpSetup(@Header("Authorization") token: String): Response<TotpSetupDto>

    @POST("auth/2fa/totp/confirm")
    suspend fun totpConfirm(
        @Header("Authorization") token: String,
        @Body body: TotpConfirmRequest
    ): Response<TotpStatusDto>

    @POST("auth/2fa/totp/disable")
    suspend fun totpDisable(
        @Header("Authorization") token: String,
        @Body body: TotpDisableRequest
    ): Response<TotpStatusDto>

    @POST("auth/2fa/totp/backup-codes")
    suspend fun totpRegenerateBackupCodes(
        @Header("Authorization") token: String,
        @Body body: TotpDisableRequest
    ): Response<TotpStatusDto>

    // ==================== Calls ====================
    // Signaling itself (invite/answer/ICE/end) is WS-only (see
    // WebSocketManager.sendCallSignal/callSignals) - these are just history
    // and the ICE server list needed to start a call.

    @GET("calls/ice-servers")
    suspend fun getIceServers(
        @Header("Authorization") token: String
    ): Response<IceServersResponse>

    @GET("calls/")
    suspend fun getCallHistory(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 50
    ): Response<CallHistoryResponse>
}
