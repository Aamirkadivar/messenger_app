package com.messenger.app.data.encryption.history

/**
 * Seals and opens the history keyring for SERVER-SIDE recovery storage.
 *
 * Separate from [HistoryKeyringVault] on purpose. That port protects the
 * device-local authoritative copy under `historyKeyringAad(userId, …)`; this one
 * protects a blob that leaves the device, under its own AAD domain
 * (`history_keyring_recovery_v1|userId`). Distinct domains mean a recovery blob
 * can never be opened as a local keyring, a vault body, or an archive record,
 * and vice versa - a confusion that would otherwise be invisible.
 *
 * Implemented by E2EEVaultRepository so MK never crosses this boundary. Callers
 * hand over plaintext and receive ciphertext; there is no accessor returning MK
 * and there must never be one.
 *
 * Both operations fail closed: a locked vault or a missing identity is a failure,
 * never an empty or unsealed result.
 */
interface HistoryKeyringRecoveryTransport {
    suspend fun sealForRecovery(plaintext: ByteArray): Result<ByteArray>
    suspend fun openFromRecovery(sealed: ByteArray): Result<ByteArray>
}
