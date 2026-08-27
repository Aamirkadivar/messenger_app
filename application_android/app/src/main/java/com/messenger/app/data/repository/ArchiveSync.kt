package com.messenger.app.data.repository

import android.util.Log
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.model.ArchiveUploadRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One validated archive received from the server, ready to be written locally.
 *
 * Deliberately a separate type from the wire DTO: everything the server sends is
 * untrusted, and only records that survive [ArchiveSync.validate] become one of
 * these. A caller holding this type knows the fields are well-formed - it does
 * NOT know the ciphertext is genuine, which only opening it can establish.
 */
data class RemoteArchive(
    val messageId: String,
    val chatId: String,
    val rootVersion: Int,
    val protocolVersion: Int,
    val ciphertextB64: String,
    val createdAt: String,
)

/**
 * Moves already-sealed archives between this device and the server.
 *
 * This class performs NO cryptography. Sealing stays in [MessageArchiver] and
 * the AEAD stays in HistoryMessageCipher; this only carries opaque bytes. It
 * therefore cannot upload a root, a keyring, MK, sessionMk, an MLS snapshot, a
 * ratchet or a sender key - it never holds any of them.
 *
 * Everything is best effort with respect to messaging: an upload or download
 * failure leaves the message, its content, and its local archive exactly as they
 * were. A message must never fail to send or display because history sync did.
 */
