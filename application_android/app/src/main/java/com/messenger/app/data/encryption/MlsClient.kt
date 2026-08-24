package com.messenger.app.data.encryption

import android.util.Log
import java.io.Closeable

/** What [MlsClient.process] found in an incoming MLS message. */
sealed interface MlsProcessed {
    /** An application message; [plaintext] is the decrypted content. */
    data class Application(val plaintext: ByteArray) : MlsProcessed
    /** A commit was applied; the group is now at [newEpoch]. */
    data class Commit(val newEpoch: Long) : MlsProcessed
    /** A proposal was staged. */
    data object Proposal : MlsProcessed
}

/**
 * One device's MLS client, backed by the shared Rust core.
 *
 * Owns a native handle, so it is [Closeable] — use it inside `use { }` or close
 * it explicitly. Holds NO cryptographic logic and must never parse an MLS
 * structure; that all lives in mls-core.
 *
 * Threading: the Rust side serializes every call behind a mutex, so this is safe
 * to use from any thread.
 *
 * Two contracts callers must respect:
 *
 *  1. **Persist [snapshot] after every state-changing call** (create, add, merge,
 *     join, process-commit). The snapshot contains private keys, so it belongs in
 *     secure storage and must never be logged or sent to the server.
 *  2. **A sender cannot decrypt its own message.** [encrypt] discards the key
 *     immediately for forward secrecy, so the caller must keep its own plaintext
 *     — it is the only readable copy that will ever exist.
 */
