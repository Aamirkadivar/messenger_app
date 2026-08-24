package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.MlsClient
import com.messenger.app.data.encryption.MlsCore
import com.messenger.app.data.encryption.MlsPolicy
import com.messenger.app.data.encryption.MlsProcessed
import com.messenger.app.data.encryption.MlsStoreId
import com.messenger.app.data.model.MlsClaimKeyPackageRequest
import com.messenger.app.data.model.MlsCommitRequest
import com.messenger.app.data.model.MlsCreateGroupRequest
import com.messenger.app.data.model.MlsKeyPackageItem
import com.messenger.app.data.model.MlsPublishKeyPackagesRequest
import com.messenger.app.data.model.MlsRecreateGroupRequest
import com.messenger.app.data.model.MlsWelcomeAckRequest
import com.messenger.app.data.model.MlsWelcomeItem
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives group messaging through the shared Rust core (mls-core / OpenMLS).
 *
 * Replaces [MlsRepository] (BouncyCastle). The protocol loop implemented here is
 * the one proven in mls-core/tests/ds_contract.rs — this class is the Android
 * transcription of it, not a fresh design:
 *
 *   claim KeyPackage (per device, single-use)
 *     -> addMembers            staged; epoch does NOT move yet
 *     -> submit commit with expected_epoch
 *          201 -> mergePending
 *          409 -> clearPending, replay handshakes, retry once
 *     -> joiner: fetch Welcome -> join -> ack ONLY after the join succeeded
 *     -> every existing member replays commits, or falls an epoch behind
 *
 * Rules carried over from the v1 post-mortem (docs/mls-session-review.md):
 *  - every device is its own leaf; credentials are "userId|deviceId"
 *  - KeyPackage stock is counted PER DEVICE, never per account
 *  - membership comes from the ratchet tree, never a local "invited" set
 *  - a Welcome is acked only after joining, so a failed join is retryable
 *  - the snapshot is persisted after EVERY state-changing call
 */
