package com.messenger.app.data.repository

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What one security event actually did. */
data class RotationOutcome(
    /** Chats whose root moved to a new version. */
    val rotated: Set<String> = emptySet(),
    /** Chats whose rotation failed; archiving stays blocked for these. */
    val failed: Set<String> = emptySet(),
    /** True when the event was a duplicate and nothing was attempted. */
    val duplicate: Boolean = false,
) {
    val isClean: Boolean get() = failed.isEmpty()
}

/**
 * Turns a [SecurityEvent] into history-root rotations.
 *
 * The ordering this class guarantees is the whole point:
 *
 *     security event -> barrier raised -> rotation -> new root current
 *
 * The barrier goes up in [HistoryKeyringRepository.markRotationRequired] BEFORE any rotation is
 * attempted, so if rotation then fails - locked vault, Keystore gone, unreadable keyring, storage
 * failure - archiving for that chat stays refused rather than quietly continuing under the retired
 * root. Rotation is never deferred to "the next send"; it happens when the event does.
 *
 * Messaging is unaffected either way. A chat that cannot rotate simply archives nothing until it
 * can; sending and receiving continue exactly as before.
 */
@Singleton
class HistoryRotationCoordinator @Inject constructor(
    private val keyring: HistoryKeyringRepository,
    /** Publishes the rotated keyring. Lazy for the same cycle reason as elsewhere. */
    private val recovery: dagger.Lazy<HistoryKeyringRecoverySync>,
    private val feature: com.messenger.app.data.encryption.history.HistoryArchiveFeature,
) {

    private val mutex = Mutex()

    /**
     * Events already handled in this process, by [SecurityEvent.dedupKey].
     *
     * A repeated callback - a double tap, a recomposition, a retried request - is a duplicate and
     * rotates once. Two different boundaries carry different keys and rotate twice.
     *
     * Process-scoped on purpose: after a restart a repeated event rotates again, which costs one
     * extra version and is strictly the safe direction to err in.
     */
    private val handled = mutableSetOf<String>()

    /**
     * @param allChatIds every locally known chat; consulted only for a device revocation.
     */
    suspend fun onSecurityEvent(
        event: SecurityEvent,
        allChatIds: Collection<String> = emptyList(),
    ): Result<RotationOutcome> = mutex.withLock {
        // A disabled feature has no roots to rotate. Returning a clean empty
        // outcome keeps the three ViewModel call sites unchanged - they treat it
        // exactly as "nothing needed rotating", which is the truth. The repository
        // guards its own mutations as well; this one just avoids the round trip
        // and keeps the dedup set from filling with events that did nothing.
        if (!feature.isEnabled()) return Result.success(RotationOutcome())
        if (!handled.add(event.dedupKey)) {
            return Result.success(RotationOutcome(duplicate = true))
        }

        val chats = HistoryRotationPolicy.affectedChats(event, allChatIds)
        if (chats.isEmpty()) return Result.success(RotationOutcome())

        // Raise every barrier first. If the process dies midway, the chats that were marked stay
        // closed to archiving rather than open under a retired root.
        chats.forEach { keyring.markRotationRequired(it) }

        val rotated = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        for (chatId in chats) {
            keyring.rotate(chatId).fold(
                onSuccess = { rotated.add(chatId) },
                onFailure = {
                    failed.add(chatId)
                    // The identifier is not secret; the reason may be. Log neither root nor key.
                    Log.w(TAG, "history rotation failed for a chat: ${it.message}")
                }
            )
        }

        // Publish the rotated keyring, outside the repository lock. Non-fatal:
        // a rotation that cannot be published is still locally authoritative.
        if (rotated.isNotEmpty()) {
            runCatching { recovery.get().uploadIfChanged() }
                .onFailure { Log.w(TAG, "keyring publish after rotation deferred: ${it.message}") }
        }

        val outcome = RotationOutcome(rotated = rotated, failed = failed)
        if (failed.isNotEmpty()) {
            // Allow a retry of the same event: the boundary has not been honoured yet.
            handled.remove(event.dedupKey)
            return Result.failure(
                HistoryRotationException(
                    "history rotation failed for ${failed.size} chat(s); " +
                        "archiving stays blocked for them until it succeeds",
                    outcome
                )
            )
        }
        Result.success(outcome)
    }

    private companion object {
        const val TAG = "HistoryRotation"
    }
}

/** Carries the partial outcome so a caller can see what did rotate. */
class HistoryRotationException(
    message: String,
    val outcome: RotationOutcome,
) : IllegalStateException(message)
