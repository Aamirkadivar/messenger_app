package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.E2ECrypto
import com.messenger.app.data.encryption.MlsProcessed
import com.messenger.app.data.encryption.DoubleRatchet
import com.messenger.app.data.encryption.DoubleRatchetV4
import com.messenger.app.data.local.dao.ScopedCachedChatDao
import com.messenger.app.data.local.dao.ScopedChatSyncStateDao
import com.messenger.app.data.local.dao.ScopedConversationDao
import com.messenger.app.data.local.dao.ScopedMessageDao
import com.messenger.app.data.local.dao.ScopedOutboxDao
import com.messenger.app.data.local.entity.CachedChatEntity
import com.messenger.app.data.local.entity.ChatSyncStateEntity
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.OutboxState
import com.messenger.app.data.encryption.history.ArchiveState
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.model.*
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
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
/**
 * True when an MLS (v6) row was sent by THIS device, so it must not be handed to
 * OpenMLS: the sender's key is dropped at encrypt time for forward secrecy, and
 * `process` answers "Cannot decrypt own messages (code -1)". Such rows are served
 * from the local plaintext cache instead.
 *
 * Per DEVICE, not per user. Another device of the same account is a separate MLS
 * leaf, so its messages ARE decryptable here and must still reach `process`.
 * A blank [senderDeviceId] (historical rows) is not evidence of ownership, so
 * those keep the previous behaviour.
 */
internal fun isOwnDeviceMlsMessage(senderDeviceId: String, myDeviceId: String): Boolean =
    senderDeviceId.isNotBlank() && myDeviceId.isNotBlank() && senderDeviceId == myDeviceId

/**
 * Whether a cached row can already be read without asking the crypto layer.
 *
 * Separate from [isOwnDeviceMlsMessage] and solving a different problem. That one
 * keeps a row we could never open out of MLS; this one keeps a row we no longer
 * NEED to open out of MLS. An MLS sender-ratchet secret is consumed by the first
 * successful decrypt, so re-opening a message we already hold in the clear cannot
 * succeed - it only earns SecretReuseError.
 *
 * Readability is a property of the stored row alone: never the sender, the device,
 * the age of the message, or the encryption version. A row still marked encrypted,
 * or one carrying no text, is not readable and must keep its normal decrypt path.
 */
internal fun hasReadableCachedText(isEncrypted: Boolean, content: String): Boolean =
    !isEncrypted && content.isNotBlank()

/**
 * Whether a row carries an archive that may legitimately be reopened.
 *
 * SEALED is the only state that may be opened. NONE means nothing was ever archived, so the
 * message keeps its ordinary failure. RETRACTED is a delete-for-everyone tombstone and must stay
 * one: reopening it would resurrect a message the user erased for every participant, which is the
 * single worst thing this fallback could do.
 */
internal fun archiveIsRestorable(archiveState: String?, archiveCiphertext: String?): Boolean =
    ArchiveState.fromWire(archiveState) == ArchiveState.SEALED && !archiveCiphertext.isNullOrBlank()

/**
 * Whether a row is an attachment rather than ordinary text.
 *
 * The server sends `file_type` for EVERY message, set from its content type, so an ordinary text
 * message arrives carrying `file_type = "text"`. Treating any non-blank value as media therefore
 * classified every text message as an attachment, and silently skipped the whole decrypt-on-store
 * block: cache-first, the MLS decrypt, the archive restore, and archiving itself.
 *
 * The discriminator is the same one the UI already uses (see `toChatMessageUi`): membership of the
 * four known media content types. Anything else - "text", blank, absent, or a type this build does
 * not know - is not an attachment, which is the safe default: it keeps the row on the ordinary
 * text path rather than silently dropping it out of caching.
 */
/**
 * Application-level view of one message's Layer B archive coverage.
 *
 * Derived on read from columns the row already carries - never stored. Archival state
 * lives in `archiveState`/`archiveCiphertext`; this only interprets them, so there is no
 * second source of truth to drift or migrate.
 *
 * There is deliberately no FAILED. A seal that fails - a locked vault being the case that
 * matters - leaves the archive columns exactly as they were, so in persisted state it is
 * indistinguishable from one not yet attempted. Both are [PENDING], and both are handled
 * identically by the existing refresh-triggered retry. Inventing FAILED would mean
 * persisting failure state for a distinction nothing acts on.
 */
enum class MessageArchiveStatus {
    /** Never eligible: encrypted, media, blank, placeholder, or a retracted tombstone. */
    NOT_APPLICABLE,

    /** Eligible and readable, but no archive yet. The next refresh will try again. */
    PENDING,

    /** A sealed archive is present and openable. */
    SEALED,
}

/**
 * Whether a readable row is worth archiving at all.
 *
 * Shared by the archiving pass and by [messageArchiveStatus] so the two can never drift:
 * a message reported PENDING is exactly a message the next pass will attempt.
 */
internal fun isArchivable(isEncrypted: Boolean, isMedia: Boolean, content: String): Boolean =
    !isEncrypted && !isMedia && content.isNotBlank() &&
        content != ChatRepository.ENCRYPTED_PLACEHOLDER

/**
 * Answers "was this message eligible for Layer B, and is it actually archived?" without
 * reading logs - the gap Phase 25 identified, where a locked vault silently stopped
 * archiving and only a log line said so.
 *
 * RETRACTED is checked first and reported [NOT_APPLICABLE]: a delete-for-everyone tombstone
 * is not an archiving opportunity, and reporting it as pending would invite something to
 * try to seal it again.
 */
internal fun messageArchiveStatus(
    isEncrypted: Boolean,
    content: String,
    fileType: String?,
    archiveState: String?,
    archiveCiphertext: String?,
): MessageArchiveStatus {
    val state = ArchiveState.fromWire(archiveState)
    if (state == ArchiveState.RETRACTED) return MessageArchiveStatus.NOT_APPLICABLE
    if (state == ArchiveState.SEALED && !archiveCiphertext.isNullOrBlank()) {
        return MessageArchiveStatus.SEALED
    }
    if (!isArchivable(isEncrypted, isMediaContentType(fileType), content)) {
        return MessageArchiveStatus.NOT_APPLICABLE
    }
    return MessageArchiveStatus.PENDING
}

internal fun isMediaContentType(fileType: String?): Boolean = when (fileType) {
    ChatRepository.VOICE_CONTENT_TYPE,
    ChatRepository.IMAGE_CONTENT_TYPE,
    ChatRepository.FILE_CONTENT_TYPE,
    ChatRepository.VIDEO_NOTE_CONTENT_TYPE -> true
    else -> false
}

