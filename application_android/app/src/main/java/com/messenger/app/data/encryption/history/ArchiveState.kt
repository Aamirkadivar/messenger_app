package com.messenger.app.data.encryption.history

/**
 * Lifecycle of one message's archive record.
 *
 * Stored as [wire] in `messages.archiveState`. A null column means [NONE] - every row that existed
 * before the v7 -> v8 migration reads that way, which is exactly right: they were never archived.
 *
 * [RETRACTED] is terminal. That is the whole point of having a state column rather than just
 * nulling the ciphertext: once a delete-for-everyone has been honoured locally, nothing may put an
 * archive back on that row. Without the tombstone, a retracted message is indistinguishable from
 * one that was never archived, and any later archiving pass would quietly resurrect it.
 *
 * This is local bookkeeping only. It says nothing about other devices, backups, or copies already
 * made - see the honesty note on retraction in the Gate 3 report.
 */
enum class ArchiveState(val wire: String) {
    /** No archive record. The state of every pre-migration row. */
    NONE("none"),

    /**
     * Plaintext is known but no definitive server messageId exists yet, so it cannot be sealed:
     * messageId is bound into both the key derivation and the AAD.
     */
    PENDING("pending"),

    /** Archive ciphertext is present and openable with the recorded root version. */
    SEALED("sealed"),

    /** Archive was removed after a delete-for-everyone. Terminal. */
    RETRACTED("retracted"),
    ;

    companion object {
        /** Unknown or absent values read as [NONE]; this never throws on stored data. */
        fun fromWire(value: String?): ArchiveState =
            entries.firstOrNull { it.wire == value } ?: NONE

        /**
         * Whether a row may move from [from] to [to].
         *
         * Self-transitions are allowed so re-running an archive pass is idempotent. Everything out
         * of [RETRACTED] is refused.
         */
        fun isValidTransition(from: ArchiveState, to: ArchiveState): Boolean = when {
            from == to -> true
            from == RETRACTED -> false
            to == RETRACTED -> true
            from == NONE -> to == PENDING || to == SEALED
            from == PENDING -> to == SEALED
            else -> false
        }
    }
}
