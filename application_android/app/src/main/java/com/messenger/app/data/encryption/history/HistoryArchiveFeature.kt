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
         * ON.
         *
         * Why this layer exists: an MLS sender-ratchet secret is consumed by the first
         * successful decrypt, so a message whose local plaintext is lost can never be reopened
         * from its own ciphertext. The archive is the only second copy, and it is keyed
         * independently of MLS - a per-chat root from the keyring, never any MLS state.
         *
         * Turned on after the WRITE half was exercised against real Windows <-> Android traffic:
         * root minted and published, message sealed, archive uploaded, and the backend verified to
         * hold nothing but opaque ciphertext.
         *
         * KNOWN GAP, stated so nobody mistakes this for a working restore: `MessageArchiver.open`
         * has no production caller. Archives are produced and stored, and the download path
         * attaches them to their rows, but no code opens one to put plaintext back. Until that is
         * wired the archive is a durable second copy that nothing consumes. Sealing is still worth
         * having on now - a copy that does not exist can never be restored later.
         *
         * Requires an UNLOCKED vault: the keyring root is sealed under the session master key, so a
         * device that logs in and restarts without unlocking archives nothing, silently.
         *
         * Flipping this is a deliberate per-build act - the same posture
         * `MlsV2Repository.ENABLED` takes.
         */
        const val DEFAULT_ENABLED = true

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