@Singleton
class ArchiveSync @Inject constructor(
    private val archiver: MessageArchiver,
    private val api: ChatApiService,
    private val tokenManager: TokenManager,
    private val feature: HistoryArchiveFeature,
    /**
     * Keyring recovery. Lazily resolved because it reaches the vault, which
     * reaches ChatRepository, which owns this class's lambda seam.
     */
    private val recovery: dagger.Lazy<HistoryKeyringRecoverySync>,
) {

    private companion object {
        const val TAG = "ArchiveSync"
        /** Matches the server's page cap; bounds the cursor loop. */
        const val PAGE_SIZE = 500
        /**
         * Safety stop so a misbehaving or looping server cannot spin forever.
         * Reaching it is NOT treated as a completed sync - see [ArchivePage].
         */
        const val MAX_PAGES = 10_000
    }

    /**
     * Seals a message locally, then uploads it.
     *
     * Returns the SEALED archive whether or not the upload succeeded, so the
     * caller still records it locally: a local archive that failed to upload is
     * exactly what a later refresh should retry, and discarding it would lose
     * history the device can still read. Upload failure is logged, never thrown.
     *
     * The flag is enforced inside [MessageArchiver.seal]; if archiving is off,
     * sealing fails and nothing is uploaded.
     */
    suspend fun sealAndUpload(
        userId: String,
        chatId: String,
        messageId: String,
        plaintext: String,
    ): SealedArchive? {
        val sealed = archiver.seal(userId, chatId, messageId, plaintext).getOrNull() ?: return null
        runCatching { upload(messageId, sealed) }
            .onFailure { Log.w(TAG, "archive upload deferred: ${it.message}") }
        // Sealing may have minted this chat's first root. Publishing happens here,
        // AFTER the keyring mutation returned and its lock was released, and is a
        // no-op unless the keyring actually changed.
        runCatching { recovery.get().uploadIfChanged() }
            .onFailure { Log.w(TAG, "keyring publish deferred: ${it.message}") }
        return sealed
    }

    /**
     * Uploads one already-sealed archive.
     *
     * Identity is the AUTHORITATIVE server message id the caller was given; no
     * archive identity is ever invented here, and a failed upload is retried
     * later under the same id rather than a new one.
     *
     * `stored = false` is success, not failure: it means the server already had
     * an archive for this (message, user) and left it untouched, which is the
     * immutability guarantee working as intended.
     */
    private suspend fun upload(messageId: String, sealed: SealedArchive): Boolean {
        if (!feature.isEnabled()) return false
        val token = currentToken() ?: return false
        val resp = api.uploadArchive(
            bearer(token),
            ArchiveUploadRequest(
                messageId = messageId,
                rootVersion = sealed.rootVersion,
                protocolVersion = HistoryArchiveProtocolVersion,
                ciphertextB64 = sealed.ciphertextB64,
            )
        )
        if (!resp.isSuccessful) {
            Log.w(TAG, "archive upload rejected: HTTP ${resp.code()}")
            return false
        }
        return true
    }

    /**
     * Downloads this user's archives for [chatId], walking the server's exclusive
     * `since` cursor until the pages run out.
     *
     * The server scopes every response to the authenticated user, so a record for
     * anyone else cannot be returned; the caller additionally refuses records
     * whose chat does not match, which is what keeps a compromised or buggy
     * server from binding an archive to the wrong conversation.
     *
     * No cursor is persisted - that would need a schema change, which Phase 2 is
     * not allowed to make - so this walks from the beginning each time. Applying
     * the result is idempotent, so repeating it is cheap and safe.
     */
    suspend fun downloadFor(chatId: String): ArchivePage {
        if (!feature.isEnabled()) return ArchivePage.disabled()
        if (chatId.isBlank()) return ArchivePage.disabled()

        // ORDERING: recover the keyring before any archive is fetched. An archive
        // applied before its root exists would be stored SEALED and then be
        // unopenable, and the apply step deliberately never overwrites a SEALED
        // row - so the damage would be permanent. One-shot per session.
        // Fail closed on BOTH shapes of failure. A returned Result.failure and a
        // thrown exception mean the same thing here - recovery is unresolved -
        // but `runCatching{}.getOrNull()` collapses a throw to null, which used
        // to read as "no failure" and let the download proceed. Flattening the
        // throw into the Result removes that asymmetry.
        //
        // What is actually at stake: applyRemoteArchives pins a server record
        // into a SEALED row and never overwrites a row that is not NONE. The
        // ciphertext itself stays openable once the right root arrives, so the
        // hazard is not "unopenable forever" - it is FIRST-WRITER-WINS. A wrong
        // or hostile record accepted now can never be replaced by the correct one.
        val recovered = runCatching { recovery.get().ensureRecovered() }
            .getOrElse { Result.failure(it) }
        if (recovered.isFailure) {
            // A cryptographic or conflicting-root failure must not be papered
            // over by downloading ciphertext we may not be able to open.
            Log.w(TAG, "skipping archive download: keyring recovery did not succeed")
            return ArchivePage(emptyList(), complete = false)
        }

        val token = currentToken() ?: return ArchivePage(emptyList(), complete = false)

        val collected = mutableListOf<RemoteArchive>()
        var since: String? = null
        var sinceId: String? = null

        repeat(MAX_PAGES) {
            val page = runCatching { api.listArchives(bearer(token), chatId, since, sinceId) }
                .getOrNull() ?: return ArchivePage(collected, complete = false)
            if (!page.isSuccessful) {
                // 404 here means "not a member of that chat" - the server's
                // deliberate non-oracle response. Nothing to sync either way.
                Log.w(TAG, "archive download stopped: HTTP ${page.code()}")
                return ArchivePage(collected, complete = false)
            }
            val body = page.body() ?: return ArchivePage(collected, complete = false)

            // A short page is the definitive end of the sequence.
            if (body.archives.size < PAGE_SIZE) {
                body.archives.mapNotNull { validate(it, chatId) }.forEach(collected::add)
                return ArchivePage(collected, complete = true)
            }

            body.archives.mapNotNull { validate(it, chatId) }.forEach(collected::add)

            // Advance BOTH cursor components. created_at alone is not unique, so
            // a timestamp-only cursor skips rows that share a timestamp across a
            // page boundary - which is silent, permanent data loss rather than
            // mere inefficiency.
            val last = body.archives.last()
            if (last.createdAt.isBlank() || last.messageId.isBlank()) {
                // Cannot form a cursor; stopping is right, but the sync is NOT
                // complete and must not be reported as such.
                Log.w(TAG, "archive download stopped: server row carries no usable cursor")
                return ArchivePage(collected, complete = false)
            }
            if (last.createdAt == since && last.messageId == sinceId) {
                // The server did not advance. Refuse to request the same page
                // forever, and do not claim completeness.
                Log.w(TAG, "archive download stopped: cursor did not advance")
                return ArchivePage(collected, complete = false)
            }
            since = last.createdAt
            sinceId = last.messageId
        }
        // Safety bound reached. Explicitly incomplete - never silently truncated.
        Log.w(TAG, "archive download hit the page safety bound; sync is incomplete")
        return ArchivePage(collected, complete = false)
    }

    /**
     * Rejects anything malformed before it can reach Room, via [ArchiveValidation].
     *
     * A rejected record is skipped individually: one bad row must not discard a
     * whole page, and must not affect the message it refers to.
     */
    internal fun validate(dto: com.messenger.app.data.model.ArchiveDto, expectedChatId: String) =
        ArchiveValidation.validate(dto, expectedChatId)

    /**
     * The access token, or null if there isn't a usable one.
     *
     * Wrapped because nothing on the archive path may throw into messaging: this
     * runs inside the message fetch, and a token-layer failure must cost the
     * archive sync, never the messages.
     */
    private suspend fun currentToken(): String? =
        runCatching { tokenManager.getAccessToken().getOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun bearer(token: String) =
        if (token.startsWith("Bearer ")) token else "Bearer $token"
}

/**
 * One download pass.
 *
 * [complete] is the explicit continuation signal: true only when the server
 * definitively reached the end of the sequence. A network failure, a refused
 * request, an unusable cursor, or the page safety bound all yield false, so a
 * caller can never mistake a truncated sync for a finished one. Whatever was
 * collected is still returned and still safe to apply.
 */
data class ArchivePage(
    val records: List<RemoteArchive>,
    val complete: Boolean,
) {
    companion object {
        /** Archiving is off, or there is nothing to sync. Vacuously complete. */
        fun disabled() = ArchivePage(emptyList(), complete = true)
    }
}

/** Wire protocol version for archive records. Mirrors the server default. */
const val HistoryArchiveProtocolVersion = 1
