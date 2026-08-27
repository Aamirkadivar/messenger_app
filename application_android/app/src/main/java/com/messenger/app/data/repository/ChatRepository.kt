package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.E2ECrypto
import com.messenger.app.data.encryption.MlsProcessed
import com.messenger.app.data.encryption.DoubleRatchet
import com.messenger.app.data.encryption.DoubleRatchetV4
import com.messenger.app.data.local.dao.CachedChatDao
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.entity.CachedChatEntity
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.encryption.history.ArchiveState
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
    private val onVaultPullNeeded: (suspend (token: String, force: Boolean) -> Unit)? = null,
    /**
     * Seals a decrypted message into the Layer B archive, or returns null if it could not be
     * archived. Passed as a lambda for the same reason as the two above: [MessageArchiver] reaches
     * E2EEVaultRepository, which depends on this class, and a direct constructor dependency would
     * close that cycle. Null when archiving is not wired, which leaves behaviour exactly as before.
     */
    private val onArchiveMessage: (
        suspend (userId: String, chatId: String, messageId: String, plaintext: String) -> SealedArchive?
    )? = null,
    /**
     * Fetches this user's server-side archives for a chat, already validated.
     * Same lambda seam and same reason as [onArchiveMessage]. Null leaves the
     * fetch path behaving exactly as it did before.
     */
    private val onArchiveFetch: (suspend (chatId: String) -> ArchivePage)? = null
) {
    /**
     * MLS group messaging (encryption_version 5). Optional and set after
     * construction to avoid a DI cycle. When a chat has no MLS group
     * established, group sends fall back to Sender Keys exactly as before —
     * the cutover is per-group, not a flag day.
     */
    @Volatile var mls: MlsRepository? = null
    companion object {
        private const val TAG = "ChatRepository"
        /** Temporary decrypt-failure diagnostics. */
        private const val DEC = "DecryptTrace"

        /**
         * The version to file a freshly generated group Sender Key under.
         *
         * Invariant: a version identifies exactly one key. It must strictly
         * advance whenever the key material changes, and must never be reused -
         * recipients select a key by (chat, sender, version), so a reused number
         * hands them the wrong key and secretbox authentication fails.
         *
         * [groupKeyEpoch] alone is not enough. It only advances on membership
         * changes, so a key regenerated for any other reason kept the same
         * number while the material underneath it changed. Taking one past the
         * highest version ever issued locally preserves the epoch's meaning when
         * it does advance, and guarantees a regenerated key gets a number of its
         * own.
         *
         * Extracted so the rule is testable: its callers need libsodium and the
         * network, this does not.
         */
        internal fun nextSenderKeyVersion(groupKeyEpoch: Int, existingVersions: Set<Int>): Int =
            maxOf(groupKeyEpoch, (existingVersions.maxOrNull() ?: -1) + 1)

        /**
         * MLS (RFC 9420) group messages. Distinct from the direct-message
         * versions (1/2/4) because the whole key schedule differs: TreeKEM
         * group state rather than a pairwise ratchet.
         */
        const val MLS_ENCRYPTION_VERSION = 5

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

    /**
     * Group messaging over the shared Rust core (mls-core / OpenMLS). Inert
     * while [MlsV2Repository.ENABLED] is false - v1 Sender Keys stay the
     * shipping path until v2 is proven on real devices.
     */
    private val mlsV2 by lazy { MlsV2Repository(chatApiService, tokenManager) }
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
    /**
     * Chat-open hook for MLS v2: publish KeyPackages, consume any pending
     * Welcome, and either create the group or wait to be added. Best-effort
     * and off the critical path - if it cannot be established the chat keeps
     * working on the existing scheme rather than failing to send.
     */
    suspend fun ensureMlsV2Group(chatId: String): Boolean =
        if (MlsV2Repository.ENABLED) mlsV2.ensureGroup(chatId) else false

    suspend fun processMlsV2Welcomes(): List<String> =
        if (MlsV2Repository.ENABLED) mlsV2.processWelcomes() else emptyList()

    suspend fun syncMlsV2Handshakes(chatId: String) {
        if (MlsV2Repository.ENABLED) mlsV2.syncHandshakes(chatId)
    }

    suspend fun ensureMlsV2KeyPackages() {
        if (MlsV2Repository.ENABLED) mlsV2.ensureKeyPackages()
    }

    /**
     * Abandons this chat's MLS group and joins a fresh incarnation.
     *
     * The recovery action for a device whose local MLS state is gone: it cannot
     * rejoin the existing tree, so the group is replaced and every current device
     * is Welcomed into the new one. Messages sent under the old group stop being
     * decryptable.
     *
     * Deliberately explicit and never automatic - it is destructive to the old
     * group - and bounded to one attempt per chat per run, so a repeated failure
     * cannot leave a trail of dead incarnations. Returns true only once this
     * device holds durably persisted state for the new group.
     */
    suspend fun recreateMlsV2Group(chatId: String): Boolean =
        if (MlsV2Repository.ENABLED) mlsV2.recreateMlsGroup(chatId) else false

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
        val myPub = myPubHex() ?: otherPub
        val devices = chatDevices[chatId].orEmpty()
        val targets = LinkedHashMap<String, String>()
        for ((uid, did) in devices) {
            if (did.isBlank()) continue
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
        senderId: String,
        senderDeviceId: String
    ): ByteArray? {
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val blob = E2ECrypto.pickFanout(payload, myDev) ?: return null
        val keyed = if (senderDeviceId.isNotBlank()) "$chatId|$senderDeviceId" else chatId
        // Same self-session rule as openDirectV4.
        val sessPub = if (senderId.isNotBlank() && senderId == currentUserIdOrEmpty())
            (myPubHex() ?: otherPub) else otherPub
        decryptDirectV3(keyed, sessPub, priv, blob)?.let { return it }
        if (keyed != chatId) decryptDirectV3(chatId, otherPub, priv, blob)?.let { return it }
        return null
    }

    // ==================== Direct v4 (X3DH-lite two-root ratchet) ====================
    // Glare-safe replacement for v3 (see DoubleRatchetV4 / docs/e2ee-protocol-v4.md).
    // Sessions are role-free: InitSession is symmetric from the two identity keys.
    // Stored under a "v4:" prefix so they never clobber legacy v3 vault entries.

    private val drV4Sessions = mutableMapOf<String, DoubleRatchetV4.Session>()

    private suspend fun loadRatchetV4(sessionKey: String, otherPub: String): DoubleRatchetV4.Session? {
        // Sessions created by earlier builds' failed decrypt attempts were
        // initialized with the other user's key even for this account's own
        // traffic; their rk0 can never open those blobs. A stored session
        // whose peerIdent disagrees with [otherPub] is stale — discard it and
        // re-derive from the right identity.
        val expected = E2ECrypto.fromHex(otherPub)
        fun identityOk(st: DoubleRatchetV4.Session): Boolean =
            expected == null || st.peerIdent.contentEquals(expected)
        drV4Sessions[sessionKey]?.let { if (identityOk(it)) return it }
        tokenManager.loadDirectRatchet("v4:$sessionKey").getOrNull()?.let { json ->
            DoubleRatchetV4.Session.fromJson(json)?.let {
                if (identityOk(it)) {
                    drV4Sessions[sessionKey] = it
                    return it
                }
            }
        }
        val myPk = E2ECrypto.fromHex(myPubHex() ?: return null) ?: return null
        val mySk = E2ECrypto.fromHex(myPriv() ?: return null) ?: return null
        val peer = expected ?: return null
        return DoubleRatchetV4.initSession(myPk, mySk, peer)?.also { drV4Sessions[sessionKey] = it }
    }

    private suspend fun persistRatchetV4(sessionKey: String, st: DoubleRatchetV4.Session) {
        st.seq += 1
        drV4Sessions[sessionKey] = st
        tokenManager.saveDirectRatchet("v4:$sessionKey", st.toJson())
        tokenManager.getAccessToken().getOrNull()?.let { notifyVaultMaterialChanged(it) }
    }

    private suspend fun encryptDirectV4(sessionKey: String, otherPub: String, plain: ByteArray): ByteArray? {
        val st = loadRatchetV4(sessionKey, otherPub) ?: return null
        val out = st.encrypt(plain) ?: return null
        persistRatchetV4(sessionKey, st)
        return out
    }

    private suspend fun decryptDirectV4(sessionKey: String, otherPub: String, payload: ByteArray): ByteArray? {
        val st = loadRatchetV4(sessionKey, otherPub) ?: return null
        val plain = st.decrypt(payload) ?: return null
        persistRatchetV4(sessionKey, st)
        return plain
    }

    /**
     * Seal a copy for THIS device with a throwaway self-session. Using the
     * stored chatId|myDev session poisoned it: decrypting our own blob on a
     * history refetch flipped its recvFirst/turnPending, after which every
     * later send became a NORMAL-type message whose DH (ephemeral with itself)
     * can never be reproduced — those rows rendered as encrypted forever. A
     * fresh session per send always emits an INITIAL-type message, which the
     * stateless path in DoubleRatchetV4.decrypt can re-read any number of times.
     */
    private suspend fun sealSelfV4(inner: ByteArray): ByteArray? {
        val pk = E2ECrypto.fromHex(myPubHex() ?: return null) ?: return null
        val sk = E2ECrypto.fromHex(myPriv() ?: return null) ?: return null
        val tmp = DoubleRatchetV4.initSession(pk, sk, pk) ?: return null
        return tmp.encrypt(inner)
    }

    private suspend fun sealDirectV4(chatId: String, otherPub: String, inner: ByteArray): ByteArray? {
        val me = myUserId ?: tokenManager.getCurrentUserId().getOrNull().orEmpty()
        val myPub = myPubHex() ?: otherPub
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val devices = chatDevices[chatId].orEmpty()
        val targets = LinkedHashMap<String, String>()
        for ((uid, did) in devices) {
            if (did.isBlank()) continue
            val pub = if (uid == me) myPub else otherPub
            if (pub.isNotBlank()) targets[did] = pub
        }
        // Always seal a copy for THIS device, even when the server's registry
        // doesn't list it (fresh install that hasn't registered yet). Without
        // this, our own history is sealed only for other devices and can never
        // be read back here.
        if (myDev.isNotBlank() && myPub.isNotBlank() && targets.isNotEmpty()) {
            targets[myDev] = myPub
        }
        if (targets.size <= 1) {
            return encryptDirectV4(chatId, otherPub, inner)
        }
        val parts = ArrayList<E2ECrypto.FanoutPart>(targets.size)
        for ((did, pub) in targets) {
            val blob = if (did == myDev) sealSelfV4(inner)
                       else encryptDirectV4("$chatId|$did", pub, inner)
            if (blob == null) return null
            parts.add(E2ECrypto.FanoutPart(did, blob))
        }
        return E2ECrypto.wrapFanout(parts)
    }

    private suspend fun openDirectV4(chatId: String, otherPub: String, payload: ByteArray, senderId: String, senderDeviceId: String): ByteArray? {
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val keyed = if (senderDeviceId.isNotBlank()) "$chatId|$senderDeviceId" else chatId
        // A message from one of this account's own devices rides a
        // self-session whose root pairs our identity with itself; initializing
        // that session with the peer's key derives the wrong rk0 and even the
        // stateless INITIAL path cannot open it.
        val sessPub = if (senderId.isNotBlank() && senderId == currentUserIdOrEmpty())
            (myPubHex() ?: otherPub) else otherPub
        val blob = E2ECrypto.pickFanout(payload, myDev)
        if (blob != null) {
            decryptDirectV4(keyed, sessPub, blob)?.let { return it }
            if (keyed != chatId) decryptDirectV4(chatId, otherPub, blob)?.let { return it }
        }
        // No blob for this device id (or it failed): this install may be
        // missing from the registry the sender fanned out to (fresh install,
        // changed device id). INITIAL blobs open statelessly with just the
        // account identity, so try the copies addressed to other devices —
        // wrong ones simply fail authentication.
        for (part in E2ECrypto.listFanout(payload)) {
            if (part.deviceId == myDev) continue
            decryptDirectV4(keyed, sessPub, part.blob)?.let { return it }
        }
        return null
    }

    private suspend fun currentUserIdOrEmpty(): String =
        myUserId ?: tokenManager.getCurrentUserId().getOrNull().orEmpty()

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
        fileSize: Long = 0,
        thumbnailUrl: String = "",
        fileUrl: String = ""
    ): SealedText {
        // Pairwise (direct) path. Group text is sealed with Sender Keys in
        // encryptGroupText; do not send MLS from here.
        val otherPub = chatOtherPub[chatId] ?: return SealedText(plaintext, false, 1)
        val priv = myPriv() ?: return SealedText(plaintext, false, 1)
        val inner = E2ECrypto.wrapEnvelope(
            plaintext.toByteArray(Charsets.UTF_8), fileName, forwardedFrom, durationMs, fileSize, thumbnailUrl, fileUrl
        )
        syncVaultDown()
        tokenManager.getAccessToken().getOrNull()?.let { refreshChatDevices(it, chatId) }
        drMutex.withLock {
            // v4 X3DH-lite (glare-safe) is the default; v3 is retired for new
            // sends because it breaks under simultaneous send. v2/v1 remain as
            // fallbacks if the ratchet cannot be established.
            val v4 = sealDirectV4(chatId, otherPub, inner)
            if (v4 != null) return SealedText(E2ECrypto.toHex(v4), true, 4)
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
        fileSize: Long = 0,
        thumbnailUrl: String = "",
        fileUrl: String = ""
    ): SealedBytes? {
        val inner = E2ECrypto.wrapEnvelope(plain, fileName, forwardedFrom, durationMs, fileSize, thumbnailUrl, fileUrl)
        if (chatTypeMap[chatId].equals("group", ignoreCase = true)) {
            // v5 (BouncyCastle MLS) removed: it never interoperated with the
            // Windows mlspp stack and its group state could not be rebuilt after
            // a restart. Group media is Sender Keys until v6 covers binary too.
            val state = ensureGroupSenderKeyReady(token, chatId) ?: return null
            val cipher = E2ECrypto.secretBoxEncryptBytes(inner, state.keyHex) ?: return null
            return SealedBytes(cipher, state.version)
        }
        val otherPub = chatOtherPub[chatId] ?: return null
        val priv = myPriv() ?: return null
        syncVaultDown()
        refreshChatDevices(token, chatId)
        drMutex.withLock {
            val v4 = sealDirectV4(chatId, otherPub, inner)
            if (v4 != null) return SealedBytes(v4, 0, 4)
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
        val fileSize: Long = 0,
        val thumbnailUrl: String = "",
        val fileUrl: String = ""
    )

    /**
     * [chatType] is the server's own classification for the row being opened,
     * and takes precedence over [chatTypeMap].
     *
     * encryption_version 1 is overloaded: it means pairwise crypto_box in a
     * direct chat and group Sender Keys in a group, with nothing in the payload
     * to tell them apart. Dispatch therefore depends on knowing the chat type -
     * and chatTypeMap is an in-memory map that is empty until the chat list
     * loads. On a cold start a group v1 row was routed to the pairwise
     * decryptor, failed its tag check, and rendered as the encrypted
     * placeholder. Passing the row's own chat_type removes that dependency.
     *
     * Blank falls back to chatTypeMap for callers that genuinely have no row
     * metadata; those keep the previous behaviour rather than guessing.
     */
    private suspend fun decryptToBytes(
        chatId: String,
        payload: ByteArray,
        senderId: String,
        keyVersion: Int,
        encryptionVersion: Int,
        senderDeviceId: String = "",
        chatType: String = ""
    ): ByteArray? {
        // MLS v2 (v6): the shared Rust/OpenMLS core. Must come first - v6 rows
        // are unreadable by the v5 BouncyCastle stack and vice versa, which is
        // exactly why they carry different version numbers.
        //
        // Note the sender cannot open its own message: OpenMLS drops the key at
        // encrypt time for forward secrecy, so our own rows are served from the
        // local plaintext cache, never from here.
        if (encryptionVersion == MlsV2Repository.ENCRYPTION_VERSION) {
            val out = mlsV2.process(chatId, payload)
            if (out is MlsProcessed.Application) {
                return out.plaintext
            }
            android.util.Log.w(DEC, "FAIL v6(mls2): no group state or open failed chat=" + chatId)
            return null
        }
        // v5 was the BouncyCastle MLS stack. It is removed: it never
        // interoperated with the Windows mlspp client and its group state could
        // not be rebuilt after a restart, so no device holds keys for these rows
        // any more. They are permanently unreadable - say so once instead of
        // retrying on every repaint.
        if (encryptionVersion == 5) {
            android.util.Log.w(DEC, "v5 row is unreadable (BouncyCastle MLS removed) chat=" + chatId)
            return null
        }
        val isGroupRow = if (chatType.isNotBlank()) {
            chatType.equals("group", ignoreCase = true)
        } else {
            chatTypeMap[chatId].equals("group", ignoreCase = true)
        }
        if (isGroupRow) {
            ensurePeerSenderKeysLoaded(chatId)
            val keyHex = groupSenderKeyFor(chatId, senderId, keyVersion) ?: run {
                android.util.Log.w(DEC, "FAIL group: no sender key chat=$chatId sender=$senderId ver=$keyVersion")
                return null
            }
            return E2ECrypto.secretBoxDecryptBytes(payload, keyHex).also {
                if (it == null) android.util.Log.w(DEC, "FAIL group: secretBox open returned null chat=$chatId")
            }
        }
        val priv = myPriv() ?: run {
            android.util.Log.w(DEC, "FAIL direct: no private key (vault empty/locked) chat=$chatId")
            return null
        }
        val otherPub = chatOtherPub[chatId] ?: ""
        android.util.Log.d(
            DEC,
            "try chat=$chatId encV=$encryptionVersion payload=${payload.size} " +
                "otherPub=${if (otherPub.isEmpty()) "MISSING" else "len${otherPub.length}"} " +
                "devId=${senderDeviceId.ifEmpty { "EMPTY" }}"
        )
        if (encryptionVersion == 4) {
            if (otherPub.isEmpty()) {
                android.util.Log.w(DEC, "FAIL v4: peer identity key missing chat=$chatId")
                return null
            }
            syncVaultDown()
            drMutex.withLock { openDirectV4(chatId, otherPub, payload, senderId, senderDeviceId) }?.let { return it }
            syncVaultDown(force = true)
            return drMutex.withLock { openDirectV4(chatId, otherPub, payload, senderId, senderDeviceId) }.also {
                if (it == null) android.util.Log.w(DEC, "FAIL v4: ratchet open failed even after vault resync chat=$chatId devId=${senderDeviceId.ifEmpty { "EMPTY" }}")
            }
        }
        if (encryptionVersion == 3) {
            syncVaultDown()
            drMutex.withLock { openDirectV3(chatId, otherPub, priv, payload, senderId, senderDeviceId) }?.let { return it }
            syncVaultDown(force = true)
            return drMutex.withLock { openDirectV3(chatId, otherPub, priv, payload, senderId, senderDeviceId) }.also {
                if (it == null) android.util.Log.w(DEC, "FAIL v3: ratchet open failed even after vault resync chat=$chatId devId=${senderDeviceId.ifEmpty { "EMPTY" }}")
            }
        }
        if (encryptionVersion >= 2) {
            return E2ECrypto.decryptBytesEphemeral(payload, priv).also {
                if (it == null) android.util.Log.w(DEC, "FAIL v2: ephemeral open failed chat=$chatId")
            }
        }
        if (otherPub.isEmpty()) {
            android.util.Log.w(DEC, "FAIL v1: peer public key missing chat=$chatId")
            return null
        }
        return E2ECrypto.decryptBytes(payload, otherPub, priv).also {
            if (it == null) android.util.Log.w(DEC, "FAIL v1: box open failed chat=$chatId")
        }
    }

    suspend fun openFor(
        chatId: String,
        content: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = "",
        chatType: String = ""
    ): OpenedMessage {
        if (!encrypted) return OpenedMessage(content)
        if (content.isBlank()) return OpenedMessage("")
        val payload = E2ECrypto.fromHex(content) ?: return OpenedMessage(ENCRYPTED_PLACEHOLDER)
        val plain = decryptToBytes(chatId, payload, senderId, keyVersion, encryptionVersion, senderDeviceId, chatType)
            ?: return OpenedMessage(ENCRYPTED_PLACEHOLDER)
        val env = E2ECrypto.unwrapEnvelope(plain)
        val text = runCatching { String(env.payload, Charsets.UTF_8) }.getOrDefault(ENCRYPTED_PLACEHOLDER)
        return OpenedMessage(text, env.fileName, env.forwardedFrom, env.durationMs, env.fileSize, env.thumbnailUrl, env.fileUrl)
    }

    suspend fun decryptFor(
        chatId: String,
        content: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = "",
        chatType: String = ""
    ): String = openFor(chatId, content, encrypted, senderId, keyVersion, encryptionVersion, senderDeviceId, chatType).text

    // ==================== Group "Sender Keys" ====================

    /**
     * The key that encrypted a given group message: our own key for
     * [keyVersion] when [senderId] is us (historical versions retained after
     * rotation), otherwise a copy fetched from the server.
     */
    private fun groupSenderKeyFor(chatId: String, senderId: String, keyVersion: Int): String? {
        if (senderId.isNotEmpty() && senderId == myUserId) {
            mySenderKeys[chatId]?.get(keyVersion)?.let { return it }
            // Other installs of this account fetch our key as a "peer" copy
            // published to ourselves; use that when this device never generated it.
            return groupOtherKeys["$chatId|$senderId|$keyVersion"]
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

            // The version must never name two different keys.
            //
            // This used to be group.keyEpoch alone. The epoch only moves when
            // group membership changes, so a key regenerated for any other
            // reason - local state lost, storage cleared - got a fresh random
            // key under the SAME version. That overwrote the previous entry
            // locally while every recipient still held the old key filed under
            // the same number, so they looked up the version, found the stale
            // key, and secretbox authentication failed on every message. The
            // sender could not even read its own history back.
            //
            // Taking one past the highest version we have ever issued keeps the
            // epoch's meaning when it does advance, guarantees a regenerated key
            // is never confused with its predecessor, and leaves older versions
            // in place so existing ciphertext stays readable.
            val nextVersion = nextSenderKeyVersion(group.keyEpoch, versions.keys)

            val recipients = group.members.mapNotNull { member ->
                val pub = member.publicKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val encrypted = E2ECrypto.encryptBytes(newKeyBytes, pub, priv) ?: return@mapNotNull null
                SenderKeyRecipientDto(userId = member.id, encryptedKey = E2ECrypto.toHex(encrypted))
            }

            val state = SenderKeyState(nextVersion, newKeyHex)
            if (recipients.isNotEmpty()) {
                // Publish under the same version the ciphertext will advertise -
                // distributing under a different number is what left senders and
                // recipients disagreeing.
                groupRepository.publishSenderKey(token, chatId, nextVersion, recipients)
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
        fileSize: Long = 0,
        thumbnailUrl: String = "",
        fileUrl: String = ""
    ): SealedText {
        val inner = E2ECrypto.wrapEnvelope(
            plaintext.toByteArray(Charsets.UTF_8), fileName, forwardedFrom, durationMs, fileSize, thumbnailUrl, fileUrl
        )
        // v5 (BouncyCastle MLS) removed - see encryptBytesForChat. Group text
        // now goes v6 (Rust/OpenMLS) in sendMessage, or Sender Keys here.
        val state = ensureGroupSenderKeyReady(token, chatId) ?: return SealedText(plaintext, false, 1, 0)
        val cipher = E2ECrypto.secretBoxEncryptBytes(inner, state.keyHex)
            ?: return SealedText(plaintext, false, 1, 0)
        return SealedText(E2ECrypto.toHex(cipher), true, 1, state.version)
    }

    suspend fun sealMessage(
        token: String,
        chatId: String,
        chatType: String,
        plaintext: String,
        forwardedFrom: String = "",
        fileName: String = "",
        durationMs: Long = 0,
        fileSize: Long = 0,
        thumbnailUrl: String = "",
        fileUrl: String = ""
    ): SealedText {
        return if (chatType.equals("group", ignoreCase = true)) {
            encryptGroupText(token, chatId, plaintext, forwardedFrom, fileName, durationMs, fileSize, thumbnailUrl, fileUrl)
        } else {
            encryptFor(chatId, plaintext, forwardedFrom, fileName, durationMs, fileSize, thumbnailUrl, fileUrl)
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
                // v2 (Rust/OpenMLS) first. null means this device is not a
                // member of the v2 group yet, and falling through to Sender
                // Keys is correct rather than an error.
                val v2 = if (MlsV2Repository.ENABLED) {
                    mlsV2.encrypt(
                        chatId,
                        E2ECrypto.wrapEnvelope(
                            plaintext.toByteArray(Charsets.UTF_8), "", forward.fromName,
                            0L, 0L, "", ""
                        )
                    )
                } else null
                Log.i(TAG, "MLS v2 gate for " + chatId + ": produced=" + (v2 != null))
                if (v2 != null) {
                    outContent = E2ECrypto.toHex(v2)
                    encrypted = true
                    keyVersion = 0
                    encVer = MlsV2Repository.ENCRYPTION_VERSION
                } else {
                    // MLS produced nothing. Before this fell straight through to
                    // Sender Keys, which is how a device that had lost its MLS
                    // state kept emitting encryption_version=1 into an MLS group -
                    // messages no member could read, with nothing to say so.
                    //
                    // Whether that fallback is legitimate depends on the group,
                    // and only the Delivery Service knows: if it holds an MLS
                    // group for this chat, the conversation is MLS-governed and a
                    // Sender-Key message is simply wrong. Fail closed and let the
                    // caller surface it; recovery is a separate, explicit act.
                    if (MlsV2Repository.ENABLED && mlsV2.serverHasGroup(chatId)) {
                        Log.e(
                            TAG,
                            "MLS group state unavailable; refusing group send for $chatId " +
                                "(this device is not a member of the MLS group)"
                        )
                        return@withContext Result.failure(MlsGroupStateUnavailableException(chatId))
                    }

                    // Legacy, non-MLS group: Sender Keys remain correct here.
                    val t = encryptGroupText(token, chatId, plaintext, forwardedFrom = forward.fromName)
                    if (!t.encrypted) {
                        return@withContext Result.failure(
                            Exception("Cannot send: group encryption key is not ready")
                        )
                    }
                    outContent = t.content
                    encrypted = true
                    keyVersion = t.keyVersion
                    encVer = t.encryptionVersion
                }
            } else {
                val s = encryptFor(chatId, plaintext, forwardedFrom = forward.fromName)
                if (!s.encrypted) {
                    // Two very different causes; naming the wrong one sends the
                    // user hunting through the other person's account. The
                    // common case is this device having no identity key yet
                    // (signed in without unlocking the vault).
                    val mine = myPriv().isNullOrBlank()
                    return@withContext Result.failure(
                        Exception(
                            if (mine) "Encryption isn't set up on this device yet. " +
                                "Unlock your encrypted messages with your password " +
                                "or recovery key to send."
                            else "Cannot send: this contact hasn't published an " +
                                "encryption key yet."
                        )
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
                // Layer B archive for our OWN message.
                //
                // This is the only chance to capture it: an MLS sender holds no key for its own
                // ciphertext, so once this row is lost the server copy is unrecoverable. The
                // plaintext cache below has always existed for exactly that reason; the archive is
                // the durable, recoverable form of the same thing.
                //
                // Ordering is send -> server success -> authoritative id -> seal -> one Room write.
                // Sealing runs only after the server assigns `sent.id`, because that id is bound
                // into both the key derivation and the AAD; archiving any earlier would require an
                // invented id and produce a record the real message could never open. The seal and
                // the insert are one statement apart, so the row lands complete in a single write
                // rather than being inserted and then updated.
                //
                // Best effort throughout: OutboundArchivePolicy returns the row unchanged on every
                // failure path, so a locked vault or an unavailable Keystore costs the archive and
                // never the message.
                val archiveFields = OutboundArchivePolicy.archiveFor(
                    userId = tokenManager.getCurrentUserId().getOrNull(),
                    chatId = sent.chatId,
                    messageId = sent.id,
                    plaintext = plaintext,
                    prior = runCatching { messageDao.getMessageById(sent.id) }.getOrNull()
                        ?.let {
                            OutboundArchivePolicy.Fields(
                                it.archiveCiphertext, it.archiveRootVersion, it.archiveState
                            )
                        }
                        ?: OutboundArchivePolicy.Fields.NONE,
                    placeholder = ENCRYPTED_PLACEHOLDER,
                    seal = { u, c, m, p -> onArchiveMessage?.invoke(u, c, m, p) }
                )
                messageDao.insertMessage(
                    MessageEntity(
                        id = sent.id,
                        conversation_id = sent.chatId,
                        senderId = sent.senderId,
                        // Cache our OWN message as plaintext. A ratchet sender
                        // does not retain the message keys it just used, and
                        // the wire self-copy only exists when the device list
                        // was available at send time - without this, our own
                        // messages turn into the encrypted placeholder when
                        // the chat is reopened from cache. Same as Windows.
                        content = plaintext,
                        type = "text",
                        status = "SENT",
                        timestamp = parseTimestamp(sent.createdAt),
                        isEncrypted = false,
                        readAt = null,
                        keyVersion = keyVersion,
                        // The version we actually sent, not a hardcoded 1. A v5
                        // row mislabelled as v1 invites any later pass to
                        // "re-decrypt" it with the wrong scheme, and an MLS
                        // sender can never recover its own plaintext once this
                        // cached copy is lost - it holds no key for its own
                        // message.
                        encryptionVersion = encVer,
                        senderDeviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty(),
                        replyTo = replyToId.ifBlank { null },
                        isForwarded = forward.isForwarded,
                        forwardedFromName = forward.fromName,
                        forwardedFromMessageId = forward.fromMessageId,
                        archiveCiphertext = archiveFields.ciphertext,
                        archiveRootVersion = archiveFields.rootVersion,
                        archiveState = archiveFields.state
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
                    fileUrl = "",
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
                    fileUrl = "",
                    fileType = VIDEO_NOTE_CONTENT_TYPE,
                    durationMs = 0,
                    thumbnailUrl = "",
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
                    fileUrl = "",
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
                senderDeviceId = e.senderDeviceId,
                replyToId = e.replyTo,
                isForwarded = e.isForwarded,
                forwardedFromName = e.forwardedFromName,
                forwardedFromMessageId = e.forwardedFromMessageId.ifBlank { null },
                readAt = e.readAt,
                createdAt = java.time.Instant.ofEpochMilli(e.timestamp).toString()
            )
        }
    }

    /** Decrypts a fetched text row, or null when it cannot be opened. */
    private suspend fun openTextOrNull(chatId: String, dto: MessageDto): OpenedMessage? {
        val payload = E2ECrypto.fromHex(dto.content) ?: return null
        val ver = if (dto.encryptionVersion == 0) 1 else dto.encryptionVersion
        // dto.type carries the server's chat_type for this row (GetMessages sets
        // Type: m.ChatType). Authoritative, and available before the chat list
        // has populated chatTypeMap.
        val plain = decryptToBytes(
            chatId, payload, dto.senderId, dto.keyVersion, ver, dto.senderDeviceId, dto.type.orEmpty()
        ) ?: return null
        val env = E2ECrypto.unwrapEnvelope(plain)
        val text = runCatching { String(env.payload, Charsets.UTF_8) }.getOrNull() ?: return null
        return OpenedMessage(text, env.fileName, env.forwardedFrom, env.durationMs, env.fileSize, env.thumbnailUrl, env.fileUrl)
    }

    private suspend fun cacheMessages(chatId: String, dtos: List<MessageDto>) {
        if (dtos.isEmpty()) return
        // Messages carry a FK to conversations; upsert a placeholder row only if
        // one doesn't exist yet so we never REPLACE (and thus cascade-delete) an
        // existing conversation's already-cached messages.
        if (conversationDao.getConversationById(chatId) == null) {
            // Prefer the rows' own server-side chat_type. Using chatTypeMap alone
            // durably recorded a group as "direct" whenever the map was still
            // cold - the cache then carried that wrong type indefinitely.
            val authoritativeType = dtos.firstOrNull { !it.type.isNullOrBlank() }?.type
                ?: chatTypeMap[chatId]
                ?: "direct"
            conversationDao.insertConversation(ConversationEntity(id = chatId, type = authoritativeType))
        }
        // Resolved once: the archive AAD binds the owning user, and this is a cheap read.
        val archiveUserId = tokenManager.getCurrentUserId().getOrNull()?.takeIf { it.isNotBlank() }
        val entities = dtos.map { dto ->
            // Decrypt-on-store for text rows. Ratchet message keys are consumed
            // on first use, so a row cached as ciphertext may never open again
            // on a later read; the decrypted copy is what makes reopening a
            // chat show text instead of the encrypted placeholder. On failure,
            // never clobber an already-readable row (e.g. our own send, stored
            // open at send time) with ciphertext the ratchet cannot reopen.
            var content = dto.content
            var encrypted = dto.encrypted
            var fwdName = dto.forwardedFromName
            val isMedia = !dto.fileType.isNullOrBlank()
            if (encrypted && !isMedia && content.isNotBlank()) {
                val opened = openTextOrNull(chatId, dto)
                if (opened != null) {
                    content = opened.text
                    fwdName = fwdName.ifBlank { opened.forwardedFrom }
                    encrypted = false
                } else {
                    val existing = messageDao.getMessageById(dto.id)
                    if (existing != null && !existing.isEncrypted && existing.content.isNotBlank()) {
                        content = existing.content
                        fwdName = fwdName.ifBlank { existing.forwardedFromName }
                        encrypted = false
                    }
                }
            }
            // Layer B archive, in the fixed order decrypt -> seal -> Room commit. Sealing happens
            // here, after the row is readable and before it is written, so a row is never committed
            // carrying an archive that does not match its content.
            //
            // insertMessages REPLACEs the row, so an existing archive must be carried forward
            // explicitly or a refresh would silently erase it.
            val prior = runCatching { messageDao.getMessageById(dto.id) }.getOrNull()
            var archiveCiphertext = prior?.archiveCiphertext
            var archiveRootVersion = prior?.archiveRootVersion ?: 0
            var archiveState = prior?.archiveState
            val archivable = !encrypted && !isMedia && content.isNotBlank() &&
                content != ENCRYPTED_PLACEHOLDER
            // Only ever archive a row once. Re-sealing on every refresh would waste work, and
            // re-sealing a RETRACTED row would resurrect a message the user deleted for everyone.
            if (archivable && ArchiveState.fromWire(prior?.archiveState) == ArchiveState.NONE) {
                archiveUserId?.let { uid ->
                    runCatching { onArchiveMessage?.invoke(uid, chatId, dto.id, content) }
                        .getOrNull()
                        ?.let { sealed ->
                            archiveCiphertext = sealed.ciphertextB64
                            archiveRootVersion = sealed.rootVersion
                            archiveState = ArchiveState.SEALED.wire
                        }
                    // A failed seal deliberately leaves the archive columns as they were. It must
                    // never write the plaintext into them, and the message itself is unaffected.
                }
            }
            MessageEntity(
                id = dto.id,
                conversation_id = chatId,
                senderId = dto.senderId,
                content = content,
                type = "text",
                status = "SENT",
                timestamp = parseTimestamp(dto.createdAt),
                isEncrypted = encrypted,
                readAt = dto.readAt,
                fileUrl = dto.fileUrl,
                fileType = dto.fileType,
                fileName = dto.fileName,
                fileSize = dto.fileSize,
                durationMs = dto.durationMs,
                keyVersion = dto.keyVersion,
                encryptionVersion = if (dto.encryptionVersion == 0) 1 else dto.encryptionVersion,
                senderDeviceId = dto.senderDeviceId,
                replyTo = dto.replyToId,
                isForwarded = dto.isForwarded,
                forwardedFromName = fwdName,
                forwardedFromMessageId = dto.forwardedFromMessageId.orEmpty(),
                archiveCiphertext = archiveCiphertext,
                archiveRootVersion = archiveRootVersion,
                archiveState = archiveState
            )
        }
        // Rows we already hold in the clear (our own sends) must not be
        // replaced by the server's encrypted copy - that would turn them back
        // into placeholders on the next refresh.
        val keepPlain = entities.mapNotNull { ent ->
            val existing = runCatching { messageDao.getMessageById(ent.id) }.getOrNull()
            if (existing != null && !existing.isEncrypted) ent.id else null
        }.toSet()
        messageDao.insertMessages(entities.filterNot { it.isEncrypted && keepPlain.contains(it.id) })

        // Layer B: pull down archives this device does not have. Runs after the
        // rows exist so a downloaded archive always lands on a real message.
        applyRemoteArchives(chatId)
    }

    /**
     * Writes server archives onto local rows that have none.
     *
     * Everything the server returned is treated as untrusted. Three refusals
     * matter, and none of them may be relaxed:
     *
     *  - a record whose chat does not match is dropped, so a wrong or hostile
     *    server cannot bind an archive to another conversation;
     *  - a record for a message this device does not hold is dropped, rather
     *    than inventing a row for it;
     *  - a row that is already SEALED or RETRACTED is left completely alone. The
     *    local copy wins over the server's, and a RETRACTED tombstone can never
     *    be undone by a refresh - that is what stops deleted history from being
     *    resurrected by sync.
     *
     * Only the archive columns are ever touched; `content` is never read or
     * written here, so a bad archive cannot damage the message itself. Repeating
     * the operation is a no-op because the second pass sees SEALED.
     */
    private suspend fun applyRemoteArchives(chatId: String) {
        val page = runCatching { onArchiveFetch?.invoke(chatId) }.getOrNull() ?: return
        if (!page.complete) {
            // Explicit continuation signal from the sync layer. Whatever arrived is
            // still applied; what must not happen is treating a truncated pass as a
            // finished one, which is how "sync completed" quietly becomes a lie.
            Log.w(TAG, "archive sync for this chat was incomplete; will continue on a later refresh")
        }
        val remote = page.records
        if (remote.isEmpty()) return
        for (r in remote) {
            if (r.chatId != chatId) continue
            val existing = runCatching { messageDao.getMessageById(r.messageId) }.getOrNull()
                ?: continue
            if (existing.conversation_id != chatId) continue
            if (ArchiveState.fromWire(existing.archiveState) != ArchiveState.NONE) continue
            runCatching {
                messageDao.setArchive(
                    r.messageId, r.ciphertextB64, r.rootVersion, ArchiveState.SEALED.wire
                )
            }.onFailure { Log.w(TAG, "could not apply a downloaded archive: ${it.message}") }
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
            // Retract this device's archive before the row goes, for BOTH kinds of delete. A
            // delete-for-me still means the user wants the message gone from this device, and an
            // archive that outlived it would quietly make it recoverable again.
            retractArchive(messageId)
            messageDao.deleteMessage(messageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage error", e)
            Result.failure(e)
        }
    }

    /** Retractions pushed by the server when someone deletes for everyone. */
    val deletedMessages = webSocketManager.deletedMessages

    /** chatIds whose MLS group advanced an epoch (see WebSocketManager). */
    val mlsCommits = webSocketManager.mlsCommits

    /**
     * Drops a locally cached message, for a retraction that arrived over the socket.
     *
     * The server pushes `message:deleted` ONLY for a delete-for-everyone (see the backend handler),
     * so reaching this method already means the deletion was global, not one participant hiding
     * their own copy.
     *
     * Retracts the archive BEFORE deleting the row, so the archive bytes are gone even if the row
     * removal is what fails.
     */
    suspend fun removeCachedMessage(messageId: String) = withContext(Dispatchers.IO) {
        retractArchive(messageId)
        runCatching { messageDao.deleteMessage(messageId) }
    }

    /**
     * Marks this device's archive of [messageId] retracted and drops its ciphertext.
     *
     * Terminal and idempotent: a repeat call, a socket replay, or a delete for a message that was
     * never archived all leave the row in the same place and cost one no-op query at most. The
     * RETRACTED state is what stops the inbound and outbound archivers from re-sealing the message
     * if it shows up again in a later refresh.
     *
     * LOCAL ONLY. This erases nothing on other devices, on devices that were offline at the time,
     * or in backups - the server offers no mechanism to reach those.
     */
    suspend fun retractArchive(messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (messageId.isBlank()) return@runCatching
            val current = messageDao.getArchiveState(messageId)
            val plan = ArchiveRetractionPolicy.plan(current)
            if (!plan.write) return@runCatching
            messageDao.clearArchive(messageId, plan.newState.wire)
        }.onFailure { Log.w(TAG, "archive retraction skipped: ${it.message}") }
    }

    /**
     * Archives a message that arrived over the socket and was decrypted for display.
     *
     * The realtime path previously left its plaintext in UI state only. That is a real gap rather
     * than a stylistic one: a ratchet or MLS message key is consumed on first use, so when the
     * later REST refresh re-fetches the same row it can no longer open it, finds no cached copy,
     * and stores the ciphertext - which then renders as the encrypted placeholder. Persisting here
     * both closes that gap and gives the archive somewhere to live.
     *
     * Goes through the same [onArchiveMessage] use-case as the fetch path, so key selection,
     * context binding, and failure policy exist in exactly one place.
     *
     * A row that already carries an archive - or a retracted one - is left completely alone.
     */
    suspend fun archiveRealtimeMessage(
        chatId: String,
        messageId: String,
        senderId: String,
        plaintext: String,
        timestampMs: Long,
        keyVersion: Int,
        encryptionVersion: Int,
        senderDeviceId: String,
        chatType: String = "direct"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (messageId.isBlank() || chatId.isBlank()) return@withContext Result.success(Unit)
            if (plaintext.isBlank() || plaintext == ENCRYPTED_PLACEHOLDER) {
                return@withContext Result.success(Unit)
            }
            val userId = tokenManager.getCurrentUserId().getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(
                    IllegalStateException("no current user; cannot bind an archive")
                )

            val existing = messageDao.getMessageById(messageId)
            if (ArchiveState.fromWire(existing?.archiveState) != ArchiveState.NONE) {
                // Already sealed, or retracted. Never re-seal: that would resurrect a message the
                // user deleted for everyone.
                return@withContext Result.success(Unit)
            }

            val sealed = onArchiveMessage?.invoke(userId, chatId, messageId, plaintext)
                ?: return@withContext Result.failure(
                    IllegalStateException("message could not be archived")
                )

            if (existing != null) {
                // Touch only the archive columns; never rewrite message fields as a side effect.
                messageDao.setArchive(
                    messageId, sealed.ciphertextB64, sealed.rootVersion, ArchiveState.SEALED.wire
                )
            } else {
                if (conversationDao.getConversationById(chatId) == null) {
                    conversationDao.insertConversation(
                        ConversationEntity(id = chatId, type = chatType)
                    )
                }
                messageDao.insertMessage(
                    MessageEntity(
                        id = messageId,
                        conversation_id = chatId,
                        senderId = senderId,
                        content = plaintext,
                        type = "text",
                        status = "SENT",
                        timestamp = timestampMs,
                        isEncrypted = false,
                        keyVersion = keyVersion,
                        encryptionVersion = encryptionVersion,
                        senderDeviceId = senderDeviceId,
                        archiveCiphertext = sealed.ciphertextB64,
                        archiveRootVersion = sealed.rootVersion,
                        archiveState = ArchiveState.SEALED.wire
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "realtime archive skipped: ${e.message}")
            Result.failure(e)
        }
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
                latest.encryptionVersion,
                latest.senderDeviceId
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
