package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves the MK-sealed history keyring between this device and the server.
 *
 * This is what makes history survive device replacement and a fresh install: the
 * keyring is otherwise device-local, so losing the device made every server-stored
 * archive permanently undecryptable. It performs NO cryptography of its own -
 * sealing is [HistoryKeyringRecoveryTransport], merging is
 * `HistoryKeyringMerge` - and never handles MK, roots in the clear, or plaintext.
 *
 * Everything is best effort with respect to messaging. A failure here costs
 * recovery coverage, never a message.
 */
@Singleton
class HistoryKeyringRecoverySync @Inject constructor(
    private val keyring: HistoryKeyringRepository,
    private val transport: HistoryKeyringRecoveryTransport,
    private val api: ChatApiService,
    private val tokenManager: TokenManager,
    private val feature: HistoryArchiveFeature,
) {

    private companion object {
        const val TAG = "KeyringRecovery"
        /** One refetch-merge-retry pass. A second conflict means real contention. */
        const val MAX_UPLOAD_ATTEMPTS = 3
    }

    private val gate = Mutex()

    /**
     * Revision last published to the server. Compared against the repository's
     * counter so an unchanged keyring costs one integer comparison rather than a
     * request - which is what keeps per-message archiving from becoming
     * per-message keyring traffic.
     */
    @Volatile
    private var uploadedRevision: Long = -1

    /** One recovery attempt per session; reset by [resetSession] on account change. */
    @Volatile
    private var recoveryAttempted = false

    /**
     * The recovery currently running, if any, and the outcome every caller of it will receive.
     *
     * This exists to separate two states the flag above cannot tell apart. `recoveryAttempted` is
     * set BEFORE the work runs, so it means "an attempt has begun" - but callers were answered
     * `success(false)`, which means "an attempt FINISHED and imported nothing". A caller arriving
     * mid-flight was therefore told a completed-and-empty story about work that had not happened
     * yet, and the one consumer that branches on this result reads any non-failure as permission to
     * proceed.
     *
     * A caller that finds this set joins the running recovery and receives its real outcome. It is
     * completed with a VALUE rather than cancelled, so a joiner is never cancelled by whatever
     * happened to the caller that owned the work.
     *
     * Atomic rather than merely volatile because [resetSession] can now clear it too, so a worker
     * finishing after a session change must be able to ask "is this still MY attempt?" and stand
     * down if it is not. A plain read-then-write could clear the incoming session handle in the
     * window between the two.
     */
    private val inFlight = AtomicReference<CompletableDeferred<Result<Boolean>>?>(null)

    /**
     * The repository revision the last successful [upload] actually published.
     *
     * Reading `currentRevision()` after the round trip instead would acknowledge a
     * mutation that landed DURING the upload and was never sent, suppressing the
     * next publish until something else moved the revision. Capturing it at the
     * moment the bytes are exported removes that window without a queue, a
     * worker, or any retry machinery - the value is simply the truth about what
     * was sent.
     */
    @Volatile
    private var publishedRevision: Long = -1

    /**
     * Publishes the keyring only if it actually changed since the last upload.
     *
     * Called AFTER a mutation has returned, never inside the repository lock:
     * network I/O must not be performed while the keyring mutex is held.
     * A failure is non-fatal and leaves [uploadedRevision] behind, so the next
     * mutation retries naturally without a queue or scheduler.
     */
    suspend fun uploadIfChanged(): Result<Unit> {
        if (!feature.isEnabled()) return Result.success(Unit)
        val current = keyring.currentRevision()
        if (current == uploadedRevision) return Result.success(Unit)

        return gate.withLock {
            // Re-check inside the gate: a concurrent caller may have just published.
            if (keyring.currentRevision() == uploadedRevision) return@withLock Result.success(Unit)
            upload().fold(
                onSuccess = {
                    uploadedRevision = publishedRevision
                    Result.success(Unit)
                },
                onFailure = {
                    // Synchronisation is not a reason to break messaging.
                    Log.w(TAG, "keyring recovery upload deferred: ${it.message}")
                    Result.success(Unit)
                }
            )
        }
    }

    /**
     * Recovers the keyring once per session, before archives are downloaded.
     *
     * Ordering matters: an archive downloaded before its root is present would be
     * stored SEALED and then be unopenable, so this must complete first. It is a
     * one-shot because recovery is convergent - repeating it merges the same
     * roots and changes nothing.
     *
     * A genuine cryptographic or conflict failure is surfaced. An absent server
     * object is not a failure: an account that never uploaded simply has nothing
     * to recover.
     */
    suspend fun ensureRecovered(): Result<Boolean> {
        if (!feature.isEnabled()) return Result.success(false)

        // Join a recovery that is already running rather than inventing an answer about it.
        inFlight.get()?.let { return it.await() }
        // A recovery that has already FINISHED still latches: this is the one-shot, unchanged.
        if (recoveryAttempted) return Result.success(false)

        // Claim the work, or discover the claim someone else made first. Only the claim is taken
        // under the gate; the recovery itself runs outside it, so a joiner never waits on a lock
        // held for the length of a network round trip.
        var claimed: CompletableDeferred<Result<Boolean>>? = null
        val joined = gate.withLock {
            inFlight.get() ?: if (recoveryAttempted) {
                null
            } else {
                CompletableDeferred<Result<Boolean>>().also { inFlight.set(it); claimed = it }
            }
        }
        val mine = claimed ?: return joined?.await() ?: Result.success(false)

        var outcome: Result<Boolean>? = null
        try {
            recoveryAttempted = true
            outcome = runRecovery()
            return outcome
        } finally {
            // Settle the joiners on every exit, including cancellation of the coroutine that owned
            // the work. Recovery runs in the CALLER's coroutine and this class owns no scope of its
            // own, so there is nothing left to continue it - stranding joiners on a deferred that
            // will never complete would be the worst outcome available. They are handed an ordinary
            // failure value instead of a cancellation, so they are not cancelled in turn, and the
            // attempt is re-armed so the next caller genuinely retries.
            // Stand down if this attempt has been superseded. A session reset clears the handle,
            // so by the time a worker gets here the field may already hold the INCOMING session's
            // attempt - and clearing that would strand its joiners on a handle nobody will complete,
            // which is exactly the fabricated-success bug this whole design exists to prevent.
            // Compare-and-set makes "still mine" and "clear it" one step.
            val stillCurrent = inFlight.compareAndSet(mine, null)
            val settled = outcome ?: run {
                // The worker coroutine went away before recovery settled - most often cancellation.
                // Re-arm only if we still own the session state; re-arming someone else's would let
                // a third caller start a duplicate recovery underneath them.
                if (stillCurrent) recoveryAttempted = false
                Result.failure(
                    IllegalStateException("history keyring recovery was interrupted before it completed")
                )
            }
            mine.complete(settled)
        }
    }

    /**
     * One recovery attempt. Extracted only so [ensureRecovered] can settle its joiners in a
     * `finally`; the body is unchanged.
     */
    private suspend fun runRecovery(): Result<Boolean> {
        // Flatten a THROWN recovery failure into a returned one.
        //
        // recoverInternal unwraps its steps with getOrElse, which handles a failure it RETURNS but
        // not one it RAISES - and its inner block is try/finally with no catch. A raise (a Keystore
        // or JNI fault out of the recovery open, a decode, an import) therefore left the caller
        // before the .onFailure below was ever applied, so the reset never ran and
        // recoveryAttempted stayed true for the rest of the session: recovery never retried, and
        // every later caller was told success(false) - which reads as "no failure" to the one
        // consumer that branches on this, suppressing its per-chat root check.
        //
        // This changes only the SHAPE of a failure, never its meaning. A returned failure is
        // untouched, success is untouched, and nothing is swallowed: the same exception reaches
        // the same .onFailure and the same caller.
        return runCatching { recoverInternal() }
            .getOrElse { Result.failure(it) }
            .map { it.imported }
            .onSuccess {
                // Converge the server copy ONLY when this device held roots the server does not. A
                // pure import already equals the server copy, so publishing it back would be a
                // pointless GET -> PUT cycle that bumps the version on every fresh install.
                if (lastRecovery?.localHadExtraRoots == true) {
                    upload().onSuccess { uploadedRevision = publishedRevision }
                        .onFailure { Log.w(TAG, "post-recovery convergence deferred: ${it.message}") }
                }
            }
            .onFailure {
                // Allow a later attempt: a transient failure must not permanently disable recovery
                // for the session.
                recoveryAttempted = false
                Log.w(TAG, "keyring recovery failed: ${it.message}")
            }
    }

    /**
     * Re-arms recovery so it runs again inside an already-active session.
     *
     * The one-shot default is right for ordinary startup - recovery is convergent
     * and repeating it changes nothing - but it means roots published by ANOTHER
     * device mid-session stay invisible until the next unlock. This is the
     * explicit escape hatch: it clears only the attempted flag and retries.
     *
     * It does not clear, replace, or re-mint any local keyring material; the
     * merge is the same union as any other recovery, so valid local roots always
     * survive. No worker, queue, or polling is involved - the caller decides when.
     */
    suspend fun refreshRecovery(): Result<Boolean> {
        if (!feature.isEnabled()) return Result.success(false)
        gate.withLock { recoveryAttempted = false }
        return ensureRecovered()
    }

    /**
     * Why the master key is being replaced.
     *
     * The two cases have DIFFERENT failure semantics, and forcing the caller to
     * name one is the point: invalidation must never be reachable by accident
     * from a password rewrap, a vault refresh, or a failed decryption. A caller
     * on one of those paths has no honest value to pass here.
     */
    sealed interface MkReplacement {
        /**
         * The account's first vault. `GET /e2ee/vault` answered 404, so no MK has
         * ever existed for it - and since a recovery blob can only be published by
         * a device that held an MK, no blob can exist either.
         *
         * Invalidation is therefore a belt-and-braces no-op, and a failure is
         * tolerable: nothing was left behind to strand.
         */
        data object FreshAccountMint : MkReplacement

        /**
         * An existing vault's MK is being deliberately replaced.
         *
         * Here a blob almost certainly DOES exist and is about to become
         * permanently unopenable, while the slot it occupies is create-only. A
         * failure here is not tolerable - see the contract on the return value.
         */
        data object IntentionalReset : MkReplacement
    }

    /**
     * Invalidates the server-side recovery blob because a NEW master key is being
     * minted. This is the only supported way to remove it.
     *
     * ORDERING. Callers must invoke this BEFORE the new MK becomes the active
     * session key. Afterwards, a live session could republish under the new key
     * and race the stale row; beforehand, the slot is provably empty when the new
     * key starts using it.
     *
     * WHAT THIS IS NOT. It must never be called because a vault failed to
     * decrypt, a blob failed to open, the network failed, a password or recovery
     * key changed, an identity key rotated, or a vault was updated. Every one of
     * those looks identical to a retired key at this level, and acting on them
     * would destroy a perfectly good blob. Only a caller that has positively
     * established it is MINTING a new MK may call this.
     *
     * FAILURE CONTRACT:
     *  - [MkReplacement.FreshAccountMint] - a failure is returned but is safe to
     *    log and continue on, because the precondition guarantees there was
     *    nothing to delete.
     *  - [MkReplacement.IntentionalReset] - a failure MUST abort the replacement
     *    before the new MK is installed. Continuing would leave a blob sealed
     *    under the retired key sitting in a create-only slot, which stranding no
     *    client can subsequently repair without deleting again.
     */
    suspend fun invalidateHistoryRecoveryBeforeMkReplacement(
        reason: MkReplacement,
    ): Result<Unit> {
        // Containment before anything else. A disabled feature has never published
        // a blob, so there is nothing to invalidate - and it must not emit recovery
        // HTTP traffic merely because a vault is being created.
        if (!feature.isEnabled()) return Result.success(Unit)

        val token = currentToken() ?: return Result.failure(
            IllegalStateException("no usable session to invalidate the recovery keyring")
        )
        val resp = runCatching { api.deleteHistoryKeyring(bearer(token)) }
            .getOrElse { return Result.failure(it) }
        if (!resp.isSuccessful) {
            return Result.failure(
                IllegalStateException(
                    "recovery keyring invalidation failed for $reason: HTTP ${resp.code()}"
                )
            )
        }
        // The local session must forget what it thought the server held, or the
        // next publish would name a version that no longer exists.
        resetSession()
        return Result.success(Unit)
    }

    /**
     * Clears session state on logout or account switch.
     *
     * Also drops the repository's opened keyring. The durable copies are left
     * completely alone - logging out is not a reason to destroy history keys -
     * but a second account signing in without a process restart must not be able
     * to read the first account's decrypted keyring out of memory.
     */
    fun resetSession() {
        keyring.clearCache()
        recoveryAttempted = false
        uploadedRevision = -1
        publishedRevision = -1
        // Forget any attempt still running for the OUTGOING session. This object is a singleton and
        // survives the account switch, so leaving the handle in place would let the next account
        // join the previous account's recovery and be handed its outcome - skipping its own.
        //
        // Only the reference is dropped. The worker keeps its own copy and still completes it, so
        // callers already attached to that attempt are settled exactly as before.
        inFlight.set(null)
    }

    /**
     * Publishes this device's keyring so another device can recover it.
     *
     * Optimistic concurrency: the server accepts a write only from a client that
     * names the version it is replacing. On conflict the remote blob is fetched,
     * opened, merged (union - never replacement), re-sealed, and retried against
     * the server's version. Conflicting root material aborts the whole upload
     * rather than picking a winner.
     */
    suspend fun upload(): Result<Int> {
        if (!feature.isEnabled()) return Result.failure(disabled())
        val token = currentToken() ?: return Result.failure(
            IllegalStateException("no usable session for keyring recovery upload")
        )
        // Same reasoning as recoverInternal: the conflict-merge below opens and imports a remote
        // blob, and that import must be filed under the account this upload began for.
        val attemptOwner = currentOwner() ?: return Result.failure(
            IllegalStateException("no signed-in account for keyring recovery upload")
        )

        // A locked vault means the authoritative keyring cannot be read at all,
        // and uploading an empty one would later merge as "this account has no
        // roots". exportForRecovery fails closed in that case.
        var expectedVersion = 0
        repeat(MAX_UPLOAD_ATTEMPTS) { attempt ->
            // Bytes and revision come out of ONE critical section, so this names
            // exactly the state being sent - not a state observed a moment before
            // or after it. Anything looser risks acknowledging a mutation that was
            // never uploaded.
            val export = keyring.exportForRecoveryAtRevision()
                .getOrElse { return Result.failure(it) }
            val revisionAtExport = export.revision
            val plain = export.bytes
            val sealed = try {
                transport.sealForRecovery(plain).getOrElse { return Result.failure(it) }
            } finally {
                com.messenger.app.data.encryption.history.HistoryCrypto.bestEffortWipe(plain)
            }

            val resp = runCatching {
                api.putHistoryKeyring(
                    bearer(token),
                    HistoryKeyringPutRequest(
                        expectedVersion = expectedVersion,
                        ciphertextB64 = Base64.getEncoder().encodeToString(sealed),
                    )
                )
            }.getOrElse { return Result.failure(it) }

            if (resp.isSuccessful) {
                publishedRevision = revisionAtExport
                return Result.success(resp.body()?.version ?: (expectedVersion + 1))
            }
            if (resp.code() != 409) {
                Log.w(TAG, "keyring recovery upload rejected: HTTP ${resp.code()}")
                return Result.failure(
                    IllegalStateException("keyring recovery upload failed: HTTP ${resp.code()}")
                )
            }

            // Conflict: someone else advanced the server copy. Merge theirs in and
            // retry against their version. Never blind-overwrite.
            val remote = fetch().getOrElse { return Result.failure(it) }
                ?: return Result.failure(
                    IllegalStateException("server reported a conflict but holds no keyring")
                )
            // The fetched blob is SEALED. It has to be opened before it can be
            // merged - importFromRecovery takes the decoded keyring encoding, and
            // handing it ciphertext makes every conflict retry fail to decode.
            val remotePlain = transport.openFromRecovery(remote.second)
                .getOrElse { return Result.failure(it) }
            try {
                keyring.importFromRecovery(remotePlain, attemptOwner)
                    .getOrElse { return Result.failure(it) }
            } finally {
                com.messenger.app.data.encryption.history.HistoryCrypto.bestEffortWipe(remotePlain)
            }
            expectedVersion = remote.first
            Log.w(TAG, "keyring recovery upload retrying at server version (attempt ${attempt + 1})")
        }
        return Result.failure(
            IllegalStateException("keyring recovery upload could not converge on a version")
        )
    }

    /** What one recovery pass actually did. */
    private data class RecoveryResult(
        val imported: Boolean,
        /**
         * True when the merged keyring is strictly larger than the server's copy,
         * i.e. this device contributed roots the server lacks. Only then is a
         * write-back worth doing.
         */
        val localHadExtraRoots: Boolean,
    )

    @Volatile
    private var lastRecovery: RecoveryResult? = null

    /**
     * Recovers the keyring from the server and merges it into this device's.
     *
     * The intended fresh-install path: the account's MK is already recoverable
     * from the password or recovery key, and this turns MK into the roots needed
     * to open archive ciphertext.
     *
     * Absent blob is not an error - a user who has never uploaded simply has
     * nothing to recover, and the local keyring is left exactly as it was.
     */
    suspend fun recover(): Result<Boolean> = recoverInternal().map { it.imported }

    private suspend fun recoverInternal(): Result<RecoveryResult> {
        if (!feature.isEnabled()) return Result.failure(disabled())
        currentToken() ?: return Result.failure(
            IllegalStateException("no usable session for keyring recovery")
        )
        // The account this attempt belongs to, captured ONCE and never re-read. Everything after
        // this line can suspend, and the signed-in account is mutable, so re-asking later would let
        // a sign-out/sign-in decide where these roots get filed.
        val attemptOwner = currentOwner() ?: return Result.failure(
            IllegalStateException("no signed-in account for keyring recovery")
        )

        val remote = fetch().getOrElse { return Result.failure(it) }
            ?: return Result.success(RecoveryResult(imported = false, localHadExtraRoots = false))
                .also { lastRecovery = it.getOrNull() }

        // Opens under the CURRENT account's MK and the recovery AAD. A blob from
        // another account, or sealed under a pre-reset MK, fails here - ownership
        // is never inferred from the ciphertext.
        val plain = transport.openFromRecovery(remote.second)
            .getOrElse { return Result.failure(it) }

        return try {
            val remoteCount = com.messenger.app.data.encryption.history.HistoryKeyring
                .decode(plain).getOrElse { return Result.failure(it) }.entries.size
            keyring.importFromRecovery(plain, attemptOwner).map { merged ->
                RecoveryResult(
                    imported = true,
                    localHadExtraRoots = merged.entries.size > remoteCount,
                )
            }.also { lastRecovery = it.getOrNull() }
        } finally {
            com.messenger.app.data.encryption.history.HistoryCrypto.bestEffortWipe(plain)
        }
    }

    /** (version, sealedBytes), or null when the account has no stored keyring. */
    private suspend fun fetch(): Result<Pair<Int, ByteArray>?> {
        val token = currentToken() ?: return Result.failure(
            IllegalStateException("no usable session")
        )
        val resp = runCatching { api.getHistoryKeyring(bearer(token)) }
            .getOrElse { return Result.failure(it) }

        if (resp.code() == 404) return Result.success(null)
        if (!resp.isSuccessful) {
            return Result.failure(
                IllegalStateException("keyring recovery fetch failed: HTTP ${resp.code()}")
            )
        }
        // Only 404 means "no recovery object exists". A 2xx that carries nothing
        // usable is CORRUPTION, not absence, and the two must not be conflated:
        // the server rejects empty ciphertext on write, so a degenerate success
        // cannot be a legitimate state. Downgrading it to "nothing stored" would
        // let a faulty or hostile server suppress recovery and then have archives
        // pinned as SEALED against roots this device does not hold.
        val body = resp.body() ?: return Result.failure(
            IllegalStateException("keyring recovery fetch returned success with no body")
        )
        if (body.ciphertextB64.isBlank()) return Result.failure(
            IllegalStateException("keyring recovery blob is empty")
        )

        val bytes = runCatching { Base64.getDecoder().decode(body.ciphertextB64) }
            .getOrElse {
                return Result.failure(IllegalStateException("keyring blob is not valid Base64"))
            }
        if (bytes.isEmpty()) return Result.failure(
            IllegalStateException("keyring recovery blob decoded to zero bytes")
        )
        return Result.success(body.version to bytes)
    }

    private fun disabled() =
        com.messenger.app.data.encryption.history.HistoryArchiveDisabledException()

    private suspend fun currentToken(): String? =
        runCatching { tokenManager.getAccessToken().getOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * The account an attempt belongs to, read once at its start.
     *
     * Deliberately separate from "who is signed in now": the two are the same at the moment an
     * attempt begins and can diverge before it finishes, and only the first is the authority on
     * where that attempt's roots may be written.
     *
     * Read from the keyring repository rather than the token store on purpose - that is the same
     * authority its persistence consults, so the capture and the later check can never disagree
     * about who the account is.
     */
    private suspend fun currentOwner(): String? =
        runCatching { keyring.currentOwner() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun bearer(token: String) =
        if (token.startsWith("Bearer ")) token else "Bearer $token"
}
