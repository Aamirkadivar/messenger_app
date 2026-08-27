package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveState

/**
 * Decides whether a successfully sent message gets archived, and with what.
 *
 * Split out of `ChatRepository.sendMessage` so the rules are testable without standing up Retrofit
 * and Room. It holds no crypto: sealing is delegated to the [MessageArchiver] use-case through the
 * [seal] function, exactly as the inbound paths do.
 *
 * The rules, in order:
 *
 *  - no owning user, no message id, or nothing to archive  -> leave the row as it is;
 *  - the row already has an archive (or a retraction)       -> leave it alone, never re-seal;
 *  - sealing fails for any reason                           -> leave the row as it is.
 *
 * Every one of those outcomes returns [prior] unchanged, so a failure can never write plaintext
 * into the archive columns and can never fail the send. Archiving is strictly best-effort: the
 * message has already reached the server by the time this runs, and losing the archive is far
 * better than losing the message.
 */
internal object OutboundArchivePolicy {

    /** The three archive columns of `messages`, moved around as one value. */
    data class Fields(
        val ciphertext: String? = null,
        val rootVersion: Int = 0,
        val state: String? = null,
    ) {
        companion object {
            val NONE = Fields()
        }
    }

    /**
     * @param userId owning user; the archive AAD binds it, so a blank one means no archive.
     * @param messageId the AUTHORITATIVE server id. Never a locally invented one - it is bound into
     *   both the key derivation and the AAD, so a synthetic id would produce an archive that the
     *   real message could never open.
     * @param prior whatever the row already carries.
     * @param seal delegates to `MessageArchiver.seal`; returns null when it could not seal.
     */
    suspend fun archiveFor(
        userId: String?,
        chatId: String,
        messageId: String,
        plaintext: String,
        prior: Fields = Fields.NONE,
        placeholder: String,
        seal: suspend (userId: String, chatId: String, messageId: String, plaintext: String) -> SealedArchive?,
    ): Fields {
        if (userId.isNullOrBlank()) return prior
        if (chatId.isBlank() || messageId.isBlank()) return prior
        if (plaintext.isBlank() || plaintext == placeholder) return prior

        // Idempotent by state, not by content.
        //
        // SEALED is left alone so a repeat pass cannot burn work or, worse, re-seal under a rotated
        // root and orphan the recorded rootVersion. RETRACTED is left alone because re-sealing
        // would resurrect a message the user deleted for everyone.
        //
        // NONE and PENDING both proceed: PENDING means "plaintext known, waiting for a definitive
        // id", and this is precisely where that id arrives. Skipping it would strand the row.
        when (ArchiveState.fromWire(prior.state)) {
            ArchiveState.SEALED, ArchiveState.RETRACTED -> return prior
            ArchiveState.NONE, ArchiveState.PENDING -> Unit
        }

        val sealed = runCatching { seal(userId, chatId, messageId, plaintext) }.getOrNull()
            ?: return prior

        return Fields(
            ciphertext = sealed.ciphertextB64,
            rootVersion = sealed.rootVersion,
            state = ArchiveState.SEALED.wire,
        )
    }
}
