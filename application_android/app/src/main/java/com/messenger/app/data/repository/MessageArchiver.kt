package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveDisabledException
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryCrypto
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** An archived message: the sealed bytes and the root version needed to reopen them. */
data class SealedArchive(
    val ciphertextB64: String,
    val rootVersion: Int,
)

/**
 * The single place a message becomes an archive record.
 *
 * Both inbound paths - the fetch/refresh path in `ChatRepository.cacheMessages` and the realtime
 * WebSocket path - go through here, so the key selection, context binding, and failure policy exist
 * once rather than being reimplemented per call site.
 *
 * Ordering is fixed and deliberate:
 *
 *     MLS decrypt  ->  archive seal  ->  Room commit
 *
 * Sealing happens after decryption (there is nothing to archive before it) and before the row is
 * committed, so a message is never persisted with an archive that does not match it. The window
 * between decrypt and commit cannot be closed - see the report - because MLS deletes the message
 * key on use and the plaintext exists only in memory until Room accepts it.
 *
 * Nothing MLS-derived is ever archived: the plaintext is the message body, and the key comes from
 * the per-chat history root. No MLS secret, epoch secret, ratchet key, or snapshot is touched.
 */
@Singleton
class MessageArchiver @Inject constructor(
    private val keyring: HistoryKeyringRepository,
    private val cipher: ArchiveCipher,
    private val feature: HistoryArchiveFeature,
) {

    /**
     * Seals one message under the chat's current history root.
     *
     * Returns failure - never a partial or plaintext result - if the keyring is unavailable (a
     * locked vault, a Keystore fault) or if the AEAD refuses. Callers must persist nothing archive
     * related when this fails; the message itself is unaffected.
     */
    suspend fun seal(
        userId: String,
        chatId: String,
        messageId: String,
        plaintext: String,
    ): Result<SealedArchive> {
        // The single flag boundary for all three archive paths - fetch, realtime, and outbound all
        // reach sealing through here, so none of them can drift from the policy.
        //
        // Checked before anything else touches the keyring or the cipher: when archiving is off no
        // root is minted, no rotation is triggered, and no AEAD call is made. The message itself is
        // untouched either way; callers already treat "no archive" as a non-event.
        if (!feature.isEnabled()) return Result.failure(HistoryArchiveDisabledException())

        validate(userId, chatId, messageId)
        val entry = keyring.ensureRoot(chatId).getOrElse { return Result.failure(it) }
        val ctx = HistoryContext(
            userId = userId,
            chatId = chatId,
            messageId = messageId,
            rootVersion = entry.rootVersion,
        )
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        val sealed = try {
            cipher.seal(entry.root, ctx, bytes)
        } finally {
            HistoryCrypto.bestEffortWipe(bytes)
        } ?: return Result.failure(IllegalStateException("archive sealing failed"))

        return Result.success(
            SealedArchive(Base64.getEncoder().encodeToString(sealed), entry.rootVersion)
        )
    }

    /**
     * Reopens an archived message.
     *
     * [rootVersion] must be the one recorded alongside the ciphertext: it is bound into both the
     * key derivation and the AAD, so a different version cannot open the record even on the right
     * device. A device that no longer holds that root version gets a failure, not an empty string.
     */
    suspend fun open(
        userId: String,
        chatId: String,
        messageId: String,
        rootVersion: Int,
        ciphertextB64: String,
    ): Result<String> {
        validate(userId, chatId, messageId)
        val entry = keyring.find(chatId, rootVersion).getOrElse { return Result.failure(it) }
            ?: return Result.failure(
                IllegalStateException(
                    "this device does not hold root version $rootVersion for the chat"
                )
            )
        val sealed = try {
            Base64.getDecoder().decode(ciphertextB64)
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalStateException("archive ciphertext is not valid Base64", e))
        }
        val ctx = HistoryContext(
            userId = userId,
            chatId = chatId,
            messageId = messageId,
            rootVersion = rootVersion,
        )
        val plain = cipher.open(entry.root, ctx, sealed)
            ?: return Result.failure(IllegalStateException("archive failed authentication"))
        return try {
            Result.success(String(plain, Charsets.UTF_8))
        } finally {
            HistoryCrypto.bestEffortWipe(plain)
        }
    }

    private fun validate(userId: String, chatId: String, messageId: String) {
        require(userId.isNotEmpty()) { "userId must not be empty" }
        require(chatId.isNotEmpty()) { "chatId must not be empty" }
        require(messageId.isNotEmpty()) { "messageId must not be empty" }
    }
}
