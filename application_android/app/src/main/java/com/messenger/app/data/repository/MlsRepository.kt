package com.messenger.app.data.repository

import android.util.Base64
import android.util.Log
import com.messenger.app.data.encryption.MlsGroupCrypto
import com.messenger.app.data.encryption.MlsPolicy
import com.messenger.app.data.model.MlsClaimKeyPackageRequest
import com.messenger.app.data.model.MlsCommitRequest
import com.messenger.app.data.model.MlsCoverageDto
import com.messenger.app.data.model.MlsCreateGroupRequest
import com.messenger.app.data.model.MlsWelcomeAckRequest
import com.messenger.app.data.model.MlsKeyPackageItem
import com.messenger.app.data.model.MlsPublishKeyPackagesRequest
import com.messenger.app.data.model.MlsPutGroupInfoRequest
import com.messenger.app.data.model.MlsWelcomeItem
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.mls.protocol.Group
import java.security.MessageDigest

/**
 * Drives the MLS (RFC 9420) Delivery Service for group chats.
 *
 * Division of trust: all cryptography happens here on the device via
 * [MlsGroupCrypto] (BouncyCastle). The server is an untrusted relay — it stores
 * KeyPackages, hands them out one at a time, orders commits by epoch, and
 * forwards Welcome/commit blobs. It never sees a group secret.
 *
 * Epoch discipline: every commit is submitted with the epoch this client last
 * observed. If another member committed first the server answers 409, and this
 * client must re-apply the missed handshakes before retrying — that is what
 * stops the group forking into two incompatible states.
 */
