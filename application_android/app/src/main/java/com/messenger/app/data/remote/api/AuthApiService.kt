package com.messenger.app.data.remote.api

import com.messenger.app.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API service for authentication endpoints
 */
interface AuthApiService {

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<AuthResponse>

    @POST("auth/logout")
    @Headers("Content-Type: application/json")
    suspend fun logout(@Header("Authorization") token: String): Response<Unit>

    @GET("auth/me")
    suspend fun getCurrentUser(@Header("Authorization") token: String): Response<UserProfile>

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest): Response<Unit>

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<Unit>

    @POST("auth/verify-email")
    suspend fun verifyEmail(@Query("token") token: String): Response<Unit>
}

/**
 * Retrofit API service for chat/messaging endpoints
 */
interface ChatApiService {

    @GET("conversations")
    suspend fun getConversations(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<PaginatedResponse<ConversationResponse>>

    @GET("conversations/{conversationId}")
    suspend fun getConversation(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String
    ): Response<ConversationResponse>

    @POST("conversations")
    suspend fun createConversation(
        @Header("Authorization") token: String,
        @Body request: CreateConversationRequest
    ): Response<ConversationResponse>

    @PUT("conversations/{conversationId}")
    suspend fun updateConversation(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Body request: UpdateConversationRequest
    ): Response<ConversationResponse>

    @DELETE("conversations/{conversationId}")
    suspend fun deleteConversation(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String
    ): Response<Unit>

    @GET("conversations/{conversationId}/messages")
    suspend fun getMessages(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Query("limit") limit: Int = 20,
        @Query("cursor") cursor: Long? = null,
        @Query("before") before: Long? = null
    ): Response<PaginatedResponse<MessageResponse>>

    @GET("conversations/{conversationId}/messages/{messageId}/around")
    suspend fun getMessagesAround(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("messageId") messageId: String,
        @Query("limit") limit: Int = 20
    ): Response<List<MessageResponse>>

    @POST("conversations/{conversationId}/messages")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Body request: SendMessageRequest
    ): Response<MessageResponse>

    @PUT("conversations/{conversationId}/messages/{messageId}")
    suspend fun editMessage(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("messageId") messageId: String,
        @Body request: EditMessageRequest
    ): Response<MessageResponse>

    @DELETE("conversations/{conversationId}/messages/{messageId}")
    suspend fun deleteMessage(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("messageId") messageId: String
    ): Response<Unit>

    @POST("conversations/{conversationId}/messages/bulk-delete")
    suspend fun bulkDeleteMessages(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Body request: BulkDeleteRequest
    ): Response<Unit>

    @PUT("conversations/{conversationId}/messages/{messageId}/react")
    suspend fun addReaction(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("messageId") messageId: String,
        @Body request: ReactionRequest
    ): Response<MessageResponse>

    @DELETE("conversations/{conversationId}/messages/{messageId}/react/{emoji}")
    suspend fun removeReaction(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("messageId") messageId: String,
        @Path("emoji") emoji: String
    ): Response<MessageResponse>

    @POST("conversations/{conversationId}/read")
    suspend fun markAsRead(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Body request: ReadReceiptRequest
    ): Response<Unit>

    @GET("conversations/{conversationId}/members")
    suspend fun getConversationMembers(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String
    ): Response<List<MemberResponse>>

    @POST("conversations/{conversationId}/members")
    suspend fun addMember(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Body request: AddMemberRequest
    ): Response<MemberResponse>

    @DELETE("conversations/{conversationId}/members/{userId}")
    suspend fun removeMember(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("userId") userId: String
    ): Response<Unit>

    @PUT("conversations/{conversationId}/members/{userId}/role")
    suspend fun updateMemberRole(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Path("userId") userId: String,
        @Body request: UpdateMemberRoleRequest
    ): Response<MemberResponse>

    @GET("users/search")
    suspend fun searchUsers(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 20
    ): Response<List<UserProfile>>

    @GET("users/{userId}/profile")
    suspend fun getUserProfile(
        @Header("Authorization") token: String,
        @Path("userId") userId: String
    ): Response<UserProfile>

    @PUT("users/me/profile")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body request: UpdateProfileRequest
    ): Response<UserProfile>

    @POST("conversations/{conversationId}/typing")
    suspend fun sendTypingIndicator(
        @Header("Authorization") token: String,
        @Path("conversationId") conversationId: String,
        @Body request: TypingIndicatorRequest
    ): Response<Unit>

    @GET("media/upload-url")
    suspend fun getUploadUrl(
        @Header("Authorization") token: String,
        @Query("filename") filename: String,
        @Query("contentType") contentType: String
    ): Response<UploadUrlResponse>
}