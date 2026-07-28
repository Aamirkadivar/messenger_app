package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.E2ECrypto
import com.messenger.app.data.local.dao.CachedChatDao
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.entity.CachedChatEntity
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
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

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
    private val cachedChatDao: CachedChatDao,
    private val webSocketManager: WebSocketManager,
    private val tokenManager: TokenManager,
    private val json: Json
) {
    companion object {
        private const val TAG = "ChatRepository"
        private const val ENCRYPTED_PLACEHOLDER = "🔒 Encrypted message"

        /** content_type marking a message as a voice note. */
        const val VOICE_CONTENT_TYPE = "audio"

        /** Parses a server ISO-8601 timestamp to epoch millis for local storage/ordering. */
        private fun parseTimestamp(iso: String?): Long =
            try {
                if (iso.isNullOrBlank()) System.currentTimeMillis()
                else OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
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

    /**
     * Outcome of establishing this device's E2EE identity key.
     */
    sealed interface KeyStatus {
        /** This device already had its keypair; nothing changed. */
        data object Existing : KeyStatus

        /** First key for this account - no prior history to lose. */
        data object Created : KeyStatus

        /**
         * The account already had a public key registered by another device,
         * and this device does not hold the matching private key. We generated
         * a new one and took the registration over, which means every message
         * sent before now is undecryptable everywhere.
         */
        data object ReplacedAnotherDevicesKey : KeyStatus
    }

    /**
     * Ensures this device has an E2EE keypair and that its public half is
     * registered with the server.
     *
     * The account's registered key is checked *before* generating, because
     * publishing a fresh key silently destroys readable history: crypto_box
     * derives its shared secret from both halves, so replacing one end makes
     * every earlier message undecryptable on every device. This used to happen
     * with no indication whatsoever the moment you signed in somewhere new.
     *
     * The takeover still goes ahead when it happens - refusing would leave the
     * new device unable to send or read anything at all - but it is reported
     * back as [KeyStatus.ReplacedAnotherDevicesKey] so the UI can say so
     * plainly instead of showing a wall of "Encrypted message".
     */
    suspend fun ensureKeysPublished(token: String): Result<KeyStatus> = withContext(Dispatchers.IO) {
        keyMutex.withLock {
            try {
                val userId = tokenManager.getCurrentUserId().getOrNull()
                    ?: return@withLock Result.failure(Exception("No current user"))
                myUserId = userId

                val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull()
                val pub = tokenManager.getE2EEPublicKey(userId).getOrNull()

                // Happy path: we already own this account's key on this device.
                if (!priv.isNullOrEmpty() && !pub.isNullOrEmpty()) {
                    myPrivateHex = priv
                    chatApiService.savePublicKey(bearer(token), mapOf("public_key" to pub))
                    return@withLock Result.success(KeyStatus.Existing)
                }

                // No local key. Does the account already have one elsewhere?
                val registered = runCatching {
                    val response = chatApiService.getMyPublicKey(bearer(token))
                    if (response.isSuccessful) {
                        response.body()?.get("public_key")?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }.getOrNull()

                val kp = E2ECrypto.generateKeyPair()
                    ?: return@withLock Result.failure(Exception("Keygen failed"))
                tokenManager.saveE2EEKeys(userId, kp.publicHex, kp.privateHex)
                myPrivateHex = kp.privateHex
                chatApiService.savePublicKey(bearer(token), mapOf("public_key" to kp.publicHex))

                if (registered != null && registered != kp.publicHex) {
                    Log.w(
                        TAG,
                        "Took over E2EE identity for this account: another device had " +
                            "registered a different public key. Messages sent before now " +
                            "cannot be decrypted on any device."
                    )
                    Result.success(KeyStatus.ReplacedAnotherDevicesKey)
                } else {
                    Log.d(TAG, "Generated first E2EE keypair for this account")
                    Result.success(KeyStatus.Created)
                }
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

    /**
     * Encrypts binary content (voice notes) for a chat, or null when we have no
     * key for it - notably group chats, where the pairwise scheme doesn't apply.
     * Callers must treat null as "this will be sent in the clear".
     */
    suspend fun encryptBytesFor(chatId: String, plain: ByteArray): ByteArray? {
        val otherPub = chatOtherPub[chatId] ?: return null
        val priv = myPriv() ?: return null
        return E2ECrypto.encryptBytes(plain, otherPub, priv)
    }

    /** Decrypts binary content produced by [encryptBytesFor]. */
    suspend fun decryptBytesFor(chatId: String, payload: ByteArray): ByteArray? {
        val otherPub = chatOtherPub[chatId] ?: return null
        val priv = myPriv() ?: return null
        return E2ECrypto.decryptBytes(payload, otherPub, priv)
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
                cacheChats(chats)
                Result.success(chats)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to load chats: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChats error", e)
            Result.failure(e)
        }
    }

    /**
     * Chat list read straight from the local cache (raw JSON snapshots), so the
     * list can render offline/instantly before the network refresh completes.
     * Learns E2EE public keys the same way getChats() does, so a cache-only
     * cold start can still decrypt last-message previews.
     */
    suspend fun loadCachedChats(): List<ChatListItemDto> = withContext(Dispatchers.IO) {
        cachedChatDao.getAllCached().mapNotNull { entity ->
            try {
                json.decodeFromString(ChatListItemDto.serializer(), entity.rawJson).also { dto ->
                    dto.otherUser?.publicKey?.takeIf { it.isNotEmpty() }?.let { chatOtherPub[dto.id] = it }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode cached chat ${entity.id}", e)
                null
            }
        }
    }

    private suspend fun cacheChats(chats: List<ChatListItemDto>) {
        if (chats.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = chats.map { dto ->
            CachedChatEntity(
                id = dto.id,
                rawJson = json.encodeToString(ChatListItemDto.serializer(), dto),
                cachedAt = now
            )
        }
        cachedChatDao.insertAll(entities)
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
                    if (response.code() == 401) Result.failure(SessionExpiredException())
                    else Result.failure(Exception("Failed to start chat: ${response.code()}"))
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
                if (conversationDao.getConversationById(sent.chatId) == null) {
                    conversationDao.insertConversation(ConversationEntity(id = sent.chatId, type = chatType))
                }
                messageDao.insertMessage(
                    MessageEntity(
                        id = sent.id,
                        conversation_id = sent.chatId,
                        senderId = sent.senderId,
                        // Store what was actually sent over the wire (ciphertext when
                        // E2EE is active), never the decrypted plaintext - matches the
                        // "server never sees plaintext, and neither does disk" principle.
                        content = sent.content ?: outContent,
                        type = "text",
                        status = "SENT",
                        timestamp = parseTimestamp(sent.createdAt),
                        isEncrypted = sent.encrypted,
                        readAt = null
                    )
                )
                Result.success(sent)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to send message: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
            Result.failure(e)
        }
    }

    /**
     * Posts a voice-note message. The audio itself is already uploaded; this
     * records the pointer to it plus how long it runs.
     */
    suspend fun sendVoiceMessage(
        token: String,
        chatId: String,
        chatType: String,
        fileUrl: String,
        durationMs: Long,
        encrypted: Boolean
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    // The bubble renders from file_url; content stays empty so a
                    // client that doesn't understand voice shows nothing rather
                    // than a bogus blob of text.
                    content = "",
                    contentType = VOICE_CONTENT_TYPE,
                    encrypted = encrypted,
                    fileUrl = fileUrl,
                    fileType = VOICE_CONTENT_TYPE,
                    durationMs = durationMs
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to send voice note: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendVoiceMessage error", e)
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
                val body = response.body()!!
                cacheMessages(chatId, body.data)
                Result.success(body)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to get messages: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMessages error", e)
            Result.failure(e)
        }
    }

    /**
     * Message history read straight from the local cache, newest-first (same
     * order the server returns), so a chat can render offline/instantly before
     * the network fetch completes. Stored content is exactly what the server
     * sent - ciphertext or plaintext-as-received, never decrypted - matching
     * the "server never sees plaintext, and neither does disk" principle.
     */
    suspend fun loadCachedMessages(chatId: String): List<MessageDto> = withContext(Dispatchers.IO) {
        messageDao.getAllMessagesByConversation(chatId).map { e ->
            MessageDto(
                id = e.id,
                chatId = e.conversation_id,
                senderId = e.senderId,
                content = e.content,
                encrypted = e.isEncrypted,
                readAt = e.readAt,
                createdAt = java.time.Instant.ofEpochMilli(e.timestamp).toString()
            )
        }
    }

    private suspend fun cacheMessages(chatId: String, dtos: List<MessageDto>) {
        if (dtos.isEmpty()) return
        // Messages carry a FK to conversations; upsert a placeholder row only if
        // one doesn't exist yet so we never REPLACE (and thus cascade-delete) an
        // existing conversation's already-cached messages.
        if (conversationDao.getConversationById(chatId) == null) {
            conversationDao.insertConversation(ConversationEntity(id = chatId, type = "direct"))
        }
        val entities = dtos.map { dto ->
            MessageEntity(
                id = dto.id,
                conversation_id = chatId,
                senderId = dto.senderId,
                content = dto.content,
                type = "text",
                status = "SENT",
                timestamp = parseTimestamp(dto.createdAt),
                isEncrypted = dto.encrypted,
                readAt = dto.readAt
            )
        }
        messageDao.insertMessages(entities)
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
    val readReceipts get() = webSocketManager.readReceipts
    val presenceUpdates get() = webSocketManager.presenceUpdates

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

    /** Mutes or unmutes a conversation locally. */
    suspend fun setChatMuted(chatId: String, muted: Boolean) = withContext(Dispatchers.IO) {
        // The row may not exist yet if this chat has never been opened.
        if (conversationDao.getConversationById(chatId) == null) {
            conversationDao.insertConversation(ConversationEntity(id = chatId, type = "direct"))
        }
        conversationDao.toggleMute(chatId, muted)
    }

    suspend fun markAsRead(token: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.markAsRead(bearer(token), chatId)
            if (response.isSuccessful) {
                conversationDao.clearUnreadCount(chatId)
                Result.success(Unit)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to mark chat read: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "markAsRead error", e)
            Result.failure(e)
        }
    }
}