class MlsRepository(
    private val api: ChatApiService,
    private val tokenManager: TokenManager
) {
    companion object {
        private const val TAG = "MlsRepository"

        /** How many spare KeyPackages to keep published for other members. */
        private const val KEY_PACKAGE_TARGET = 10

        /** Republish when the server reports fewer than this many left. */
        private const val KEY_PACKAGE_LOW_WATER = 3
    }

    private fun bearer(token: String) = "Bearer $token"
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Live MLS state per chat. Serialized into the vault by [groupStateJson]. */
    private val groups = mutableMapOf<String, Group>()

    /** Our identity per chat (leaf + signature keys). */
    private val identities = mutableMapOf<String, MlsGroupCrypto.Identity>()

    /**
     * KeyPackages we published, keyed by their ref hash, so a Welcome naming one
     * can be opened with the matching init private key.
     */
    private val publishedKeyPackages = mutableMapOf<String, MlsGroupCrypto.PublishedKeyPackage>()

    private val mutex = Mutex()

    /**
     * chatIds we have already attempted an external rejoin for this process.
     *
     * externalRejoin() submits a commit, and every commit advances the epoch and
     * issues a fresh Welcome. Running it on each chat open drove the group to
     * epoch 9 with five unconsumed Welcomes and still no members. At most one
     * attempt per chat per process makes opening a chat idempotent; a genuine
     * retry can happen on the next launch.
     */
    private val rejoinAttempted = mutableSetOf<String>()

    /**
     * Leaf credential format, shared byte-for-byte with the Windows client.
     * Several devices of one account are separate leaves, so the credential
     * must name the device or the ratchet tree cannot tell them apart — which
     * is what forced membership checks onto an unreliable local "invited" set.
     */
    private fun mlsCredential(userId: String, deviceId: String): String =
        if (deviceId.isBlank()) userId else "$userId|$deviceId"

    private fun refHash(kpWire: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(kpWire)
            .joinToString("") { "%02x".format(it) }

    // ---- KeyPackages ----

    /**
     * Publishes a fresh batch of KeyPackages so other members can add this
     * device to groups. Each is single-use server-side, so they need topping up.
     */
    suspend fun publishKeyPackages(count: Int = KEY_PACKAGE_TARGET): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = tokenManager.getAccessToken().getOrNull()
                    ?: return@runCatching 0
                val userId = tokenManager.getCurrentUserId().getOrNull().orEmpty()
                val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()

                val items = mutableListOf<MlsKeyPackageItem>()
                mutex.withLock {
                    repeat(count) {
                        val identity = MlsGroupCrypto.newIdentity(mlsCredential(userId, deviceId))
                        val published = MlsGroupCrypto.newKeyPackage(identity)
                        val wire = MlsGroupCrypto.encodeKeyPackage(published.keyPackage)
                        val hash = refHash(wire)
                        // Retain the init key: it is what opens a Welcome sent
                        // to this KeyPackage. Losing it means we cannot join.
                        publishedKeyPackages[hash] = published
                        identities[hash] = identity
                        // Persist immediately: if the app dies before the
                        // Welcome arrives, the init key must survive or the
                        // invite can never be opened.
                        persistKeyPackage(hash, published, identity)
                        items.add(
                            MlsKeyPackageItem(
                                deviceId = deviceId,
                                cipherSuite = MlsGroupCrypto.SUITE_ID.toInt(),
                                keyPackageB64 = b64(wire),
                                refHash = hash,
                                // Deliberately unattributed. These are
                                // BouncyCastle packages whose init keys live in
                                // this repository's own bundle store, not in an
                                // OpenMLS store, so no store id can honestly
                                // describe them. Blank keeps them out of every
                                // scoped claim.
                                storeId = ""
                            )
                        )
                    }
                }
                val resp = api.publishMlsKeyPackages(
                    bearer(token), MlsPublishKeyPackagesRequest(items)
                )
                if (!resp.isSuccessful) {
                    Log.w(TAG, "publishKeyPackages failed: ${resp.code()}")
                    return@runCatching 0
                }
                resp.body()?.stored ?: 0
            }
        }

    /** Tops up published KeyPackages when the server is running low. */
    suspend fun ensureKeyPackagesAvailable(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@runCatching
            val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            val resp = api.countMlsKeyPackages(bearer(token), myDev)
            val available = resp.body()?.available ?: 0
            if (available < KEY_PACKAGE_LOW_WATER) {
                publishKeyPackages(KEY_PACKAGE_TARGET - available)
            }
        }
    }

    // ---- Group creation and membership ----

    /**
     * Creates the MLS group for [chatId] and registers it with the Delivery
     * Service. The caller becomes the only member; add others with [addMember].
     */
    suspend fun createGroup(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenManager.getAccessToken().getOrNull()
                ?: error("no access token")
            val userId = tokenManager.getCurrentUserId().getOrNull().orEmpty()
            val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            val groupId = chatId.toByteArray(Charsets.UTF_8)

            mutex.withLock {
                val identity = MlsGroupCrypto.newIdentity(mlsCredential(userId, myDev))
                val group = MlsGroupCrypto.createGroup(groupId, identity)
                identities[chatId] = identity
                groups[chatId] = group
                persistGroupBundle(chatId, identity, welcome = null)
            }

            val resp = api.createMlsGroup(
                bearer(token),
                MlsCreateGroupRequest(
                    chatId = chatId,
                    groupIdB64 = b64(groupId),
                    cipherSuite = MlsGroupCrypto.SUITE_ID.toInt()
                )
            )
            // 409: another device already registered the real group. Keeping
            // this local tree would make us encrypt to an audience of one —
            // the other device could never read those sends.
            if (resp.code() == 409) {
                Log.i(TAG, "createGroup 409 for $chatId — dropping local orphan")
                forgetGroup(chatId)
                error("mls group already exists")
            }
            if (!resp.isSuccessful) {
                forgetGroup(chatId)
                error("createMlsGroup failed: ${resp.code()}")
            }
            // Add this device a second time so we hold a Welcome. The creator
            // otherwise has nothing to rebuild from after a restart, and the
            // other devices see a zombie leaf and never re-invite us.
            persistSelfWelcome(chatId)
        }
    }

    /**
     * Adds one live device of [targetUserId] to the group for [chatId]: claims
     * a KeyPackage (optionally for [targetDeviceId]), commits the add, and
     * uploads the commit plus a Welcome addressed to that device.
     *
     * Multi-device accounts need one leaf per device. Claiming without a
     * device id only admits whichever package the server hands out first, so
     * the sender's other installs cannot unprotect their own history.
     */
    suspend fun addMember(
        chatId: String,
        targetUserId: String,
        targetDeviceId: String = ""
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = tokenManager.getAccessToken().getOrNull()
                    ?: error("no access token")
                val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()

                val claim = api.claimMlsKeyPackage(
                    bearer(token),
                    MlsClaimKeyPackageRequest(
                        userId = targetUserId,
                        deviceId = targetDeviceId
                    )
                )
                if (!claim.isSuccessful) error("no key package for $targetUserId")
                val claimed = claim.body() ?: error("empty claim response")
                val kpWire = unb64(claimed.keyPackageB64)

                val (commitBytes, welcomeBytes, observedEpoch) = mutex.withLock {
                    val group = groups[chatId] ?: error("no local MLS group for $chatId")
                    val before = group.epoch
                    val result = MlsGroupCrypto.addMember(
                        group, MlsGroupCrypto.decodeKeyPackage(kpWire)
                    )
                    groups[chatId] = result.group
                    Triple(result.commit, result.welcome, before)
                }

                val resp = api.submitMlsCommit(
                    bearer(token), chatId,
                    MlsCommitRequest(
                        expectedEpoch = observedEpoch,
                        commitB64 = b64(commitBytes),
                        senderDeviceId = deviceId,
                        welcomes = listOf(
                            MlsWelcomeItem(
                                userId = claimed.userId.ifBlank { targetUserId },
                                deviceId = claimed.deviceId,
                                welcomeB64 = b64(welcomeBytes)
                            )
                        )
                    )
                )
                if (resp.code() == 409) {
                    // Local state already applied the rejected add. Syncing on
                    // top of that fork skips the winning commit (its epoch is
                    // "behind" us). Rebuild from the persisted Welcome first.
                    Log.w(TAG, "commit rejected (stale epoch) for $chatId — rebuilding from Welcome")
                    if (!rebuildFromBundle(chatId)) {
                        mutex.withLock { groups.remove(chatId) }
                    }
                    syncHandshakes(chatId)
                    error("stale epoch; retry the add")
                }
                if (!resp.isSuccessful) error("submitMlsCommit failed: ${resp.code()}")

                // If we just added OURSELF (creator self-welcome), we hold the
                // KeyPackage private key and can persist the Welcome. That is
                // what lets this device rebuild after a restart.
                val hash = refHash(kpWire)
                mutex.withLock {
                    val published = publishedKeyPackages[hash]
                    val id = identities[hash]
                    if (published != null && id != null) {
                        persistGroupBundle(chatId, id, welcomeBytes, published)
                    }
                }

                // Publish GroupInfo for the new epoch so a member who loses
                // local state can rejoin by external commit. Best-effort: the
                // add itself already succeeded.
                publishGroupInfo(chatId)
                Unit
            }
        }

    /**
     * Makes sure [chatId] has a usable MLS group on this device, establishing
     * one if the chat has never had it.
     *
     * Existing group chats predate MLS, so nothing would ever create a group for
     * them and they would fall back to Sender Keys forever. This is what
     * actually switches a conversation over.
     *
     * Both members may call this at once. That is safe: the Delivery Service
     * accepts one registration and answers 409 to the loser, who then waits for
     * the winner's Welcome rather than creating a second, conflicting group.
     *
     * Returns true when this device ends up with usable MLS state.
     */
    suspend fun ensureGroupEstablished(chatId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = tokenManager.getAccessToken().getOrNull() ?: return@runCatching false

                // Ask the server FIRST, even when we hold local state. If the
                // group no longer exists there (reset, or wiped during
                // development) our local bundle describes a group nobody else
                // is in - keeping it would make this device talk to itself
                // forever. Drop it and re-establish from scratch.
                val existing = api.getMlsGroup(bearer(token), chatId)
                if (existing.code() == 404 && hasGroup(chatId)) {
                    Log.i(TAG, "MLS $chatId: server has no group; dropping stale local state")
                    forgetGroup(chatId)
                }

                // Existence is not enough: a group that was deleted and
                // recreated reuses the same chat_id, so a 200 can describe a
                // DIFFERENT tree than the one our bundle belongs to. Keeping
                // that state made every unprotect fail the AEAD check
                // ("mac check in GCM failed") with no other symptom.
                val serverInstance = existing.body()?.instanceId.orEmpty()
                if (existing.isSuccessful && serverInstance.isNotBlank()) {
                    val knownInstance = loadGroupInstanceId(chatId)
                    if (hasGroup(chatId) && knownInstance != serverInstance) {
                        // Blank counts as a mismatch. A bundle written before
                        // instance stamping existed has unknown provenance, and
                        // treating it as valid simply stamped the current id
                        // onto state from a deleted group - which is exactly the
                        // state that fails "mac check in GCM".
                        val from = knownInstance.ifBlank { "unstamped" }
                        Log.i(TAG, "MLS $chatId: group instance mismatch " +
                            "($from -> ${serverInstance.take(8)}); dropping stale state")
                        forgetGroup(chatId)
                    }
                    mutex.withLock { groupInstanceIds[chatId] = serverInstance }
                }

                if (hasGroup(chatId)) {
                    syncHandshakes(chatId)
                    if (!bundleHasWelcome(chatId)) persistSelfWelcome(chatId)
                    inviteMissingDevices(chatId)
                    logState(chatId, "ensure-has-group")
                    return@runCatching true
                }
                if (existing.isSuccessful) {
                    processWelcomes()
                    if (hasGroup(chatId)) {
                        syncHandshakes(chatId)
                        inviteMissingDevices(chatId)
                        logState(chatId, "ensure-joined-welcome")
                        return@runCatching true
                    }
                    // Not a member yet. We deliberately do NOT external-join
                    // here: an external join is a commit, and because
                    // BouncyCastle cannot serialize group state we would have
                    // to repeat it on every launch. Two devices doing that
                    // leapfrog each other's epochs forever, which is why
                    // opening the chat on a second phone broke the first.
                    //
                    // Instead, make sure this device is claimable and wait to
                    // be added by a current member. The resulting Welcome is
                    // persistable, so the join survives restarts (see
                    // docs/mls-multi-device.md, "The invariant").
                    ensureKeyPackagesAvailable()
                    logState(chatId, "ensure-awaiting-welcome")
                    Log.i(TAG, "MLS $chatId: not a member; awaiting Welcome (KeyPackages published)")
                    return@runCatching false
                }

                // Creator election. A creator holds no Welcome, so it cannot
                // rebuild its own state after a restart — it has to be re-added
                // by someone else. Electing exactly one creator deterministically
                // keeps that cost to a single device and stops two devices from
                // racing to create (the loser's group is orphaned by the DS 409).
                // Lowest (user_id, device_id) among devices that have a
                // published KeyPackage wins.
                if (!isElectedCreator(chatId)) {
                    ensureKeyPackagesAvailable()
                    logState(chatId, "ensure-not-creator")
                    Log.i(TAG, "MLS $chatId: not the elected creator; awaiting Welcome")
                    return@runCatching false
                }
                createGroup(chatId).getOrElse {
                    Log.w(TAG, "createGroup failed for $chatId: " + it.message)
                    processWelcomes()
                    if (hasGroup(chatId)) {
                        syncHandshakes(chatId)
                        inviteMissingDevices(chatId)
                        logState(chatId, "ensure-create-409-joined")
                        return@runCatching true
                    }
                    ensureKeyPackagesAvailable()
                    logState(chatId, "ensure-create-failed")
                    return@runCatching false
                }
                publishGroupInfo(chatId)
                inviteMissingDevices(chatId)
                logState(chatId, "ensure-created")
                hasGroup(chatId)
            }
        }

    /**
     * Adds this device a second time so the creator holds a Welcome it can
     * rebuild from. Best-effort: a missing KeyPackage just means this session
     * keeps using the unpersistable creator leaf.
     */
    private suspend fun persistSelfWelcome(chatId: String) {
        val me = tokenManager.getCurrentUserId().getOrNull().orEmpty()
        val myDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        if (me.isBlank() || myDev.isBlank()) return
        ensureKeyPackagesAvailable()
        addMember(chatId, me, myDev).onFailure {
            Log.w(TAG, "self-welcome for $chatId: ${it.message}")
        }
    }

    private suspend fun bundleHasWelcome(chatId: String): Boolean {
        val json = tokenManager.loadMlsBundle(groupKey(chatId)).getOrNull() ?: return false
        return runCatching {
            org.json.JSONObject(json).optString("welcome").isNotBlank()
        }.getOrDefault(false)
    }

    /**
     * Adds every live device that is not already a leaf — other users AND this
     * account's other installs. One leaf per device is what lets a sender's
     * other phone/PC unprotect the ciphertext (MLS senders cannot open their
     * own application messages).
     */
    suspend fun inviteOtherMembers(chatId: String): Result<Unit> = inviteMissingDevices(chatId)

    /**
     * True when this device is the deterministic creator for [chatId]: the
     * lowest (user_id, device_id) among devices that have a published
     * KeyPackage (so a winner that cannot self-welcome is not elected).
     *
     * Falls back to true if the device list cannot be read — better one extra
     * creation attempt (the DS answers 409 and we join by Welcome) than a group
     * that nobody ever creates.
     */
    private suspend fun isElectedCreator(chatId: String): Boolean {
        val meDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val me = tokenManager.getCurrentUserId().getOrNull().orEmpty()
        val coverage = fetchCoverage(chatId) ?: return true
        val candidates = coverage.liveDevices
            .filter { it.deviceId.isNotBlank() && (it.claimable || it.deviceId == meDev) }
            .map { mlsCredential(it.userId, it.deviceId) }
        val meKey = mlsCredential(me, meDev)
        val win = MlsPolicy.isLowest(meKey, candidates)
        if (!win) {
            Log.i(TAG, "MLS $chatId: not elected creator (candidates=$candidates me=$meKey)")
        }
        return win
    }

    /** Only the lowest-ordered current leaf invites; everyone else waits. */
    private suspend fun isElectedAdder(chatId: String): Boolean {
        val me = tokenManager.getCurrentUserId().getOrNull().orEmpty()
        val meDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val roster = mutex.withLock {
            groups[chatId]?.let { MlsGroupCrypto.memberIdentities(it) }.orEmpty()
        }
        val meCred = mlsCredential(me, meDev)
        val win = MlsPolicy.isElectedAdder(meCred, roster)
        if (!win) {
            Log.i(TAG, "MLS $chatId: not elected adder (roster=$roster me=$meCred)")
        }
        return win
    }

    suspend fun inviteOwnOtherDevices(chatId: String): Result<Unit> = inviteMissingDevices(chatId)

    suspend fun inviteMissingDevices(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasGroup(chatId)) return@runCatching
            if (!isElectedAdder(chatId)) return@runCatching
            val token = tokenManager.getAccessToken().getOrNull() ?: return@runCatching
            val meDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            val roster = mutex.withLock {
                groups[chatId]?.let { MlsGroupCrypto.memberIdentities(it) }.orEmpty()
            }
            val devices = api.listChatE2EEDevices(bearer(token), chatId)
                .body()?.devices.orEmpty()
            Log.i(TAG, "[mls-invite] $chatId epoch=${epochOf(chatId)} roster=$roster live=${devices.size}")
            for (d in devices) {
                if (d.deviceId.isBlank() || d.deviceId == meDev) continue
                if (mlsCredential(d.userId, d.deviceId) in roster) continue
                Log.i(TAG, "[mls-invite] $chatId WILL INVITE ${d.deviceId.take(8)}")
                val result = addMember(chatId, d.userId, d.deviceId)
                result.onFailure {
                    Log.w(TAG, "MLS invite ${d.deviceId.take(8)} on $chatId: ${it.message}")
                }
                // A 409 means we rebuilt; further Adds would race again.
                if (result.exceptionOrNull()?.message?.contains("stale epoch") == true) break
            }
        }
    }

    private suspend fun loadInvitedDevices(chatId: String): Set<String> =
        tokenManager.loadMlsBundle("invited/$chatId").getOrNull()
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private suspend fun persistInvitedDevices(chatId: String, ids: Set<String>) {
        tokenManager.saveMlsBundle("invited/$chatId", ids.joinToString(","))
    }

    // ---- Inbound: welcomes and commits ----

    /**
     * Fetches Welcomes addressed to this device and joins those groups.
     * Returns the chat ids joined.
     */
    suspend fun processWelcomes(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenManager.getAccessToken().getOrNull()
                ?: return@runCatching emptyList()
            val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            val resp = api.getMlsWelcomes(bearer(token), deviceId)
            val welcomes = resp.body()?.welcomes.orEmpty()

            val joined = mutableListOf<String>()
            val acked = mutableListOf<String>()
            for (w in welcomes) {
                if (hasGroup(w.chatId)) {
                    // Already a member (typically the creator holding a
                    // self-welcome). Rejoining would replace the in-session
                    // creator leaf with the Welcome leaf and drop later
                    // commits until a resync. Ack so coverage counts us.
                    Log.i(TAG, "MLS ${w.chatId}: already a member; acking Welcome without rejoin")
                    if (w.id.isNotBlank()) acked.add(w.id)
                    continue
                }
                val welcomeBytes = unb64(w.welcomeB64)
                val ok = mutex.withLock {
                    // Try each retained KeyPackage: only the one the inviter
                    // claimed can open this Welcome.
                    publishedKeyPackages.entries.firstNotNullOfOrNull { (hash, published) ->
                        val identity = identities[hash] ?: return@firstNotNullOfOrNull null
                        runCatching {
                            MlsGroupCrypto.joinFromWelcome(published, identity, welcomeBytes)
                        }.getOrNull()?.also { group ->
                            groups[w.chatId] = group
                            identities[w.chatId] = identity
                            // The Welcome is what lets us rebuild this group
                            // after a restart, so keep it with the identity.
                            persistGroupBundle(w.chatId, identity, welcomeBytes, published)
                        }
                    } != null
                }
                if (ok) {
                    joined.add(w.chatId)
                    // Retire it only now that the join actually worked. A
                    // Welcome we could not open stays pending, so a transient
                    // failure is retried instead of locking this device out.
                    if (w.id.isNotBlank()) acked.add(w.id)
                } else {
                    Log.w(TAG, "no key package opened welcome for ${w.chatId}; leaving it pending")
                }
            }
            if (acked.isNotEmpty()) {
                runCatching { api.ackMlsWelcomes(bearer(token), MlsWelcomeAckRequest(acked)) }
                    .onFailure { Log.w(TAG, "welcome ack failed: ${it.message}") }
            }
            for (id in joined) {
                inviteMissingDevices(id)
            }
            joined
        }
    }

    /** Applies any commits this client has not yet seen, advancing the epoch. */
    suspend fun syncHandshakes(chatId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenManager.getAccessToken().getOrNull()
                ?: return@runCatching 0L
            val since = mutex.withLock { groups[chatId]?.epoch ?: 0L }
            val resp = api.getMlsHandshakes(bearer(token), chatId, since)
            val handshakes = resp.body()?.handshakes.orEmpty()

            mutex.withLock {
                val group = groups[chatId] ?: return@withLock since
                var current = group
                for (h in handshakes) {
                    // Our own commit is already applied locally.
                    if (h.epoch <= current.epoch) continue
                    val next = runCatching {
                        MlsGroupCrypto.applyCommit(current, unb64(h.payloadB64))
                    }.getOrNull()
                    if (next == null) {
                        // Skipping a failed commit and applying later ones
                        // forks the epoch. Stop and wait for a rebuild.
                        Log.w(TAG, "MLS $chatId: stopping handshake replay at epoch ${h.epoch}")
                        break
                    }
                    current = next
                }
                groups[chatId] = current
                current.epoch
            }
        }
    }

    // ---- Application messages ----

    /** Encrypts a group message. Null when this chat has no MLS group yet. */
    suspend fun protect(chatId: String, plaintext: ByteArray): ByteArray? = mutex.withLock {
        val group = groups[chatId] ?: return null
        runCatching { MlsGroupCrypto.protect(group, plaintext) }.getOrNull()
    }

    /** Decrypts a group message. Null when it does not open. */
    suspend fun unprotect(chatId: String, payload: ByteArray): ByteArray? = mutex.withLock {
        val group = groups[chatId] ?: return null
        MlsGroupCrypto.unprotect(group, payload)
    }

    /**
     * True when every other live device has acked a Welcome and this device
     * is at the server's epoch. Until then group sends stay on Sender Keys.
     */
    suspend fun readyToSend(chatId: String): Boolean {
        if (!hasGroup(chatId)) return false
        val meDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val coverage = fetchCoverage(chatId) ?: return false
        val live = coverage.liveDevices.map { it.deviceId }
        val ready = MlsPolicy.readyToSend(
            hasGroup = true,
            meDev = meDev,
            liveDeviceIds = live,
            acked = coverage.ackedDeviceIds.toSet(),
            localEpoch = epochOf(chatId),
            serverEpoch = coverage.epoch
        )
        if (!ready) {
            val missing = MlsPolicy.missingLiveDevices(meDev, live, coverage.ackedDeviceIds.toSet())
            Log.i(
                TAG,
                "[mls-send] $chatId not ready epoch=${epochOf(chatId)}/${coverage.epoch} " +
                    "missing=${missing.map { it.take(8) }} pending=${coverage.pendingDeviceIds.map { it.take(8) }}"
            )
        }
        return ready
    }

    private suspend fun fetchCoverage(chatId: String): MlsCoverageDto? {
        val token = tokenManager.getAccessToken().getOrNull() ?: return null
        return runCatching { api.getMlsCoverage(bearer(token), chatId).body() }.getOrNull()
    }

    private suspend fun logState(chatId: String, where: String) {
        val coverage = fetchCoverage(chatId)
        val roster = mutex.withLock {
            groups[chatId]?.let { MlsGroupCrypto.memberIdentities(it) }.orEmpty()
        }
        val meDev = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
        val live = coverage?.liveDevices?.map { it.deviceId }.orEmpty()
        val acked = coverage?.ackedDeviceIds.orEmpty().toSet()
        val ready = MlsPolicy.readyToSend(
            hasGroup(chatId), meDev, live, acked, epochOf(chatId), coverage?.epoch ?: -1L
        )
        Log.i(
            TAG,
            "[mls-state] where=$where chat=$chatId hasGroup=${hasGroup(chatId)} " +
                "epoch=${epochOf(chatId)} serverEpoch=${coverage?.epoch} roster=$roster " +
                "acked=${coverage?.ackedDeviceIds?.map { it.take(8) }} " +
                "pending=${coverage?.pendingDeviceIds?.map { it.take(8) }} " +
                "missing=${MlsPolicy.missingLiveDevices(meDev, live, acked).map { it.take(8) }} " +
                "ready=$ready"
        )
    }

    /**
     * Drops forked in-memory state and rebuilds from the persisted Welcome.
     * Used after a 409: the rejected add is already applied locally and must
     * not be the base for handshake replay.
     */
    private suspend fun rebuildFromBundle(chatId: String): Boolean {
        val json = tokenManager.loadMlsBundle(groupKey(chatId)).getOrNull() ?: return false
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return false
        val identity = MlsGroupCrypto.decodeIdentity(o.optString("identity")) ?: return false
        val welcomeB64 = o.optString("welcome")
        val kpJson = o.optString("kp")
        if (welcomeB64.isBlank() || kpJson.isBlank()) return false
        val published = MlsGroupCrypto.decodePublishedKeyPackage(kpJson) ?: return false
        val group = runCatching {
            MlsGroupCrypto.joinFromWelcome(published, identity, unb64(welcomeB64))
        }.getOrNull() ?: return false
        mutex.withLock {
            groups[chatId] = group
            identities[chatId] = identity
        }
        Log.i(TAG, "MLS $chatId: rebuilt from Welcome at epoch ${group.epoch}")
        return true
    }

    /**
     * Publishes the current GroupInfo so a member who lost state can rejoin.
     * Best-effort — failure never fails the operation that triggered it.
     */
    suspend fun publishGroupInfo(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@runCatching
            val (info, epoch) = mutex.withLock {
                val g = groups[chatId] ?: return@runCatching
                MlsGroupCrypto.exportGroupInfo(g) to g.epoch
            }
            val resp = api.putMlsGroupInfo(
                bearer(token), chatId, MlsPutGroupInfoRequest(epoch, b64(info))
            )
            if (!resp.isSuccessful) Log.w(TAG, "publishGroupInfo ${resp.code()} for $chatId")
        }.onFailure { Log.w(TAG, "publishGroupInfo failed for $chatId: ${it.message}") }
    }

    /**
     * Recovers a group this device can no longer rebuild — the creator case,
     * where no Welcome exists and group state cannot be serialized.
     *
     * Rejoining costs an epoch (the external commit must be accepted by the
     * Delivery Service) and cannot recover messages sent before the rejoin:
     * those keys went with the lost state. It restores membership, not history.
     */
    suspend fun externalRejoin(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = tokenManager.getAccessToken().getOrNull() ?: error("no access token")
            val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            val userId = tokenManager.getCurrentUserId().getOrNull().orEmpty()

            val infoResp = api.getMlsGroupInfo(bearer(token), chatId)
            if (!infoResp.isSuccessful) error("no group info published for $chatId")
            val info = infoResp.body() ?: error("empty group info")
            if (!info.isCurrent) {
                // Building against a stale tree yields a commit the server will
                // reject; ask a live member to republish instead of guessing.
                error("published group info is stale (epoch ${info.groupInfoEpoch} vs ${info.epoch})")
            }

            // A fresh KeyPackage identifies this device in the external commit.
            val identity = MlsGroupCrypto.newIdentity(mlsCredential(userId, deviceId))
            val published = MlsGroupCrypto.newKeyPackage(identity)

            val joined = MlsGroupCrypto.externalJoin(
                published, identity, unb64(info.groupInfoB64)
            )

            val resp = api.submitMlsCommit(
                bearer(token), chatId,
                MlsCommitRequest(
                    expectedEpoch = info.epoch,
                    commitB64 = b64(joined.commit),
                    senderDeviceId = deviceId
                )
            )
            if (resp.code() == 409) error("epoch moved during rejoin; retry")
            if (!resp.isSuccessful) error("external commit rejected: ${resp.code()}")

            mutex.withLock {
                groups[chatId] = joined.group
                identities[chatId] = identity
                persistGroupBundle(chatId, identity, welcome = null, published = published)
            }
            publishGroupInfo(chatId)
            Unit
        }
    }

    // ---- Persistence ----
    //
    // A live MLS group cannot be serialized (see MlsGroupCrypto "Persistence"),
    // so restart recovery REBUILDS each group from stored material plus the
    // commits the Delivery Service retains: our identity keys, the KeyPackage
    // we published (with its init private key), and the Welcome that admitted
    // us.

    /**
     * Forgets this device's MLS state for a chat so it can re-establish. Used
     * when the Delivery Service no longer knows the group: the local bundle
     * then describes a group of one that nobody else can ever join.
     */
    private suspend fun forgetGroup(chatId: String) {
        mutex.withLock {
            groups.remove(chatId)
            identities.remove(chatId)
        }
        rejoinAttempted.remove(chatId)
        // Drop the cached instance too: the caller re-stamps it from the
        // server's current value, and a stale entry here would make the next
        // comparison agree with a group we are no longer part of.
        groupInstanceIds.remove(chatId)
        tokenManager.deleteMlsBundle(groupKey(chatId))
        // Scoped to the group we just dropped. Carrying it into the new group
        // would make us skip re-inviting those devices, and they now wait to be
        // added instead of external-joining — so they would wait forever.
        tokenManager.deleteMlsBundle("invited/$chatId")
    }

    /** Instance id of the group incarnation each local bundle belongs to. */
    private val groupInstanceIds = mutableMapOf<String, String>()

    private suspend fun loadGroupInstanceId(chatId: String): String {
        groupInstanceIds[chatId]?.let { return it }
        val json = tokenManager.loadMlsBundle(groupKey(chatId)).getOrNull() ?: return ""
        val id = runCatching { org.json.JSONObject(json).optString("instance") }
            .getOrNull().orEmpty()
        // optString yields the literal "null" for a JSON null.
        return if (id == "null") "" else id
    }

    private fun kpKey(hash: String) = "kp/$hash"
    private fun groupKey(chatId: String) = "group/$chatId"

    private suspend fun persistKeyPackage(
        hash: String,
        published: MlsGroupCrypto.PublishedKeyPackage,
        identity: MlsGroupCrypto.Identity
    ) {
        val o = org.json.JSONObject()
        o.put("kp", MlsGroupCrypto.encodePublishedKeyPackage(published))
        o.put("identity", MlsGroupCrypto.encodeIdentity(identity))
        tokenManager.saveMlsBundle(kpKey(hash), o.toString())
    }

    private suspend fun persistGroupBundle(
        chatId: String,
        identity: MlsGroupCrypto.Identity,
        welcome: ByteArray?,
        published: MlsGroupCrypto.PublishedKeyPackage? = null
    ) {
        val o = org.json.JSONObject()
        o.put("identity", MlsGroupCrypto.encodeIdentity(identity))
        if (welcome != null) o.put("welcome", b64(welcome))
        if (published != null) o.put("kp", MlsGroupCrypto.encodePublishedKeyPackage(published))
        // Stamp which incarnation of the group this state belongs to, so a
        // group that was deleted and recreated under the same chat_id can be
        // told apart from the one we actually joined.
        groupInstanceIds[chatId]?.let { o.put("instance", it) }
        tokenManager.saveMlsBundle(groupKey(chatId), o.toString())
    }

    /**
     * Rebuilds every persisted group after a restart, then catches each one up
     * to the current epoch from the Delivery Service. Returns the chat ids
     * restored.
     */
    suspend fun restoreGroups(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // Re-arm published KeyPackages first, so a Welcome that arrives
            // after the restart can still be opened.
            tokenManager.listMlsBundles("kp/").getOrDefault(emptyMap()).forEach { (key, json) ->
                val hash = key.removePrefix("kp/")
                runCatching {
                    val o = org.json.JSONObject(json)
                    val kp = MlsGroupCrypto.decodePublishedKeyPackage(o.optString("kp"))
                        ?: return@runCatching
                    val id = MlsGroupCrypto.decodeIdentity(o.optString("identity"))
                        ?: return@runCatching
                    mutex.withLock {
                        publishedKeyPackages[hash] = kp
                        identities[hash] = id
                    }
                }
            }

            val restored = mutableListOf<String>()
            for ((key, json) in tokenManager.listMlsBundles("group/").getOrDefault(emptyMap())) {
                val chatId = key.removePrefix("group/")
                val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: continue
                val identity = MlsGroupCrypto.decodeIdentity(o.optString("identity")) ?: continue
                val welcomeB64 = o.optString("welcome")
                val kpJson = o.optString("kp")
                if (welcomeB64.isBlank() || kpJson.isBlank()) {
                    // A group we created ourselves: no Welcome is addressed to
                    // us and BouncyCastle cannot serialize the state we held.
                    // Rebuilding needs an external-commit rejoin (RFC 9420
                    // 12.4.3.2), which the DS does not serve GroupInfo for yet.
                    Log.w(TAG, "cannot rebuild self-created group $chatId (needs external join)")
                    continue
                }
                val published = MlsGroupCrypto.decodePublishedKeyPackage(kpJson) ?: continue
                val group = runCatching {
                    MlsGroupCrypto.joinFromWelcome(published, identity, unb64(welcomeB64))
                }.getOrNull()
                if (group == null) {
                    Log.w(TAG, "rejoin from welcome failed for $chatId")
                    continue
                }
                mutex.withLock {
                    groups[chatId] = group
                    identities[chatId] = identity
                }
                // The Welcome lands us at the epoch we joined; replay anything
                // committed since so this device is current.
                syncHandshakes(chatId)
                restored.add(chatId)
            }
            restored
        }
    }

    /** True when this chat has usable MLS state on this device. */
    suspend fun hasGroup(chatId: String): Boolean = mutex.withLock { groups.containsKey(chatId) }

    /** Current epoch for diagnostics/tests. */
    suspend fun epochOf(chatId: String): Long = mutex.withLock { groups[chatId]?.epoch ?: -1L }
}