class MlsV2Repository(
    private val api: ChatApiService,
    private val tokenManager: TokenManager
) {
    companion object {
        private const val TAG = "MlsV2"

        /**
         * Off by default. v1 Sender Keys remain the shipping path until this is
         * proven on real devices; flipping this is a deliberate, per-build act.
         */
        const val ENABLED = true

        /**
         * Distinct from v5 on purpose. v5 rows were written by the BouncyCastle
         * stack, whose group state this core cannot read — reusing the number
         * would make the two indistinguishable on the wire and in the database.
         */
        const val ENCRYPTION_VERSION = 6

        private const val SNAPSHOT_KEY = "mls2_snapshot"

        /** MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519, matching mls-core. */
        private const val CIPHER_SUITE = 1
        private const val KEY_PACKAGE_TARGET = 10
        private const val KEY_PACKAGE_LOW_WATER = 3
    }

    private val mutex = Mutex()
    private var client: MlsClient? = null

    /**
     * Opaque tag for the store incarnation [client] is currently backed by, or
     * null when this process has not published under it yet.
     *
     * Only ever used to scope the "do I need more KeyPackages?" count. The tag
     * actually published is re-derived from the packages being sent, so a stale
     * value here can only cause an unnecessary republish - never a package
     * filed under a store that did not make it.
     */
    @Volatile
    private var storeId: String? = null

    /** Group ids we hold live state for, so a send can tell "not a member" apart. */
    private val liveGroups = mutableSetOf<String>()

    /**
     * Chats the Delivery Service confirms are MLS-governed. Positive answers
     * only - see [serverHasGroup].
     */
    private val serverGroups = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Chats whose active group id has been checked against the Delivery Service
     * in this process.
     *
     * A local group that LOADS is not evidence that it is the current one. A
     * device that missed a recreation still holds a perfectly valid group for the
     * abandoned tree, and every path that could have noticed - send, chat open,
     * handshake sync - short-circuited on that successful load. It then encrypted
     * into a group nobody else was on, in both directions, indefinitely.
     *
     * So the DS is asked once per chat per run, and again whenever a message
     * fails to apply (see [applyBytes]) - which is exactly the symptom of the id
     * having moved underneath us.
     */
    private val gidVerified = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Active MLS group id per chat, learned from the Delivery Service. Absent
     * means "use the chat-derived default" - see [gidOf].
     */
    private val activeGid = java.util.Collections.synchronizedMap(mutableMapOf<String, ByteArray>())

    /**
     * Chats this process has already attempted to recreate. Recovery is an
     * explicit, bounded act: one attempt per chat per run, so a persistent
     * failure cannot spin up a chain of dead group incarnations.
     */
    private val recreateAttempted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The MLS group id currently active for [chatId].
     *
     * This used to be `chatId.toByteArray()` unconditionally, which made the
     * group id a permanent function of the chat - so a chat could only ever have
     * one MLS group, and a broken tree could never be replaced. A recreated
     * incarnation needs an id of its own, so the active id is now learned from
     * the Delivery Service and cached here.
     *
     * The chat-derived value remains the default. Groups created before this
     * change genuinely have `group_id == chat_id` bytes, so falling back to it
     * keeps them working untouched; it is a starting value, not a second id to
     * try on decrypt failure.
     */
    private fun gidOf(chatId: String): ByteArray =
        activeGid[chatId] ?: chatId.toByteArray(Charsets.UTF_8)

    /**
     * Records the active group id for a chat and persists it, so a restart does
     * not silently drop back to the chat-derived id and start talking to an
     * abandoned tree.
     */
    private suspend fun setActiveGid(chatId: String, gid: ByteArray) {
        activeGid[chatId] = gid
        tokenManager.saveMlsBundle(gidKey(chatId), b64(gid))
            .onFailure { Log.w(TAG, "could not persist active group id for $chatId: ${it.message}") }
    }

    /** Loads the persisted active group id, if one was recorded. */
    private suspend fun loadActiveGid(chatId: String) {
        if (activeGid.containsKey(chatId)) return
        val stored = tokenManager.loadMlsBundle(gidKey(chatId)).getOrElse {
            // Present but unreadable: do not guess. Leaving it unset means the
            // chat-derived default applies, and a genuine mismatch surfaces as
            // "not a member" rather than as silent traffic to the wrong tree.
            Log.w(TAG, "active group id for $chatId is unreadable: ${it.message}")
            null
        }
        if (!stored.isNullOrBlank()) {
            runCatching { unb64(stored) }.getOrNull()?.let { activeGid[chatId] = it }
        }
    }

    private fun gidKey(chatId: String) = "mls2_gid_$chatId"

    /**
     * Loads the client from secure storage, or creates one. Restoring must NOT
     * produce a commit — that is invariant 1, and the thing v1 violated on every
     * single launch.
     */
    suspend fun ensureClient(): MlsClient? = withContext(Dispatchers.IO) {
        if (!MlsCore.isAvailable) {
            Log.w(TAG, "mls-core unavailable: ${MlsCore.lastError}")
            return@withContext null
        }
        mutex.withLock {
            val userId = tokenManager.getCurrentUserId().getOrNull().orEmpty()
            val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            if (userId.isBlank() || deviceId.isBlank()) {
                Log.w(TAG, "no identity yet; not creating an MLS client")
                return@withLock null
            }

            // A cached client belongs to whoever was signed in when it was
            // built. Handing it to a different account is how one account came
            // to publish KeyPackages under another account's MLS identity, so
            // the cached client is only reusable while the identity still
            // matches. This holds even if nothing told us the account changed.
            client?.let { cached ->
                if (cached.credential == "$userId|$deviceId") return@withLock cached
                Log.w(TAG, "identity changed; discarding the previous account's MLS client")
                runCatching { cached.close() }
                client = null
                forgetInMemoryState()
            }

            // Three states, and they must stay distinct. Collapsing "unreadable"
            // into "absent" is what destroyed group state: a fresh empty client
            // looks legitimate, and the next persist writes its empty whole-store
            // snapshot straight over the real one.
            val stored = tokenManager.loadMlsBundle(SNAPSHOT_KEY)
            val blob = stored.getOrElse { e ->
                // Case C(i): something is stored but cannot be read. Fail; the
                // blob stays on disk so a later read (or a fixed Keystore) can
                // still recover it.
                Log.e(TAG, "MLS snapshot present but unreadable (${e.message}); " +
                    "refusing to start with an empty client")
                return@withLock null
            }

            val c = if (blob.isNullOrBlank()) {
                // Case A: genuinely nothing stored. A fresh client is correct.
                // Guard against a blank-but-present value, which would be a
                // stored bundle we must not silently replace.
                if (tokenManager.hasMlsBundle(SNAPSHOT_KEY).getOrDefault(false)) {
                    Log.e(TAG, "MLS snapshot is stored but decoded empty; refusing " +
                        "to start with an empty client")
                    return@withLock null
                }
                Log.i(TAG, "creating a fresh MLS client")
                MlsClient.create(userId, deviceId)
            } else {
                // Case B / C(ii): restore, and fail if that does not work. The
                // previous behaviour started clean here, which silently discarded
                // every group this device belonged to.
                runCatching {
                    MlsClient.restore(android.util.Base64.decode(blob, android.util.Base64.NO_WRAP), userId, deviceId)
                }.getOrElse { e ->
                    Log.e(TAG, "SNAPSHOT RESTORE FAILED (${e.message}) - keeping the " +
                        "stored snapshot; this device needs recovery, not a reset")
                    return@withLock null
                }
            }
            client = c
            c
        }
    }

    /**
     * Persists the client's state. MUST be called after every state-changing
     * operation: the snapshot is a whole-store image, not an incremental write.
     *
     * The blob CONTAINS PRIVATE KEYS. It goes to secure storage and is never
     * logged or sent to the server.
     */
    /**
     * Builds a brand-new MLS client WITHOUT attempting to restore the stored
     * snapshot, abandoning whatever MLS state this device held.
     *
     * This is the one destructive escape hatch from [ensureClient]'s fail-closed
     * rule, and it is deliberately NOT reachable from it. A device whose snapshot
     * is corrupt cannot restore, so [ensureClient] returns null everywhere - which
     * is right for normal operation but also blocks the only action that could
     * repair the device. This function unblocks exactly that action and nothing
     * else.
     *
     * Constraints that make it safe:
     *  - private, with exactly one caller: [recreateMlsGroup]
     *  - that caller reaches it only after the Delivery Service has already
     *    accepted a new incarnation, so local state is never discarded on the
     *    strength of a request that failed
     *  - it does NOT persist. The first durable write stays tied to the existing
     *    commit-accepted path, so a failure between here and an accepted commit
     *    leaves the old snapshot on disk and the recovery retryable.
     */
    private suspend fun replaceClientForRecovery(): MlsClient? = withContext(Dispatchers.IO) {
        if (!MlsCore.isAvailable) {
            Log.w(TAG, "mls-core unavailable: ${MlsCore.lastError}")
            return@withContext null
        }
        mutex.withLock {
            val userId = tokenManager.getCurrentUserId().getOrNull().orEmpty()
            val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            if (userId.isBlank() || deviceId.isBlank()) {
                Log.w(TAG, "no identity yet; not creating an MLS client for recovery")
                return@withLock null
            }

            val fresh = runCatching { MlsClient.create(userId, deviceId) }.getOrElse { e ->
                Log.e(TAG, "recovery: could not create a fresh MLS client: ${e.message}")
                return@withLock null
            }

            Log.w(TAG, "recovery: replacing unusable local MLS state with a fresh client " +
                "(explicit user-confirmed reset; stored snapshot is left on disk until " +
                "a commit is accepted)")
            runCatching { client?.close() }
            // The fresh client is a member of nothing; in-memory membership has
            // to say so too, or a later send would address a group it lacks.
            liveGroups.clear()
            // A new store has published nothing. Keeping the old tag would make
            // the next replenishment check ask about the abandoned store's
            // stock and conclude, wrongly, that this device is well supplied.
            storeId = null
            client = fresh
            fresh
        }
    }

    /**
     * Creates a group using an explicitly supplied client.
     *
     * Split out of [createGroup] so the recovery path can build a group on the
     * fresh client it just made, without going back through [ensureClient] - which
     * would still refuse, because the corrupt snapshot is deliberately still there.
     */
    private suspend fun createGroupWithClient(chatId: String, c: MlsClient): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                c.createGroup(gidOf(chatId))
                liveGroups.add(chatId)
                // Deliberately not persisted here - see createGroup.
                true
            }.getOrElse {
                Log.w(TAG, "createGroup $chatId failed: ${it.message}")
                false
            }
        }

    private suspend fun persist(c: MlsClient): Result<Unit> {
        return runCatching {
            val raw = c.snapshot()

            // Defence in depth behind the ensureClient fix. A whole-store
            // snapshot from an empty client would erase every group, so an empty
            // image may never replace a populated one. Decided on the plaintext
            // snapshot's own entry count, never by comparing wrapped blobs.
            if (snapshotEntryCount(raw) == 0 && storedSnapshotIsPopulated()) {
                throw IllegalStateException(
                    "refusing to overwrite a populated MLS snapshot with an empty one"
                )
            }

            val encoded = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
            tokenManager.saveMlsBundle(SNAPSHOT_KEY, encoded).getOrThrow()
        }.onFailure { Log.e(TAG, "failed to persist MLS state: ${it.message}") }
    }

    /**
     * Entry count from a native snapshot: `MLS1` magic then a big-endian u64.
     * Null when the blob is not a snapshot we recognise - treated as "unknown",
     * never as "empty".
     */
    private fun snapshotEntryCount(blob: ByteArray): Int? {
        if (blob.size < 12) return null
        if (blob[0] != 'M'.code.toByte() || blob[1] != 'L'.code.toByte() ||
            blob[2] != 'S'.code.toByte() || blob[3] != '1'.code.toByte()
        ) return null
        var count = 0L
        for (i in 4 until 12) count = (count shl 8) or (blob[i].toLong() and 0xFF)
        return if (count > Int.MAX_VALUE) Int.MAX_VALUE else count.toInt()
    }

    /**
     * Whether the durable snapshot holds at least one entry.
     *
     * An unreadable stored snapshot counts as populated: that is the case this
     * guard exists for, and assuming "empty" there would licence exactly the
     * overwrite it is meant to prevent.
     */
    private suspend fun storedSnapshotIsPopulated(): Boolean {
        val stored = tokenManager.loadMlsBundle(SNAPSHOT_KEY)
        val blob = stored.getOrElse {
            return tokenManager.hasMlsBundle(SNAPSHOT_KEY).getOrDefault(true)
        }
        if (blob.isNullOrBlank()) {
            return tokenManager.hasMlsBundle(SNAPSHOT_KEY).getOrDefault(false)
        }
        val decoded = runCatching {
            android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        }.getOrElse { return true }
        // Unrecognised existing content is treated as populated, for the same
        // reason as an unreadable one.
        return (snapshotEntryCount(decoded) ?: 1) > 0
    }

    /**
     * True when the Delivery Service has an MLS v2 group for [chatId] - i.e. the
     * conversation is MLS-governed, regardless of whether *this* device is a
     * member.
     *
     * This is the signal that separates "MLS group, and we have lost our state"
     * from "ordinary Sender-Key group". A send must never infer that from
     * encryption_version: the versions on the wire are a consequence of the
     * choice, not evidence for it.
     *
     * Cached per chat because it gates every group send. Only a positive answer
     * is cached: a group can be created at any time, so "no group yet" must stay
     * re-checkable, while a group that exists never stops existing.
     */
    suspend fun serverHasGroup(chatId: String): Boolean = withContext(Dispatchers.IO) {
        // Adoption lives in ensureCurrentGid so there is exactly one place that
        // compares against the DS. The positive cache below used to be set
        // BEFORE adoption ran, which latched this path shut for the rest of the
        // process and was the second reason a stale group was never noticed.
        if (!ensureCurrentGid(chatId)) return@withContext serverGroups.contains(chatId)
        serverGroups.contains(chatId)
    }

    /**
     * Drops this device's local state for [chatId] without touching the durable
     * snapshot's other groups.
     *
     * Used when the active incarnation changes: the old group's keys are dead, and
     * keeping them live would let a send address a tree nobody else is on.
     */
    private suspend fun forgetLocal(chatId: String) {
        val c = client ?: return
        runCatching { c.dropGroup(gidOf(chatId)) }
            .onFailure { Log.w(TAG, "dropGroup $chatId: ${it.message}") }
        liveGroups.remove(chatId)
        // Persist the removal. This is a non-empty snapshot in every realistic
        // case (identity keys remain), so the empty-overwrite guard does not fire.
        persist(c).onFailure { Log.w(TAG, "could not persist group drop: ${it.message}") }
    }

    /**
     * Removes a group only after a newer Welcome has proven it stale.
     *
     * Unlike [forgetLocal], this is a gate for a subsequent single-use
     * Welcome-open. If either the core deletion or its durable snapshot fails,
     * do not call joinFromWelcome: an existing group makes OpenMLS consume the
     * KeyPackage and then fail with "already exists".
     */
    private suspend fun dropStaleLocalForWelcome(chatId: String): Boolean {
        val c = client ?: return false
        if (runCatching { c.dropGroup(gidOf(chatId)) }
                .onFailure { Log.w(TAG, "drop stale group $chatId: ${it.message}") }
                .isFailure) {
            return false
        }
        liveGroups.remove(chatId)
        return persist(c).onFailure {
            Log.w(TAG, "could not persist stale group drop for $chatId: ${it.message}")
        }.isSuccess
    }

    /**
     * Makes it safe to present [welcomeEpoch]'s Welcome to OpenMLS.
     *
     * The DS GID check prevents a locally loadable abandoned incarnation from
     * being treated as current. Once the active GID agrees, a local epoch below
     * the Welcome's post-commit epoch is the narrow proof that this local group
     * is stale. Equal or newer local state is healthy/redundant and must not be
     * opened, dropped, or acknowledged here.
     */
    private suspend fun prepareForWelcome(chatId: String, welcomeEpoch: Long): Boolean {
        if (chatId.isBlank() || !ensureCurrentGid(chatId)) return false
        val c = ensureClient() ?: return false
        val localEpoch = runCatching { c.loadGroup(gidOf(chatId)) }.getOrNull()
        if (localEpoch == null) return true

        if (!MlsPolicy.shouldReplaceLocalGroupForWelcome(localEpoch, welcomeEpoch)) {
            liveGroups.add(chatId)
            Log.i(TAG, "MLS v2 $chatId: skipping redundant Welcome at epoch $welcomeEpoch " +
                "(local epoch $localEpoch)")
            return false
        }

        Log.i(TAG, "MLS v2 $chatId: replacing stale local epoch $localEpoch " +
            "for Welcome epoch $welcomeEpoch")
        return dropStaleLocalForWelcome(chatId)
    }

    /**
     * Abandons a chat's MLS group and joins a fresh incarnation, keeping the same
     * chat_id.
     *
     * The recovery path for a device whose local MLS state is gone. It cannot
     * rejoin the existing tree - remove_members is not reachable from this client
     * and external-commit rejoin does not exist - so the group is replaced and
     * every current device is Welcomed into the new one.
     *
     * Bounded: one attempt per chat per process. Never called automatically on
     * startup; recovery is an explicit act.
     *
     * Returns true only when this device holds durably persisted state for the
     * new incarnation.
     */
    suspend fun recreateMlsGroup(chatId: String): Boolean = withContext(Dispatchers.IO) {
        if (!ENABLED) return@withContext false
        if (!recreateAttempted.add(chatId)) {
            Log.w(TAG, "recreate already attempted for $chatId this run; not retrying")
            return@withContext false
        }
        val t = token() ?: return@withContext false

        // Only MLS-governed chats. Server metadata decides, never a message's
        // encryption_version.
        val current = runCatching { api.getMlsGroup(bearer(t), chatId) }.getOrNull()
        if (current == null || !current.isSuccessful) {
            Log.w(TAG, "recreate $chatId: chat is not MLS-governed; refusing")
            return@withContext false
        }
        val expectedInstance = current.body()?.instanceId.orEmpty()

        // A fresh, unpredictable id - deliberately NOT derived from chat_id, so
        // the new incarnation cannot collide with the abandoned one.
        val freshGid = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        val resp = runCatching {
            api.recreateMlsGroup(
                bearer(t), chatId,
                MlsRecreateGroupRequest(b64(freshGid), CIPHER_SUITE, expectedInstance)
            )
        }.getOrNull()
        if (resp == null) {
            Log.w(TAG, "recreate $chatId: request failed")
            return@withContext false
        }

        val adoptedGid: ByteArray = when {
            resp.isSuccessful -> freshGid
            resp.code() == 409 -> {
                // Another device recreated first. Re-read the group rather than
                // parsing the conflict body, and adopt the winner's incarnation
                // instead of starting a third tree.
                Log.i(TAG, "recreate $chatId: lost the race; adopting the existing incarnation")
                val winner = runCatching { api.getMlsGroup(bearer(t), chatId) }.getOrNull()
                val winnerGid = winner?.body()?.groupIdB64
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { unb64(it) }.getOrNull() }
                winnerGid ?: return@withContext false
            }
            else -> {
                Log.w(TAG, "recreate $chatId: DS refused status=${resp.code()}")
                return@withContext false
            }
        }

        // Past this point the Delivery Service has accepted a new incarnation, so
        // discarding unusable local MLS state is legitimate - and only here.
        //
        // Prefer the existing client: a healthy device that tapped Reset keeps its
        // identity and published KeyPackages. Only when the stored snapshot cannot
        // be restored at all does this fall back to a fresh client, which is the
        // case that used to dead-end at "local createGroup failed".
        // The gid the abandoned group is filed under. Captured while it is still
        // the active one - setActiveGid below makes it unreachable.
        val oldGid = gidOf(chatId)

        val c = when (val restorable = ensureClient()) {
            null -> replaceClientForRecovery()
            else -> {
                // Surgery, not demolition: drop only this chat's group so the
                // device keeps its MLS identity, its other chats' groups, and the
                // private init keys behind its published KeyPackages.
                //
                // Deliberately NOT forgetLocal(): that persists, and a durable
                // write here would land before the new group's commit is
                // accepted. The first snapshot write stays on the accepted-commit
                // path, so a failure in between leaves the old snapshot intact.
                val dropped = runCatching { restorable.dropGroup(oldGid) }
                    .onFailure { Log.w(TAG, "recreate $chatId: dropGroup failed: ${it.message}") }
                    .isSuccess

                if (dropped) {
                    liveGroups.remove(chatId)
                    restorable
                } else {
                    // The abandoned group could not be removed, so this client
                    // cannot be corrected surgically and must not be carried
                    // forward half-modified. Replacing it wholesale is only
                    // permissible because the user explicitly asked for a reset
                    // and the Delivery Service has already accepted the new
                    // incarnation.
                    Log.w(TAG, "recreate $chatId: falling back to a fresh client")
                    replaceClientForRecovery()
                }
            }
        } ?: run {
            Log.w(TAG, "recreate $chatId: no usable MLS client, even for recovery")
            return@withContext false
        }

        setActiveGid(chatId, adoptedGid)
        serverGroups.add(chatId)

        if (adoptedGid.contentEquals(freshGid)) {
            // We won: build the group and Welcome everyone else into it.
            if (!createGroupWithClient(chatId, c)) {
                Log.w(TAG, "recreate $chatId: local createGroup failed")
                return@withContext false
            }
            inviteMissingDevices(chatId)
        } else {
            // We lost: wait to be Welcomed into the winner's group.
            processWelcomes()
        }

        val recovered = hasGroup(chatId)
        if (recovered) {
            // Recovery may have replaced the store, which orphans every
            // KeyPackage this device had published: they are no longer
            // claimable, and nothing else would ever notice, so the device
            // would quietly become impossible to ADD to any future group.
            // Republish under the new incarnation now.
            //
            // Only on the recovered path, and only here at the end: this
            // persists, and a durable write is allowed only once a commit has
            // been accepted - which holding group state is the evidence of.
            ensureKeyPackages()
        }
        recovered
    }

    /** True when this device holds usable state for [chatId]. */
    /**
     * Makes the locally active group id agree with the Delivery Service.
     *
     * Returns false when the DS could not be reached, and the caller must then
     * treat the chat as unusable rather than fall back to the chat-derived id:
     * guessing is what let a device address an abandoned tree forever.
     *
     * Adoption reuses the existing [forgetLocal] + [setActiveGid] pair; this adds
     * no second persistence mechanism.
     */
    private suspend fun ensureCurrentGid(chatId: String): Boolean {
        if (gidVerified.contains(chatId)) return true
        val t = token() ?: return false
        val resp = runCatching { api.getMlsGroup(bearer(t), chatId) }.getOrNull() ?: return false
        if (!resp.isSuccessful) {
            // 404 means the DS holds no group: nothing to converge on, and the
            // create path owns that case. Anything else is an unknown answer.
            return if (resp.code() == 404) { gidVerified.add(chatId); true } else false
        }
        serverGroups.add(chatId)
        val b64Gid = resp.body()?.groupIdB64
        if (b64Gid.isNullOrBlank()) return false
        val serverGid = runCatching { unb64(b64Gid) }.getOrNull() ?: return false

        loadActiveGid(chatId)
        if (!serverGid.contentEquals(gidOf(chatId))) {
            Log.i(TAG, "active MLS group id changed for $chatId; adopting the DS value")
            forgetLocal(chatId)
            setActiveGid(chatId, serverGid)
        }
        gidVerified.add(chatId)
        return true
    }

    suspend fun hasGroup(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext false
        // The DS owns group identity. Checking this BEFORE trusting a loadable
        // local group is the whole point: a stale group loads just as cleanly as
        // a current one, and accepting it is how a device went silent in both
        // directions while believing it was fine.
        if (!ensureCurrentGid(chatId)) {
            Log.w(TAG, "hasGroup $chatId: cannot confirm the active group id; failing closed")
            return@withContext false
        }
        if (liveGroups.contains(chatId)) return@withContext true
        // Try loading from restored storage before concluding we are not a member.
        runCatching { c.loadGroup(gidOf(chatId)) }
            .onSuccess { liveGroups.add(chatId) }
            .onFailure { Log.w(TAG, "loadGroup " + chatId + ": " + it.message) }
            .isSuccess
    }

    suspend fun createGroup(chatId: String): Boolean = withContext(Dispatchers.IO) {
        // Do not persist until the Delivery Service accepts the registration. A
        // 409 means another device owns the real group; persisting this orphan
        // would make us encrypt to a tree nobody else has.
        val c = ensureClient() ?: return@withContext false
        createGroupWithClient(chatId, c)
    }

    /**
     * Stages an Add for [keyPackages] and returns the blobs for the caller to
     * submit. Nothing is applied locally: the caller MUST call [onCommitAccepted]
     * or [onCommitRejected] once the Delivery Service has answered.
     */
    suspend fun stageAdd(chatId: String, keyPackages: List<ByteArray>): MlsClient.AddResult? =
        withContext(Dispatchers.IO) {
            val c = ensureClient() ?: return@withContext null
            runCatching { c.addMembers(gidOf(chatId), keyPackages) }.getOrElse {
                Log.w(TAG, "stageAdd $chatId failed: ${it.message}")
                null
            }
        }

    /** The DS accepted the commit: apply it and persist the new epoch. */
    suspend fun onCommitAccepted(chatId: String): Long = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext -1
        runCatching {
            val epoch = c.mergePending(gidOf(chatId))
            // An unpersisted merge means the next start sits on the pre-commit
            // epoch while the group has moved on - the fork v1 suffered from.
            persist(c).getOrThrow()
            epoch
        }.getOrElse {
            Log.w(TAG, "merge $chatId failed: ${it.message}")
            -1
        }
    }

    /**
     * The DS answered 409: discard the staged commit. Keeping it is exactly how
     * v1 forked groups — local state advanced past a commit the group rejected.
     */
    suspend fun onCommitRejected(chatId: String) = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext
        runCatching {
            c.clearPending(gidOf(chatId))
            persist(c)
        }.onFailure { Log.w(TAG, "clearPending $chatId failed: ${it.message}") }
    }

    /**
     * Joins from a Welcome. Returns the chat id on success; the caller acks the
     * Welcome server-side ONLY when this returns non-null.
     */
    /**
     * Joins from a Welcome and returns the chat id only once the resulting state
     * is durably stored.
     *
     * Persistence used to be fire-and-forget here, so a join whose snapshot was
     * never written still reported success - and the caller then acked the
     * Welcome, retiring the only copy of the invite while the membership existed
     * solely in memory. The next process start had no group and no way back in.
     */
    suspend fun joinFromWelcome(welcome: ByteArray): String? = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext null
        runCatching {
            val gid = c.joinFromWelcome(welcome)
            val chatId = String(gid, Charsets.UTF_8)
            liveGroups.add(chatId)
            persist(c).getOrElse { e ->
                // Not durable, so not joined. Drop the in-memory membership so
                // the device does not believe it is a member on this run either.
                liveGroups.remove(chatId)
                throw IllegalStateException("join not persisted: ${e.message}", e)
            }
            chatId
        }.getOrElse {
            Log.w(TAG, "join failed: ${it.message}; leaving the Welcome pending")
            null
        }
    }

    /** A fresh single-use KeyPackage to publish for this device. */
    suspend fun keyPackage(): ByteArray? = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext null
        runCatching {
            val kp = c.keyPackage()
            // The private init key was just written to storage; losing it means
            // a Welcome sent to this KeyPackage could never be opened. So an
            // unpersisted KeyPackage must never be handed out for publishing.
            persist(c).getOrThrow()
            kp
        }.getOrNull()
    }

    /**
     * Encrypts for [chatId].
     *
     * The caller MUST keep the plaintext: OpenMLS discards the key at encrypt
     * time for forward secrecy, so this device can never decrypt its own message
     * and the local copy is the only readable one that will ever exist.
     */
    suspend fun encrypt(chatId: String, plaintext: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            val c = ensureClient()
            if (c == null) {
                Log.w(TAG, "encrypt " + chatId + ": no client")
                return@withContext null
            }
            if (!hasGroup(chatId)) {
                // Distinguishing "not a member" from "client missing" is the
                // whole point: they have different fixes and looked identical.
                Log.w(TAG, "encrypt " + chatId + ": not a member (live=" +
                    liveGroups.size + ")")
                return@withContext null
            }
            runCatching {
                val ct = c.encrypt(gidOf(chatId), plaintext)
                // The sender ratchet advanced. If that cannot be stored, the
                // next start would reuse a superseded ratchet state, so the
                // send fails rather than emitting a message we cannot account
                // for.
                persist(c).getOrThrow()
                ct
            }.getOrElse {
                Log.w(TAG, "encrypt $chatId failed: ${it.message}")
                null
            }
        }

    /** Processes an incoming message, commit or proposal. */
    suspend fun process(chatId: String, message: ByteArray): MlsProcessed? =
        withContext(Dispatchers.IO) {
            val c = ensureClient() ?: return@withContext null
            // Load the group from storage first. After a restart the Rust core
            // holds no live group until load_group() runs, so going straight to
            // process() failed with "group not loaded" even though the state was
            // on disk. encrypt() went through hasGroup(); this path did not.
            if (!hasGroup(chatId)) {
                processWelcomes()
                if (!hasGroup(chatId)) {
                    Log.w(TAG, "process " + chatId + ": not a member")
                    return@withContext null
                }
            }
            val first = applyBytes(c, chatId, message)
            if (first != null) return@withContext first
            // An MLS application message only opens at the epoch it was sent
            // in. Catch up on commits, then retry once.
            syncHandshakes(chatId)
            applyBytes(c, chatId, message).also { out ->
                if (out == null) {
                    Log.w(TAG, "process $chatId failed after handshake catch-up")
                }
            }
        }

    /** Applies one MLS blob without retrying handshakes (avoids recursion). */
    private suspend fun applyBytes(c: MlsClient, chatId: String, message: ByteArray): MlsProcessed? {
        val out = runCatching { c.process(gidOf(chatId), message) }
            .onFailure {
                Log.w(TAG, "apply $chatId: ${it.message}")
                // "Message group ID differs" is precisely what a recreation looks
                // like from here. Re-check with the DS on the next pass rather
                // than staying latched on this run's answer.
                gidVerified.remove(chatId)
            }
            .getOrNull()
        if (out != null) persist(c)
        return out
    }

    /**
     * Current members, by credential. The ratchet tree is the ONLY authority on
     * "is this device already a member?" — a persisted invited-set outlived
     * server resets in v1 and deadlocked every re-invite.
     */
    suspend fun roster(chatId: String): List<String> = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext emptyList()
        runCatching { c.roster(gidOf(chatId)) }.getOrElse { emptyList() }
    }

    suspend fun epoch(chatId: String): Long = withContext(Dispatchers.IO) {
        val c = ensureClient() ?: return@withContext -1
        runCatching { c.epoch(gidOf(chatId)) }.getOrElse { -1 }
    }

    /** Drops local state for a chat (server 404, or group instance mismatch). */
    suspend fun forget(chatId: String) = withContext(Dispatchers.IO) {
        val c = client
        if (c != null) {
            runCatching { c.dropGroup(gidOf(chatId)) }
                .onFailure { Log.w(TAG, "dropGroup $chatId: ${it.message}") }
            persist(c)
        }
        mutex.withLock { liveGroups.remove(chatId) }
    }


    // ---------------------------------------------------------------- DS wiring

    private fun bearer(t: String) = "Bearer $t"
    private fun b64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(v: String): ByteArray =
        android.util.Base64.decode(v, android.util.Base64.NO_WRAP)

    private suspend fun token(): String? = tokenManager.getAccessToken().getOrNull()
    private suspend fun myDevice(): String =
        tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()

    /**
     * Publishes KeyPackages so other devices can add this one. Counted PER
     * DEVICE: counting per account let a new device see a sibling's stock,
     * publish nothing, and become permanently unaddable.
     */
    suspend fun ensureKeyPackages(): Unit = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext
        val dev = myDevice()

        // Only this store's own packages count as stock. Anything an earlier
        // incarnation published is unusable to us - its private init keys went
        // with it - so counting it would report a full shelf and stop the
        // republish this store actually needs. Until we know our own tag, the
        // stock is zero by definition; there is nothing on the server that this
        // store is known to have made.
        val known = storeId
        val available = if (known == null) 0 else runCatching {
            api.countMlsKeyPackages(bearer(t), dev, known).body()?.available ?: 0
        }.getOrDefault(0)
        if (available >= KEY_PACKAGE_LOW_WATER) return@withContext

        val fresh = mutableListOf<ByteArray>()
        repeat(KEY_PACKAGE_TARGET - available) {
            keyPackage()?.let { fresh.add(it) }
        }
        if (fresh.isEmpty()) return@withContext

        // Derived from the packages being published rather than from the cached
        // tag, so the value on the wire always describes the store that really
        // made them - even if the cache above was stale.
        val sid = MlsStoreId.of(fresh.first())
        if (sid == null) {
            // Publishing untagged would put these back in the same
            // indistinguishable pool the tag exists to escape.
            Log.w(TAG, "could not derive a store id; not publishing key packages")
            return@withContext
        }
        storeId = sid

        val items = fresh.map { kp ->
            val hash = MessageDigest.getInstance("SHA-256").digest(kp)
                .joinToString("") { b -> "%02x".format(b) }
            MlsKeyPackageItem(dev, CIPHER_SUITE, b64(kp), hash, sid)
        }
        runCatching { api.publishMlsKeyPackages(bearer(t), MlsPublishKeyPackagesRequest(items)) }
            .onSuccess { Log.i(TAG, "published " + items.size + " key packages for store " + sid.take(8)) }
            .onFailure { Log.w(TAG, "publish failed: " + it.message) }
    }

    /**
     * Brings this device into the group for [chatId], creating it only if the
     * Delivery Service has none. Returns true when we hold usable state.
     */
    suspend fun ensureGroup(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext false
        ensureKeyPackages()

        // Pending Welcomes first: being added is always preferable to creating,
        // because a Welcome-based join is the only one we can rebuild after a
        // restart without committing.
        processWelcomes()
        if (hasGroup(chatId)) {
            syncHandshakes(chatId)
            inviteMissingDevices(chatId)
            return@withContext true
        }

        val existing = runCatching { api.getMlsGroup(bearer(t), chatId) }.getOrNull()
        if (existing != null && existing.isSuccessful) {
            // Someone else owns it and we are not a member yet. Wait to be added
            // rather than external-joining: an external join is a commit, and
            // repeating it every launch is what made v1 devices desync forever.
            Log.i(TAG, "MLS v2 " + chatId + ": awaiting Welcome")
            return@withContext false
        }

        if (!createGroup(chatId)) return@withContext false
        val registered = runCatching {
            api.createMlsGroup(
                bearer(t),
                MlsCreateGroupRequest(chatId, b64(chatId.toByteArray(Charsets.UTF_8)), CIPHER_SUITE)
            )
        }.getOrNull()
        if (registered == null || !registered.isSuccessful) {
            val code = registered?.code() ?: 0
            Log.w(TAG, "MLS v2 " + chatId + ": DS refused registration status=" + code)
            forget(chatId)
            if (code == 409) {
                processWelcomes()
                if (hasGroup(chatId)) {
                    syncHandshakes(chatId)
                    return@withContext true
                }
            }
            return@withContext false
        }
        val c = ensureClient()
        if (c != null) persist(c)
        inviteMissingDevices(chatId)
        true
    }

    /**
     * Adds every chat device that is not already a leaf, in ONE commit.
     * Membership comes from the ratchet tree - a persisted "invited" set
     * outlived server resets in v1 and deadlocked every re-invite.
     */
    suspend fun inviteMissingDevices(chatId: String): Unit = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext
        if (!hasGroup(chatId)) return@withContext
        val meDev = myDevice()

        // A leaf in the tree is not proof the device joined. One that never
        // consumed its Welcome looks like a member from here, so it is never
        // re-invited, while being unable to read anything - stranded, and
        // blocking its own recovery. Evict those first so the ordinary
        // KeyPackage/Welcome flow below can add them back for real.
        evictPhantomMembers(chatId, t, meDev)

        val current = roster(chatId).toSet()

        val devices = runCatching {
            api.listChatE2EEDevices(bearer(t), chatId).body()?.devices.orEmpty()
        }.getOrDefault(emptyList())

        // The client is needed to vet candidates before any of them reach a
        // commit; without one there is nothing to invite anybody into.
        val c = ensureClient() ?: return@withContext

        // MLS identifies members by signature key and refuses an Add that
        // proposes one already in the tree, or two candidates carrying the same
        // one - rejecting the whole batch. Two devices left behind by an account
        // switch really did share an identity in production, and each package
        // was individually valid, so per-package validation could not see it.
        // Seed from the group, then grow as candidates are accepted.
        val seenSignatureKeys = c.memberSignatureKeys(gidOf(chatId))
            .mapTo(mutableSetOf()) { it.toList() }

        val claimedIds = mutableListOf<Pair<String, String>>()
        val claimedKps = mutableListOf<ByteArray>()
        for (d in devices) {
            if (d.deviceId.isBlank() || d.deviceId == meDev) continue
            if ((d.userId + "|" + d.deviceId) in current) continue
            val kp = runCatching {
                api.claimMlsKeyPackage(
                    bearer(t), MlsClaimKeyPackageRequest(d.userId, d.deviceId, CIPHER_SUITE)
                ).body()?.keyPackageB64.orEmpty()
            }.getOrDefault("")
            if (kp.isBlank()) {
                Log.w(TAG, "no key package for " + d.deviceId.take(8))
                continue
            }
            val bytes = unb64(kp)
            // Vet each candidate on its own. stageAdd is all-or-nothing, so a
            // single unusable package - a legacy one from another MLS stack, say
            // - would reject the whole Add after every package in the batch had
            // already been consumed. One stale device must not keep healthy
            // devices out of the group.
            val signatureKey = c.signatureKeyOf(bytes)
            if (signatureKey == null) {
                Log.w(TAG, "skipping unusable key package from " + d.deviceId.take(8) +
                    "; that device needs to republish before it can be added")
                continue
            }
            if (!seenSignatureKeys.add(signatureKey.toList())) {
                // Already a member, or another candidate in this batch presents
                // the same identity. Adding it would take every healthy device
                // down with it.
                Log.w(TAG, "skipping " + d.deviceId.take(8) +
                    ": its MLS identity is already in this group or batch")
                continue
            }
            claimedIds.add(d.userId to d.deviceId)
            claimedKps.add(bytes)
        }
        if (claimedKps.isEmpty()) return@withContext

        // Survivors go in together: one Add, one commit, one epoch - splitting
        // them per device would multiply commit-ordering conflicts.
        val staged = stageAdd(chatId, claimedKps) ?: return@withContext
        val expected = epoch(chatId)
        val welcomes = claimedIds.map { (uid, did) ->
            MlsWelcomeItem(uid, did, b64(staged.welcome))
        }

        val resp = runCatching {
            api.submitMlsCommit(
                bearer(t), chatId,
                MlsCommitRequest(expected, b64(staged.commit), meDev, welcomes)
            )
        }.getOrNull()

        if (resp != null && resp.isSuccessful) {
            val e = onCommitAccepted(chatId)
            Log.i(TAG, "MLS v2 " + chatId + ": added " + claimedKps.size + " device(s), epoch=" + e)
        } else if (resp != null && resp.code() == 409) {
            // Someone committed first. Discard ours and catch up; the retry is
            // the caller's next pass, not a loop here.
            Log.i(TAG, "MLS v2 " + chatId + ": 409, discarding staged commit")
            onCommitRejected(chatId)
            syncHandshakes(chatId)
        } else {
            Log.w(TAG, "MLS v2 " + chatId + ": commit rejected")
            onCommitRejected(chatId)
        }
    }

    /**
     * Removes leaves the Delivery Service reports as having an OUTSTANDING
     * invitation: the device's newest Welcome is unconsumed and the group has
     * already moved past it.
     *
     * "Outstanding" is the server's judgement and deliberately narrower than
     * "has some unconsumed Welcome". A device carrying an ancient Welcome it can
     * never open - the KeyPackage private key died with an old store
     * incarnation - is not a phantom if it later joined through a newer one, and
     * a device invited by the commit that just landed has simply not polled yet.
     * Reading either as proof evicts a genuine member, and since eviction is
     * followed by a re-invite it does not misfire once: it loops, burning two
     * epochs a pass. See GetCoverage in back-end/handlers/mls.go.
     *
     * Gated on POSITIVE evidence only. Coverage that cannot be fetched, or that
     * lists nothing pending, removes nobody: absence of information must never
     * justify evicting a member.
     *
     * The removal is one commit; the re-invite is the caller's normal path.
     */
    private suspend fun evictPhantomMembers(chatId: String, t: String, meDev: String) {
        val coverage = runCatching { api.getMlsCoverage(bearer(t), chatId).body() }.getOrNull()
            ?: return
        val phantoms = MlsPolicy.phantomMembers(
            roster(chatId), meDev, coverage.pendingDeviceIds.toSet()
        )
        if (phantoms.isEmpty()) return

        val c = ensureClient() ?: return

        Log.i(TAG, "MLS v2 $chatId: evicting ${phantoms.size} phantom member(s) " +
            "whose invitation the DS reports outstanding")
        val commit = runCatching { c.removeMembers(gidOf(chatId), phantoms) }.getOrElse {
            Log.w(TAG, "MLS v2 $chatId: phantom removal could not be staged: ${it.message}")
            return
        }
        val expected = epoch(chatId)
        val resp = runCatching {
            api.submitMlsCommit(bearer(t), chatId, MlsCommitRequest(expected, b64(commit), meDev, emptyList()))
        }.getOrNull()
        if (resp != null && resp.isSuccessful) {
            val e = onCommitAccepted(chatId)
            Log.i(TAG, "MLS v2 $chatId: phantom(s) removed, epoch=$e")
        } else {
            // Someone else moved first, or the DS refused. Discard and let the
            // next pass retry; never leave a staged commit behind.
            Log.w(TAG, "MLS v2 $chatId: phantom removal rejected (${resp?.code() ?: -1})")
            onCommitRejected(chatId)
            syncHandshakes(chatId)
        }
    }

    /** Applies commits we have not seen. Without this we fall an epoch behind. */
    suspend fun syncHandshakes(chatId: String): Unit = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext
        if (!hasGroup(chatId)) return@withContext
        val since = epoch(chatId)
        if (since < 0) return@withContext
        val handshakes = runCatching {
            api.getMlsHandshakes(bearer(t), chatId, since).body()?.handshakes.orEmpty()
        }.getOrDefault(emptyList())
        for (h in handshakes) {
            if (h.payloadB64.isBlank()) continue
            val c = ensureClient() ?: return@withContext
            val out = applyBytes(c, chatId, unb64(h.payloadB64))
            if (out is MlsProcessed.Commit) {
                Log.i(TAG, "MLS v2 " + chatId + ": epoch -> " + out.newEpoch)
            }
        }
    }

    /**
     * Fetches pending Welcomes, joins, and acks ONLY what actually joined. A
     * Welcome we cannot open stays pending, so a transient failure is retried
     * instead of locking this device out of the group for good.
     */
    suspend fun processWelcomes(): List<String> = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext emptyList()
        val pending = runCatching {
            api.getMlsWelcomes(bearer(t), myDevice()).body()?.welcomes.orEmpty()
        }.getOrDefault(emptyList())

        // GetWelcomes is ordered oldest first. A device can carry an ancient
        // dead-store Welcome beside a newer recovery Welcome; processing the
        // old one first can only waste its single-use KeyPackage. The newest
        // row per chat is the only invitation that describes the current state.
        val newestPerChat = pending.asReversed().distinctBy { it.chatId }.asReversed()
        val joined = mutableListOf<String>()
        val acked = mutableListOf<String>()
        for (w in newestPerChat) {
            if (w.welcomeB64.isBlank()) continue
            if (!prepareForWelcome(w.chatId, w.epoch)) continue
            val chatId = joinFromWelcome(unb64(w.welcomeB64))
            if (chatId != null) {
                joined.add(chatId)
                if (w.id.isNotBlank()) acked.add(w.id)
                Log.i(TAG, "MLS v2: joined " + chatId)
            }
        }
        if (acked.isNotEmpty()) {
            runCatching { api.ackMlsWelcomes(bearer(t), MlsWelcomeAckRequest(acked)) }
                .onFailure { Log.w(TAG, "welcome ack failed: " + it.message) }
        }
        joined
    }

    fun close() {
        client?.close()
        client = null
        forgetInMemoryState()
    }

    /**
     * Drops everything scoped to the client that just went away: membership,
     * the store tag, and the learned group ids. Leaving any of it behind would
     * describe a client this repository no longer holds.
     */
    private fun forgetInMemoryState() {
        liveGroups.clear()
        storeId = null
        activeGid.clear()
        serverGroups.clear()
        gidVerified.clear()
        recreateAttempted.clear()
    }
}
