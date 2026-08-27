package com.messenger.app.data.encryption.history

/**
 * Seals and opens the history keyring under the vault's session master key.
 *
 * Implemented by E2EEVaultRepository. The master key never crosses this boundary in either
 * direction - callers hand over plaintext and receive ciphertext, and never obtain MK itself.
 * There is deliberately no `getMasterKey()` here and there must never be one.
 *
 * Both operations MUST fail closed. Returning a failure when the vault is locked is correct;
 * returning the plaintext unsealed, or an empty keyring, is not.
 */
interface HistoryKeyringVault {
    suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray>
    suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray>
}

/**
 * Local persistence for the history keyring, in two copies with different jobs.
 *
 *  - **Authoritative** (`saveHistoryKeyring`/`loadHistoryKeyring`): the MK-sealed keyring. This is
 *    the recovery copy and the source of truth. Reading it requires an unlocked vault.
 *  - **Cache** (`saveHistoryKeyringCache`/`loadHistoryKeyringCache`): the keyring's plain encoding
 *    protected by Android Keystore alone. This exists so an ordinary restart can read archived
 *    history without a password prompt, which the authoritative copy cannot do.
 *
 * Both live in the same SharedPreferences file as the rest of the secure store but go through a
 * dedicated FAIL-CLOSED path. Unlike `saveMlsBundle`, which does
 *
 *     wrapSecret(json).getOrElse { json }
 *
 * and therefore writes plaintext when the Keystore is unavailable, these operations return failure
 * instead of persisting anything. That legacy MLS fallback exists for migration and is deliberately
 * left untouched; history roots must never take it.
 *
 * Every read distinguishes three outcomes, and the distinction is load-bearing:
 *
 *     nothing stored        -> success(null)     a device that has no keyring yet
 *     stored and readable   -> success(bytes)
 *     stored but unreadable -> failure           NEVER success(null)
 *
 * Collapsing the third case into the first would let a transient Keystore fault look like a fresh
 * device, and the caller would mint a new root - orphaning every message already archived under the
 * old one. This is the same failure shape that stranded EMU-A at epoch 8.
 *
 * SECURITY NOTE on the cache: it holds root material protected only by the Keystore, not by MK.
 * That is the explicit cost of cold-start access. It is never written unprotected, and a device
 * whose Keystore is compromised loses the roots it holds regardless of which copy is stored.
 */
interface HistoryKeyringStore {
    suspend fun saveHistoryKeyring(sealed: ByteArray): Result<Unit>
    suspend fun loadHistoryKeyring(): Result<ByteArray?>
    suspend fun deleteHistoryKeyring(): Result<Unit>

    suspend fun saveHistoryKeyringCache(plain: ByteArray): Result<Unit>
    suspend fun loadHistoryKeyringCache(): Result<ByteArray?>
    suspend fun deleteHistoryKeyringCache(): Result<Unit>

    /**
     * The keyring generation marker.
     *
     * A third slot, deliberately. The authoritative copy and the cache are two
     * separate durable writes, so a process death between them can leave the
     * cache silently older than the authoritative keyring - and because the cache
     * is what a cold start reads FIRST (it is the only copy readable without MK),
     * a stale one would shadow the newer truth indefinitely.
     *
     * The marker is what makes that divergence detectable without needing MK:
     * it is bumped BEFORE the authoritative write and stamped into the cache only
     * AFTER both writes have landed, so a cache whose stamp does not equal the
     * marker is provably not known to match the authoritative copy.
     */
    suspend fun saveHistoryKeyringGeneration(generation: Long): Result<Unit>
    suspend fun loadHistoryKeyringGeneration(): Result<Long?>
    suspend fun deleteHistoryKeyringGeneration(): Result<Unit>
}