class MlsClient private constructor(
    private var handle: Long,
    private val userId: String,
    private val deviceId: String
) : Closeable {

    companion object {
        private const val TAG = "MlsClient"

        /** Credential separator understood by mls-core groupRemove. */
        private val SEPARATOR = Char(10).toString()

        /**
         * Splits mls-core's `len(gid) || gid || commit` reply.
         *
         * Separated from [externalJoin] so the framing can be tested on the JVM
         * without the native library: a length-prefix decode is exactly where an
         * off-by-one hides, and the on-device path cannot be unit-tested.
         */
        internal fun decodeExternalJoin(blob: ByteArray): ExternalJoinResult {
            require(blob.size >= 4) { "externalJoin returned a short buffer" }
            val gidLen = ((blob[0].toInt() and 0xff) shl 24) or
                ((blob[1].toInt() and 0xff) shl 16) or
                ((blob[2].toInt() and 0xff) shl 8) or
                (blob[3].toInt() and 0xff)
            require(gidLen >= 0 && 4 + gidLen <= blob.size) {
                "externalJoin length prefix out of range"
            }
            return ExternalJoinResult(
                groupId = blob.copyOfRange(4, 4 + gidLen),
                commit = blob.copyOfRange(4 + gidLen, blob.size)
            )
        }

        /** Fresh identity for this device. */
        fun create(userId: String, deviceId: String): MlsClient {
            require(MlsCore.isAvailable) { "mls-core unavailable: ${MlsCore.lastError}" }
            val h = MlsNative.clientNew(userId, deviceId)
            check(h != 0L) { "clientNew returned a null handle" }
            return MlsClient(h, userId, deviceId)
        }

        /**
         * Rebuilds from a snapshot previously produced by [snapshot]. The same
         * identity must be supplied — a mismatch is an error, not a silent new
         * identity.
         */
        fun restore(blob: ByteArray, userId: String, deviceId: String): MlsClient {
            require(MlsCore.isAvailable) { "mls-core unavailable: ${MlsCore.lastError}" }
            val h = MlsNative.clientRestore(blob, userId, deviceId)
            check(h != 0L) { "clientRestore returned a null handle" }
            return MlsClient(h, userId, deviceId)
        }

        /** Encodes a KeyPackage list the way [addMembers] expects. */
        fun encodeKeyPackages(packages: List<ByteArray>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (kp in packages) {
                val len = kp.size
                out.write((len ushr 24) and 0xff)
                out.write((len ushr 16) and 0xff)
                out.write((len ushr 8) and 0xff)
                out.write(len and 0xff)
                out.write(kp)
            }
            return out.toByteArray()
        }
    }

    /** Credential this device appears under in the ratchet tree. */
    val credential: String get() = "$userId|$deviceId"

    private fun alive(): Long {
        check(handle != 0L) { "MlsClient has been closed" }
        return handle
    }

    /** Contains PRIVATE KEYS. Store securely; never log or upload. */
    fun snapshot(): ByteArray = MlsNative.snapshot(alive())

    /** A single-use KeyPackage for publishing so others can add this device. */
    fun keyPackage(): ByteArray = MlsNative.keyPackage(alive())

    /**
     * The signature public key [keyPackage] commits to, or null if the core
     * cannot use the package at all.
     *
     * Doubles as the validity check: [addMembers] is all-or-nothing, so one
     * unusable package rejects the whole Add after the Delivery Service has
     * already consumed every package in it.
     *
     * MLS identifies members by this key and rejects an Add that proposes one
     * already in the tree, or two candidates carrying the same one - rejecting
     * the WHOLE batch. A caller assembling an Add needs to see that coming, and
     * the answer comes from the core: the app must never parse a KeyPackage.
     */
    fun signatureKeyOf(keyPackage: ByteArray): ByteArray? =
        runCatching { MlsNative.keyPackageSignatureKey(alive(), keyPackage) }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    /** Signature public keys already in [groupId]. */
    fun memberSignatureKeys(groupId: ByteArray): List<ByteArray> =
        runCatching { decodeLengthPrefixed(MlsNative.groupSignatureKeys(alive(), groupId)) }
            .getOrDefault(emptyList())

    private fun decodeLengthPrefixed(blob: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= blob.size) {
            val len = ((blob[i].toInt() and 0xff) shl 24) or
                ((blob[i + 1].toInt() and 0xff) shl 16) or
                ((blob[i + 2].toInt() and 0xff) shl 8) or
                (blob[i + 3].toInt() and 0xff)
            i += 4
            if (len < 0 || i + len > blob.size) break
            out.add(blob.copyOfRange(i, i + len))
            i += len
        }
        return out
    }

    fun createGroup(groupId: ByteArray) = MlsNative.groupCreate(alive(), groupId)

    /**
     * Stages a Remove of [credentials] (each "userId|deviceId"). Returns the
     * commit, NOT applied until [mergePending] - same contract as [addMembers].
     *
     * Only for evicting a leaf whose device never consumed its Welcome: it looks
     * like a member to everyone else, so it is never re-invited, while being
     * unable to read anything. Removing the dead leaf lets the ordinary
     * KeyPackage/Welcome flow add the device back for real.
     */
    fun removeMembers(groupId: ByteArray, credentials: List<String>): ByteArray =
        MlsNative.groupRemove(
            alive(), groupId, credentials.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)
        )


    /**
     * Stages an Add commit for [keyPackages]. Returns the commit and the Welcome.
     *
     * NOT applied locally: call [mergePending] only after the Delivery Service
     * accepts the commit, or [clearPending] if it answers 409. Merging first is
     * how the old design forked its groups.
     */
    fun addMembers(groupId: ByteArray, keyPackages: List<ByteArray>): AddResult {
        val blob = MlsNative.groupAdd(alive(), groupId, encodeKeyPackages(keyPackages))
        require(blob.size >= 4) { "groupAdd returned a short buffer" }
        val commitLen = ((blob[0].toInt() and 0xff) shl 24) or
            ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or
            (blob[3].toInt() and 0xff)
        require(4 + commitLen <= blob.size) { "groupAdd length prefix out of range" }
        return AddResult(
            commit = blob.copyOfRange(4, 4 + commitLen),
            welcome = blob.copyOfRange(4 + commitLen, blob.size)
        )
    }

    data class AddResult(val commit: ByteArray, val welcome: ByteArray)

    /** Applies a staged commit the DS accepted. Returns the new epoch. */
    fun mergePending(groupId: ByteArray): Long = MlsNative.mergePending(alive(), groupId)

    /** Discards a staged commit the DS rejected (409). */
    fun clearPending(groupId: ByteArray) = MlsNative.clearPending(alive(), groupId)

    /** Drops a group the DS refused so we do not encrypt to an orphan tree. */
    fun dropGroup(groupId: ByteArray) = MlsNative.dropGroup(alive(), groupId)

    /** Joins from a Welcome. Returns the group id; ack it only after this succeeds. */
    fun joinFromWelcome(welcome: ByteArray): ByteArray =
        MlsNative.joinWelcome(alive(), welcome)

    /** Encrypts. Remember: this device cannot decrypt the result. */
    fun encrypt(groupId: ByteArray, plaintext: ByteArray): ByteArray =
        MlsNative.encrypt(alive(), groupId, plaintext)

    fun process(groupId: ByteArray, message: ByteArray): MlsProcessed {
        val out = MlsNative.process(alive(), groupId, message)
        require(out.isNotEmpty()) { "process returned an empty buffer" }
        return when (val kind = out[0].toInt()) {
            1 -> MlsProcessed.Application(out.copyOfRange(1, out.size))
            2 -> {
                require(out.size == 9) { "commit payload must carry an 8-byte epoch" }
                var e = 0L
                for (i in 1..8) e = (e shl 8) or (out[i].toLong() and 0xff)
                MlsProcessed.Commit(e)
            }
            3 -> MlsProcessed.Proposal
            else -> throw MlsException("unknown processed kind $kind")
        }
    }

    fun epoch(groupId: ByteArray): Long = MlsNative.epoch(alive(), groupId)

    /** Loads a group from restored storage. Must not produce a commit. */
    fun loadGroup(groupId: ByteArray): Long = MlsNative.loadGroup(alive(), groupId)

    /**
     * Credentials of the current leaves. This — not any local bookkeeping — is
     * the answer to "is this device already a member?".
     */
    fun roster(groupId: ByteArray): List<String> =
        String(MlsNative.roster(alive(), groupId), Charsets.UTF_8)
            .split('\n')
            .filter { it.isNotBlank() }

    /**
     * Signed GroupInfo for [groupId], carrying the ratchet tree.
     *
     * Public material: no private keys, so it is safe to move between devices
     * or hand to the Delivery Service. It is what a device that has lost its
     * own group state needs in order to rejoin by external commit.
     */
    fun exportGroupInfo(groupId: ByteArray): ByteArray =
        MlsNative.exportGroupInfo(alive(), groupId)

    /**
     * Rejoins a group this device still belongs to but can no longer follow.
     *
     * The one case a Welcome cannot fix: MLS refuses to add a device that is
     * already a member, so a stranded member would otherwise have to be removed
     * and re-added. OpenMLS folds a Remove for the matching identity into this
     * same commit, so the membership set is preserved — the stale leaf is
     * replaced, not added alongside — and the signature key is reused, so the
     * roster is unchanged at both the credential and key level.
     *
     * Contract, which differs from [addMembers] and [removeMembers]: on DS
     * acceptance call [mergePending]; on rejection call [dropGroup] and rebuild
     * from fresh GroupInfo. [clearPending] CANNOT rescue an external commit,
     * because this client was not in the tree before it and there is no
     * pre-commit state to return to.
     */
    fun externalJoin(groupInfo: ByteArray): ExternalJoinResult =
        decodeExternalJoin(MlsNative.externalJoin(alive(), groupInfo))

    data class ExternalJoinResult(val groupId: ByteArray, val commit: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExternalJoinResult) return false
            return groupId.contentEquals(other.groupId) && commit.contentEquals(other.commit)
        }

        override fun hashCode(): Int = 31 * groupId.contentHashCode() + commit.contentHashCode()
    }

    override fun close() {
        if (handle != 0L) {
            MlsNative.clientFree(handle)
            handle = 0L
        }
    }

    /**
     * End-to-end check on the device: two clients, a real Welcome, a message
     * decrypted, and a snapshot/restore that does NOT move the epoch.
     */
    fun selfTestStatic(): Boolean = runSelfTest()
}

