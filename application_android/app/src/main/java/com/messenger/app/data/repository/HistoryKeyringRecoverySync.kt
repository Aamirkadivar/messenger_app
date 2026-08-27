package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
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
        if (recoveryAttempted) return Result.success(false)

        return gate.withLock {
            if (recoveryAttempted) return@withLock Result.success(false)
            recoveryAttempted = true
            recoverInternal().map { it.imported }.onSuccess {
                // Converge the server copy ONLY when this device held roots the
                // server does not. A pure import already equals the server copy,
                // so publishing it back would be a pointless GET -> PUT cycle
                // that bumps the version on every fresh install.
                if (lastRecovery?.localHadExtraRoots == true) {
                    upload().onSuccess { uploadedRevision = publishedRevision }
                        .onFailure { Log.w(TAG, "post-recovery convergence deferred: ${it.message}") }
                }
            }.onFailure {
                // Allow a later attempt: a transient failure must not permanently
                // disable recovery for the session.
                recoveryAttempted = false
                Log.w(TAG, "keyring recovery failed: ${it.message}")
            }
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
                keyring.importFromRecovery(remotePlain).getOrElse { return Result.failure(it) }
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
            keyring.importFromRecovery(plain).map { merged ->
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

    private fun bearer(token: String) =
        if (token.startsWith("Bearer ")) token else "Bearer $token"
}
