package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.E2ECrypto
import com.messenger.app.data.encryption.DoubleRatchet
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
    private val json: Json,
    /** Fired after local vault-relevant material changes (sender keys / peer pubs). */
    private val onVaultMaterialChanged: (suspend (token: String) -> Unit)? = null,
    /** Pull+merge vault from server (sibling device ratchets). */
    private val onVaultPullNeeded: (suspend (token: String, force: Boolean) -> Unit)? = null
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
    private val drMutex = Mutex()
    private val drSessions = mutableMapOf<String, DoubleRatchet.State>()

    // ==================== Group "Sender Keys" state ====================

    private data class SenderKeyState(val version: Int, val keyHex: String)

    // chatId -> the server's current key_epoch (the rotation signal).
    private val groupKeyEpoch = mutableMapOf<String, Int>()
    // chatId -> version -> this device's own Sender Key hex (historical + current).
    private val mySenderKeys = mutableMapOf<String, MutableMap<Int, String>>()
    // "chatId|senderId|version" -> decrypted Sender Key (hex) for other members.
    private val groupOtherKeys = mutableMapOf<String, String>()
    private val peerKeysLoaded = mutableSetOf<String>()
    private val pendingPeerPubs = mutableMapOf<String, String>()
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

        /**
         * Local keys are missing but a password-wrapped vault exists. Callers
         * must unlock the vault with the account password instead of generating
         * a replacement identity.
         */
        data object NeedsVaultUnlock : KeyStatus
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
     * When a vault exists and [allowTakeover] is false, returns
     * [KeyStatus.NeedsVaultUnlock] instead of minting a new identity.
     * Password login should call [E2EEVaultRepository.syncAfterPasswordLogin]
     * first so keys are restored from the vault.
     */
    suspend fun ensureKeysPublished(
        token: String,
        allowTakeover: Boolean = true
    ): Result<KeyStatus> = withContext(Dispatchers.IO) {
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
                    val saved = chatApiService.savePublicKey(bearer(token), mapOf("public_key" to pub))
                    if (saved.code() == 409) {
                        return@withLock Result.success(KeyStatus.NeedsVaultUnlock)
                    }
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

                if (registered != null) {
                    val vaultResp = runCatching {
                        chatApiService.getE2EEVault(bearer(token))
                    }.getOrNull()
                    if (vaultResp != null && vaultResp.isSuccessful) {
                        Log.w(TAG, "Vault exists but local identity keys are missing")
                        return@withLock Result.success(KeyStatus.NeedsVaultUnlock)
                    }
                    if (!allowTakeover) {
                        Log.w(TAG, "Refusing key takeover (allowTakeover=false)")
                        return@withLock Result.success(KeyStatus.NeedsVaultUnlock)
                    }
                }

                val kp = E2ECrypto.generateKeyPair()
                    ?: return@withLock Result.failure(Exception("Keygen failed"))
                val saved = chatApiService.savePublicKey(
                    bearer(token),
                    mapOf("public_key" to kp.publicHex)
                )
                if (saved.code() == 409) {
                    Log.w(TAG, "Server refused identity takeover (identity_locked)")
                    return@withLock Result.success(KeyStatus.NeedsVaultUnlock)
                }
                if (!saved.isSuccessful) {
                    return@withLock Result.failure(Exception("Failed to publish public key"))
                }
                tokenManager.saveE2EEKeys(userId, kp.publicHex, kp.privateHex)
                myPrivateHex = kp.privateHex

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

    private suspend fun myPubHex(): String? {
        val userId = myUserId ?: tokenManager.getCurrentUserId().getOrNull() ?: return null
        return tokenManager.getE2EEPublicKey(userId).getOrNull()
    }

    private suspend fun loadRatchet(sessionKey: String, otherPub: String, priv: String, sending: Boolean): DoubleRatchet.State? {
        drSessions[sessionKey]?.let { return it }
        tokenManager.loadDirectRatchet(sessionKey).getOrNull()?.let { json ->
            DoubleRatchet.State.fromJson(json)?.let {
                drSessions[sessionKey] = it
                return it
            }
        }
        val their = E2ECrypto.fromHex(otherPub) ?: return null
        if (sending) {
            return DoubleRatchet.initAlice(their)?.also { drSessions[sessionKey] = it }
        }
        val pk = E2ECrypto.fromHex(myPubHex() ?: return null) ?: return null
        val sk = E2ECrypto.fromHex(priv) ?: return null
        return DoubleRatchet.initBob(pk, sk).also { drSessions[sessionKey] = it }
    }

    private suspend fun persistRatchet(sessionKey: String, st: DoubleRatchet.State) {
        st.seq += 1
        drSessions[sessionKey] = st
        tokenManager.saveDirectRatchet(sessionKey, st.toJson())
        tokenManager.getAccessToken().getOrNull()?.let { notifyVaultMaterialChanged(it) }
    }

    suspend fun adoptDirectRatchetsFromVault(sessions: Map<String, String>) {
        drMutex.withLock {
            for ((key, json) in sessions) {
                if (key.isBlank() || json.isBlank()) continue
                DoubleRatchet.State.fromJson(json)?.let { drSessions[key] = it }
            }
        }
    }

    private suspend fun encryptDirectV3(sessionKey: String, otherPub: String, priv: String, plain: ByteArray): ByteArray? {
        var st = loadRatchet(sessionKey, otherPub, priv, sending = true) ?: return null
        if (st.cks.size != 32) {
            st = DoubleRatchet.initAlice(E2ECrypto.fromHex(otherPub) ?: return null) ?: return null
            drSessions[sessionKey] = st
        }
        val out = DoubleRatchet.encrypt(st, plain) ?: return null
        persistRatchet(sessionKey, st)
        return out
    }

    private suspend fun decryptDirectV3(sessionKey: String, otherPub: String, priv: String, payload: ByteArray): ByteArray? {
        val st = loadRatchet(sessionKey, otherPub, priv, sending = false) ?: return null
        val plain = DoubleRatchet.decrypt(st, payload) ?: return null
        persistRatchet(sessionKey, st)
        return plain
    }

    private val chatDevices = mutableMapOf<String, List<Pair<String, String>>>() // chatId -> (userId, deviceId)

    suspend fun refreshChatDevices(token: String, chatId: String) {
        try {
            val r = chatApiService.listChatE2EEDevices(bearer(token), chatId)
            if (r.isSuccessful) {
                chatDevices[chatId] = r.body()?.devices.orEmpty().map { it.userId to it.deviceId }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshChatDevices", e)
        }
    }

    private suspend fun sealDirectV3(chatId: String, otherPub: String, priv: String, inner: ByteArray): ByteArray? {
        val me = myUserId ?: tokenManager.getCurrentUserId().getOrNull().orEmpty()
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val myPub = myPubHex() ?: otherPub
        val devices = chatDevices[chatId].orEmpty()
        val targets = LinkedHashMap<String, String>()
        for ((uid, did) in devices) {
            if (did.isBlank() || did == myDev) continue
            val pub = if (uid == me) myPub else otherPub
            if (pub.isNotBlank()) targets[did] = pub
        }
        if (targets.size <= 1) {
            return encryptDirectV3(chatId, otherPub, priv, inner)
        }
        val parts = ArrayList<E2ECrypto.FanoutPart>(targets.size)
        for ((did, pub) in targets) {
            val blob = encryptDirectV3("$chatId|$did", pub, priv, inner) ?: return null
            parts.add(E2ECrypto.FanoutPart(did, blob))
        }
        return E2ECrypto.wrapFanout(parts)
    }

    private suspend fun openDirectV3(
        chatId: String,
        otherPub: String,
        priv: String,
        payload: ByteArray,
        senderDeviceId: String
    ): ByteArray? {
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val blob = E2ECrypto.pickFanout(payload, myDev) ?: return null
        val keyed = if (senderDeviceId.isNotBlank()) "$chatId|$senderDeviceId" else chatId
        decryptDirectV3(keyed, otherPub, priv, blob)?.let { return it }
        if (keyed != chatId) decryptDirectV3(chatId, otherPub, priv, blob)?.let { return it }
        return null
    }

    /** Encrypt for a direct chat with protocol v3 Double Ratchet when possible. */
    data class SealedText(
        val content: String,
        val encrypted: Boolean,
        val encryptionVersion: Int,
        val keyVersion: Int = 0
    )

    suspend fun encryptFor(
        chatId: String,
        plaintext: String,
        forwardedFrom: String = "",
        fileName: String = "",
        durationMs: Long = 0,
        fileSize: Long = 0
    ): SealedText {
        val otherPub = chatOtherPub[chatId] ?: return SealedText(plaintext, false, 1)
        val priv = myPriv() ?: return SealedText(plaintext, false, 1)
        val inner = E2ECrypto.wrapEnvelope(
            plaintext.toByteArray(Charsets.UTF_8), fileName, forwardedFrom, durationMs, fileSize
        )
        syncVaultDown()
        tokenManager.getAccessToken().getOrNull()?.let { refreshChatDevices(it, chatId) }
        drMutex.withLock {
            val v3 = sealDirectV3(chatId, otherPub, priv, inner)
            if (v3 != null) return SealedText(E2ECrypto.toHex(v3), true, 3)
        }
        val v2 = E2ECrypto.encryptBytesEphemeral(inner, otherPub)
        if (v2 != null) return SealedText(E2ECrypto.toHex(v2), true, 2)
        val cipher = E2ECrypto.encryptBytes(inner, otherPub, priv) ?: return SealedText(plaintext, false, 1)
        return SealedText(E2ECrypto.toHex(cipher), true, 1)
    }

    /**
     * Result of [encryptBytesFor]: sealed payload plus the Sender Key version
     * used for a group (0 for direct crypto_box). Callers must treat null as
     * "this will be sent in the clear".
     */
    data class SealedBytes(
        val bytes: ByteArray,
        val keyVersion: Int = 0,
        val encryptionVersion: Int = 1
    )

    /**
     * Encrypts binary content (voice / attachment / round video) for a chat.
     * Direct chats use pairwise crypto_box; groups use the sender's current
     * Sender Key (same secretbox as group text, but raw bytes for the upload).
     * [token] is needed so a stale/missing group key can be (re)distributed.
     */
    suspend fun encryptBytesFor(
        token: String,
        chatId: String,
        plain: ByteArray,
        fileName: String = "",
        forwardedFrom: String = "",
        durationMs: Long = 0,
        fileSize: Long = 0
    ): SealedBytes? {
        val inner = E2ECrypto.wrapEnvelope(plain, fileName, forwardedFrom, durationMs, fileSize)
        if (chatTypeMap[chatId].equals("group", ignoreCase = true)) {
            val state = ensureGroupSenderKeyReady(token, chatId) ?: return null
            val cipher = E2ECrypto.secretBoxEncryptBytes(inner, state.keyHex) ?: return null
            return SealedBytes(cipher, state.version)
        }
        val otherPub = chatOtherPub[chatId] ?: return null
        val priv = myPriv() ?: return null
        syncVaultDown()
        refreshChatDevices(token, chatId)
        drMutex.withLock {
            val v3 = sealDirectV3(chatId, otherPub, priv, inner)
            if (v3 != null) return SealedBytes(v3, 0, 3)
        }
        val eph = E2ECrypto.encryptBytesEphemeral(inner, otherPub)
        if (eph != null) return SealedBytes(eph, 0, 2)
        val cipher = E2ECrypto.encryptBytes(inner, otherPub, priv) ?: return null
        return SealedBytes(cipher, 0, 1)
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
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = ""
    ): ByteArray? = openBytesFor(chatId, payload, senderId, keyVersion, encryptionVersion, senderDeviceId)?.payload

    suspend fun openBytesFor(
        chatId: String,
        payload: ByteArray,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = ""
    ): E2ECrypto.Envelope? {
        val plain = decryptToBytes(chatId, payload, senderId, keyVersion, encryptionVersion, senderDeviceId) ?: return null
        return E2ECrypto.unwrapEnvelope(plain)
    }

    data class OpenedMessage(
        val text: String,
        val fileName: String = "",
        val forwardedFrom: String = "",
        val durationMs: Long = 0,
        val fileSize: Long = 0
    )

    private suspend fun decryptToBytes(
        chatId: String,
        payload: ByteArray,
        senderId: String,
        keyVersion: Int,
        encryptionVersion: Int,
        senderDeviceId: String = ""
    ): ByteArray? {
        if (chatTypeMap[chatId].equals("group", ignoreCase = true)) {
            ensurePeerSenderKeysLoaded(chatId)
            val keyHex = groupSenderKeyFor(chatId, senderId, keyVersion) ?: return null
            return E2ECrypto.secretBoxDecryptBytes(payload, keyHex)
        }
        val priv = myPriv() ?: return null
        val otherPub = chatOtherPub[chatId] ?: ""
        if (encryptionVersion == 3) {
            syncVaultDown()
            drMutex.withLock { openDirectV3(chatId, otherPub, priv, payload, senderDeviceId) }?.let { return it }
            syncVaultDown(force = true)
            return drMutex.withLock { openDirectV3(chatId, otherPub, priv, payload, senderDeviceId) }
        }
        if (encryptionVersion >= 2) {
            return E2ECrypto.decryptBytesEphemeral(payload, priv)
        }
        if (otherPub.isEmpty()) return null
        return E2ECrypto.decryptBytes(payload, otherPub, priv)
    }

    suspend fun openFor(
        chatId: String,
        content: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = ""
    ): OpenedMessage {
        if (!encrypted) return OpenedMessage(content)
        if (content.isBlank()) return OpenedMessage("")
        val payload = E2ECrypto.fromHex(content) ?: return OpenedMessage(ENCRYPTED_PLACEHOLDER)
        val plain = decryptToBytes(chatId, payload, senderId, keyVersion, encryptionVersion, senderDeviceId)
            ?: return OpenedMessage(ENCRYPTED_PLACEHOLDER)
        val env = E2ECrypto.unwrapEnvelope(plain)
        val text = runCatching { String(env.payload, Charsets.UTF_8) }.getOrDefault(ENCRYPTED_PLACEHOLDER)
        return OpenedMessage(text, env.fileName, env.forwardedFrom, env.durationMs, env.fileSize)
    }

    suspend fun decryptFor(
        chatId: String,
        content: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = ""
    ): String = openFor(chatId, content, encrypted, senderId, keyVersion, encryptionVersion, senderDeviceId).text

    // ==================== Group "Sender Keys" ====================

    /**
     * The key that encrypted a given group message: our own key for
     * [keyVersion] when [senderId] is us (historical versions retained after
     * rotation), otherwise a copy fetched from the server.
     */
    private fun groupSenderKeyFor(chatId: String, senderId: String, keyVersion: Int): String? {
        if (senderId.isNotEmpty() && senderId == myUserId) {
            return mySenderKeys[chatId]?.get(keyVersion)
        }
        return groupOtherKeys["$chatId|$senderId|$keyVersion"]
    }

    private suspend fun ensurePeerSenderKeysLoaded(chatId: String) {
        if (!peerKeysLoaded.add(chatId)) return
        tokenManager.loadPeerSenderKeys(chatId).getOrNull()?.forEach { (senderAndVer, hex) ->
            groupOtherKeys["$chatId|$senderAndVer"] = hex
        }
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
            if (mySenderKeys[chatId].isNullOrEmpty()) {
                tokenManager.loadGroupSenderKeys(chatId).getOrNull()?.let { stored ->
                    if (stored.isNotEmpty()) {
                        mySenderKeys[chatId] = stored.toMutableMap()
                    }
                }
            }

            val currentEpoch = groupKeyEpoch[chatId] ?: 0
            val versions = mySenderKeys.getOrPut(chatId) { mutableMapOf() }
            val current = versions.maxByOrNull { it.key }?.let { SenderKeyState(it.key, it.value) }
            if (current != null && current.version >= currentEpoch) {
                return@withLock current
            }

            // Stale (or missing) - fetch the authoritative member list + epoch,
            // generate a fresh key, and redistribute it to everyone.
            val group = groupRepository.getGroupInfo(token, chatId).getOrNull() ?: return@withLock current
            groupKeyEpoch[chatId] = group.keyEpoch

            val newKeyHex = E2ECrypto.secretBoxGenerateKey() ?: return@withLock current
            val newKeyBytes = E2ECrypto.fromHex(newKeyHex) ?: return@withLock current
            val priv = myPriv() ?: return@withLock current
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
            versions[state.version] = state.keyHex
            tokenManager.saveGroupSenderKey(chatId, "${state.version}:${state.keyHex}")
            notifyVaultMaterialChanged(token)
            state
        }

    /**
     * Downloads and decrypts every Sender Key distributed to us for [chatId],
     * caching them for [groupSenderKeyFor]. Must run before group history can
     * be decrypted - call this before reading/rendering a group's messages.
     */
    suspend fun fetchGroupSenderKeys(token: String, chatId: String) {
        val priv = myPriv() ?: return
        ensurePeerSenderKeysLoaded(chatId)
        groupRepository.getSenderKeys(token, chatId).onSuccess { entries ->
            for (entry in entries) {
                if (entry.senderPublicKey.isBlank() || entry.encryptedKey.isBlank()) continue
                val payload = E2ECrypto.fromHex(entry.encryptedKey) ?: continue
                val keyBytes = E2ECrypto.decryptBytes(payload, entry.senderPublicKey, priv) ?: continue
                val hex = E2ECrypto.toHex(keyBytes)
                groupOtherKeys["$chatId|${entry.senderId}|${entry.keyVersion}"] = hex
                tokenManager.savePeerSenderKey(chatId, entry.senderId, entry.keyVersion, hex)
            }
        }.onFailure { Log.w(TAG, "fetchGroupSenderKeys failed for $chatId", it) }
    }

    /**
     * Encrypts [plaintext] for a group chat using this device's current
     * Sender Key, (re)establishing one first if needed. Returns
     * (hexCiphertext, encrypted, keyVersion) - encrypted=false means we
     * couldn't get a key at all and [plaintext] is being returned as-is.
     */
    private suspend fun encryptGroupText(
        token: String,
        chatId: String,
        plaintext: String,
        forwardedFrom: String = "",
        fileName: String = "",
        durationMs: Long = 0,
        fileSize: Long = 0
    ): Triple<String, Boolean, Int> {
        val state = ensureGroupSenderKeyReady(token, chatId) ?: return Triple(plaintext, false, 0)
        val inner = E2ECrypto.wrapEnvelope(
            plaintext.toByteArray(Charsets.UTF_8), fileName, forwardedFrom, durationMs, fileSize
        )
        val cipher = E2ECrypto.secretBoxEncryptBytes(inner, state.keyHex)
            ?: return Triple(plaintext, false, 0)
        return Triple(E2ECrypto.toHex(cipher), true, state.version)
    }

    suspend fun sealMessage(
        token: String,
        chatId: String,
        chatType: String,
        plaintext: String,
        forwardedFrom: String = "",
        fileName: String = "",
        durationMs: Long = 0,
        fileSize: Long = 0
    ): SealedText {
        return if (chatType.equals("group", ignoreCase = true)) {
            val t = encryptGroupText(token, chatId, plaintext, forwardedFrom, fileName, durationMs, fileSize)
            SealedText(t.first, t.second, 1, t.third)
        } else {
            encryptFor(chatId, plaintext, forwardedFrom, fileName, durationMs, fileSize)
        }
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
        if (!dto.type.equals("group", ignoreCase = true)) {
            applyPeerIdentity(dto.id, pub)
        } else {
            chatOtherPub[dto.id] = pub
        }
    }

    /**
     * First-seen identity is pinned (TOFU). A later server value is held as
     * pending until [acceptPeerKeyChange] — encrypt keeps using the pinned key.
     */
    private suspend fun applyPeerIdentity(chatId: String, serverPub: String) {
        val known = tokenManager.getKnownPublicKey(chatId).getOrNull()
        when {
            known.isNullOrBlank() -> {
                tokenManager.saveKnownPublicKey(chatId, serverPub)
                tokenManager.clearPendingPublicKey(chatId)
                pendingPeerPubs.remove(chatId)
                chatOtherPub[chatId] = serverPub
                tokenManager.getAccessToken().getOrNull()?.let { notifyVaultMaterialChanged(it) }
            }
            known.equals(serverPub, ignoreCase = true) -> {
                tokenManager.clearPendingPublicKey(chatId)
                pendingPeerPubs.remove(chatId)
                chatOtherPub[chatId] = known
            }
            else -> {
                tokenManager.savePendingPublicKey(chatId, serverPub)
                pendingPeerPubs[chatId] = serverPub
                pendingSecurityNotices.add(chatId)
                chatOtherPub[chatId] = known
                tokenManager.clearSafetyVerified(chatId)
            }
        }
    }

    fun hasPendingPeerKeyChange(chatId: String): Boolean = pendingPeerPubs.containsKey(chatId)

    suspend fun acceptPeerKeyChange(chatId: String) {
        val pending = tokenManager.getPendingPublicKey(chatId).getOrNull()
            ?: pendingPeerPubs[chatId]
            ?: return
        tokenManager.saveKnownPublicKey(chatId, pending)
        tokenManager.clearPendingPublicKey(chatId)
        tokenManager.clearSafetyVerified(chatId)
        pendingPeerPubs.remove(chatId)
        pendingSecurityNotices.remove(chatId)
        chatOtherPub[chatId] = pending
        drMutex.withLock {
            val oldSeq = drSessions[chatId]?.seq ?: 0L
            drSessions.remove(chatId)
            tokenManager.deleteDirectRatchet(chatId)
            val their = E2ECrypto.fromHex(pending) ?: return@withLock
            val st = DoubleRatchet.initAlice(their) ?: return@withLock
            st.seq = oldSeq
            persistRatchet(chatId, st)
        }
    }

    private suspend fun notifyVaultMaterialChanged(token: String) {
        runCatching { onVaultMaterialChanged?.invoke(token) }
            .onFailure { Log.w(TAG, "Vault material refresh failed", it) }
    }

    private suspend fun syncVaultDown(force: Boolean = false) {
        val token = tokenManager.getAccessToken().getOrNull() ?: return
        runCatching { onVaultPullNeeded?.invoke(token, force) }
            .onFailure { Log.w(TAG, "Vault pull failed", it) }
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
            val outContent: String
            val encrypted: Boolean
            val keyVersion: Int
            val encVer: Int
            if (chatType.equals("group", ignoreCase = true)) {
                val t = encryptGroupText(token, chatId, plaintext, forwardedFrom = forward.fromName)
                if (!t.second) {
                    return@withContext Result.failure(
                        Exception("Cannot send: group encryption key is not ready")
                    )
                }
                outContent = t.first
                encrypted = true
                keyVersion = t.third
                encVer = 1
            } else {
                val s = encryptFor(chatId, plaintext, forwardedFrom = forward.fromName)
                if (!s.encrypted) {
                    return@withContext Result.failure(
                        Exception("Cannot send: peer encryption key is missing")
                    )
                }
                outContent = s.content
                encrypted = true
                keyVersion = 0
                encVer = s.encryptionVersion
            }
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = outContent,
                    encrypted = encrypted,
                    keyVersion = keyVersion,
                    encryptionVersion = encVer,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = "",
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
                        encryptionVersion = encVer,
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
        encryptionVersion: Int = 1,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = "",
        sealedContent: String = ""
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = sealedContent,
                    contentType = VOICE_CONTENT_TYPE,
                    encrypted = encrypted,
                    fileUrl = fileUrl,
                    fileType = VOICE_CONTENT_TYPE,
                    durationMs = 0,
                    keyVersion = keyVersion,
                    encryptionVersion = encryptionVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = "",
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
        encryptionVersion: Int = 1,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = "",
        sealedContent: String = ""
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = sealedContent,
                    contentType = VIDEO_NOTE_CONTENT_TYPE,
                    encrypted = encrypted,
                    fileUrl = fileUrl,
                    fileType = VIDEO_NOTE_CONTENT_TYPE,
                    durationMs = 0,
                    thumbnailUrl = thumbnailUrl,
                    keyVersion = keyVersion,
                    encryptionVersion = encryptionVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = "",
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
        encryptionVersion: Int = 1,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = "",
        sealedContent: String = ""
    ): Result<SendMessageResponseData> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.sendMessage(
                bearer(token),
                SendMessageRequest(
                    chatId = chatId,
                    chatType = chatType,
                    content = sealedContent,
                    contentType = contentType,
                    encrypted = encrypted,
                    fileUrl = fileUrl,
                    fileType = contentType,
                    fileName = "",
                    fileSize = 0,
                    keyVersion = keyVersion,
                    encryptionVersion = encryptionVersion,
                    replyToId = replyToId,
                    isForwarded = forward.isForwarded,
                    forwardedFromName = "",
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
                encryptionVersion = e.encryptionVersion,
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
                encryptionVersion = if (dto.encryptionVersion == 0) 1 else dto.encryptionVersion,
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

    suspend fun safetyNumberForChat(chatId: String): String? {
        val userId = tokenManager.getCurrentUserId().getOrNull() ?: return null
        val mine = tokenManager.getE2EEPublicKey(userId).getOrNull() ?: return null
        val peer = tokenManager.getKnownPublicKey(chatId).getOrNull() ?: return null
        return E2ECrypto.safetyNumber(mine, peer)
    }

    suspend fun isSafetyVerified(chatId: String): Boolean =
        tokenManager.isSafetyVerified(chatId).getOrDefault(false)

    suspend fun verifySafetyNumberScan(chatId: String, scanned: String): Boolean {
        val local = safetyNumberForChat(chatId) ?: return false
        val a = E2ECrypto.parseSafetyNumberQr(scanned) ?: return false
        val b = E2ECrypto.parseSafetyNumberQr(E2ECrypto.safetyNumberQrPayload(local) ?: return false)
            ?: return false
        if (!a.equals(b, ignoreCase = true)) return false
        val peer = tokenManager.getKnownPublicKey(chatId).getOrNull() ?: return false
        tokenManager.markSafetyVerified(chatId, peer)
        return true
    }

    // ==================== Real-time (WebSocket) ====================

    fun connectRealtime() = webSocketManager.connect()
    fun disconnectRealtime() = webSocketManager.disconnect()
    fun nudgeRealtime() = webSocketManager.nudgeReconnect()
    fun ensureRealtime() = webSocketManager.ensureConnected()
    fun joinChatRoom(chatId: String) = webSocketManager.joinChat(chatId)
    fun leaveChatRoom(chatId: String) = webSocketManager.leaveChat(chatId)
    val connectionState get() = webSocketManager.connectionState
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
                latest.keyVersion,
                latest.encryptionVersion
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

    /**
     * Blocks [userId]. The server also hides any direct chat with them from
     * this account's list (same left_at stamp as deleteChat).
     */
    suspend fun blockUser(token: String, userId: String, chatId: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = chatApiService.blockUser(bearer(token), userId)
                if (!response.isSuccessful) {
                    return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                    else Result.failure(Exception("Failed to block user: ${response.code()}"))
                }
                if (!chatId.isNullOrBlank()) {
                    messageDao.deleteMessagesByConversation(chatId)
                    conversationDao.deleteConversation(chatId)
                    cachedChatDao.deleteCached(chatId)
                    chatOtherPub.remove(chatId)
                    chatTypeMap.remove(chatId)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "blockUser error", e)
                Result.failure(e)
            }
        }

    suspend fun unblockUser(token: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.unblockUser(bearer(token), userId)
            if (!response.isSuccessful) {
                return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to unblock user: ${response.code()}"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "unblockUser error", e)
            Result.failure(e)
        }
    }

    suspend fun listBlockedUsers(token: String): Result<List<BlockedUserDto>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.listBlockedUsers(bearer(token))
            if (!response.isSuccessful) {
                return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to list blocks: ${response.code()}"))
            }
            Result.success(response.body()?.users.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "listBlockedUsers error", e)
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