/**
 * Exercises the whole stack on-device. Mirrors the Rust integration tests so a
 * platform-specific problem (JNI marshalling, .so packaging) is distinguishable
 * from a protocol problem.
 */
internal fun runSelfTest(): Boolean {
    val tag = "MlsClient"
    if (!MlsCore.isAvailable) {
        Log.e(tag, "selftest skipped: ${MlsCore.lastError}")
        return false
    }
    return try {
        val gid = "selftest-group".toByteArray()
        MlsClient.create("alice", "phone").use { alice ->
            MlsClient.create("alice", "desktop").use { desktop ->
                alice.createGroup(gid)

                val add = alice.addMembers(gid, listOf(desktop.keyPackage()))
                check(alice.epoch(gid) == 0L) { "staged commit must not advance the epoch" }
                check(alice.mergePending(gid) == 1L) { "merge must reach epoch 1" }

                val joined = desktop.joinFromWelcome(add.welcome)
                check(joined.contentEquals(gid)) { "joined the wrong group" }
                check(desktop.epoch(gid) == 1L) { "joiner must start at the add epoch" }

                val roster = alice.roster(gid)
                check(roster.containsAll(listOf("alice|phone", "alice|desktop"))) {
                    "roster missing a device: $roster"
                }

                // The case reported broken for a whole session: one device of an
                // account reads a message sent by another.
                val ct = alice.encrypt(gid, "from my phone".toByteArray())
                val got = desktop.process(gid, ct)
                check(got is MlsProcessed.Application) { "expected an application message" }
                check(String(got.plaintext) == "from my phone") { "wrong plaintext" }
                Log.i(tag, "selftest: own other device read the message")

                // Invariant 1: restore must not move the epoch or stage a commit.
                val blob = alice.snapshot()
                MlsClient.restore(blob, "alice", "phone").use { restored ->
                    check(restored.loadGroup(gid) == 1L) { "epoch must survive restore" }
                    val ct2 = restored.encrypt(gid, "after restore".toByteArray())
                    val got2 = desktop.process(gid, ct2)
                    check(got2 is MlsProcessed.Application) { "restored client must encrypt" }
                    check(String(got2.plaintext) == "after restore") { "wrong plaintext" }
                }
                Log.i(tag, "selftest: snapshot/restore kept the epoch and stayed usable")
            }
        }
        Log.i(tag, "selftest PASSED")
        true
    } catch (e: Throwable) {
        Log.e(tag, "selftest FAILED: ${e.message}", e)
        false
    }
}
