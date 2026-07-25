package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.E2ECrypto
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.model.*
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository for chat/messaging operations, matching the real backend's
 * currently-implemented endpoints (see back-end/main.go).
 *
 * Also owns end-to-end encryption: it generates/stores the device's keypair,
 * publishes the public key, caches each chat's other-participant public key,
 * and provides encrypt/decrypt helpers. The private key never leaves the
 * device and the server only ever relays opaque ciphertext.
 */
class ChatRepository(
    private val chatApiService: ChatApiService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val webSocketManager: WebSocketManager,
    private val tokenManager: TokenManager
) {
    companion object {
        private const val TAG = "ChatRepository"
        private const val ENCRYPTED_PLACEHOLDER = "🔒 Encrypted message"
    }

    private fun bearer(token: String) = "Bearer $token"

    // chatId -> other participant's public key (hex), learned from the chat list.
    private val chatOtherPub = mutableMapOf<String, String>()
    // Cached local keys so encrypt/decrypt don't hit disk on every message.
    @Volatile private var myPrivateHex: String? = null
    @Volatile private var myUserId: String? = null
    // Serializes key generation so concurrent callers (multiple ViewModels)
    // don't each generate a different keypair and clobber each other.
    private val keyMutex = Mutex()

    /** Generate a keypair if this device doesn't have one yet, then publish the public key. */
    suspend fun ensureKeysPublished(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        keyMutex.withLock {
            try {
                val userId = tokenManager.getCurrentUserId().getOrNull()
                    ?: return@withLock Result.failure(Exception("No current user"))
                myUserId = userId

                var priv = tokenManager.getE2EEPrivateKey(userId).getOrNull()
                var pub = tokenManager.getE2EEPublicKey(userId).getOrNull()

                if (priv.isNullOrEmpty() || pub.isNullOrEmpty()) {
                    val kp = E2ECrypto.generateKeyPair()
                        ?: return@withLock Result.failure(Exception("Keygen failed"))
                    tokenManager.saveE2EEKeys(userId, kp.publicHex, kp.privateHex)
                    priv = kp.privateHex
                    pub = kp.publicHex
                    Log.d(TAG, "Generated new E2EE keypair")
                }
                myPrivateHex = priv

                chatApiService.savePublicKey(bearer(token), mapOf("public_key" to (pub ?: "")))
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "ensureKeysPublished error", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun myPriv(): String? {
        myPrivateHex?.let { return it }
        val userId = myUserId ?: tokenManager.getCurrentUserId().getOrNull() ?: return null
        myUserId = userId
        val p = tokenManager.getE2EEPrivateKey(userId).getOrNull()
        myPrivateHex = p
        return p
    }

    /** Encrypt for a chat. Returns (content, encrypted). Falls back to plaintext when we lack the key. */
    suspend fun encryptFor(chatId: String, plaintext: String): Pair<String, Boolean> {
        val otherPub = chatOtherPub[chatId] ?: return plaintext to false
        val priv = myPriv() ?: return plaintext to false
        val cipher = E2ECrypto.encrypt(plaintext, otherPub, priv) ?: return plaintext to false
        return cipher to true
    }

    /** Decrypt a message for a chat. Returns plaintext, or a placeholder if we can't. */
    suspend fun decryptFor(chatId: String, content: String, encrypted: Boolean): String {
        if (!encrypted) return content
        val otherPub = chatOtherPub[chatId] ?: return ENCRYPTED_PLACEHOLDER
        val priv = myPriv() ?: return ENCRYPTED_PLACEHOLDER
        return E2ECrypto.decrypt(content, otherPub, priv) ?: ENCRYPTED_PLACEHOLDER
    }

    suspend fun getChats(token: String): Result<List<ChatListItemDto>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getChats(bearer(token))
            if (response.isSuccessful && response.body() != null) {
                val chats = response.body()!!.data
                // Learn each chat's other-participant public key for E2EE.
                chats.forEach { c ->
                    c.otherUser?.publicKey?.takeIf { it.isNotEmpty() }?.let { chatOtherPub[c.id] = it }
                }
                Result.success(chats)
            } else {
                Result.failure(Exception("Failed to load chats: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChats error", e)
            Result.failure(e)
        }
    }

    suspend fun getOrCreateDirectChat(token: String, contactId: String): Result<DirectChatDto> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.getOrCreateDirectChat(bearer(token), contactId)
                if (response.isSuccessful && response.body() != null) {
                    val chat = response.body()!!.data
                    conversationDao.insertConversation(
                        ConversationEntity(
                            id = chat.id,
                            type = chat.type,
                            name = chat.name,
                            avatarUrl = chat.avatarUrl
                        )
                    )
                    Result.success(chat)
                } else {
                    Result.failure(Exception("Failed to start chat: ${response.code()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "getOrCreateDirectChat error", e)
                Result.failure(e)
            }
        }

    suspend fun sendMessage(
        token: String,
        chatId: String,
        chatType: String,
        plaintext: String
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            // E2EE-encrypt when we have the recipient's key; plaintext fallback otherwise.
            val (outContent, encrypted) = encryptFor(chatId, plaintext)
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = outContent,
                    encrypted = encrypted
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val sent = response.body()!!.data
                messageDao.insertMessage(
                    MessageEntity(
                        id = sent.id,
                        conversation_id = sent.chatId,
                        senderId = sent.senderId,
                        content = plaintext,
                        type = "text",
                        status = "SENT",
                        timestamp = System.currentTimeMillis()
                    )
                )
                Result.success(sent)
            } else {
                Result.failure(Exception("Failed to send message: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
            Result.failure(e)
        }
    }

    suspend fun getMessages(
        token: String,
        chatId: String,
        limit: Int = 50,
        before: String? = null
    ): Result<MessagesResponse> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getMessages(bearer(token), chatId, limit, before)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get messages: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMessages error", e)
            Result.failure(e)
        }
    }

    suspend fun searchUsers(token: String, query: String): Result<List<UserSearchResult>> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.searchUsers(bearer(token), query)
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!.users)
                } else {
                    Result.failure(Exception("Failed to search users: ${response.code()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchUsers error", e)
                Result.failure(e)
            }
        }

    fun sendTypingIndicator(chatId: String, userId: String, isTyping: Boolean) {
        webSocketManager.sendTypingIndicator(chatId, userId, isTyping)
    }

    // ==================== Real-time (WebSocket) ====================

    fun connectRealtime() = webSocketManager.connect()
    fun disconnectRealtime() = webSocketManager.disconnect()
    fun joinChatRoom(chatId: String) = webSocketManager.joinChat(chatId)
    fun leaveChatRoom(chatId: String) = webSocketManager.leaveChat(chatId)
    val incomingMessages get() = webSocketManager.incomingMessages
    val typingUpdates get() = webSocketManager.typingUpdates

    // ==================== Local cache access ====================

    fun getMessagesFlow(chatId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesByConversation(chatId)
            .catch { e ->
                Log.e(TAG, "Error observing messages", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)

    fun getConversationsFlow(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversationsFlow()
            .catch { e ->
                Log.e(TAG, "Error observing conversations", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)

    suspend fun markAsRead(token: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.markAsRead(bearer(token), chatId)
            if (response.isSuccessful) {
                conversationDao.clearUnreadCount(chatId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark chat read: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "markAsRead error", e)
            Result.failure(e)
        }
    }
}