class ChatRepository(
    private val chatApiService: ChatApiService,
    private val messageDao: ScopedMessageDao,
    private val conversationDao: ScopedConversationDao,
    private val cachedChatDao: ScopedCachedChatDao,
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
    private val onArchiveFetch: (suspend (chatId: String) -> ArchivePage)? = null,
    /**
     * Reopens one archived message. Same lambda seam and same cycle reason as the two
     * above; null leaves every read path behaving exactly as it did before.
     *
     * This is the read half of Layer B. Sealing without it produced a durable copy that
     * nothing could consume: an MLS ratchet secret is spent by the first successful
     * decrypt, so once local plaintext is gone the archive is the only way back.
     */
    private val onArchiveOpen: (
        suspend (userId: String, chatId: String, messageId: String, rootVersion: Int, ciphertextB64: String) -> Result<String>
    )? = null,
    /**
     * Phase 71 durable outbox and per-chat sync points. Null only in tests that never send or sync;
     * a send without an outbox fails closed rather than falling back to a volatile path.
     */
    private val outboxDao: ScopedOutboxDao? = null,
    private val syncStateDao: ScopedChatSyncStateDao? = null
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
    // chatId -> "direct" | "group", learned from the chat list.

    /**
     * Group messaging over the shared Rust core (mls-core / OpenMLS). Inert
     * while [MlsV2Repository.ENABLED] is false - v1 Sender Keys stay the
     * shipping path until v2 is proven on real devices.
     */
    private val mlsV2 by lazy { MlsV2Repository(chatApiService, tokenManager) }
    // Cached local keys so encrypt/decrypt don't hit disk on every message.
    /**
     * The account whose state the maps below currently hold, or null when they
     * are empty.
     *
     * This exists so the repository can heal itself. Gate 24 showed that relying
     * on logout to call a reset is not enough: the WebSocket outlives logout and
     * nothing cancels its coroutines, so state can be touched when no one has
     * announced a change. MlsV2Repository already solves this by re-checking the
     * signed-in identity on every client access and discarding a client that
     * belongs to someone else; this mirrors that.
     *
     * There is deliberately NO memoised private key and no memoised user id any
     * more. A flat `myPrivateHex` cache let account B perform crypto with
     * account A's E2EE private key, because the memo short-circuited before any
     * identity check.
     */
    @Volatile private var stateOwner: String? = null

    /**
     * Every account-sensitive map this class holds, in one place, reachable only
     * through [owned].
     *
     * They used to be plain fields, which meant any code path could touch them
     * and the only protection was remembering to call a check first. That is
     * check-then-act, and across a coroutine suspension it is not a check at
     * all: AuditRaceProofTest showed learnChatMeta validating owner A, calling
     * applyPeerIdentity, suspending on storage IO, and then writing account A's
     * pending peer key into a singleton that by then belonged to account B.
     */
    private class AccountMemory {
        /** chatId (or "chatId|deviceId") -> live Double Ratchet v3 session. */
        val drSessions = mutableMapOf<String, DoubleRatchet.State>()
        /** Same, for the v4 two-root ratchet. */
        val drV4Sessions = mutableMapOf<String, DoubleRatchetV4.Session>()
        /** chatId -> version -> this account's own group Sender Keys. */
        val mySenderKeys = mutableMapOf<String, MutableMap<Int, String>>()
        /** "chatId|senderId|version" -> a peer's group Sender Key. */
        val groupOtherKeys = mutableMapOf<String, String>()
        /** Guard deciding whether peer keys are reloaded; paired with groupOtherKeys. */
        val peerKeysLoaded = mutableSetOf<String>()
        /** chatId -> the peer identity currently trusted for this chat. */
        val chatOtherPub = mutableMapOf<String, String>()
        /** chatId -> a peer identity the server offered that is NOT yet trusted. */
        val pendingPeerPubs = mutableMapOf<String, String>()
        /**
         * chatId -> the other participant's user id.
         *
         * Deliberately separate from [chatOtherPub]: who the peer is, and
         * whether they have published a key yet, are different facts. While the
         * chat list was the only source of both, a peer who published after our
         * last fetch stayed unreadable for the whole session, because nothing
         * could ask for their key on its own.
         */
        val chatOtherUserId = mutableMapOf<String, String>()
        /** Chats whose peer key was already re-read once; bounds the retry. */
        val peerKeyRefreshed = mutableSetOf<String>()
        /** chatId -> (userId, deviceId) fan-out targets. */
        val chatDevices = mutableMapOf<String, List<Pair<String, String>>>()
        /** chatId -> "direct"/"group". */
        val chatTypeMap = mutableMapOf<String, String>()
        /** chatId -> the group's Sender Key rotation epoch. */
        val groupKeyEpoch = mutableMapOf<String, Int>()
        /** Chats whose peer identity changed and whose warning is unshown. */
        val pendingSecurityNotices = mutableSetOf<String>()

        fun clear() {
            drSessions.clear()
            drV4Sessions.clear()
            mySenderKeys.clear()
            groupOtherKeys.clear()
            peerKeysLoaded.clear()      // paired with groupOtherKeys - see above
            chatOtherPub.clear()
            pendingPeerPubs.clear()
            chatOtherUserId.clear()
            peerKeyRefreshed.clear()
            chatDevices.clear()
            chatTypeMap.clear()
            groupKeyEpoch.clear()
            pendingSecurityNotices.clear()
        }
    }

    private val mem = AccountMemory()

    /**
     * The only way to reach [mem]. Runs [block] if, and only if, [owner] is
     * still the signed-in account at this instant.
     *
     * [block] is deliberately NOT a suspend lambda, and that is the entire
     * mechanism rather than a stylistic choice: the compiler rejects any
     * suspension inside it, so no account switch can interleave between the
     * validation above and the accesses within. An ownership check performed
     * before a suspension does not authorise an access after it - every access
     * has to be dominated by a check that nothing can run between.
     *
     * Returns null when the operation no longer owns the account, which callers
     * treat as "refused", never as "empty".
     */
    private suspend fun <T> owned(owner: String, block: (AccountMemory) -> T): T? {
        val active = healIfAccountChanged()
        if (active == null || owner.isBlank() || owner != active) return null
        return block(mem)
    }

    /**
     * A read issued by whoever is signed in right now. Still validated: the heal
     * inside [owned] discards another account's state before anything reads it.
     */
    private suspend fun <T> ownedNow(block: (AccountMemory) -> T): T? =
        owned(operationOwner(), block)

    /**
     * [owned] for mutations. Returns whether the change was applied, which a map
     * assignment cannot report on its own - `map[k] = v` evaluates to the value
     * it replaced, so null there means "nothing was there before", not "refused".
     */
    private suspend fun ownedApply(owner: String, block: (AccountMemory) -> Unit): Boolean {
        val active = healIfAccountChanged()
        if (active == null || owner.isBlank() || owner != active) return false
        block(mem)
        return true
    }
    // Serializes key generation so concurrent callers (multiple ViewModels)
    // don't each generate a different keypair and clobber each other.
    private val keyMutex = Mutex()
    private val drMutex = Mutex()

    // ==================== Group "Sender Keys" state ====================

    private data class SenderKeyState(val version: Int, val keyHex: String)

    // chatId -> the server's current key_epoch (the rotation signal).
    // chatId -> version -> this device's own Sender Key hex (historical + current).
    // "chatId|senderId|version" -> decrypted Sender Key (hex) for other members.
    // Serializes (re)generation/distribution per chat so concurrent sends
    // don't each publish a different key for the same epoch.
    private val groupKeyMutex = Mutex()

    /**
     * chatIds whose direct-chat E2EE public key changed since we last saw it -
     * a WhatsApp-style "security code changed" event. Pulled (and cleared) by
     * the UI via [takePendingSecurityNotice] rather than pushed, so it doesn't
     * need its own SharedFlow plumbing.
     */

    /**
     * True exactly once per change - calling this clears the flag for [chatId].
     *
     * suspend, because it reads account-scoped memory and every such access has
     * to be ownership-validated. It used to be a plain getter, which is how
     * account B came to consume a peer-key-change warning raised in account A's
     * session - and consume it, so A never saw its own warning again.
     */
    suspend fun takePendingSecurityNotice(chatId: String): Boolean =
        ownedNow { m -> m.pendingSecurityNotices.remove(chatId) } ?: false

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

    suspend fun chatTypeFor(chatId: String): String =
        ownedNow { m -> m.chatTypeMap[chatId] } ?: "direct"

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

                val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull()
                val pub = tokenManager.getE2EEPublicKey(userId).getOrNull()

                // Happy path: we already own this account's key on this device.
                if (!priv.isNullOrEmpty() && !pub.isNullOrEmpty()) {
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

    /**
     * This account's E2EE private key, resolved fresh every time.
     *
     * Deliberately not cached. TokenManager already stores keys per account and
     * reads them from the Keystore-backed store, so the only thing a process
     * cache bought was skipping that read - at the cost of handing account B
     * account A's private key after a switch, which is what Gate 24 proved.
     * Correctness of whose key this is outranks avoiding a disk read.
     */
    private suspend fun myPriv(): String? {
        val userId = currentUserIdOrEmpty().takeIf { it.isNotBlank() } ?: return null
        return tokenManager.getE2EEPrivateKey(userId).getOrNull()
    }

    private suspend fun myPubHex(): String? {
        val userId = currentUserIdOrEmpty().takeIf { it.isNotBlank() } ?: return null
        return tokenManager.getE2EEPublicKey(userId).getOrNull()
    }

    private suspend fun loadRatchet(owner: String, sessionKey: String, otherPub: String, priv: String, sending: Boolean): DoubleRatchet.State? {
        owned(owner) { m -> m.drSessions[sessionKey] }?.let { return it }
        // The storage read below suspends, so the install afterwards needs its
        // own validation - the one above authorised nothing past that point.
        tokenManager.loadDirectRatchet(owner, sessionKey).getOrNull()?.let { json ->
            DoubleRatchet.State.fromJson(json)?.let { restored ->
                if (!ownedApply(owner) { m -> m.drSessions[sessionKey] = restored }) return null
                return restored
            }
        }
        val their = E2ECrypto.fromHex(otherPub) ?: return null
        if (sending) {
            val fresh = DoubleRatchet.initAlice(their) ?: return null
            if (!ownedApply(owner) { m -> m.drSessions[sessionKey] = fresh }) return null
            return fresh
        }
        val pk = E2ECrypto.fromHex(myPubHex() ?: return null) ?: return null
        val sk = E2ECrypto.fromHex(priv) ?: return null
        val bob = DoubleRatchet.initBob(pk, sk)
        if (!ownedApply(owner) { m -> m.drSessions[sessionKey] = bob }) return null
        return bob
    }

    private suspend fun persistRatchet(owner: String, sessionKey: String, st: DoubleRatchet.State) {
        st.seq += 1
        if (!ownedApply(owner) { m -> m.drSessions[sessionKey] = st }) return
        tokenManager.saveDirectRatchet(owner, sessionKey, st.toJson())
        tokenManager.getAccessToken().getOrNull()?.let { notifyVaultMaterialChanged(it) }
    }

    suspend fun adoptDirectRatchetsFromVault(owner: String, sessions: Map<String, String>) {
        drMutex.withLock {
            val restored = sessions.mapNotNull { (key, json) ->
                if (key.isBlank() || json.isBlank()) null
                else DoubleRatchet.State.fromJson(json)?.let { key to it }
            }
            ownedApply(owner) { m -> for ((key, st) in restored) m.drSessions[key] = st }
        }
    }

    private suspend fun encryptDirectV3(owner: String, sessionKey: String, otherPub: String, priv: String, plain: ByteArray): ByteArray? {
        var st = loadRatchet(owner, sessionKey, otherPub, priv, sending = true) ?: return null
        if (st.cks.size != 32) {
            st = DoubleRatchet.initAlice(E2ECrypto.fromHex(otherPub) ?: return null) ?: return null
            val reset = st
            if (!ownedApply(owner) { m -> m.drSessions[sessionKey] = reset }) return null
        }
        val out = DoubleRatchet.encrypt(st, plain) ?: return null
        persistRatchet(owner, sessionKey, st)
        return out
    }

    private suspend fun decryptDirectV3(owner: String, sessionKey: String, otherPub: String, priv: String, payload: ByteArray): ByteArray? {
        val st = loadRatchet(owner, sessionKey, otherPub, priv, sending = false) ?: return null
        val plain = DoubleRatchet.decrypt(st, payload) ?: return null
        persistRatchet(owner, sessionKey, st)
        return plain
    }


    suspend fun refreshChatDevices(token: String, chatId: String) {
        try {
            val owner = operationOwner()
            val r = chatApiService.listChatE2EEDevices(bearer(token), chatId)
            if (r.isSuccessful) {
                val devices = r.body()?.devices.orEmpty().map { it.userId to it.deviceId }
                ownedApply(owner) { m -> m.chatDevices[chatId] = devices }
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshChatDevices", e)
        }
    }

    private suspend fun sealDirectV3(owner: String, chatId: String, otherPub: String, priv: String, inner: ByteArray): ByteArray? {
        val me = currentUserIdOrEmpty()
        val myPub = myPubHex() ?: otherPub
        val devices = owned(owner) { m -> m.chatDevices[chatId] }.orEmpty()
        val targets = LinkedHashMap<String, String>()
        for ((uid, did) in devices) {
            if (did.isBlank()) continue
            val pub = if (uid == me) myPub else otherPub
            if (pub.isNotBlank()) targets[did] = pub
        }
        if (targets.size <= 1) {
            return encryptDirectV3(owner, chatId, otherPub, priv, inner)
        }
        val parts = ArrayList<E2ECrypto.FanoutPart>(targets.size)
        for ((did, pub) in targets) {
            val blob = encryptDirectV3(owner, "$chatId|$did", pub, priv, inner) ?: return null
            parts.add(E2ECrypto.FanoutPart(did, blob))
        }
        return E2ECrypto.wrapFanout(parts)
    }

    private suspend fun openDirectV3(
        owner: String,
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
        decryptDirectV3(owner, keyed, sessPub, priv, blob)?.let { return it }
        if (keyed != chatId) decryptDirectV3(owner, chatId, otherPub, priv, blob)?.let { return it }
        return null
    }

    // ==================== Direct v4 (X3DH-lite two-root ratchet) ====================
    // Glare-safe replacement for v3 (see DoubleRatchetV4 / docs/e2ee-protocol-v4.md).
    // Sessions are role-free: InitSession is symmetric from the two identity keys.
    // Stored under a "v4:" prefix so they never clobber legacy v3 vault entries.


    private suspend fun loadRatchetV4(owner: String, sessionKey: String, otherPub: String): DoubleRatchetV4.Session? {
        // Sessions created by earlier builds' failed decrypt attempts were
        // initialized with the other user's key even for this account's own
        // traffic; their rk0 can never open those blobs. A stored session
        // whose peerIdent disagrees with [otherPub] is stale — discard it and
        // re-derive from the right identity.
        val expected = E2ECrypto.fromHex(otherPub)
        fun identityOk(st: DoubleRatchetV4.Session): Boolean =
            expected == null || st.peerIdent.contentEquals(expected)
        owned(owner) { m -> m.drV4Sessions[sessionKey] }?.let { if (identityOk(it)) return it }
        // The storage read suspends; the install below is validated again.
        tokenManager.loadDirectRatchet(owner, "v4:$sessionKey").getOrNull()?.let { json ->
            DoubleRatchetV4.Session.fromJson(json)?.let { restored ->
                if (identityOk(restored)) {
                    if (!ownedApply(owner) { m -> m.drV4Sessions[sessionKey] = restored }) return null
                    return restored
                }
            }
        }
        val myPk = E2ECrypto.fromHex(myPubHex() ?: return null) ?: return null
        val mySk = E2ECrypto.fromHex(myPriv() ?: return null) ?: return null
        val peer = expected ?: return null
        val fresh = DoubleRatchetV4.initSession(myPk, mySk, peer) ?: return null
        if (!ownedApply(owner) { m -> m.drV4Sessions[sessionKey] = fresh }) return null
        return fresh
    }

    private suspend fun persistRatchetV4(owner: String, sessionKey: String, st: DoubleRatchetV4.Session) {
        st.seq += 1
        if (!ownedApply(owner) { m -> m.drV4Sessions[sessionKey] = st }) return
        tokenManager.saveDirectRatchet(owner, "v4:$sessionKey", st.toJson())
        tokenManager.getAccessToken().getOrNull()?.let { notifyVaultMaterialChanged(it) }
    }

    private suspend fun encryptDirectV4(owner: String, sessionKey: String, otherPub: String, plain: ByteArray): ByteArray? {
        val st = loadRatchetV4(owner, sessionKey, otherPub) ?: return null
        val out = st.encrypt(plain) ?: return null
        persistRatchetV4(owner, sessionKey, st)
        return out
    }

    private suspend fun decryptDirectV4(owner: String, sessionKey: String, otherPub: String, payload: ByteArray): ByteArray? {
        val st = loadRatchetV4(owner, sessionKey, otherPub) ?: return null
        val plain = st.decrypt(payload) ?: return null
        persistRatchetV4(owner, sessionKey, st)
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
    private suspend fun sealSelfV4(owner: String, inner: ByteArray): ByteArray? {
        val pk = E2ECrypto.fromHex(myPubHex() ?: return null) ?: return null
        val sk = E2ECrypto.fromHex(myPriv() ?: return null) ?: return null
        val tmp = DoubleRatchetV4.initSession(pk, sk, pk) ?: return null
        return tmp.encrypt(inner)
    }

    private suspend fun sealDirectV4(owner: String, chatId: String, otherPub: String, inner: ByteArray): ByteArray? {
        val me = currentUserIdOrEmpty()
        val myPub = myPubHex() ?: otherPub
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val devices = owned(owner) { m -> m.chatDevices[chatId] }.orEmpty()
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
            return encryptDirectV4(owner, chatId, otherPub, inner)
        }
        val parts = ArrayList<E2ECrypto.FanoutPart>(targets.size)
        for ((did, pub) in targets) {
            val blob = if (did == myDev) sealSelfV4(owner, inner)
                       else encryptDirectV4(owner, "$chatId|$did", pub, inner)
            if (blob == null) return null
            parts.add(E2ECrypto.FanoutPart(did, blob))
        }
        return E2ECrypto.wrapFanout(parts)
    }

    private suspend fun openDirectV4(owner: String, chatId: String, otherPub: String, payload: ByteArray, senderId: String, senderDeviceId: String): ByteArray? {
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
            decryptDirectV4(owner, keyed, sessPub, blob)?.let { return it }
            if (keyed != chatId) decryptDirectV4(owner, chatId, otherPub, blob)?.let { return it }
        }
        // No blob for this device id (or it failed): this install may be
        // missing from the registry the sender fanned out to (fresh install,
        // changed device id). INITIAL blobs open statelessly with just the
        // account identity, so try the copies addressed to other devices —
        // wrong ones simply fail authentication.
        for (part in E2ECrypto.listFanout(payload)) {
            if (part.deviceId == myDev) continue
            decryptDirectV4(owner, keyed, sessPub, part.blob)?.let { return it }
        }
        return null
    }

    /** The signed-in account, read fresh. Never memoised - see [stateOwner]. */
    private suspend fun currentUserIdOrEmpty(): String =
        tokenManager.getCurrentUserId().getOrNull()?.trim().orEmpty()

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
        val owner = operationOwner()
        val otherPub = owned(owner) { m -> m.chatOtherPub[chatId] }
            ?: return SealedText(plaintext, false, 1)
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
            val v4 = sealDirectV4(owner, chatId, otherPub, inner)
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
        val owner = operationOwner()
        val inner = E2ECrypto.wrapEnvelope(plain, fileName, forwardedFrom, durationMs, fileSize, thumbnailUrl, fileUrl)
        if (owned(owner) { m -> m.chatTypeMap[chatId] }.equals("group", ignoreCase = true)) {
            // v5 (BouncyCastle MLS) removed: it never interoperated with the
            // Windows mlspp stack and its group state could not be rebuilt after
            // a restart. Group media is Sender Keys until v6 covers binary too.
            val state = ensureGroupSenderKeyReady(operationOwner(), token, chatId) ?: return null
            val cipher = E2ECrypto.secretBoxEncryptBytes(inner, state.keyHex) ?: return null
            return SealedBytes(cipher, state.version)
        }
        val otherPub = owned(owner) { m -> m.chatOtherPub[chatId] } ?: return null
        val priv = myPriv() ?: return null
        syncVaultDown()
        refreshChatDevices(token, chatId)
        drMutex.withLock {
            val v4 = sealDirectV4(owner, chatId, otherPub, inner)
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
        val plain = decryptToBytes(operationOwner(), chatId, payload, senderId, keyVersion, encryptionVersion, senderDeviceId) ?: return null
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
        owner: String,
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
            // Enforce the invariant above instead of only documenting it. A
            // history refresh returns our own rows too, and feeding one to
            // OpenMLS earns "Cannot decrypt own messages (code -1)" on every
            // refresh - wasted work and log noise for a row the caller is about
            // to serve from the local cache anyway.
            //
            // The test is per DEVICE, not per user, which is where this differs
            // from the v4 self-check: another device of ours is a separate MLS
            // leaf, so its messages ARE decryptable here and must still go
            // through mlsV2.process. Only our own leaf's messages cannot be.
            //
            // A blank sender_device_id (historical rows) tells us nothing, so
            // those keep the previous behaviour rather than being skipped.
            val myDeviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            if (isOwnDeviceMlsMessage(senderDeviceId, myDeviceId)) {
                return null
            }
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
            owned(owner) { m -> m.chatTypeMap[chatId] }.equals("group", ignoreCase = true)
        }
        if (isGroupRow) {
            ensurePeerSenderKeysLoaded(owner, chatId)
            val me = currentUserIdOrEmpty()
            val keyHex = owned(owner) { m ->
                groupSenderKeyFor(chatId, senderId, keyVersion, me, m)
            } ?: run {
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
        val otherPub = owned(owner) { m -> m.chatOtherPub[chatId] } ?: ""
        android.util.Log.d(
            DEC,
            "try chat=$chatId encV=$encryptionVersion payload=${payload.size} " +
                "otherPub=${if (otherPub.isEmpty()) "MISSING" else "len${otherPub.length}"} " +
                "devId=${senderDeviceId.ifEmpty { "EMPTY" }}"
        )
        if (encryptionVersion == 4) {
            var peerPub = otherPub
            if (peerPub.isEmpty()) {
                // Most likely the contact published after our last chat-list
                // fetch. Ask the server once before giving up; on failure the
                // behaviour is unchanged - no key is assumed, nothing is shown.
                peerPub = refreshPeerKey(owner, chatId).orEmpty()
                if (peerPub.isEmpty()) {
                    android.util.Log.w(DEC, "FAIL v4: peer identity key missing chat=$chatId")
                    return null
                }
                android.util.Log.i(DEC, "recovered peer identity key for chat=$chatId")
            }
            @Suppress("NAME_SHADOWING") val otherPub = peerPub
            syncVaultDown()
            drMutex.withLock { openDirectV4(owner, chatId, otherPub, payload, senderId, senderDeviceId) }?.let { return it }
            syncVaultDown(force = true)
            return drMutex.withLock { openDirectV4(owner, chatId, otherPub, payload, senderId, senderDeviceId) }.also {
                if (it == null) android.util.Log.w(DEC, "FAIL v4: ratchet open failed even after vault resync chat=$chatId devId=${senderDeviceId.ifEmpty { "EMPTY" }}")
            }
        }
        if (encryptionVersion == 3) {
            syncVaultDown()
            drMutex.withLock { openDirectV3(owner, chatId, otherPub, priv, payload, senderId, senderDeviceId) }?.let { return it }
            syncVaultDown(force = true)
            return drMutex.withLock { openDirectV3(owner, chatId, otherPub, priv, payload, senderId, senderDeviceId) }.also {
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
        val plain = decryptToBytes(operationOwner(), chatId, payload, senderId, keyVersion, encryptionVersion, senderDeviceId, chatType)
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

    /**
     * Preview text for a chat-list row, preferring the copy we already hold.
     *
     * The list renders the newest message of every chat on each refresh. Doing
     * that by decrypting [content] again cannot work for MLS: the ratchet secret
     * was consumed by the decrypt that first displayed the message, so OpenMLS
     * answers SecretReuseError and the row renders as the encrypted placeholder
     * even though the chat itself shows the text fine.
     *
     * Readability is judged from the stored row alone - never from the sender,
     * the device, the version, or the age of the message. A row we cannot
     * already read (encrypted, blank, or absent) falls through to exactly the
     * previous behaviour, so a genuinely new message still gets its one decrypt.
     */
    suspend fun previewFor(
        chatId: String,
        messageId: String,
        content: String,
        encrypted: Boolean,
        senderId: String = "",
        keyVersion: Int = 0,
        encryptionVersion: Int = 1,
        senderDeviceId: String = "",
        chatType: String = ""
    ): String {
        if (messageId.isNotBlank()) {
            val cached = runCatching { messageDao.getMessageById(messageId) }.getOrNull()
            if (cached != null && hasReadableCachedText(cached.isEncrypted, cached.content)) {
                return cached.content
            }
        }
        return decryptFor(
            chatId, content, encrypted, senderId, keyVersion, encryptionVersion, senderDeviceId, chatType
        )
    }

    // ==================== Group "Sender Keys" ====================

    /**
     * The key that encrypted a given group message: our own key for
     * [keyVersion] when [senderId] is us (historical versions retained after
     * rotation), otherwise a copy fetched from the server.
     */
    private fun groupSenderKeyFor(
        chatId: String,
        senderId: String,
        keyVersion: Int,
        /**
         * The signed-in account, resolved by the caller. Previously this
         * compared against a memoised `myUserId`, which after an account switch
         * still held the PREVIOUS account - so a message sent by A was treated
         * as "mine" while B was signed in, and answered with A's own Sender Key.
         */
        me: String,
        /**
         * Passed in rather than reached for: this runs inside an [owned] block,
         * which is what guarantees the account still owns these maps.
         */
        m: AccountMemory
    ): String? {
        if (senderId.isNotEmpty() && me.isNotEmpty() && senderId == me) {
            m.mySenderKeys[chatId]?.get(keyVersion)?.let { return it }
            // Other installs of this account fetch our key as a "peer" copy
            // published to ourselves; use that when this device never generated it.
            return m.groupOtherKeys["$chatId|$senderId|$keyVersion"]
        }
        return m.groupOtherKeys["$chatId|$senderId|$keyVersion"]
    }

    private suspend fun ensurePeerSenderKeysLoaded(owner: String, chatId: String) {
        // Claiming the reload slot and installing what comes back are two
        // separate ownership decisions: the storage read between them suspends.
        val firstToLoad = owned(owner) { m -> m.peerKeysLoaded.add(chatId) } ?: return
        if (!firstToLoad) return
        val loaded = tokenManager.loadPeerSenderKeys(owner, chatId).getOrNull() ?: return
        val installed = ownedApply(owner) { m ->
            for ((senderAndVer, hex) in loaded) m.groupOtherKeys["$chatId|$senderAndVer"] = hex
        }
        // Refused: the reload slot must not stay claimed, or the account that
        // does own this memory would skip its own load.
        if (!installed) owned(owner) { m -> m.peerKeysLoaded.remove(chatId) }
    }

    /**
     * Ensures this device has a Sender Key for [chatId] that covers the
     * group's current key_epoch, (re)generating and redistributing it to every
     * member when it's missing or stale. Returns null only when we couldn't
     * establish one at all (e.g. offline) - callers must fall back to sending
     * in the clear rather than blocking the message entirely.
     */
    private suspend fun ensureGroupSenderKeyReady(
        owner: String,
        token: String,
        chatId: String
    ): SenderKeyState? =
        groupKeyMutex.withLock {
            // Generating or distributing a Sender Key is an account-bound act.
            val needsLoad = owned(owner) { m -> m.mySenderKeys[chatId].isNullOrEmpty() }
                ?: return@withLock null
            if (needsLoad) {
                val stored = tokenManager.loadGroupSenderKeys(owner, chatId).getOrNull()
                if (!stored.isNullOrEmpty()) {
                    if (!ownedApply(owner) { m -> m.mySenderKeys[chatId] = stored.toMutableMap() }) {
                        return@withLock null
                    }
                }
            }

            val snapshot = owned(owner) { m ->
                val epoch = m.groupKeyEpoch[chatId] ?: 0
                val v = m.mySenderKeys.getOrPut(chatId) { mutableMapOf() }
                epoch to v.maxByOrNull { it.key }?.let { SenderKeyState(it.key, it.value) }
            } ?: return@withLock null
            val currentEpoch = snapshot.first
            val current = snapshot.second
            if (current != null && current.version >= currentEpoch) {
                return@withLock current
            }

            // Stale (or missing) - fetch the authoritative member list + epoch,
            // generate a fresh key, and redistribute it to everyone.
            val group = groupRepository.getGroupInfo(token, chatId).getOrNull() ?: return@withLock current
            if (!ownedApply(owner) { m -> m.groupKeyEpoch[chatId] = group.keyEpoch }) {
                return@withLock null
            }

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
            val knownVersions = owned(owner) { m ->
                m.mySenderKeys.getOrPut(chatId) { mutableMapOf() }.keys.toSet()
            } ?: return@withLock null
            val nextVersion = nextSenderKeyVersion(group.keyEpoch, knownVersions)

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
            // Persist BEFORE installing in memory.
            //
            // The order used to be the other way round, which meant a failed
            // save left a key that was usable this session and gone the next -
            // and once the in-memory maps are cleared on logout, unrecoverable.
            // Failing closed here costs a send; the alternative silently
            // encrypts under a key no one can reconstruct.
            val persisted = runCatching {
                tokenManager.saveGroupSenderKey(owner, chatId, "${state.version}:${state.keyHex}")
            }.getOrNull()
            if (persisted == null || persisted.isFailure) {
                Log.w(TAG, "sender key for $chatId could not be persisted; not installing it")
                return@withLock null
            }
            if (!ownedApply(owner) { m ->
                    m.mySenderKeys.getOrPut(chatId) { mutableMapOf() }[state.version] = state.keyHex
                }) return@withLock null
            notifyVaultMaterialChanged(token)
            state
        }

    /**
     * Downloads and decrypts every Sender Key distributed to us for [chatId],
     * caching them for [groupSenderKeyFor]. Must run before group history can
     * be decrypted - call this before reading/rendering a group's messages.
     */
    suspend fun fetchGroupSenderKeys(token: String, chatId: String) {
        // Captured once, at the operation boundary, and used for every durable
        // write below. Re-reading the active account after the network call is
        // exactly the mistake Gate 22.1 ruled out.
        val owner = operationOwner()
        val priv = myPriv() ?: return
        ensurePeerSenderKeysLoaded(owner, chatId)
        groupRepository.getSenderKeys(token, chatId).onSuccess { entries ->
            for (entry in entries) {
                if (entry.senderPublicKey.isBlank() || entry.encryptedKey.isBlank()) continue
                val payload = E2ECrypto.fromHex(entry.encryptedKey) ?: continue
                val keyBytes = E2ECrypto.decryptBytes(payload, entry.senderPublicKey, priv) ?: continue
                val hex = E2ECrypto.toHex(keyBytes)
                if (!ownedApply(owner) { m ->
                        m.groupOtherKeys["$chatId|${entry.senderId}|${entry.keyVersion}"] = hex
                    }) return@onSuccess
                tokenManager.savePeerSenderKey(owner, chatId, entry.senderId, entry.keyVersion, hex)
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
        val state = ensureGroupSenderKeyReady(operationOwner(), token, chatId)
            ?: return SealedText(plaintext, false, 1, 0)
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
    private suspend fun learnChatMeta(owner: String, dto: ChatListItemDto) {
        // A chat list fetched as A must not populate B's in-memory metadata if
        // the reply lands after a switch.
        val peerId = dto.otherUserId?.takeIf { it.isNotBlank() }
            ?: dto.otherUser?.id?.takeIf { it.isNotBlank() }
        if (!ownedApply(owner) { m ->
                m.chatTypeMap[dto.id] = dto.type
                m.groupKeyEpoch[dto.id] = dto.keyEpoch
                // Record the peer's identity even when they have no key yet -
                // that is precisely the case refreshPeerKey() has to recover.
                if (peerId != null) m.chatOtherUserId[dto.id] = peerId
            }) return
        val pub = dto.otherUser?.publicKey?.takeIf { it.isNotEmpty() } ?: return
        if (!dto.type.equals("group", ignoreCase = true)) {
            // applyPeerIdentity suspends before it writes, so it re-validates
            // rather than relying on the check above.
            applyPeerIdentity(owner, dto.id, pub)
        } else {
            ownedApply(owner) { m -> m.chatOtherPub[dto.id] = pub }
        }
    }

    /**
     * Re-reads one chat's peer identity key from the server.
     *
     * The chat list is a snapshot: a contact who publishes after our last fetch
     * is invisible to us until the next one, which left v4 threads permanently
     * unreadable. This asks the authoritative endpoint instead, and feeds the
     * answer through [applyPeerIdentity] so TOFU pinning and the "security code
     * changed" prompt behave exactly as they do for a chat-list key.
     *
     * Bounded to one attempt per chat per account: a peer who genuinely has no
     * key must not turn every arriving message into a request.
     *
     * Returns the trusted key after the refresh, or null if there still is one.
     */
    private suspend fun refreshPeerKey(owner: String, chatId: String): String? {
        val already = owned(owner) { m -> m.peerKeyRefreshed.contains(chatId) } ?: return null
        if (already) return null
        val userId = owned(owner) { m -> m.chatOtherUserId[chatId] }?.takeIf { it.isNotBlank() }
            ?: return null
        if (!ownedApply(owner) { m -> m.peerKeyRefreshed.add(chatId) }) return null

        val token = tokenManager.getAccessToken().getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val pub = try {
            val resp = chatApiService.getPublicKey(bearer(token), userId)
            if (resp.isSuccessful) resp.body()?.get("public_key").orEmpty() else ""
        } catch (e: Exception) {
            Log.w(TAG, "peer key refresh failed for chat=$chatId: ${e.message}")
            ""
        }
        // Absent is a legitimate answer - the peer has not published. Never
        // invent a key, never fall back to plaintext.
        if (pub.isBlank()) return null

        // The network call suspended, so the account may have changed under us.
        // applyPeerIdentity re-validates ownership itself before it writes.
        applyPeerIdentity(owner, chatId, pub)
        return owned(owner) { m -> m.chatOtherPub[chatId] }?.takeIf { it.isNotEmpty() }
    }

    /**
     * First-seen identity is pinned (TOFU). A later server value is held as
     * pending until [acceptPeerKeyChange] — encrypt keeps using the pinned key.
     */
    private suspend fun applyPeerIdentity(owner: String, chatId: String, serverPub: String) {
        val known = tokenManager.getKnownPublicKey(owner, chatId).getOrNull()
        // getKnownPublicKey above suspends. Everything below is therefore a
        // fresh ownership decision - the check learnChatMeta made before calling
        // in here authorised nothing past that suspension. This is exactly the
        // window AuditRaceProofTest parks in.
        when {
            known.isNullOrBlank() -> {
                if (!ownedApply(owner) { m ->
                        m.pendingPeerPubs.remove(chatId)
                        m.chatOtherPub[chatId] = serverPub
                    }) return
                tokenManager.saveKnownPublicKey(owner, chatId, serverPub)
                tokenManager.clearPendingPublicKey(owner, chatId)
                tokenManager.getAccessToken().getOrNull()?.let { notifyVaultMaterialChanged(it) }
            }
            known.equals(serverPub, ignoreCase = true) -> {
                if (!ownedApply(owner) { m ->
                        m.pendingPeerPubs.remove(chatId)
                        m.chatOtherPub[chatId] = known
                    }) return
                tokenManager.clearPendingPublicKey(owner, chatId)
            }
            else -> {
                // A peer identity that disagrees with the pinned one: recorded as
                // pending, never trusted, and the warning is raised for the
                // account that actually saw it.
                if (!ownedApply(owner) { m ->
                        m.pendingPeerPubs[chatId] = serverPub
                        m.pendingSecurityNotices.add(chatId)
                        m.chatOtherPub[chatId] = known
                    }) return
                tokenManager.savePendingPublicKey(owner, chatId, serverPub)
                tokenManager.clearSafetyVerified(owner, chatId)
            }
        }
    }

    /**
     * suspend for the same reason as [takePendingSecurityNotice]: it reads
     * account-scoped memory, and a plain getter cannot validate ownership.
     */
    suspend fun hasPendingPeerKeyChange(chatId: String): Boolean =
        ownedNow { m -> m.pendingPeerPubs.containsKey(chatId) } ?: false

    suspend fun acceptPeerKeyChange(chatId: String) {
        val owner = operationOwner()
        val pending = tokenManager.getPendingPublicKey(owner, chatId).getOrNull()
            ?: owned(owner) { m -> m.pendingPeerPubs[chatId] }
            ?: return
        // Pinning a new peer identity is the single most trust-relevant act here,
        // so it is refused outright once the account has changed - never applied
        // on behalf of whoever is signed in now.
        if (!ownedApply(owner) { m ->
                m.pendingPeerPubs.remove(chatId)
                m.pendingSecurityNotices.remove(chatId)
                m.chatOtherPub[chatId] = pending
            }) return
        tokenManager.saveKnownPublicKey(owner, chatId, pending)
        tokenManager.clearPendingPublicKey(owner, chatId)
        tokenManager.clearSafetyVerified(owner, chatId)
        drMutex.withLock {
            val oldSeq = owned(owner) { m ->
                val prev = m.drSessions[chatId]?.seq ?: 0L
                m.drSessions.remove(chatId)
                prev
            } ?: return@withLock
            tokenManager.deleteDirectRatchet(owner, chatId)
            val their = E2ECrypto.fromHex(pending) ?: return@withLock
            val st = DoubleRatchet.initAlice(their) ?: return@withLock
            st.seq = oldSeq
            persistRatchet(owner, chatId, st)
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
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()

            val response = chatApiService.getChats(bearer(token))
            if (response.isSuccessful && response.body() != null) {
                val chats = response.body()!!.data
                // Learn each chat's type/key_epoch/other-participant public key.
                for (c in chats) learnChatMeta(owner, c)
                cacheChats(owner, chats)
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
        val owner = operationOwner()
        val result = mutableListOf<ChatListItemDto>()
        for (entity in cachedChatDao.getAllCached()) {
            try {
                val dto = json.decodeFromString(ChatListItemDto.serializer(), entity.rawJson)
                learnChatMeta(owner, dto)
                result.add(dto)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode cached chat ${entity.id}", e)
            }
        }
        result
    }

    private suspend fun cacheChats(owner: String, chats: List<ChatListItemDto>) {
        if (chats.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = chats.map { dto ->
            CachedChatEntity(
                id = dto.id,
                rawJson = json.encodeToString(ChatListItemDto.serializer(), dto),
                cachedAt = now
            )
        }
        cachedChatDao.insertAll(owner, entities)
    }

    suspend fun getOrCreateDirectChat(token: String, contactId: String): Result<DirectChatDto> =
        withContext(Dispatchers.IO) {
            try {
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()
                val response = chatApiService.getOrCreateDirectChat(bearer(token), contactId)
                if (response.isSuccessful && response.body() != null) {
                    val chat = response.body()!!.data
                    // Insert only when the row is genuinely new; otherwise UPDATE.
                    //
                    // This is the "resume a direct chat" path, so the row usually
                    // already exists WITH cached messages hanging off it.
                    // insertConversation is REPLACE, and SQLite implements that as
                    // delete-then-insert, which fires the messages table's
                    // ON DELETE CASCADE: reopening a direct chat silently destroyed
                    // its entire cached history, and the reload that followed made
                    // it look as though nothing had ever been cached. It also reset
                    // mute/archive/unread, which the server response does not carry.
                    //
                    // Every other insertConversation call site already guards this
                    // way; this one did not.
                    if (conversationDao.getConversationById(chat.id) == null) {
                        conversationDao.insertConversation(
                            owner,
                            ConversationEntity(
                                id = chat.id,
                                type = chat.type,
                                name = chat.name,
                                avatarUrl = chat.avatarUrl
                            )
                        )
                    } else {
                        conversationDao.updateConversationMeta(
                            owner, chat.id, chat.type, chat.name, chat.avatarUrl
                        )
                    }
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

    /** Ciphertext for one outgoing text: produced once, then stored and re-sent unchanged. */
    private data class OutgoingCiphertext(val content: String, val keyVersion: Int, val encryptionVersion: Int)

    /**
     * Encrypts one outgoing text with the chat's scheme - MLS v2, Sender Keys or the direct ratchet -
     * exactly as the send path always has, failing closed when no scheme can seal it. Called ONCE per
     * logical message: the outbox stores the result and every retry re-sends those bytes.
     */
    private suspend fun sealOutgoingText(
        token: String,
        chatId: String,
        chatType: String,
        plaintext: String,
        forward: ForwardMeta
    ): Result<OutgoingCiphertext> = withContext(Dispatchers.IO) {
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
            Result.success(OutgoingCiphertext(outContent, keyVersion, encVer))
    }

    /** What a send produced, from the caller's point of view. */
    sealed class SendOutcome {
        abstract val clientMessageId: String

        /** The server durably stored it. [data] is null when another outbox pass got the answer. */
        data class Accepted(
            override val clientMessageId: String,
            val serverId: String,
            val createdAt: String?,
            val data: SendMessageResponseData?
        ) : SendOutcome()

        /** Stored in the outbox and not yet accepted; it is retried automatically. */
        data class Queued(override val clientMessageId: String, val reason: String) : SendOutcome()

        /** Could not be sealed, or refused in a way only an explicit retry can change. */
        data class Failed(override val clientMessageId: String, val error: Throwable) : SendOutcome()
    }

    class MessageQueuedException(val clientMessageId: String, reason: String) :
        Exception("Queued on this device; it will be retried: $reason")

    /**
     * The Phase 71 send path:
     *
     *   plaintext -> E2EE seal (once) -> durable outbox row -> HTTP POST -> server acceptance
     *
     * The outbox row - ciphertext, protocol metadata and [clientMessageId], never plaintext - is
     * committed before the first transmission, so the message survives the ViewModel, navigation,
     * a process restart and a lost ACK. The POST goes out whether or not the WebSocket is connected;
     * the socket only speeds up the recipient's realtime view and never gates acceptance.
     *
     * [clientMessageId] is minted by the caller (or here) once and reused by every retry; the server
     * resolves a repeat to the message it already holds, so a retry cannot duplicate a message.
     */
    suspend fun sendText(
        token: String,
        chatId: String,
        chatType: String,
        plaintext: String,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = "",
        clientMessageId: String = java.util.UUID.randomUUID().toString()
    ): SendOutcome = withContext(Dispatchers.IO) {
        // Owner captured at operation START, before the network call. A reply
        // that lands after an account switch is refused, never re-attributed.
        val owner = operationOwner()
        // Claim the in-memory state for this account before touching it.
        // This both establishes ownership (so a later read does not mistake
        // A's own state for a previous account's and discard it) and refuses
        // the write outright if the account has since changed.
        if (!ownedApply(owner) { m -> m.chatTypeMap[chatId] = chatType }) {
            return@withContext SendOutcome.Failed(
                clientMessageId, IllegalStateException("account changed; refusing to send")
            )
        }
        val store = outboxStoreFor(owner)
            ?: return@withContext SendOutcome.Failed(clientMessageId, IllegalStateException("outbox unavailable"))
        val sealed = sealOutgoingText(token, chatId, chatType, plaintext, forward).getOrElse {
            return@withContext SendOutcome.Failed(clientMessageId, it)
        }
        val request = SendMessageRequest(
            chatId = chatId,
            chatType = chatType,
            content = sealed.content,
            encrypted = true,
            keyVersion = sealed.keyVersion,
            encryptionVersion = sealed.encryptionVersion,
            replyToId = replyToId,
            isForwarded = forward.isForwarded,
            forwardedFromName = "",
            forwardedFromMessageId = forward.fromMessageId,
            clientMessageId = clientMessageId
        )
        val now = System.currentTimeMillis()
        pendingDisplay[clientMessageId] = PendingDisplay(owner, plaintext, forward.fromName)
        try {
            outbox.enqueue(
                store,
                OutboxEntity(
                    clientMessageId = clientMessageId,
                    chatId = chatId,
                    chatType = chatType,
                    requestJson = json.encodeToString(SendMessageRequest.serializer(), request),
                    state = OutboxState.PENDING,
                    createdAt = now,
                    attempts = 0,
                    nextAttemptAt = now,
                    lastError = null,
                    serverMessageId = null,
                    acceptedAt = null
                )
            )
        } catch (e: Exception) {
            pendingDisplay.remove(clientMessageId)
            return@withContext SendOutcome.Failed(clientMessageId, e)
        }
        val acceptedNow = drainOutboxFor(owner, store)
        outcomeOf(clientMessageId, acceptedNow)
    }

    /**
     * Compatibility wrapper over [sendText] for callers that want a Result. A message that is
     * queued rather than accepted is reported as [MessageQueuedException] - it is NOT lost.
     */
    suspend fun sendMessage(
        token: String,
        chatId: String,
        chatType: String,
        plaintext: String,
        forward: ForwardMeta = ForwardMeta(),
        replyToId: String = ""
    ): Result<SendMessageResponseData> =
        when (val outcome = sendText(token, chatId, chatType, plaintext, forward, replyToId)) {
            is SendOutcome.Accepted -> Result.success(
                outcome.data ?: SendMessageResponseData(
                    id = outcome.serverId, chatId = chatId, senderId = operationOwner(),
                    createdAt = outcome.createdAt.orEmpty()
                )
            )
            is SendOutcome.Queued -> Result.failure(MessageQueuedException(outcome.clientMessageId, outcome.reason))
            is SendOutcome.Failed -> Result.failure(outcome.error)
        }

    // ------------------------------------------------------------ Phase 71 outbox

    private val outbox = MessageOutbox()

    /** Accepted / Failed transitions of queued messages, for whoever is showing them. */
    val outboxEvents: SharedFlow<MessageOutbox.Event> get() = outbox.events

    private val outboxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboxWakeLock = Any()
    private var outboxWake: Job? = null

    /**
     * The text of each queued message, for its bubble and for the local plaintext row written on
     * acceptance. MEMORY ONLY, by design: the durable outbox never holds plaintext. It outlives the
     * ViewModel (this repository is a singleton) but not the process - a message queued before a
     * restart is still delivered from the stored ciphertext, it just has no text on this device.
     */
    private data class PendingDisplay(val owner: String, val text: String, val forwardedFromName: String)
    private val pendingDisplay = java.util.concurrent.ConcurrentHashMap<String, PendingDisplay>()

    /** A queued (PENDING) or refused (FAILED) outgoing message, for rehydrating a chat's bubbles. */
    data class PendingOutgoing(
        val clientMessageId: String,
        val chatId: String,
        val failed: Boolean,
        val createdAt: Long,
        /** Null when this process never held the text (queued before a restart). */
        val text: String?,
        val error: String?,
        val replyToId: String
    )

    private fun outboxStoreFor(owner: String): OutboxStore? {
        val dao = outboxDao ?: return null
        if (owner.isBlank()) return null
        return object : OutboxStore {
            override suspend fun insert(item: OutboxEntity) = dao.insert(owner, item)
            override suspend fun get(clientMessageId: String) = dao.get(clientMessageId)
            override suspend fun due(now: Long) = dao.due(now)
            override suspend fun markAccepted(clientMessageId: String, serverId: String, at: Long) =
                dao.markAccepted(owner, clientMessageId, serverId, at)
            override suspend fun markRetry(clientMessageId: String, attempts: Int, nextAttemptAt: Long, error: String) =
                dao.markRetry(owner, clientMessageId, attempts, nextAttemptAt, error)
            override suspend fun markFailed(clientMessageId: String, attempts: Int, error: String) =
                dao.markFailed(owner, clientMessageId, attempts, error)
            override suspend fun pruneAccepted(olderThan: Long) = dao.pruneAccepted(owner, olderThan)
        }
    }

    /** POSTs a stored body byte for byte. No WebSocket involvement of any kind. */
    private val outboxTransport: OutboxTransport =
        HttpOutboxTransport(chatApiService) { tokenManager.getAccessToken().getOrNull() }

    private suspend fun drainOutboxFor(owner: String, store: OutboxStore): Map<String, SendMessageResponseData> {
        val acceptedNow = java.util.concurrent.ConcurrentHashMap<String, SendMessageResponseData>()
        runCatching {
            outbox.drain(store, outboxTransport) { item, data ->
                acceptedNow[item.clientMessageId] = data
                acceptOutgoing(owner, item, data)
            }
        }.onFailure { Log.w(TAG, "outbox pass failed: ${it.javaClass.simpleName}") }
        scheduleOutboxWake()
        return acceptedNow
    }

    private suspend fun outcomeOf(
        clientMessageId: String,
        acceptedNow: Map<String, SendMessageResponseData>
    ): SendOutcome {
        acceptedNow[clientMessageId]?.let {
            return SendOutcome.Accepted(clientMessageId, it.id, it.createdAt, it)
        }
        val row = runCatching { outboxDao?.get(clientMessageId) }.getOrNull()
        return when (row?.state) {
            OutboxState.ACCEPTED -> SendOutcome.Accepted(clientMessageId, row.serverMessageId.orEmpty(), null, null)
            OutboxState.FAILED -> SendOutcome.Failed(clientMessageId, Exception(row.lastError ?: "Failed to send message"))
            else -> SendOutcome.Queued(clientMessageId, row?.lastError ?: "waiting for the server")
        }
    }

    /** One outbox pass for whoever is signed in. Safe to call any number of times. */
    suspend fun drainOutbox() {
        val owner = operationOwner().takeIf { it.isNotBlank() } ?: return
        val store = outboxStoreFor(owner) ?: return
        drainOutboxFor(owner, store)
    }

    /** Non-blocking trigger: app start, connectivity back, socket connected, chat opened. */
    fun kickOutbox() {
        outboxScope.launch { drainOutbox() }
    }

    /** Re-runs the outbox when the earliest backed-off item becomes due, while any are PENDING. */
    private fun scheduleOutboxWake() {
        outboxScope.launch {
            val next = runCatching { outboxDao?.earliestPendingAt() }.getOrNull() ?: return@launch
            val wait = (next - System.currentTimeMillis()).coerceIn(250L, 60_000L)
            synchronized(outboxWakeLock) {
                outboxWake?.cancel()
                outboxWake = outboxScope.launch {
                    delay(wait)
                    drainOutbox()
                }
            }
        }
    }

    /** Queued and refused messages of [chatId], oldest first. */
    suspend fun pendingOutbox(chatId: String): List<PendingOutgoing> = withContext(Dispatchers.IO) {
        val owner = operationOwner()
        val rows = runCatching { outboxDao?.unacceptedForChat(chatId) }.getOrNull().orEmpty()
        rows.map { row ->
            val display = pendingDisplay[row.clientMessageId]?.takeIf { it.owner == owner }
            val replyTo = runCatching {
                json.decodeFromString(SendMessageRequest.serializer(), row.requestJson).replyToId
            }.getOrDefault("")
            PendingOutgoing(
                clientMessageId = row.clientMessageId,
                chatId = row.chatId,
                failed = row.state == OutboxState.FAILED,
                createdAt = row.createdAt,
                text = display?.text,
                error = row.lastError,
                replyToId = replyTo
            )
        }
    }

    /**
     * Explicit retry of a FAILED message: the same row, client_message_id and ciphertext - so if the
     * server had in fact stored it, the retry resolves to that message instead of duplicating it.
     */
    suspend fun retryOutbox(clientMessageId: String): Boolean = withContext(Dispatchers.IO) {
        val owner = operationOwner().takeIf { it.isNotBlank() } ?: return@withContext false
        val reset = runCatching {
            outboxDao?.resetForRetry(owner, clientMessageId, System.currentTimeMillis()) ?: 0
        }.getOrDefault(0)
        if (reset > 0) kickOutbox()
        reset > 0
    }

    /**
     * The local half of acceptance, unchanged from the old direct send: conversation row, then our
     * OWN plaintext row (with its Layer B archive), because an MLS or ratchet sender holds no key for
     * its own ciphertext. Only possible while this process still holds the text; a message queued
     * before a restart is delivered all the same, and the server copy is its record.
     */
    private suspend fun acceptOutgoing(owner: String, item: OutboxEntity, sent: SendMessageResponseData) {
        val display = pendingDisplay.remove(item.clientMessageId)
        if (conversationDao.getConversationById(sent.chatId) == null) {
            conversationDao.insertConversation(owner, ConversationEntity(id = sent.chatId, type = item.chatType))
        }
        if (display == null || display.owner != owner) return
        val request = runCatching {
            json.decodeFromString(SendMessageRequest.serializer(), item.requestJson)
        }.getOrNull() ?: return
        val plaintext = display.text
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
            // The send operation's owner, captured before the request.
            userId = owner.takeIf { it.isNotBlank() },
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
            owner,
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
                keyVersion = request.keyVersion,
                // The version we actually sent, not a hardcoded 1. A v5
                // row mislabelled as v1 invites any later pass to
                // "re-decrypt" it with the wrong scheme, and an MLS
                // sender can never recover its own plaintext once this
                // cached copy is lost - it holds no key for its own
                // message.
                encryptionVersion = request.encryptionVersion,
                senderDeviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty(),
                replyTo = request.replyToId.ifBlank { null },
                isForwarded = request.isForwarded,
                forwardedFromName = display.forwardedFromName,
                forwardedFromMessageId = request.forwardedFromMessageId,
                archiveCiphertext = archiveFields.ciphertext,
                archiveRootVersion = archiveFields.rootVersion,
                archiveState = archiveFields.state
            )
        )
    }

    // ------------------------------------------------------------ Phase 71 catch-up

    private val syncMutex = Mutex()

    /**
     * Reconnect catch-up for every chat in [chatIds]: pages `?after=` from each chat's stored sync
     * point until the server has nothing more (see [ChatSyncPager]), ingesting through the same
     * decrypt-on-store path as history. Covers chats that are not open. Bounded per chat per call.
     */
    suspend fun syncChats(token: String, chatIds: Collection<String>): List<ChatSyncPager.Outcome> =
        withContext(Dispatchers.IO) {
            val owner = operationOwner().takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            val states = syncStateDao ?: return@withContext emptyList()
            syncMutex.withLock {
                val pager = ChatSyncPager(
                    fetch = { chatId, before, after, limit -> fetchMessagePage(token, chatId, before, after, limit) },
                    ingest = { chatId, rows -> cacheMessages(owner, chatId, rows) },
                    isKnownLocally = { id -> messageDao.getMessageById(id) != null },
                    hasLocalHistory = { chatId -> messageDao.getLatestMessage(chatId) != null },
                    loadSyncPoint = { chatId ->
                        states.get(chatId)?.let { ChatSyncPager.SyncPoint(it.lastSeq, it.backfillBefore) }
                    },
                    saveSyncPoint = { chatId, point ->
                        states.upsert(
                            owner,
                            ChatSyncStateEntity(chatId, point.lastSeq, point.backfillBefore, System.currentTimeMillis())
                        )
                    }
                )
                chatIds.distinct().mapNotNull { chatId ->
                    runCatching { pager.sync(chatId) }
                        .onFailure { e ->
                            Log.w(TAG, "catch-up failed for one chat: ${e.javaClass.simpleName}")
                            if (e is SessionExpiredException) throw e
                        }
                        .getOrNull()
                }
            }
        }

    private suspend fun fetchMessagePage(
        token: String,
        chatId: String,
        before: Long?,
        after: Long?,
        limit: Int
    ): MessagesResponse {
        val response = chatApiService.getMessages(bearer(token), chatId, limit, before?.toString(), after?.toString())
        val body = response.body()
        if (response.isSuccessful && body != null) return body
        if (response.code() == 401) throw SessionExpiredException()
        throw Exception("Failed to get messages: ${response.code()}")
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
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()
            val response = chatApiService.getMessages(bearer(token), chatId, limit, before)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                cacheMessages(owner, chatId, body.data)
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

    /**
     * Layer B coverage for one message, straight from the stored row.
     *
     * This is what lets the app answer "eligible but not archived yet" without reading logs. A
     * message this reports as [MessageArchiveStatus.PENDING] is exactly one the next refresh will
     * try to seal, because both use [isArchivable].
     */
    suspend fun archiveStatusFor(messageId: String): MessageArchiveStatus = withContext(Dispatchers.IO) {
        val row = runCatching { messageDao.getMessageById(messageId) }.getOrNull()
            ?: return@withContext MessageArchiveStatus.NOT_APPLICABLE
        messageArchiveStatus(
            isEncrypted = row.isEncrypted,
            content = row.content,
            fileType = row.fileType,
            archiveState = row.archiveState,
            archiveCiphertext = row.archiveCiphertext,
        )
    }

    /**
     * How many messages in a chat are readable and eligible but still unarchived.
     *
     * A number that stays above zero across refreshes is the visible symptom of archiving being
     * stalled - a locked vault being the case that matters - which previously showed up only as a
     * log line. Counting rather than reporting an error keeps this honest: it says what is true of
     * the stored data, not why the last attempt failed.
     */
    suspend fun pendingArchiveCount(chatId: String): Int = withContext(Dispatchers.IO) {
        runCatching { messageDao.getAllMessagesByConversation(chatId) }.getOrDefault(emptyList())
            .count {
                messageArchiveStatus(
                    isEncrypted = it.isEncrypted,
                    content = it.content,
                    fileType = it.fileType,
                    archiveState = it.archiveState,
                    archiveCiphertext = it.archiveCiphertext,
                ) == MessageArchiveStatus.PENDING
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
            operationOwner(),
            chatId, payload, dto.senderId, dto.keyVersion, ver, dto.senderDeviceId, dto.type.orEmpty()
        ) ?: return null
        val env = E2ECrypto.unwrapEnvelope(plain)
        val text = runCatching { String(env.payload, Charsets.UTF_8) }.getOrNull() ?: return null
        return OpenedMessage(text, env.fileName, env.forwardedFrom, env.durationMs, env.fileSize, env.thumbnailUrl, env.fileUrl)
    }

    /**
     * The account that owns an operation about to begin.
     *
     * Called at the START of an operation, before any network or WebSocket work,
     * and threaded down to that operation's cache writes. Never call this from
     * inside a callback: by then it reports whoever is signed in at commit time,
     * which is exactly the attribution bug it exists to prevent.
     */
    private suspend fun operationOwner(): String = currentUserIdOrEmpty()

    /**
     * Drops every scrap of account-sensitive process state.
     *
     * Called on logout and whenever the signed-in account is observed to have
     * changed. Everything discarded here has a durable source and is rebuilt on
     * demand for whoever is signed in next:
     *
     *  - ratchet sessions      <- tokenManager.loadDirectRatchet
     *  - own Sender Keys       <- tokenManager.loadGroupSenderKeys
     *  - peers' Sender Keys    <- tokenManager.loadPeerSenderKeys
     *  - peer public keys      <- tokenManager.saveKnownPublicKey store + chat list
     *  - chat type/epoch/devices <- the server chat list (learnChatMeta)
     *
     * peerKeysLoaded MUST be cleared alongside groupOtherKeys: it is the guard
     * that decides whether peer keys are reloaded at all, so clearing the keys
     * without it would leave the next account unable to decrypt group traffic.
     */
    fun clearAccountScopedState() {
        mem.clear()
        stateOwner = null
    }

    /**
     * Gate for every account-sensitive read or mutation of the maps above.
     *
     * [owner] is the account that owned the operation when it STARTED. Two
     * things happen here, and both are needed:
     *
     *  1. Self-healing. If the signed-in account is not the one the in-memory
     *     state belongs to, that state is discarded before anything reads it.
     *     This does not depend on logout having told us anything, which matters
     *     because WebSocket coroutines outlive logout.
     *
     *  2. Ownership. If the operation's owner is no longer the signed-in
     *     account, it is refused. A late callback from account A must not
     *     populate B's freshly-cleared memory with A's data - dropping it is
     *     the only safe outcome, exactly as for database writes.
     */
    private suspend fun claimMemoryFor(owner: String): Boolean {
        val active = healIfAccountChanged()
        return active != null && owner.isNotBlank() && owner == active
    }

    /**
     * Discards in-memory state that belongs to a different account, and reports
     * who is signed in now (null when nobody is).
     *
     * Every account-sensitive READ goes through this. Guarding only the writes
     * was not enough: a plain getter such as [chatTypeFor] would still hand the
     * next account whatever the previous one had left behind, which is exactly
     * what SingletonStateLeakTest demonstrated.
     */
    private suspend fun healIfAccountChanged(): String? {
        val active = currentUserIdOrEmpty().takeIf { it.isNotBlank() }
        if (active == null) {
            if (stateOwner != null) clearAccountScopedState()
            return null
        }
        if (stateOwner != active) {
            clearAccountScopedState()
            stateOwner = active
        }
        return active
    }

    private suspend fun cacheMessages(owner: String, chatId: String, dtos: List<MessageDto>) {
        if (dtos.isEmpty()) return
        // Messages carry a FK to conversations; upsert a placeholder row only if
        // one doesn't exist yet so we never REPLACE (and thus cascade-delete) an
        // existing conversation's already-cached messages.
        if (conversationDao.getConversationById(chatId) == null) {
            // Prefer the rows' own server-side chat_type. Using chatTypeMap alone
            // durably recorded a group as "direct" whenever the map was still
            // cold - the cache then carried that wrong type indefinitely.
            val authoritativeType = dtos.firstOrNull { !it.type.isNullOrBlank() }?.type
                ?: owned(owner) { m -> m.chatTypeMap[chatId] }
                ?: "direct"
            conversationDao.insertConversation(owner, ConversationEntity(id = chatId, type = authoritativeType))
        }
        // The archive AAD binds the owning user. That is the operation's OWNER,
        // captured before the fetch - not a fresh read of current_user_id, which
        // would seal under whoever is signed in by the time this line runs.
        val archiveUserId = owner.takeIf { it.isNotBlank() }
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
            val isMedia = isMediaContentType(dto.fileType)
            if (encrypted && !isMedia && content.isNotBlank()) {
                // Cache first, MLS second. A row we already hold in the clear must never
                // be handed back to OpenMLS: its ratchet secret was consumed by the
                // decrypt that produced that plaintext, so asking again only earns
                // SecretReuseError. Only a row we cannot already read is worth the
                // attempt, and a genuine failure there still leaves the row encrypted.
                val existing = messageDao.getMessageById(dto.id)
                if (existing != null && hasReadableCachedText(existing.isEncrypted, existing.content)) {
                    content = existing.content
                    fwdName = fwdName.ifBlank { existing.forwardedFromName }
                    encrypted = false
                } else {
                    val opened = openTextOrNull(chatId, dto)
                    if (opened != null) {
                        content = opened.text
                        fwdName = fwdName.ifBlank { opened.forwardedFrom }
                        encrypted = false
                    } else {
                        // Layer B, and only now. The archive is a fallback for plaintext
                        // this device no longer holds - never a blanket handler for MLS
                        // errors. It is consulted only when THIS row carries a sealed
                        // archive, so a group-not-loaded, wrong-epoch or missing-handshake
                        // failure on a message that was never archived still fails exactly
                        // as it did before, and stays retryable.
                        val restored = restoreFromArchive(archiveUserId, chatId, dto.id, existing)
                        if (restored != null) {
                            content = restored
                            encrypted = false
                        }
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
            val archivable = isArchivable(encrypted, isMedia, content)
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
        messageDao.insertMessages(owner, entities.filterNot { it.isEncrypted && keepPlain.contains(it.id) })

        // Layer B: pull down archives this device does not have. Runs after the
        // rows exist so a downloaded archive always lands on a real message.
        val newlyArchived = applyRemoteArchives(owner, chatId)

        // Layer B, second half: an archive that only arrived just now could not have been opened
        // during the restore attempt above, because it did not exist locally yet. Without this a
        // freshly recovered device needs a SECOND refresh before its history becomes readable.
        restoreNewlyArchived(owner, chatId, newlyArchived)

        // Archive coverage after this pass, as one number and nothing else.
        //
        // Read here rather than inside applyRemoteArchives because that returns early
        // when there is nothing to download - which is the ordinary case - and because
        // the count is only true once downloaded archives have been applied.
        //
        // A count that stays above zero across refreshes is the visible symptom of
        // archiving being stalled. A locked vault is the case that matters: the first
        // archive in a chat needs the vault to mint a root, and until Phase 28 that
        // showed up only as a per-message warning at the instant it failed, with no way
        // to tell afterwards that anything was still outstanding.
        //
        // Deliberately an aggregate: no message id, no chat id, no content. The number
        // is the whole signal, and anything more would put identifiers in the log for no
        // added diagnostic value.
        val pendingArchives = pendingArchiveCount(chatId)
        if (pendingArchives > 0) {
            Log.i(TAG, "archive coverage: $pendingArchives message(s) eligible but not yet archived")
        }
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
    /**
     * Reopens a row from its own sealed archive, or null when that is not possible.
     *
     * Deliberately narrow. It requires a row that is actually SEALED and actually holds
     * ciphertext, so:
     *
     *  - a message that was never archived is untouched, and its MLS failure still stands;
     *  - a RETRACTED tombstone is never reopened, which is what stops delete-for-everyone
     *    from being undone by a refresh;
     *  - a failure to open (tampered record, or a root version this device no longer
     *    holds) returns null and leaves the row encrypted rather than guessing.
     *
     * No MLS state is consulted: the archive key comes from the history keyring, and the
     * ciphertext from the row. That is the whole point - it works precisely when the
     * ratchet secret is gone.
     */
    private suspend fun restoreFromArchive(
        userId: String?,
        chatId: String,
        messageId: String,
        existing: MessageEntity?,
    ): String? {
        val open = onArchiveOpen ?: return null
        val uid = userId?.takeIf { it.isNotBlank() } ?: return null
        val row = existing ?: return null
        if (!archiveIsRestorable(row.archiveState, row.archiveCiphertext)) return null
        val ciphertext = row.archiveCiphertext.orEmpty()
        val result = runCatching { open(uid, chatId, messageId, row.archiveRootVersion, ciphertext) }
            .getOrElse { Result.failure(it) }
        val text = result.getOrElse {
            // Message only; never the ciphertext, the root, or any partial plaintext.
            Log.w(TAG, "archive restore failed for a message: ${it.message}")
            return null
        }
        if (text.isBlank()) return null
        Log.i(TAG, "restored a message from its Layer B archive")
        return text
    }

    /**
     * Downloads this chat's archives and pins them onto rows that have none.
     *
     * Returns the message ids it actually pinned in THIS pass - the rows whose archive did not
     * exist locally a moment ago. [restoreNewlyArchived] uses exactly that list, so the follow-up
     * restore touches only rows that could not possibly have been restored earlier in the same
     * fetch.
     */
    private suspend fun applyRemoteArchives(owner: String, chatId: String): List<String> {
        val pinned = mutableListOf<String>()
        val page = runCatching { onArchiveFetch?.invoke(chatId) }.getOrNull() ?: return pinned
        if (!page.complete) {
            // Explicit continuation signal from the sync layer. Whatever arrived is
            // still applied; what must not happen is treating a truncated pass as a
            // finished one, which is how "sync completed" quietly becomes a lie.
            Log.w(TAG, "archive sync for this chat was incomplete; will continue on a later refresh")
        }
        val remote = page.records
        if (remote.isEmpty()) return pinned
        for (r in remote) {
            if (r.chatId != chatId) continue
            val existing = runCatching { messageDao.getMessageById(r.messageId) }.getOrNull()
                ?: continue
            if (existing.conversation_id != chatId) continue
            if (ArchiveState.fromWire(existing.archiveState) != ArchiveState.NONE) continue
            runCatching {
                messageDao.setArchive(
                    owner,
                    r.messageId, r.ciphertextB64, r.rootVersion, ArchiveState.SEALED.wire
                )
            }.onSuccess { pinned += r.messageId }
                .onFailure { Log.w(TAG, "could not apply a downloaded archive: ${it.message}") }
        }
        return pinned
    }

    /**
     * Opens archives that only became available during THIS fetch.
     *
     * WHY THIS EXISTS. Within one cacheMessages pass the order is fixed: the restore attempt runs
     * while building the rows, and the download that supplies the archive runs afterwards - because
     * applyRemoteArchives can only pin an archive onto a row that already exists. A device that has
     * just recovered its keyring therefore met the archive one step too late: the first fetch left
     * the message encrypted and only a later refresh could open it. That is a latency defect in
     * exactly the new-device recovery case Layer B exists for.
     *
     * The fix is deliberately a second pass rather than a reordering. Moving the download earlier
     * would run it before the rows are inserted, and applyRemoteArchives skips archives whose
     * message row is absent - so a brand-new message, which is precisely the case that matters,
     * would have its archive dropped entirely instead of merely delayed.
     *
     * Scope is the ids applyRemoteArchives actually pinned, so this never rescans the chat, never
     * touches a row that was already readable, and does no work at all when nothing was downloaded.
     *
     * NO MLS HERE. This performs no decrypt attempt of any kind: the ratchet secret is already
     * spent, which is the whole reason the archive is being consulted. Rows that are no longer
     * encrypted are skipped, so a plaintext row can never be rewritten.
     */
    private suspend fun restoreNewlyArchived(owner: String, chatId: String, pinned: List<String>) {
        if (pinned.isEmpty()) return
        val uid = owner.takeIf { it.isNotBlank() } ?: return
        for (messageId in pinned) {
            val row = runCatching { messageDao.getMessageById(messageId) }.getOrNull() ?: continue
            if (row.conversation_id != chatId) continue
            // Already readable - by cache, by MLS, or by the earlier restore attempt.
            if (hasReadableCachedText(row.isEncrypted, row.content)) continue
            val text = restoreFromArchive(uid, chatId, messageId, row) ?: continue
            // Writes only content + isEncrypted, exactly as the MLS path does, so delivery and read
            // state, timestamps, reply/forward metadata and the archive columns all survive.
            runCatching { messageDao.setPlaintext(owner, messageId, text) }
                .onFailure { Log.w(TAG, "could not persist a restored message: ${it.message}") }
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
        val peer = tokenManager.getKnownPublicKey(userId, chatId).getOrNull() ?: return null
        return E2ECrypto.safetyNumber(mine, peer)
    }

    suspend fun isSafetyVerified(chatId: String): Boolean =
        tokenManager.isSafetyVerified(operationOwner(), chatId).getOrDefault(false)

    suspend fun verifySafetyNumberScan(chatId: String, scanned: String): Boolean {
        val local = safetyNumberForChat(chatId) ?: return false
        val a = E2ECrypto.parseSafetyNumberQr(scanned) ?: return false
        val b = E2ECrypto.parseSafetyNumberQr(E2ECrypto.safetyNumberQrPayload(local) ?: return false)
            ?: return false
        if (!a.equals(b, ignoreCase = true)) return false
        val owner = operationOwner()
        val peer = tokenManager.getKnownPublicKey(owner, chatId).getOrNull() ?: return false
        tokenManager.markSafetyVerified(owner, chatId, peer)
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
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()
            val response = chatApiService.deleteMessage(bearer(token), chatId, messageId, forEveryone)
            if (!response.isSuccessful) {
                return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to delete message: ${response.code()}"))
            }
            // Retract this device's archive before the row goes, for BOTH kinds of delete. A
            // delete-for-me still means the user wants the message gone from this device, and an
            // archive that outlived it would quietly make it recoverable again.
            retractArchive(owner, messageId)
            messageDao.deleteMessage(owner, messageId)
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
     * Group lifecycle events from the server (group_created, member_added, ...).
     *
     * Broadcast to every client, so a listener must re-read the chat list and
     * let the server say what this account is a member of, rather than adding
     * anything locally from the event.
     */
    val groupLifecycle = webSocketManager.groupLifecycle

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
        val owner = operationOwner()
        retractArchive(owner, messageId)
        runCatching { messageDao.deleteMessage(owner, messageId) }
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
    suspend fun retractArchive(owner: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (messageId.isBlank()) return@runCatching
            val current = messageDao.getArchiveState(messageId)
            val plan = ArchiveRetractionPolicy.plan(current)
            if (!plan.write) return@runCatching
            messageDao.clearArchive(owner, messageId, plan.newState.wire)
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
        /**
         * The account that owned the WebSocket delivery when it was ACCEPTED.
         * Captured by the collector, not re-read here: this handler runs after
         * decryption and a network seal, so by commit time the signed-in account
         * may already be someone else.
         */
        owner: String,
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
            val userId = owner.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(
                    IllegalStateException("no current user; cannot bind an archive")
                )

            val existing = messageDao.getMessageById(messageId)
            if (ArchiveState.fromWire(existing?.archiveState) != ArchiveState.NONE) {
                // Already sealed, or retracted. Never re-seal: that would resurrect a message the
                // user deleted for everyone.
                return@withContext Result.success(Unit)
            }

            // The readable-cache invariant, established BEFORE sealing and
            // independently of it. An MLS sender-ratchet secret is consumed by the
            // first successful decrypt, so this plaintext is the only copy that will
            // ever exist: a row left as ciphertext can never be reopened. Sealing is a
            // separately configured feature, and gating persistence on it meant that
            // whenever it was unavailable this returned without storing anything and
            // the message became permanently unreadable.
            if (existing == null) {
                if (conversationDao.getConversationById(chatId) == null) {
                    conversationDao.insertConversation(
                        owner, ConversationEntity(id = chatId, type = chatType)
                    )
                }
                messageDao.insertMessage(
                    owner,
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
                        senderDeviceId = senderDeviceId
                    )
                )
            } else if (!hasReadableCachedText(existing.isEncrypted, existing.content)) {
                // Writes only content+isEncrypted, so every other column - delivery and
                // read state, timestamps, reply/forward metadata - survives untouched.
                messageDao.setPlaintext(owner, messageId, plaintext)
            }

            val sealed = onArchiveMessage?.invoke(userId, chatId, messageId, plaintext)
                ?: return@withContext Result.failure(
                    IllegalStateException("message could not be archived")
                )

            // Touch only the archive columns; never rewrite message fields as a side effect.
            messageDao.setArchive(
                owner, messageId, sealed.ciphertextB64, sealed.rootVersion, ArchiveState.SEALED.wire
            )
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
        val owner = operationOwner()
        // The row may not exist yet if this chat has never been opened.
        if (conversationDao.getConversationById(chatId) == null) {
            conversationDao.insertConversation(owner, ConversationEntity(id = chatId, type = "direct"))
        }
        conversationDao.toggleMute(owner, chatId, muted)
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
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()
            val response = chatApiService.deleteChat(bearer(token), chatId)
            if (!response.isSuccessful) {
                return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                else Result.failure(Exception("Failed to delete chat: ${response.code()}"))
            }

            messageDao.deleteMessagesByConversation(owner, chatId)
            conversationDao.deleteConversation(owner, chatId)
            cachedChatDao.deleteCached(owner, chatId)

            ownedApply(owner) { m ->
                m.chatOtherPub.remove(chatId)
                m.chatTypeMap.remove(chatId)
                m.groupKeyEpoch.remove(chatId)
                m.mySenderKeys.remove(chatId)
                m.pendingSecurityNotices.remove(chatId)
            }

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
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()
                val response = chatApiService.blockUser(bearer(token), userId)
                if (!response.isSuccessful) {
                    return@withContext if (response.code() == 401) Result.failure(SessionExpiredException())
                    else Result.failure(Exception("Failed to block user: ${response.code()}"))
                }
                if (!chatId.isNullOrBlank()) {
                    messageDao.deleteMessagesByConversation(owner, chatId)
                    conversationDao.deleteConversation(owner, chatId)
                    cachedChatDao.deleteCached(owner, chatId)
                    ownedApply(owner) { m ->
                        m.chatOtherPub.remove(chatId)
                        m.chatTypeMap.remove(chatId)
                    }
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
            // Owner captured at operation START, before the network call. A reply
            // that lands after an account switch is refused, never re-attributed.
            val owner = operationOwner()
            val response = chatApiService.markAsRead(bearer(token), chatId)
            if (response.isSuccessful) {
                conversationDao.clearUnreadCount(owner, chatId)
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
