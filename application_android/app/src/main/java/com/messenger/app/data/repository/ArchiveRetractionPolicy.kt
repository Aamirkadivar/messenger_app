package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveState

/**
 * Decides what happens to a local archive when its message is deleted.
 *
 * Split out of `ChatRepository` so the rules are testable without Room. The rules are short but the
 * consequences are not: getting them wrong either leaves recoverable ciphertext for a message the
 * user deleted, or lets a later sync put an archive back on a retracted row.
 *
 * IMPORTANT SCOPE NOTE. This is LOCAL retraction only. It removes this device's archive of a
 * message. It does not and cannot erase archives on other devices, on devices that were offline
 * when the deletion happened, in device backups, or anywhere the plaintext was already copied. The
 * current server architecture provides no mechanism to reach those, and nothing here should be
 * described as global deletion.
 */
internal object ArchiveRetractionPolicy {

    /**
     * @param write false when the row is already terminal and must not be touched again.
     * @param newState always [ArchiveState.RETRACTED] when [write] is true.
     * @param clearCiphertext whether the archive bytes should be dropped.
     */
    data class Plan(
        val write: Boolean,
        val newState: ArchiveState = ArchiveState.RETRACTED,
        val clearCiphertext: Boolean = true,
    )

    private val NO_OP = Plan(write = false)

    /**
     * Retraction is terminal and idempotent.
     *
     * A row in any non-terminal state - including one that was never archived - becomes RETRACTED.
     * Marking a never-archived row is deliberate rather than wasteful: it is the tombstone that
     * stops [OutboundArchivePolicy] and the inbound paths from archiving the message if it is seen
     * again, which is what "a deletion cannot be undone by a refresh" actually means in practice.
     *
     * A row that is already RETRACTED is left completely alone, so a duplicate delete notification
     * - a socket replay, a reconnect, the same event seen twice - changes nothing.
     */
    fun plan(current: ArchiveState): Plan =
        if (current == ArchiveState.RETRACTED) NO_OP else Plan(write = true)

    /** Convenience for callers holding the raw column value. */
    fun plan(currentWire: String?): Plan = plan(ArchiveState.fromWire(currentWire))
}
