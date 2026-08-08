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
 *
 * Group messages use a WhatsApp/Signal-style "Sender Key" scheme instead of
 * the pairwise crypto_box used for direct chats: each sender generates one
 * crypto_secretbox key for messages they send, distributes it pairwise (via
 * [encryptBytesFor]'s underlying primitive) to every other member through
 * [groupRepository], and encrypts their own messages with it directly. See
 * [ensureGroupSenderKeyReady]/[fetchGroupSenderKeys] below. [Chat.KeyEpoch]
 * (server-bumped on every membership change) is the rotation signal.
 */
class ChatRepository(
    private val chatApiService: ChatApiService,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val cachedChatDao: CachedChatDao,
    private val webSocketManager: WebSocketManager,
    private val tokenManager: TokenManager,
    private val groupRepository: GroupRepository,
    private val json: Json
) {
    companion object {
        private const val TAG = "ChatRepository"

        /** Shown when a message can't be decrypted on this device (no/stale key). */
        const val ENCRYPTED_PLACEHOLDER = "🔒 Encrypted message"

        /** content_type marking a message as a voice note. */
        const val VOICE_CONTENT_TYPE = "audio"

        /** content_type marking a message as an image attachment. */
        const val IMAGE_CONTENT_TYPE = "image"

        /** content_type marking a message as a generic file attachment. */
        const val FILE_CONTENT_TYPE = "file"

        /** Telegram-style round video message. */
        const val VIDEO_NOTE_CONTENT_TYPE = "video_note"

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
    // chatId -> "direct" | "group", learned from the chat list.
    private val chatTypeMap = mutableMapOf<String, String>()
    // Cached local keys so encrypt/decrypt don't hit disk on every message.
    @Volatile private var myPrivateHex: String? = null
    @Volatile private var myUserId: String? = null
    // Serializes key generation so concurrent callers (multiple ViewModels)
    // don't each generate a different keypair and clobber each other.
    private val keyMutex = Mutex()

    // ==================== Group "Sender Keys" state ====================

    private data class SenderKeyState(val version: Int, val keyHex: String)

    // chatId -> the server's current key_epoch (the rotation signal).
    private val groupKeyEpoch = mutableMapOf<String, Int>()
    // chatId -> this device's own current Sender Key for that group.
    private val mySenderKeys = mutableMapOf<String, SenderKeyState>()
    // "chatId|senderId|version" -> decrypted Sender Key (hex) for other members.
    private val groupOtherKeys = mutableMapOf<String, String>()
    // Serializes (re)generation/distribution per chat so concurrent sends
    // don't each publish a different key for the same epoch.
    private val groupKeyMutex = Mutex()

    /**
     * chatIds whose direct-chat E2EE public key changed since we last saw it -
     * a WhatsApp-style "security code changed" event. Pulled (and cleared) by
     * the UI via [takePendingSecurityNotice] rather than pushed, so it doesn't
     * need its own SharedFlow plumbing.
     */
    private val pendingSecurityNotices = mutableSetOf<String>()

    /** True exactly once per change - calling this clears the flag for [chatId]. */
    fun takePendingSecurityNotice(chatId: String): Boolean = pendingSecurityNotices.remove(chatId)

    /** The chat's type ("direct"/"group"), if this device has seen it in the chat list yet. */
    fun chatTypeFor(chatId: String): String = chatTypeMap[chatId] ?: "direct"

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
     * Result of [encryptBytesFor]: sealed payload plus the Sender Key version
     * used for a group (0 for direct crypto_box). Callers must treat null as
     * "this will be sent in the clear".
     */
    data class SealedBytes(val bytes: ByteArray, val keyVersion: Int = 0)

    /**
     * Encrypts binary content (voice / attachment / round video) for a chat.
     * Direct chats use pairwise crypto_box; groups use the sender's current
     * Sender Key (same secretbox as group text, but raw bytes for the upload).
     * [token] is needed so a stale/missing group key can be (re)distributed.
     */
    suspend fun encryptBytesFor(token: String, chatId: String, plain: ByteArray): SealedBytes? {
        if (chatTypeMap[chatId].equals("group", ignoreCase = true)) {
            val state = ensureGroupSenderKeyReady(token, chatId) ?: return null
            val cipher = E2ECrypto.secretBoxEncryptBytes(plain, state.keyHex) ?: return null
            return SealedBytes(cipher, state.version)
        }
        val otherPub = chatOtherPub[chatId] ?: return null
        val priv = myPriv() ?: return null
        val cipher = E2ECrypto.encryptBytes(plain, otherPub, priv) ?: return null
        return SealedBytes(cipher, 0)
    }

    /**
     * Decrypts binary content produced by [encryptBytesFor]. For a group,
     * [senderId] and [keyVersion] select whose Sender Key sealed the blob
     * (same lookup as [decryptFor] for text).
     */
    suspend fun decryptBytesFor(
        chatId: String,
        payload: ByteArray,
        senderId: String = "",
        keyVersion: Int = 0
    ): ByteArray? {
        if (chatTypeMap[chatId].equals("group", ignoreCase = true)) {
            val keyHex = groupSenderKeyFor(chatId, senderId, keyVersion) ?: return null
            return E2ECrypto.secretBoxDecryptBytes(payload, keyHex)
        }
        val otherPub = chatOtherPub[chatId] ?: return null
        val priv = myPriv() ?: return null
        return E2ECrypto.decryptBytes(payload, otherPub, priv)
    }

    /**
     * Decrypt a message for a chat. Returns plaintext, or a placeholder if we
     * can't. [senderId]/[keyVersion] are only meaningful (and only needed) for
     * a group chat, where decryption depends on whose Sender Key encrypted it.
     */
    suspend fun decryptFor(
        chatId: String,
        content: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0
    ): String {
        if (!encrypted) return content
        if (chatTypeMap[chatId].equals("group", ignoreCase = true)) {
            val keyHex = groupSenderKeyFor(chatId, senderId, keyVersion) ?: return ENCRYPTED_PLACEHOLDER
            val payload = E2ECrypto.fromHex(content) ?: return ENCRYPTED_PLACEHOLDER
            val plain = E2ECrypto.secretBoxDecryptBytes(payload, keyHex) ?: return ENCRYPTED_PLACEHOLDER
            return runCatching { String(plain, Charsets.UTF_8) }.getOrDefault(ENCRYPTED_PLACEHOLDER)
        }
        val otherPub = chatOtherPub[chatId] ?: return ENCRYPTED_PLACEHOLDER
        val priv = myPriv() ?: return ENCRYPTED_PLACEHOLDER
        return E2ECrypto.decrypt(content, otherPub, priv) ?: ENCRYPTED_PLACEHOLDER
    }

    // ==================== Group "Sender Keys" ====================

    /**
     * The key that encrypted a given group message: our own current key when
     * [senderId] is us, otherwise a copy we've previously fetched/decrypted
     * from the server (see [fetchGroupSenderKeys]).
     */
    private fun groupSenderKeyFor(chatId: String, senderId: String, keyVersion: Int): String? {
        if (senderId.isNotEmpty() && senderId == myUserId) {
            val mine = mySenderKeys[chatId] ?: return null
            return mine.keyHex.takeIf { mine.version == keyVersion }
        }
        return groupOtherKeys["$chatId|$senderId|$keyVersion"]
    }

    /**
     * Ensures this device has a Sender Key for [chatId] that covers the
     * group's current key_epoch, (re)generating and redistributing it to every
     * member when it's missing or stale. Returns null only when we couldn't
     * establish one at all (e.g. offline) - callers must fall back to sending
     * in the clear rather than blocking the message entirely.
     */
    private suspend fun ensureGroupSenderKeyReady(token: String, chatId: String): SenderKeyState? =
        groupKeyMutex.withLock {
            if (mySenderKeys[chatId] == null) {
                tokenManager.getGroupSenderKey(chatId).getOrNull()?.let { stored ->
                    val parts = stored.split(":", limit = 2)
                    val version = parts.getOrNull(0)?.toIntOrNull()
                    val keyHex = parts.getOrNull(1)
                    if (version != null && !keyHex.isNullOrEmpty()) {
                        mySenderKeys[chatId] = SenderKeyState(version, keyHex)
                    }
                }
            }

            val currentEpoch = groupKeyEpoch[chatId] ?: 0
            val existing = mySenderKeys[chatId]
            if (existing != null && existing.version >= currentEpoch) {
                return@withLock existing
            }

            // Stale (or missing) - fetch the authoritative member list + epoch,
            // generate a fresh key, and redistribute it to everyone.
            val group = groupRepository.getGroupInfo(token, chatId).getOrNull() ?: return@withLock existing
            groupKeyEpoch[chatId] = group.keyEpoch

            val newKeyHex = E2ECrypto.secretBoxGenerateKey() ?: return@withLock existing
            val newKeyBytes = E2ECrypto.fromHex(newKeyHex) ?: return@withLock existing
            val priv = myPriv() ?: return@withLock existing
            val myId = myUserId

            val recipients = group.members.mapNotNull { member ->
                if (member.id == myId) return@mapNotNull null
                val pub = member.publicKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val encrypted = E2ECrypto.encryptBytes(newKeyBytes, pub, priv) ?: return@mapNotNull null
                SenderKeyRecipientDto(userId = member.id, encryptedKey = E2ECrypto.toHex(encrypted))
            }

            val state = SenderKeyState(group.keyEpoch, newKeyHex)
            if (recipients.isNotEmpty()) {
                groupRepository.publishSenderKey(token, chatId, group.keyEpoch, recipients)
                    .onFailure { Log.w(TAG, "publishSenderKey failed for $chatId - keeping key locally anyway", it) }
            }
            // Adopt the key locally regardless of publish success: refusing to
            // send at all would be worse than a message some members can't yet
            // decrypt (they'll catch up once the publish succeeds/retries).
            mySenderKeys[chatId] = state
            tokenManager.saveGroupSenderKey(chatId, "${state.version}:${state.keyHex}")
            state
        }

    /**
     * Downloads and decrypts every Sender Key distributed to us for [chatId],
     * caching them for [groupSenderKeyFor]. Must run before group history can
     * be decrypted - call this before reading/rendering a group's messages.
     */
    suspend fun fetchGroupSenderKeys(token: String, chatId: String) {
        val priv = myPriv() ?: return
        groupRepository.getSenderKeys(token, chatId).onSuccess { entries ->
            for (entry in entries) {
                if (entry.senderPublicKey.isBlank() || entry.encryptedKey.isBlank()) continue
                val payload = E2ECrypto.fromHex(entry.encryptedKey) ?: continue
                val keyBytes = E2ECrypto.decryptBytes(payload, entry.senderPublicKey, priv) ?: continue
                groupOtherKeys["$chatId|${entry.senderId}|${entry.keyVersion}"] = E2ECrypto.toHex(keyBytes)
            }
        }.onFailure { Log.w(TAG, "fetchGroupSenderKeys failed for $chatId", it) }
    }

    /**
     * Encrypts [plaintext] for a group chat using this device's current
     * Sender Key, (re)establishing one first if needed. Returns
     * (hexCiphertext, encrypted, keyVersion) - encrypted=false means we
     * couldn't get a key at all and [plaintext] is being returned as-is.
     */
    private suspend fun encryptGroupText(token: String, chatId: String, plaintext: String): Triple<String, Boolean, Int> {
        val state = ensureGroupSenderKeyReady(token, chatId) ?: return Triple(plaintext, false, 0)
        val cipher = E2ECrypto.secretBoxEncryptBytes(plaintext.toByteArray(Charsets.UTF_8), state.keyHex)
            ?: return Triple(plaintext, false, 0)
        return Triple(E2ECrypto.toHex(cipher), true, state.version)
    }

    /**
     * Learns a chat's type, key_epoch, and (for direct chats) the other
     * participant's E2EE public key from a chat-list entry - and detects a
     * WhatsApp-style "security code changed" event when that key differs from
     * what we last saw.
     */
    private suspend fun learnChatMeta(dto: ChatListItemDto) {
        chatTypeMap[dto.id] = dto.type
        groupKeyEpoch[dto.id] = dto.keyEpoch
        val pub = dto.otherUser?.publicKey?.takeIf { it.isNotEmpty() } ?: return
        chatOtherPub[dto.id] = pub
        if (!dto.type.equals("group", ignoreCase = true)) {
            checkSecurityCodeChange(dto.id, pub)
        }
    }

    private suspend fun checkSecurityCodeChange(chatId: String, newPublicKeyHex: String) {
        val known = tokenManager.getKnownPublicKey(chatId).getOrNull()
        if (known != null && known != newPublicKeyHex) {
            pendingSecurityNotices.add(chatId)
        }
        if (known != newPublicKeyHex) {
            tokenManager.saveKnownPublicKey(chatId, newPublicKeyHex)
        }
    }

    suspend fun getChats(token: String): Result<List<ChatListItemDto>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getChats(bearer(token))
            if (response.isSuccessful && response.body() != null) {
                val chats = response.body()!!.data
                // Learn each chat's type/key_epoch/other-participant public key.
                for (c in chats) learnChatMeta(c)
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
        val result = mutableListOf<ChatListItemDto>()
        for (entity in cachedChatDao.getAllCached()) {
            try {
                val dto = json.decodeFromString(ChatListItemDto.serializer(), entity.rawJson)
                learnChatMeta(dto)
                result.add(dto)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode cached chat ${entity.id}", e)
            }
        }
        result
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
        plaintext: String,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = ""
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            chatTypeMap[chatId] = chatType
            // Group: Sender Key (crypto_secretbox). Direct: pairwise crypto_box.
            // Plaintext fallback when we have no key for either.
            val (outContent, encrypted, keyVersion) = if (chatType.equals("group", ignoreCase = true)) {
                encryptGroupText(token, chatId, plaintext)
            } else {
                val (c, enc) = encryptFor(chatId, plaintext)
                Triple(c, enc, 0)
            }
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = outContent,
                    encrypted = encrypted,
                    keyVersion = keyVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = forward.fromName,
                    forwardedFromMessageId = forward.fromMessageId
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
                        readAt = null,
                        keyVersion = keyVersion,
                        replyTo = replyToId.ifBlank { null },
                        isForwarded = forward.isForwarded,
                        forwardedFromName = forward.fromName,
                        forwardedFromMessageId = forward.fromMessageId
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
        encrypted: Boolean,
        keyVersion: Int = 0,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = ""
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
                    durationMs = durationMs,
                    keyVersion = keyVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = forward.fromName,
                    forwardedFromMessageId = forward.fromMessageId
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

    /**
     * Posts a round video message. The video and its poster frame are already
     * uploaded (see RoundVideoRepository); this records the pointers plus the
     * duration, so a bubble can be laid out before anything is downloaded.
     */
    suspend fun sendVideoNoteMessage(
        token: String,
        chatId: String,
        chatType: String,
        fileUrl: String,
        thumbnailUrl: String,
        durationMs: Long,
        encrypted: Boolean,
        keyVersion: Int = 0,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = ""
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    // Same as a voice note: the bubble renders from file_url, and
                    // a client that does not understand round videos shows
                    // nothing rather than a bogus blob of text.
                    content = "",
                    contentType = VIDEO_NOTE_CONTENT_TYPE,
                    encrypted = encrypted,
                    fileUrl = fileUrl,
                    fileType = VIDEO_NOTE_CONTENT_TYPE,
                    durationMs = durationMs,
                    thumbnailUrl = thumbnailUrl,
                    keyVersion = keyVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = forward.fromName,
                    forwardedFromMessageId = forward.fromMessageId
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to send video message: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendVideoNoteMessage error", e)
            Result.failure(e)
        }
    }

    /**
     * Posts a generic file/image attachment message. The file itself is
     * already uploaded (see AttachmentRepository); this records the pointer
     * plus display metadata (name/size). Group attachments use Sender Keys
     * the same way group text/voice do ([keyVersion] non-zero).
     */
    suspend fun sendAttachmentMessage(
        token: String,
        chatId: String,
        chatType: String,
        fileUrl: String,
        fileName: String,
        fileSize: Long,
        contentType: String,
        encrypted: Boolean,
        keyVersion: Int = 0,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = ""
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = "",
                    contentType = contentType,
                    encrypted = encrypted,
                    fileUrl = fileUrl,
                    fileType = contentType,
                    fileName = fileName,
                    fileSize = fileSize,
                    keyVersion = keyVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = forward.fromName,
                    forwardedFromMessageId = forward.fromMessageId
                )
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data)
            } else {
                if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to send attachment: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendAttachmentMessage error", e)
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
                fileUrl = e.fileUrl,
                fileType = e.fileType,
                fileName = e.fileName,
                fileSize = e.fileSize,
                durationMs = e.durationMs,
                keyVersion = e.keyVersion,
                replyToId = e.replyTo,
                isForwarded = e.isForwarded,
                forwardedFromName = e.forwardedFromName,
                forwardedFromMessageId = e.forwardedFromMessageId.ifBlank { null },
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
            conversationDao.insertConversation(ConversationEntity(id = chatId, type = chatTypeMap[chatId] ?: "direct"))
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
                readAt = dto.readAt,
                fileUrl = dto.fileUrl,
                fileType = dto.fileType,
                fileName = dto.fileName,
                fileSize = dto.fileSize,
                durationMs = dto.durationMs,
                keyVersion = dto.keyVersion,
                replyTo = dto.replyToId,
                isForwarded = dto.isForwarded,
                forwardedFromName = dto.forwardedFromName,
                forwardedFromMessageId = dto.forwardedFromMessageId.orEmpty()
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

    /**
     * Ids of every locally-muted conversation, in one read.
     *
     * Mute lives only in the local Room row, so the chat list (which is built
     * from the server response) has to be joined against it to know which
     * rows are muted.
     */
    suspend fun mutedChatIds(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            conversationDao.getAllConversations().filter { it.isMuted }.map { it.id }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Removes a message. [forEveryone] retracts it for both sides and is only
     * permitted on your own messages; otherwise it disappears from this
     * account's view alone (the server records it in deleted_for).
     *
     * The local Room row goes too, or the message reappears from cache on the
     * next cold start, before any network refresh can correct it.
     */
    suspend fun deleteMessage(
        token: String,
        chatId: String,
        messageId: String,
        forEveryone: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.deleteMessage(bearer(token), chatId, messageId, forEveryone)
            if (!response.isSuccessful) {
                return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to delete message: ${response.code()}"))
            }
            messageDao.deleteMessage(messageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage error", e)
            Result.failure(e)
        }
    }

    /** Retractions pushed by the server when someone deletes for everyone. */
    val deletedMessages = webSocketManager.deletedMessages

    /** Drops a locally cached message, for a retraction that arrived over the socket. */
    suspend fun removeCachedMessage(messageId: String) = withContext(Dispatchers.IO) {
        runCatching { messageDao.deleteMessage(messageId) }
    }

    /**
     * Decrypted preview text for the newest remaining message in [chatId], or
     * empty if the chat has none left. Used to refresh the chat-list preview
     * after a delete without waiting for a full getChats() round-trip.
     */
    suspend fun latestMessagePreview(chatId: String): String = withContext(Dispatchers.IO) {
        val latest = runCatching { messageDao.getLatestMessage(chatId) }.getOrNull() ?: return@withContext ""
        when (latest.fileType) {
            VOICE_CONTENT_TYPE -> "🎤 Voice message"
            IMAGE_CONTENT_TYPE -> "📷 Photo"
            FILE_CONTENT_TYPE -> "📎 ${latest.fileName?.takeIf { it.isNotBlank() } ?: "File"}"
            VIDEO_NOTE_CONTENT_TYPE -> "📹 Video message"
            else -> decryptFor(
                chatId,
                latest.encryptedContent ?: latest.content,
                latest.isEncrypted,
                latest.senderId,
                latest.keyVersion
            )
        }
    }

    /** Whether one conversation is muted. Used to suppress its notifications. */
    suspend fun isChatMuted(chatId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { conversationDao.getConversationById(chatId)?.isMuted == true }.getOrDefault(false)
    }

    /** Mutes or unmutes a conversation locally. */
    suspend fun setChatMuted(chatId: String, muted: Boolean) = withContext(Dispatchers.IO) {
        // The row may not exist yet if this chat has never been opened.
        if (conversationDao.getConversationById(chatId) == null) {
            conversationDao.insertConversation(ConversationEntity(id = chatId, type = "direct"))
        }
        conversationDao.toggleMute(chatId, muted)
    }

    /**
     * Removes a chat from this user's list. The server only marks us as having
     * left it, so the other participant keeps their copy and no message
     * history is destroyed; a later message in a direct chat brings it back.
     *
     * The local caches are cleared too - otherwise the chat reappears from the
     * cache on the next cold start and its messages keep occupying storage.
     */
    suspend fun deleteChat(token: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.deleteChat(bearer(token), chatId)
            if (!response.isSuccessful) {
                return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to delete chat: ${response.code()}"))
            }

            messageDao.deleteMessagesByConversation(chatId)
            conversationDao.deleteConversation(chatId)
            cachedChatDao.deleteCached(chatId)

            chatOtherPub.remove(chatId)
            chatTypeMap.remove(chatId)
            groupKeyEpoch.remove(chatId)
            mySenderKeys.remove(chatId)
            pendingSecurityNotices.remove(chatId)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteChat error", e)
            Result.failure(e)
        }
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
