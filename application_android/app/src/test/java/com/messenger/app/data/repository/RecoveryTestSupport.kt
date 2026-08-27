package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import io.mockk.mockk

/**
 * Shared fixtures for tests that must construct classes taking a
 * `dagger.Lazy<HistoryKeyringRecoverySync>`.
 *
 * A no-op recovery keeps tests that are not about recovery focused on what they
 * do test: the flag is OFF, so `uploadIfChanged` and `ensureRecovered` return
 * immediately without touching the network, the keyring, or the transport.
 */
object RecoveryTestSupport {

    private class NoTransport : HistoryKeyringRecoveryTransport {
        override suspend fun sealForRecovery(plaintext: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("no recovery in this test"))
        override suspend fun openFromRecovery(sealed: ByteArray): Result<ByteArray> =
            Result.failure(IllegalStateException("no recovery in this test"))
    }

    /** A disabled recovery sync - safe to inject anywhere recovery is irrelevant. */
    fun disabled(keyring: HistoryKeyringRepository): HistoryKeyringRecoverySync =
        HistoryKeyringRecoverySync(
            keyring,
            NoTransport(),
            mockk<ChatApiService>(relaxed = true),
            mockk<TokenManager>(relaxed = true),
            HistoryArchiveFeature { false },
        )

    /** `dagger.Lazy` is a single-method interface, so a SAM lambda is enough. */
    fun lazyOf(sync: HistoryKeyringRecoverySync): dagger.Lazy<HistoryKeyringRecoverySync> =
        dagger.Lazy { sync }

    fun disabledLazy(keyring: HistoryKeyringRepository): dagger.Lazy<HistoryKeyringRecoverySync> =
        lazyOf(disabled(keyring))
}
