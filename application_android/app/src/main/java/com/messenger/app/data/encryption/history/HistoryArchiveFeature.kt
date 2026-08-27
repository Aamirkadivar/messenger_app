package com.messenger.app.data.encryption.history

/**
 * Whether new messages are archived into the Layer B history store.
 *
 * Follows the existing flag convention in this codebase (`MlsV2Repository.ENABLED`): a compile-time
 * constant with a documented default, flipped as a deliberate per-build act rather than at runtime.
 * It is expressed as an interface only so JVM tests can exercise both states without a second
 * settings mechanism - production has exactly one binding, [Default].
 *
 * The flag governs SEALING ONLY. Opening an existing archive is deliberately never gated: turning
 * archiving off must not make already-archived history unreadable, and it must not disturb the
 * keyring, rotation, or retraction machinery, all of which stay live either way.
 */
fun interface HistoryArchiveFeature {

    fun isEnabled(): Boolean

    companion object {
        /**
         * OFF by default.
         *
         * The archive layer is complete and tested, but nothing has yet run it against real
         * traffic, and the flag is what keeps that decision explicit. Flipping this is a deliberate
         * per-build act - the same posture `MlsV2Repository.ENABLED` takes.
         */
        const val DEFAULT_ENABLED = false

        /** Production binding. */
        val Default = HistoryArchiveFeature { DEFAULT_ENABLED }

        /** For tests that need the enabled path; never bound in production. */
        val Enabled = HistoryArchiveFeature { true }
    }
}

/**
 * Returned when archiving is switched off.
 *
 * A distinct type so callers can tell "disabled" apart from "tried and failed" - both leave the
 * message untouched, but only the latter indicates something is wrong.
 */
class HistoryArchiveDisabledException :
    IllegalStateException("history archiving is disabled by feature flag")
