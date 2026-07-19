package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.MessageEncryption
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.local.entity.UserEntity
import com.messenger.app.data.model.*
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.MessageEnvelope
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.data.remote.websocket.WebSocketMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Repository for chat/messaging operations.
 * Combines data from REST API, WebSocket, and local Room database.
 */
class ChatRepository(
    private val chatApiService: ChatApiService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val webSocketManager: WebSocketManager,
    private val messageEncryption: MessageEncryption
) {

    companion object {
        private const val TAG = "ChatRepository"
        private const val MESSAGES_PER_PAGE = 20
    }

    // ==================== Conversations ====================

    /**
     * Get conversations list with pagination
     */
    suspend fun getConversations(
        authToken: String,
        page: Int = 1,
        limit: Int = 20
    ): Result<PaginatedResponse<ConversationResponse>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getConversations("Bearer $authAuthToken", page, limit)
            if (response.isSuccessful && response.body() != null) {
                // Cache conversations locally
                response.body()!!.data.forEach { conv ->
                    conversationDao.upsertConversation(
                        ConversationEntity(
                            id = conv.id,
                            name = conv.name,
                            type = conv.type,
                            avatarUrl = conv.avatarUrl,
                            lastMessage = conv.lastMessage,
                            lastMessageAt = conv.lastMessageAt,
                            unreadCount = conv.unreadCount,
                            participants = conv.participants?.joinToString(",") { it.id } ?: "",
                            isGroup = conv.isGroup,
                            isDeleted = false,
                            createdAt = conv.createdAt,
                            updatedAt = conv.updatedAt
                        )
                    )
                }
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get conversations: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get conversations error", e)
            Result.failure(e)
        }
    }

    /**
     * Get single conversation details
     */
    suspend fun getConversation(
        authToken: String,
        conversationId: String
    ): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getConversation("Bearer $authToken", conversationId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get conversation: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get conversation error", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new conversation (group or 1-on-1)
     */
    suspend fun createConversation(
        authToken: String,
        request: CreateConversationRequest
    ): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.createConversation("Bearer $authToken", request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to create conversation: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create conversation error", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a conversation
     */
    suspend fun deleteConversation(
        authToken: String,
        conversationId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.deleteConversation("Bearer $authToken", conversationId)
            if (response.isSuccessful) {
                conversationDao.markConversationDeleted(conversationId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete conversation: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete conversation error", e)
            Result.failure(e)
        }
    }

    // ==================== Messages ====================

    /**
     * Get messages with pagination (cursor-based)
     * Returns messages in chronological order (oldest first)
     */
    suspend fun getMessages(
        authToken: String,
        conversationId: String,
        limit: Int = MESSAGES_PER_PAGE,
        cursor: Long? = null
    ): Result<PaginatedResponse<MessageResponse>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getMessages(
                "Bearer $authToken",
                conversationId,
                limit,
                cursor,
                null
            )
            if (response.isSuccessful && response.body() != null) {
                val messages = response.body()!!.data
                // Cache messages locally
                messages.forEach { msg ->
                    messageDao.upsertMessage(
                        MessageEntity(
                            id = msg.id,
                            conversationId = msg.conversationId,
                            senderId = msg.senderId,
                            encryptedContent = msg.encryptedContent,
                            encryptedKey = msg.encryptedKey,
                            type = msg.type,
                            fileId = msg.fileId,
                            isSent = msg.isSent,
                            isDelivered = msg.isDelivered,
                            isRead = msg.isRead,
                            isDeleted = false,
                            createdAt = msg.createdAt,
                            updatedAt = msg.updatedAt
                        )
                    )
                }
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get messages: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get messages error", e)
            Result.failure(e)
        }
    }

    /**
     * Get messages around a specific message (for infinite scroll up)
     */
    suspend fun getMessagesAround(
        authToken: String,
        conversationId: String,
        messageId: String,
        limit: Int = MESSAGES_PER_PAGE
    ): Result<List<MessageResponse>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getMessagesAround(
                "Bearer $authToken",
                conversationId,
                messageId,
                limit
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get messages around: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get messages around error", e)
            Result.failure(e)
        }
    }

    /**
     * Send a message (encrypted)
     */
    suspend fun sendMessage(
        authToken: String,
        conversationId: String,
        senderId: String,
        recipientId: String,
        content: String,
        type: String = "TEXT",
        fileId: String? = null
    ): Result<MessageResponse> = withContext(Dispatchers.IO) {
        return try {
            // Encrypt the message content
            val senderPrivateKey = keyStoreManager.getPrivateKey()
            val recipientPublicKey = keyStoreManager.getPublicKey(recipientId)

            if (senderPrivateKey.isFailure || recipientPublicKey.isFailure) {
                return@withContext Result.failure(
                    Exception("Encryption keys not available")
                )
            }

            val encryptedResult = messageEncryption.encryptMessage(
                plaintext = content,
                senderPrivateKey = senderPrivateKey.getOrNull()!!,
                recipientPublicKey = recipientPublicKey.getOrNull()!!
            )

            if (encryptedResult.isFailure) {
                return@withContext Result.failure(encryptedResult.exceptionOrNull()!!)
            }

            // Create placeholder message (will be updated when server confirms)
            val placeholder = MessageResponse(
                id = "", // Will be filled by server
                conversationId = conversationId,
                senderId = senderId,
                encryptedContent = encryptedResult.getOrNull()!!,
                encryptedKey = "", // In production, encrypt the symmetric key
                type = type,
                fileId = fileId,
                isSent = false,
                isDelivered = false,
                isRead = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // Save placeholder locally
            messageDao.upsertMessage(
                MessageEntity(
                    id = placeholder.id,
                    conversationId = placeholder.conversationId,
                    senderId = placeholder.senderId,
                    encryptedContent = placeholder.encryptedContent,
                    encryptedKey = placeholder.encryptedKey,
                    type = placeholder.type,
                    fileId = placeholder.fileId,
                    isSent = false,
                    isDelivered = false,
                    isRead = false,
                    isDeleted = false,
                    createdAt = placeholder.createdAt,
                    updatedAt = placeholder.updatedAt
                )
            )

            // Send via WebSocket for real-time delivery
            val sent = webSocketManager.sendMessage(
                MessageEnvelope(
                    type = "message",
                    payload = mapOf(
                        "conversationId" to conversationId,
                        "senderId" to senderId,
                        "encryptedContent" to placeholder.encryptedContent,
                        "type" to type,
                        "fileId" to (fileId ?: ""),
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            )

            if (sent) {
                // Update sent status
                messageDao.updateMessageSent(placeholder.id, System.currentTimeMillis())
                Result.success(placeholder)
            } else {
                Result.failure(Exception("Failed to send message via WebSocket"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send message error", e)
            Result.failure(e)
        }
    }

    /**
     * Edit a message
     */
    suspend fun editMessage(
        authToken: String,
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<MessageResponse> = withContext(Dispatchers.IO) {
        try {
            // Re-encrypt with new content
            val encryptedResult = /* re-encrypt */ Result.failure<MessageResponse>(
                Exception("Edit requires re-encryption")
            )

            val request = EditMessageRequest(content = newContent)
            val response = chatApiService.editMessage(
                "Bearer $authToken",
                conversationId,
                messageId,
                request
            )

            if (response.isSuccessful && response.body() != null) {
                messageDao.updateMessageContent(messageId, newContent, System.currentTimeMillis())
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to edit message: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Edit message error", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a message
     */
    suspend fun deleteMessage(
        authToken: String,
        conversationId: String,
        messageId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.deleteMessage(
                "Bearer $authToken",
                conversationId,
                messageId
            )
            if (response.isSuccessful) {
                messageDao.markMessageDeleted(messageId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete message: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete message error", e)
            Result.failure(e)
        }
    }

    /**
     * Mark message as read
     */
    suspend fun markAsRead(
        authToken: String,
        conversationId: String,
        messageId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = ReadReceiptRequest(
                messageId = messageId,
                timestamp = System.currentTimeMillis()
            )
            val response = chatApiService.markAsRead(
                "Bearer $authToken",
                conversationId,
                request
            )
            if (response.isSuccessful) {
                messageDao.markMessageRead(messageId, System.currentTimeMillis())
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark as read: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mark as read error", e)
            Result.failure(e)
        }
    }

    // ==================== Reactions ====================

    /**
     * Add reaction to message
     */
    suspend fun addReaction(
        conversationId: String,
        messageId: String,
        emoji: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            webSocketManager.sendMessage(
                MessageEnvelope(
                    type = "reaction",
                    payload = mapOf(
                        "conversationId" to conversationId,
                        "messageId" to messageId,
                        "emoji" to emoji,
                        "action" to "ADD"
                    )
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Add reaction error", e)
            false
        }
    }

    /**
     * Remove reaction from message
     */
    suspend fun removeReaction(
        conversationId: String,
        messageId: String,
        emoji: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            webSocketManager.sendMessage(
                MessageEnvelope(
                    type = "reaction",
                    payload = mapOf(
                        "conversationId" to conversationId,
                        "messageId" to messageId,
                        "emoji" to emoji,
                        "action" to "REMOVE"
                    )
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Remove reaction error", e)
            false
        }
    }

    // ==================== Members ====================

    /**
     * Get conversation members
     */
    suspend fun getConversationMembers(
        authToken: String,
        conversationId: String
    ): Result<List<MemberResponse>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getConversationMembers(
                "Bearer $authToken",
                conversationId
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get members: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get members error", e)
            Result.failure(e)
        }
    }

    /**
     * Add member to conversation
     */
    suspend fun addMember(
        authToken: String,
        conversationId: String,
        userId: String
    ): Result<MemberResponse> = withContext(Dispatchers.IO) {
        try {
            val request = AddMemberRequest(userId = userId)
            val response = chatApiService.addMember(
                "Bearer $authToken",
                conversationId,
                request
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to add member: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Add member error", e)
            Result.failure(e)
        }
    }

    /**
     * Remove member from conversation
     */
    suspend fun removeMember(
        authToken: String,
        conversationId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.removeMember(
                "Bearer $authToken",
                conversationId,
                userId
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to remove member: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remove member error", e)
            Result.failure(e)
        }
    }

    // ==================== User Search ====================

    /**
     * Search users by query
     */
    suspend fun searchUsers(
        authToken: String,
        query: String,
        limit: Int = 20
    ): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.searchUsers("Bearer $authToken", query, limit)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to search users: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search users error", e)
            Result.failure(e)
        }
    }

    /**
     * Get user profile by ID
     */
    suspend fun getUserProfile(
        authToken: String,
        userId: String
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getUserProfile("Bearer $authToken", userId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get user profile: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get user profile error", e)
            Result.failure(e)
        }
    }

    // ==================== Typing Indicators ====================

    /**
     * Send typing indicator
     */
    fun sendTypingIndicator(conversationId: String, isTyping: Boolean) {
        webSocketManager.sendTypingIndicator(conversationId, isTyping)
    }

    // ==================== Local Data Access ====================

    /**
     * Get flow of messages for a conversation (for UI observation)
     */
    fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesByConversation(conversationId)
            .catch { e ->
                Log.e(TAG, "Error observing messages", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)

    /**
     * Get flow of conversations
     */
    fun getConversationsFlow(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()
            .catch { e ->
                Log.e(TAG, "Error observing conversations", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)

    /**
     * Load cached messages for a conversation
     */
    suspend fun getCachedMessages(conversationId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            messageDao.getMessagesByConversation(conversationId).val messages = messageDao.getMessagesByConversation(conversationId)
            messages
        }

    /**
     * Load cached conversations
     */
    suspend fun getCachedConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            conversationDao.getAllConversations()
        }

    /**
     * Mark conversation as read
     */
    suspend fun markConversationRead(conversationId: String) {
        conversationDao.updateUnreadCount(conversationId, 0)
    }

    /**
     * Clear chat history
     */
    suspend fun clearChatHistory(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messageDao.deleteMessagesByConversation(conversationId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}