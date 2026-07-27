package com.messenger.app.data.remote.api

import com.messenger.app.data.model.*
import retrofit2.Response
import retrofit2.http.Body
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

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<AuthResponse>
}

/**
 * Matches back-end/main.go's protected /api/v1 routes (users, chats, messages, crypto).
 */
interface ChatApiService {
    @GET("users/me")
    suspend fun getCurrentUser(@Header("Authorization") token: String): Response<UserDto>

    @GET("users/search")
    suspend fun searchUsers(
        @Header("Authorization") token: String,
        @Query("q") query: String
    ): Response<UserSearchResponse>

    @GET("chats")
    suspend fun getChats(@Header("Authorization") token: String): Response<ChatsListResponse>

    @POST("chats/direct/{contactId}")
    suspend fun getOrCreateDirectChat(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String
    ): Response<DirectChatResponse>

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

    @POST("groups/{chatId}/leave")
    suspend fun leaveGroup(
        @Header("Authorization") token: String,
        @Path("chatId") chatId: String
    ): Response<Unit>

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
}
