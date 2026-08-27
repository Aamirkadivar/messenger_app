package com.messenger.app.data.repository

import android.os.Build
import android.util.Log
import com.messenger.app.data.encryption.VaultCrypto
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.model.ChangePasswordRequest
import com.messenger.app.data.model.E2EEDeviceDto
import com.messenger.app.data.model.E2EEDeviceRegisterRequest
import com.messenger.app.data.model.E2EEPairingCompleteRequest
import com.messenger.app.data.model.E2EEPairingCreateRequest
import com.messenger.app.data.model.E2EEVaultDto
import com.messenger.app.data.model.E2EEVaultPutRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates / unlocks the password-wrapped E2EE vault after login so a new
 * device can restore identity keys instead of overwriting them.
 */
@Singleton
class E2EEVaultRepository @Inject constructor(
    private val chatApiService: ChatApiService,
    private val tokenManager: TokenManager,
    private val chatRepository: ChatRepository,
    private val webSocketManager: WebSocketManager,
    /**
     * History keyring recovery, triggered as soon as MK becomes available.
     *
     * dagger.Lazy because this class IS the recovery transport - resolving it
     * eagerly would close the cycle. Lazy defers it to first use, by which point
     * the graph is built.
     */
    private val historyRecovery: dagger.Lazy<HistoryKeyringRecoverySync>,
    /**
     * The local keyring, needed only so an intentional reset can discard the
     * generation the retired master key sealed. Lazy for the same cycle reason.
     */
    private val historyKeyring: dagger.Lazy<HistoryKeyringRepository>
) : HistoryKeyringVault, HistoryKeyringRecoveryTransport {
    companion object {
        private const val TAG = "E2EEVaultRepository"
        private const val VAULT_REFRESH_DEBOUNCE_MS = 2_000L
        private const val VAULT_REFRESH_MAX_RETRIES = 3
        private const val VAULT_PULL_DEBOUNCE_MS = 2_000L

        /**
         * AAD domain for the sealed history keyring. Follows the existing
         * `masterKeyAad`/`vaultAad` convention in VaultCrypto, with its own label so a keyring
         * ciphertext can never be opened as a vault body or vice versa.
         */
        internal fun historyKeyringAad(userId: String, formatVersion: Int): ByteArray =
            "history-keyring|$userId|$formatVersion|${VaultCrypto.SUITE_VAULT_AEAD}"
                .toByteArray(Charsets.UTF_8)

        private const val HISTORY_KEYRING_AAD_VERSION = 1

        /**
         * AAD domain for the SERVER-STORED recovery blob.
         *
         * Deliberately distinct from [historyKeyringAad]: the local copy and the
         * recovery copy protect the same plaintext but live in different places
         * with different exposure, and a shared domain would let one be
         * substituted for the other. Also distinct from `vaultAad`, so a vault
         * body and a keyring can never be confused even though both are sealed
         * under MK.
         */
        internal fun historyKeyringRecoveryAad(userId: String): ByteArray =
            "history_keyring_recovery_v1|$userId|${VaultCrypto.SUITE_VAULT_AEAD}"
                .toByteArray(Charsets.UTF_8)
    }

    private val mutex = Mutex()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingRefresh: Job? = null

    /** Handle for the background recovery so a reset can stop it before tearing down. */
    private var pendingHistoryRecovery: Job? = null

    /**
     * A post-commit reset outcome that no UI has consumed yet.
     *
     * Held on the singleton repository rather than the screen-scoped ViewModel so
     * that navigating away mid-reset cannot discard the newly minted recovery key.
     */
    private val _pendingResetOutcome =
        kotlinx.coroutines.flow.MutableStateFlow<E2EEResetResult?>(null)
    val pendingResetOutcome: kotlinx.coroutines.flow.StateFlow<E2EEResetResult?> =
        _pendingResetOutcome.asStateFlow()

    /** The account [_pendingResetOutcome] belongs to. */
    @Volatile private var pendingResetOutcomeOwner: String? = null

    /**
     * The parked outcome, but only if it belongs to [userId].
     *
     * The repository is a singleton and outlives a sign-out, so an outcome that
     * was never consumed would otherwise be adopted by whichever account happens
     * to open Settings next - handing one user another user's vault recovery key.
     * Ownership is checked rather than assumed, so the guarantee does not depend
     * on the logout hook having run.
     */
    fun pendingResetOutcomeFor(userId: String?): E2EEResetResult? {
        val owner = pendingResetOutcomeOwner ?: return null
        if (userId.isNullOrBlank() || userId != owner) return null
        return _pendingResetOutcome.value
    }

    /** Called once the outcome has actually been shown. */
    fun acknowledgeResetOutcome() {
        _pendingResetOutcome.value = null
        pendingResetOutcomeOwner = null
    }
    @Volatile private var lastPullAtMs: Long = 0

    init {
        refreshScope.launch {
            webSocketManager.connectionState.collect { state ->
                if (state != WebSocketManager.ConnectionState.CONNECTED) return@collect
                val token = tokenManager.getAccessToken().getOrNull() ?: return@collect
                pullAndMergeVault(token, force = true)
                // Re-assert this install in the device registry on every
                // connect. Registration used to happen only on vault
                // unlock/create/pairing, so a restored session with a fresh
                // device id stayed unregistered — and the FN1 fan-out never
                // included it, making every message (own sends included)
                // undecryptable on this device.
                registerDevice(token)
            }
        }
    }

    /** Session MK after unlock/create — needed to refresh vault ciphertext. */
    @Volatile private var sessionMk: ByteArray? = null

    /**
     * Whether [sessionMk] is known to be the key the SERVER holds.
     *
     * Every ordinary way of obtaining a master key proves this on the spot: a
     * password or recovery-key unlock opens the server's own vault ciphertext,
     * and pairing transports a key that already did. One path does not - a reset
     * whose commit could not be confirmed activates a CANDIDATE key that may
     * exist nowhere but this device.
     *
     * The distinction matters only for writing. Sealing durable state under a
     * candidate key produces material the account's real key cannot open, and
     * because the plaintext cache answers reads first, the damage stays invisible
     * until the cache is dropped - at which point the keyring is unopenable for
     * good. So candidate keys may be held and displayed, but never sealed under.
     */
    @Volatile private var sessionMkVerified: Boolean = false
    @Volatile private var sessionVaultVersion: Int = 0
    @Volatile private var sessionWraps: SessionWraps? = null

    /** In-flight device pairing (new device side). */
    @Volatile private var pairingSessionId: String? = null
    @Volatile private var pairingEphPriv: ByteArray? = null

    private data class SessionWraps(
        val pwKdf: String,
        val pwSaltB64: String,
        val pwParams: String,
        val pwWrappedMasterB64: String,
        val rkKdf: String,
        val rkSaltB64: String,
        val rkWrappedMasterB64: String
    )

    sealed class VaultSyncResult {
        data object Unlocked : VaultSyncResult()
        /** First vault upload; [recoveryKeyDisplay] must be shown once to the user. */
        data class Created(val recoveryKeyDisplay: String?) : VaultSyncResult()
        /**
         * Password wrap failed (e.g. account password was reset). Caller should
         * prompt for the recovery key; auth tokens are already valid.
         */
        data class NeedsRecovery(val message: String) : VaultSyncResult()
        data class Failed(val message: String) : VaultSyncResult()
    }

    private fun bearer(token: String) =
        if (token.startsWith("Bearer ")) token else "Bearer $token"

    fun clearSessionSecrets() {
        // An unconsumed reset outcome carries a recovery key. It must not survive
        // into another account's session - the ownership check below is the
        // structural guarantee, this is the timely one.
        acknowledgeResetOutcome()
        runCatching { historyRecovery.get().resetSession() }
        pendingRefresh?.cancel()
        pendingRefresh = null
        sessionMk = null
        sessionMkVerified = false
        sessionVaultVersion = 0
        sessionWraps = null
        pairingSessionId = null
        pairingEphPriv = null
    }

    fun hasSessionMk(): Boolean = sessionMk != null

    // ---------------------------------------------------------------- HistoryKeyringVault
    //
    // Seals and opens the Layer B history keyring under the session master key. MK does not cross
    // this boundary: callers pass plaintext and receive ciphertext. There is no accessor that
    // returns MK to application code, and adding one would defeat the point of routing history
    // sealing through this class at all.
    //
    // The keyring is sealed with MK directly plus a dedicated AAD, matching how the vault body is
    // already sealed (`sealXChaCha(mk, plain, vaultAad(...))`). No new primitive is introduced.

    /**
     * Same provenance rule as [sealHistoryKeyring]: a candidate key must not put
     * material on the server either, or another device would inherit a blob it
     * can never open.
     */
    override suspend fun sealForRecovery(plaintext: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val mk = sessionMk
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be sealed for recovery: vault is locked")
                )
            if (!sessionMkVerified) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "history keyring cannot be sealed for recovery: this device is holding " +
                            "a candidate master key whose commit was never confirmed"
                    )
                )
            }
            val userId = tokenManager.getCurrentUserId().getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be sealed for recovery: no current user")
                )
            val sealed = VaultCrypto.sealXChaCha(mk, plaintext, historyKeyringRecoveryAad(userId))
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring recovery sealing failed")
                )
            Result.success(sealed)
        }

    /**
     * Opens a recovery blob under the CURRENT account's MK.
     *
     * A blob belonging to another account, or sealed under a previous MK (an
     * E2EE reset mints a fresh one), fails authentication here. That is the
     * intended outcome: ownership is never inferred from ciphertext, and a
     * pre-reset keyring is not meant to be recoverable under the new identity.
     */
    override suspend fun openFromRecovery(sealed: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val mk = sessionMk
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be opened: vault is locked")
                )
            val userId = tokenManager.getCurrentUserId().getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be opened: no current user")
                )
            val plain = VaultCrypto.openXChaCha(mk, sealed, historyKeyringRecoveryAad(userId))
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring recovery blob failed authentication")
                )
            Result.success(plain)
        }

    override suspend fun sealHistoryKeyring(plaintext: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val mk = sessionMk
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be sealed: vault is locked")
                )
            if (!sessionMkVerified) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "history keyring cannot be sealed: this device is holding a candidate " +
                            "master key whose commit was never confirmed. Sealing under it would " +
                            "produce state the account's real key cannot open."
                    )
                )
            }
            val userId = tokenManager.getCurrentUserId().getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be sealed: no current user")
                )
            val sealed = VaultCrypto.sealXChaCha(
                mk, plaintext, historyKeyringAad(userId, HISTORY_KEYRING_AAD_VERSION)
            ) ?: return@withContext Result.failure(
                IllegalStateException("history keyring sealing failed")
            )
            Result.success(sealed)
        }

    /**
     * Opens a sealed keyring. Any failure - locked vault, wrong user, tampered ciphertext - is
     * reported as failure. It never returns the input, and never an empty keyring.
     */
    override suspend fun openHistoryKeyring(sealed: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val mk = sessionMk
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be opened: vault is locked")
                )
            val userId = tokenManager.getCurrentUserId().getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("history keyring cannot be opened: no current user")
                )
            val plain = VaultCrypto.openXChaCha(
                mk, sealed, historyKeyringAad(userId, HISTORY_KEYRING_AAD_VERSION)
            ) ?: return@withContext Result.failure(
                IllegalStateException("history keyring failed authentication")
            )
            Result.success(plain)
        }

    /**
     * New device: create a short-lived pairing session and return the code to
     * show (QR-compatible string). Old device pastes it under Settings → Link.
     */
    suspend fun startDevicePairing(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kp = VaultCrypto.generateX25519KeyPair()
                ?: return@withContext Result.failure(Exception("Keygen failed"))
            val deviceId = tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            val pubHex = kp.publicKey.joinToString("") { "%02x".format(it) }
            val resp = chatApiService.createE2EEPairing(
                bearer(token),
                E2EEPairingCreateRequest(pubHex, deviceId)
            )
            if (!resp.isSuccessful || resp.body() == null) {
                return@withContext Result.failure(
                    Exception("Pairing create failed HTTP ${resp.code()}")
                )
            }
            pairingEphPriv = kp.privateKey
            pairingSessionId = resp.body()!!.sessionId
            Result.success(resp.body()!!.pairingString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * New device: poll until the old device uploads a sealed MK, then unlock.
     */
    suspend fun awaitDevicePairing(token: String, timeoutMs: Long = 300_000L): VaultSyncResult =
        withContext(Dispatchers.IO) {
            val sessionId = pairingSessionId
            val ephPriv = pairingEphPriv
            if (sessionId.isNullOrBlank() || ephPriv == null) {
                return@withContext VaultSyncResult.Failed("No pairing in progress")
            }
            val userId = tokenManager.getCurrentUserId().getOrNull()
                ?: return@withContext VaultSyncResult.Failed("No current user")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val take = chatApiService.takeE2EEPairingPayload(bearer(token), sessionId)
                when {
                    take.code() == 204 -> {
                        delay(1500)
                        continue
                    }
                    take.isSuccessful && take.body() != null -> {
                        val body = take.body()!!
                        val sealed = VaultCrypto.unb64(body.payloadB64)
                            ?: return@withContext VaultSyncResult.Failed("Bad pairing payload")
                        val senderPub = try {
                            body.senderPubHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } catch (_: Exception) {
                            return@withContext VaultSyncResult.Failed("Bad sender public key")
                        }
                        val mk = VaultCrypto.openPairingMk(ephPriv, senderPub, sealed)
                            ?: return@withContext VaultSyncResult.Failed("Could not open pairing payload")
                        return@withContext mutex.withLock {
                            val get = chatApiService.getE2EEVault(bearer(token))
                            val dto = get.body()
                                ?: return@withLock VaultSyncResult.Failed("Vault not found")
                            val ct = VaultCrypto.unb64(dto.vaultCiphertextB64)
                                ?: return@withLock VaultSyncResult.Failed("Bad vault ciphertext")
                            val unlocked = VaultCrypto.openVaultWithMk(
                                userId, dto.vaultVersion, ct, mk
                            ) ?: return@withLock VaultSyncResult.Failed("Vault open failed")
                            applyUnlocked(token, userId, unlocked, dto)
                            registerDevice(token)
                            pairingSessionId = null
                            pairingEphPriv = null
                            VaultSyncResult.Unlocked
                        }
                    }
                    take.code() == 410 || take.code() == 409 ->
                        return@withContext VaultSyncResult.Failed("Pairing expired or used")
                    else ->
                        return@withContext VaultSyncResult.Failed(
                            "Pairing poll failed HTTP ${take.code()}"
                        )
                }
            }
            VaultSyncResult.Failed("Pairing timed out")
        }

    /** Old device: seal session MK to the pairing code from the new device. */
    suspend fun approveDevicePairing(token: String, pairingString: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val mk = sessionMk
                    ?: return@withContext Result.failure(
                        Exception("Unlock this device's vault before linking")
                    )
                val parsed = VaultCrypto.parsePairingString(pairingString)
                    ?: return@withContext Result.failure(Exception("Invalid pairing code"))
                val (sessionId, recipientPub) = parsed
                val sealed = VaultCrypto.sealPairingMk(recipientPub, mk)
                    ?: return@withContext Result.failure(Exception("Failed to seal master key"))
                val (senderPub, payload) = sealed
                val senderHex = senderPub.joinToString("") { "%02x".format(it) }
                val resp = chatApiService.completeE2EEPairing(
                    bearer(token),
                    sessionId,
                    E2EEPairingCompleteRequest(
                        payloadB64 = VaultCrypto.b64(payload),
                        senderPubHex = senderHex
                    )
                )
                if (!resp.isSuccessful) {
                    val msg = resp.errorBody()?.string()?.takeIf { it.isNotBlank() }
                        ?: "Link failed (HTTP ${resp.code()})"
                    return@withContext Result.failure(Exception(msg))
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun vaultExists(token: String): Boolean = withContext(Dispatchers.IO) {
        val r = chatApiService.getE2EEVault(bearer(token))
        r.isSuccessful && r.body() != null
    }

    /**
     * After a password login (or 2FA completion): unlock remote vault into
     * local prefs, or create one from local keys if none exists yet.
     */
    suspend fun syncAfterPasswordLogin(token: String, password: String): VaultSyncResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val userId = tokenManager.getCurrentUserId().getOrNull()
                        ?: return@withContext VaultSyncResult.Failed("No current user")

                    val get = chatApiService.getE2EEVault(bearer(token))
                    val result = if (get.isSuccessful && get.body() != null) {
                        unlockAndApply(token, userId, password, get.body()!!)
                    } else if (get.code() == 404) {
                        createAndUpload(token, userId, password)
                    } else {
                        VaultSyncResult.Failed("Vault fetch failed: HTTP ${get.code()}")
                    }

                    if (result is VaultSyncResult.Unlocked || result is VaultSyncResult.Created) {
                        registerDevice(token)
                    }
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "syncAfterPasswordLogin error", e)
                    VaultSyncResult.Failed(e.message ?: "Vault sync failed")
                }
            }
        }

    /**
     * Unlock vault with the recovery key shown at first vault creation.
     * Optionally rewraps MK under [newPassword] so future logins work.
     */
    suspend fun unlockWithRecoveryKey(
        token: String,
        recoveryKeyB64: String,
        newPassword: String?
    ): VaultSyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val userId = tokenManager.getCurrentUserId().getOrNull()
                    ?: return@withContext VaultSyncResult.Failed("No current user")
                val get = chatApiService.getE2EEVault(bearer(token))
                if (!get.isSuccessful || get.body() == null) {
                    return@withContext VaultSyncResult.Failed("Vault not found")
                }
                val dto = get.body()!!
                val ct = VaultCrypto.unb64(dto.vaultCiphertextB64)
                    ?: return@withContext VaultSyncResult.Failed("Bad vault ciphertext")
                val rkSalt = VaultCrypto.unb64(dto.rkSaltB64)
                    ?: return@withContext VaultSyncResult.Failed("No recovery wrap on vault")
                val rkWrap = VaultCrypto.unb64(dto.rkWrappedMasterB64)
                    ?: return@withContext VaultSyncResult.Failed("No recovery wrap on vault")

                val unlocked = VaultCrypto.unlockVaultWithRecovery(
                    userId = userId,
                    recoveryKeyB64 = recoveryKeyB64,
                    vaultVersion = dto.vaultVersion,
                    vaultCiphertext = ct,
                    rkSalt = rkSalt,
                    rkWrappedMaster = rkWrap
                ) ?: return@withContext VaultSyncResult.Failed("Invalid recovery key")

                applyUnlocked(token, userId, unlocked, dto)

                if (!newPassword.isNullOrBlank()) {
                    rewrapPasswordAndUpload(token, userId, newPassword, unlocked.mk, dto)
                }

                registerDevice(token)
                VaultSyncResult.Unlocked
            } catch (e: Exception) {
                Log.e(TAG, "unlockWithRecoveryKey error", e)
                VaultSyncResult.Failed(e.message ?: "Recovery unlock failed")
            }
        }
    }

    /**
     * Coalesce vault re-uploads (v3 ratchet / sender keys / peer pubs).
     * Immediate [refreshVaultContents] stays for password change / create.
     */
    fun scheduleRefreshVaultContents(token: String) {
        pendingRefresh?.cancel()
        pendingRefresh = refreshScope.launch {
            delay(VAULT_REFRESH_DEBOUNCE_MS)
            refreshVaultContents(token)
        }
    }

    /**
     * Download the current vault and merge ratchets/keys into local state.
     * Used on websocket reconnect and before v3 send/decrypt so a sibling
     * device's session is visible without waiting for a stale PUT.
     */
    suspend fun pullAndMergeVault(token: String, force: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPullAtMs < VAULT_PULL_DEBOUNCE_MS) return@withContext true
            val mk = sessionMk ?: return@withContext false
            val userId = tokenManager.getCurrentUserId().getOrNull() ?: return@withContext false
            val pub = tokenManager.getE2EEPublicKey(userId).getOrNull() ?: return@withContext false
            val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull() ?: return@withContext false
            val get = chatApiService.getE2EEVault(bearer(token))
            val dto = get.body() ?: return@withContext false
            lastPullAtMs = now
            // The version shortcut is a bandwidth optimisation, not a correctness
            // rule, and it must not apply while this session is holding a
            // CANDIDATE key. After an unresolved reset the client's own recorded
            // version already equals the one it hoped to create, so equality here
            // proves nothing - and skipping the merge would leave the session
            // unverified forever, with history refused, until the user happened to
            // sign in again.
            //
            // Falling through costs one vault open and settles it either way:
            // applyRemoteVaultDto opens the server's own ciphertext with this key,
            // which is the same proof a login provides. If it opens, the key is the
            // account's and the session becomes verified; if it does not, the key
            // is a phantom and the session stays unverified. Convergence, without
            // trusting anything the server did not demonstrate.
            if (sessionMkVerified && dto.vaultVersion == sessionVaultVersion) {
                return@withContext true
            }
            applyRemoteVaultDto(userId, mk, pub, priv, dto)
        }
    }

    /**
     * Re-upload vault plaintext (identity + sender keys + peer pubs + ratchets) under the
     * session MK. No-op if vault was never unlocked this session.
     */
    suspend fun refreshVaultContents(token: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            refreshVaultContentsLocked(token, 0)
        }
    }

    private suspend fun refreshVaultContentsLocked(token: String, attempt: Int): Boolean {
            val mk = sessionMk ?: return false
            val wraps = sessionWraps ?: return false
            val userId = tokenManager.getCurrentUserId().getOrNull() ?: return false
            val pub = tokenManager.getE2EEPublicKey(userId).getOrNull() ?: return false
            val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull() ?: return false

            val expected = sessionVaultVersion
            val next = expected + 1
            val plaintext = VaultCrypto.VaultPlaintext(
                identityPubHex = pub,
                identityPrivHex = priv,
                ownSenderKeys = tokenManager.exportVaultSenderKeys().getOrElse { emptyMap() },
                peerPubs = tokenManager.exportVaultPeerPubs().getOrElse { emptyMap() },
                peerSenderKeys = tokenManager.exportVaultPeerSenderKeys().getOrElse { emptyMap() },
                directRatchets = tokenManager.exportVaultDirectRatchets().getOrElse { emptyMap() }
            )
            if (!isRewrapOfSessionMk(mk, "refreshVaultContents")) return false
            val sealed = VaultCrypto.resealVault(userId, mk, plaintext, next)
                ?: return false

            val put = chatApiService.putE2EEVault(
                bearer(token),
                E2EEVaultPutRequest(
                    vaultVersion = next,
                    protocolVersion = VaultCrypto.PROTOCOL_VERSION,
                    suite = VaultCrypto.SUITE_VAULT_AEAD,
                    vaultCiphertextB64 = VaultCrypto.b64(sealed),
                    pwKdf = wraps.pwKdf,
                    pwSaltB64 = wraps.pwSaltB64,
                    pwParams = wraps.pwParams,
                    pwWrappedMasterB64 = wraps.pwWrappedMasterB64,
                    rkKdf = wraps.rkKdf,
                    rkSaltB64 = wraps.rkSaltB64,
                    rkWrappedMasterB64 = wraps.rkWrappedMasterB64,
                    expectedVersion = expected
                )
            )
            if (put.isSuccessful) {
                sessionVaultVersion = next
                Log.i(TAG, "Vault refreshed to v$next")
                return true
            }
            if (put.code() == 409 && attempt < VAULT_REFRESH_MAX_RETRIES) {
                if (mergeRemoteVault(token, userId, mk, pub, priv)) {
                    return refreshVaultContentsLocked(token, attempt + 1)
                }
            }
            Log.w(TAG, "Vault refresh failed HTTP ${put.code()}")
            return false
    }

    private suspend fun mergeRemoteVault(
        token: String,
        userId: String,
        mk: ByteArray,
        pub: String,
        priv: String
    ): Boolean {
        val get = chatApiService.getE2EEVault(bearer(token))
        val dto = get.body() ?: return false
        return applyRemoteVaultDto(userId, mk, pub, priv, dto)
    }

    private suspend fun applyRemoteVaultDto(
        userId: String,
        mk: ByteArray,
        pub: String,
        priv: String,
        dto: E2EEVaultDto
    ): Boolean {
        val ct = VaultCrypto.unb64(dto.vaultCiphertextB64) ?: return false
        val unlocked = VaultCrypto.openVaultWithMk(userId, dto.vaultVersion, ct, mk) ?: return false
        val local = VaultCrypto.VaultPlaintext(
            identityPubHex = pub,
            identityPrivHex = priv,
            ownSenderKeys = tokenManager.exportVaultSenderKeys().getOrElse { emptyMap() },
            peerPubs = tokenManager.exportVaultPeerPubs().getOrElse { emptyMap() },
            peerSenderKeys = tokenManager.exportVaultPeerSenderKeys().getOrElse { emptyMap() },
            directRatchets = tokenManager.exportVaultDirectRatchets().getOrElse { emptyMap() }
        )
        val merged = VaultCrypto.mergePlaintext(local, unlocked.plaintext)
        if (merged.ownSenderKeys.isNotEmpty()) {
            tokenManager.restoreVaultSenderKeys(merged.ownSenderKeys)
        }
        if (merged.peerPubs.isNotEmpty()) {
            tokenManager.restoreVaultPeerPubs(merged.peerPubs)
        }
        if (merged.peerSenderKeys.isNotEmpty()) {
            tokenManager.restoreVaultPeerSenderKeys(merged.peerSenderKeys)
        }
        tokenManager.restoreVaultDirectRatchets(merged.directRatchets)
        chatRepository.adoptDirectRatchetsFromVault(merged.directRatchets)
        rememberSession(mk, dto.vaultVersion, dto)
        Log.i(TAG, "Merged remote vault v${dto.vaultVersion}")
        return true
    }

    /**
     * Change account password and rewrap the E2EE vault under the new password.
     * Order: obtain MK (session or old-password unlock) → server password update → vault rewrap.
     * If rewrap fails after the server update, recovery key can still unlock the vault.
     */
    suspend fun changeAccountPassword(
        token: String,
        currentPassword: String,
        newPassword: String
    ): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (newPassword.length < 8) {
                    return@withContext Result.failure(Exception("New password must be at least 8 characters"))
                }
                val userId = tokenManager.getCurrentUserId().getOrNull()
                    ?: return@withContext Result.failure(Exception("No current user"))

                // Ensure MK is available before changing the login password.
                var mk = sessionMk
                var dto: E2EEVaultDto? = null
                if (mk == null) {
                    val get = chatApiService.getE2EEVault(bearer(token))
                    if (get.isSuccessful && get.body() != null) {
                        dto = get.body()!!
                        val ct = VaultCrypto.unb64(dto.vaultCiphertextB64)
                            ?: return@withContext Result.failure(Exception("Bad vault ciphertext"))
                        val salt = VaultCrypto.unb64(dto.pwSaltB64)
                            ?: return@withContext Result.failure(Exception("Bad vault salt"))
                        val wrapped = VaultCrypto.unb64(dto.pwWrappedMasterB64)
                            ?: return@withContext Result.failure(Exception("Bad wrapped master"))
                        val unlocked = VaultCrypto.unlockVault(
                            userId, currentPassword, dto.vaultVersion, ct, salt, dto.pwParams, wrapped
                        ) ?: return@withContext Result.failure(
                            Exception("Current password does not unlock the vault")
                        )
                        mk = unlocked.mk
                        rememberSession(mk, dto.vaultVersion, dto)
                    }
                }

                val change = chatApiService.changePassword(
                    bearer(token),
                    ChangePasswordRequest(currentPassword, newPassword)
                )
                if (!change.isSuccessful) {
                    val msg = change.errorBody()?.string()?.takeIf { it.isNotBlank() }
                        ?: "Password change failed (HTTP ${change.code()})"
                    // Prefer server {"error":"..."} when present.
                    val parsed = runCatching {
                        org.json.JSONObject(msg).optString("error").takeIf { it.isNotBlank() }
                    }.getOrNull()
                    return@withContext Result.failure(Exception(parsed ?: msg))
                }

                if (mk != null) {
                    if (dto == null) {
                        val get = chatApiService.getE2EEVault(bearer(token))
                        dto = get.body()
                    }
                    if (dto != null) {
                        val rewrapped = rewrapPasswordAndUpload(token, userId, newPassword, mk, dto)
                        if (!rewrapped) {
                            Log.w(TAG, "Login password updated but vault rewrap failed; use recovery key if needed")
                        }
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "changeAccountPassword error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun listDevices(token: String): Result<List<E2EEDeviceDto>> = withContext(Dispatchers.IO) {
        try {
            val r = chatApiService.listE2EEDevices(bearer(token))
            if (r.isSuccessful) Result.success(r.body()?.devices.orEmpty())
            else Result.failure(Exception("HTTP ${r.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeDevice(token: String, deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val r = chatApiService.revokeE2EEDevice(bearer(token), deviceId)
            if (r.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("HTTP ${r.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createAndUpload(
        token: String,
        userId: String,
        password: String
    ): VaultSyncResult {
        val keyStatus = chatRepository.ensureKeysPublished(token, allowTakeover = true)
            .getOrElse {
                return VaultSyncResult.Failed(it.message ?: "Key setup failed")
            }
        Log.d(TAG, "Keys before vault create: $keyStatus")

        val pub = tokenManager.getE2EEPublicKey(userId).getOrNull()
        val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull()
        if (pub.isNullOrBlank() || priv.isNullOrBlank()) {
            return VaultSyncResult.Failed("Missing local E2EE keys")
        }

        val ownSenderKeys = tokenManager.exportVaultSenderKeys().getOrElse { emptyMap() }
        val peerPubs = tokenManager.exportVaultPeerPubs().getOrElse { emptyMap() }
        val peerSenderKeys = tokenManager.exportVaultPeerSenderKeys().getOrElse { emptyMap() }
        val directRatchets = tokenManager.exportVaultDirectRatchets().getOrElse { emptyMap() }

        val built = VaultCrypto.createVault(
            userId, password, pub, priv, ownSenderKeys, peerPubs, peerSenderKeys, directRatchets = directRatchets
        ) ?: return VaultSyncResult.Failed("Vault create failed")

        val rkSalt = built.rkSalt
        val rkWrap = built.rkWrappedMaster
        val put = chatApiService.putE2EEVault(
            bearer(token),
            E2EEVaultPutRequest(
                vaultVersion = built.vaultVersion,
                protocolVersion = VaultCrypto.PROTOCOL_VERSION,
                suite = VaultCrypto.SUITE_VAULT_AEAD,
                vaultCiphertextB64 = VaultCrypto.b64(built.vaultCiphertext),
                pwKdf = VaultCrypto.KDF_ARGON2ID,
                pwSaltB64 = VaultCrypto.b64(built.pwSalt),
                pwParams = built.pwParamsJson,
                pwWrappedMasterB64 = VaultCrypto.b64(built.pwWrappedMaster),
                rkKdf = if (rkWrap != null) VaultCrypto.KDF_HKDF_SHA256 else "",
                rkSaltB64 = if (rkSalt != null) VaultCrypto.b64(rkSalt) else "",
                rkWrappedMasterB64 = if (rkWrap != null) VaultCrypto.b64(rkWrap) else "",
                expectedVersion = 0
            )
        )
        if (!put.isSuccessful) {
            return VaultSyncResult.Failed("Vault upload failed: HTTP ${put.code()}")
        }

        val dto = put.body() ?: E2EEVaultDto(
            vaultVersion = built.vaultVersion,
            pwKdf = VaultCrypto.KDF_ARGON2ID,
            pwSaltB64 = VaultCrypto.b64(built.pwSalt),
            pwParams = built.pwParamsJson,
            pwWrappedMasterB64 = VaultCrypto.b64(built.pwWrappedMaster),
            rkKdf = if (rkWrap != null) VaultCrypto.KDF_HKDF_SHA256 else "",
            rkSaltB64 = if (rkSalt != null) VaultCrypto.b64(rkSalt) else "",
            rkWrappedMasterB64 = if (rkWrap != null) VaultCrypto.b64(rkWrap) else ""
        )
        // THE FRESH-MK BOUNDARY. This is the only path in the app that mints a
        // brand-new master key, and it is reached only after the server answered
        // 404 for this account's vault - so the code has positively established
        // that the previous MK (if any) is being replaced, not merely that
        // something failed to open.
        //
        // Any recovery blob still stored under that retired MK can never be
        // opened again, and PUT with expected_version=0 is create-only, so
        // leaving it would make every future publish conflict forever. Delete it
        // BEFORE the new MK is installed, so the window where a live session
        // could re-publish under the new key never overlaps the stale row.
        //
        // Tolerating a failure is sound HERE and only here: this path is reached
        // only after a 404, so no MK has ever existed for the account and a
        // recovery blob - which only an MK-holding device can publish - cannot
        // exist either. The call is belt-and-braces against a partially-created
        // account, not the load-bearing invalidation.
        //
        // An INTENTIONAL RESET must not copy this. See the failure contract on
        // MkReplacement.IntentionalReset and the Gate 6.1 note below.
        runCatching {
            historyRecovery.get().invalidateHistoryRecoveryBeforeMkReplacement(
                HistoryKeyringRecoverySync.MkReplacement.FreshAccountMint
            )
        }.onFailure { Log.w(TAG, "stale recovery keyring not cleared: ${it.message}") }

        built.mk?.let { rememberSession(it, built.vaultVersion, dto) }

        Log.i(TAG, "Created E2EE vault v${built.vaultVersion}")
        return VaultSyncResult.Created(built.recoveryKeyDisplay)
    }

    private suspend fun unlockAndApply(
        token: String,
        userId: String,
        password: String,
        dto: E2EEVaultDto
    ): VaultSyncResult {
        val ct = VaultCrypto.unb64(dto.vaultCiphertextB64)
            ?: return VaultSyncResult.Failed("Bad vault ciphertext")
        val salt = VaultCrypto.unb64(dto.pwSaltB64)
            ?: return VaultSyncResult.Failed("Bad vault salt")
        val wrapped = VaultCrypto.unb64(dto.pwWrappedMasterB64)
            ?: return VaultSyncResult.Failed("Bad wrapped master")

        val unlocked = VaultCrypto.unlockVault(
            userId = userId,
            password = password,
            vaultVersion = dto.vaultVersion,
            vaultCiphertext = ct,
            pwSalt = salt,
            pwParamsJson = dto.pwParams,
            pwWrappedMaster = wrapped
        ) ?: return VaultSyncResult.NeedsRecovery(
            "Vault password unlock failed — enter your recovery key"
        )

        applyUnlocked(token, userId, unlocked, dto)
        return VaultSyncResult.Unlocked
    }

    private suspend fun applyUnlocked(
        token: String,
        userId: String,
        unlocked: VaultCrypto.UnlockedVault,
        dto: E2EEVaultDto
    ) {
        val plain = unlocked.plaintext
        if (plain.identityPubHex.isBlank() || plain.identityPrivHex.isBlank()) {
            throw IllegalStateException("Vault missing identity keys")
        }
        tokenManager.saveE2EEKeys(userId, plain.identityPubHex, plain.identityPrivHex)
        if (plain.ownSenderKeys.isNotEmpty()) {
            tokenManager.restoreVaultSenderKeys(plain.ownSenderKeys)
        }
        if (plain.peerPubs.isNotEmpty()) {
            tokenManager.restoreVaultPeerPubs(plain.peerPubs)
        }
        if (plain.peerSenderKeys.isNotEmpty()) {
            tokenManager.restoreVaultPeerSenderKeys(plain.peerSenderKeys)
        }
        if (plain.directRatchets.isNotEmpty()) {
            tokenManager.restoreVaultDirectRatchets(plain.directRatchets)
            chatRepository.adoptDirectRatchetsFromVault(
                tokenManager.exportVaultDirectRatchets().getOrElse { plain.directRatchets }
            )
        }
        chatRepository.ensureKeysPublished(token, allowTakeover = false)
        rememberSession(unlocked.mk, dto.vaultVersion, dto)
        Log.i(TAG, "Unlocked E2EE vault v${dto.vaultVersion}")
    }

    /**
     * The earliest point at which account identity, device identity and MK are
     * all available - so it is the earliest point history keyring recovery can
     * run. Fire-and-forget on the existing refresh scope: recovery is a
     * synchronisation feature and must never delay or fail an unlock.
     *
     * Idempotent and one-shot per session inside ensureRecovered, and a complete
     * no-op while the archive feature flag is off.
     */
    private fun recoverHistoryKeyringInBackground() {
        pendingHistoryRecovery?.cancel()
        pendingHistoryRecovery = refreshScope.launch {
            runCatching { historyRecovery.get().ensureRecovered() }
                .onFailure { Log.w(TAG, "history keyring recovery not completed: ${it.message}") }
        }
    }

    /**
     * Outcome of [resetE2EEVault]. [Aborted.stage] and
     * [IncompleteAfterReplacement.stage] name the last step that ran, so the UI
     * can say what state the account is actually in rather than "something went
     * wrong".
     */
    sealed class E2EEResetResult {
        /** The new generation is live. [recoveryKeyDisplay] must be shown once. */
        data class Success(val recoveryKeyDisplay: String?) : E2EEResetResult()

        /** Nothing irreversible happened; the old key is still in use. */
        data class Aborted(val message: String, val stage: String) : E2EEResetResult()

        /**
         * The new key IS authoritative but a later step did not finish. The account
         * is usable; recovery converges on the next publish. Never reported as
         * success.
         */
        data class IncompleteAfterReplacement(
            val message: String,
            val stage: String,
            /** Always present after a commit: the only key that opens the new vault. */
            val recoveryKeyDisplay: String?,
        ) : E2EEResetResult()

        /**
         * The vault PUT was neither confirmed nor refused - the reply was lost.
         * The reset may have completed, so [recoveryKeyDisplay] must be shown.
         */
        data class PossiblyCompleted(
            val message: String,
            val recoveryKeyDisplay: String?,
        ) : E2EEResetResult()
    }

    /**
     * Intentionally replaces this account's vault master key.
     *
     * This is the reset boundary Gate 6 hardened, now reachable. It is destructive
     * by design: the history keyring is sealed under the master key, so replacing
     * the key abandons every archived message that keyring could open. The caller
     * must have obtained explicit user confirmation first - nothing here re-asks.
     *
     * WHAT IT DOES NOT DO. It does not rotate the account's identity keypair. The
     * existing keys are carried into the new vault unchanged, so MLS group state,
     * pairing, and peer sessions are untouched. The server's identity reset flag is
     * deliberately not used: no client can currently send it, and rotating identity
     * would break exactly the subsystems this gate must not redesign.
     *
     * ORDERING - the invariant this whole gate exists for:
     *
     *     authorize -> revoke peers -> INVALIDATE RECOVERY -> mint new MK
     *               -> persist vault -> discard local keyring -> activate MK
     *
     * Invalidation precedes activation. A successful reset can never leave a blob
     * sealed under the retired key while the app treats the new key as current.
     */
    suspend fun resetE2EEVault(token: String, password: String): E2EEResetResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    resetLocked(token, password)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // Structured cancellation, propagated. Reaching here means the
                    // cancellation happened BEFORE the irreversible PUT - anything
                    // after it runs NonCancellable and cannot surface this way - so
                    // nothing has changed and there is no result worth inventing.
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "E2EE reset error", e)
                    E2EEResetResult.Aborted(e.message ?: "Reset failed", "unexpected")
                }
            }
        }

    /**
     * What the vault PUT actually established.
     *
     * The distinction is the whole point. A response - any response, including
     * 4xx and 5xx - means the server reached a decision: every rejection in
     * PutVault returns before or instead of the database write, so a received
     * status is proof the vault did NOT change. A thrown transport error proves
     * nothing at all: the request may have committed and only the reply was lost.
     * Collapsing that third case into "failed" is what silently destroyed the
     * user's recovery key.
     */
    private sealed interface CommitOutcome {
        data class Committed(val dto: E2EEVaultDto?) : CommitOutcome
        data class Rejected(val code: Int) : CommitOutcome
        data class Unknown(val cause: Throwable) : CommitOutcome
    }

    /** What device revocation established before anything irreversible happens. */
    private sealed interface RevocationOutcome {
        data object NothingToRevoke : RevocationOutcome
        data object AllRevoked : RevocationOutcome
        data class EnumerationFailed(val cause: Throwable) : RevocationOutcome
        data class PartiallyRevoked(val failed: List<String>) : RevocationOutcome
    }

    /**
     * Revokes every device except this one.
     *
     * Never conflates "the account has no other devices" with "the device list
     * could not be read". Those produce identical work - zero revocations - but
     * opposite safety conclusions, and treating the second as the first let a
     * single failed GET turn into a reset that reported success while every peer
     * kept the retired master key.
     */
    private suspend fun revokeOtherDevices(token: String, ownDeviceId: String?): RevocationOutcome {
        val devices = listDevices(token).getOrElse {
            return RevocationOutcome.EnumerationFailed(it)
        }
        val targets = devices.filter {
            it.deviceId.isNotBlank() && it.deviceId != ownDeviceId && it.revokedAt.isNullOrBlank()
        }
        if (targets.isEmpty()) return RevocationOutcome.NothingToRevoke

        val failed = mutableListOf<String>()
        for (d in targets) {
            revokeDevice(token, d.deviceId).onFailure {
                Log.w(TAG, "reset could not revoke " + d.deviceId + ": " + it.message)
                failed += d.deviceId
            }
        }
        return if (failed.isEmpty()) RevocationOutcome.AllRevoked
        else RevocationOutcome.PartiallyRevoked(failed)
    }

    private suspend fun resetLocked(token: String, password: String): E2EEResetResult {
        val userId = tokenManager.getCurrentUserId().getOrNull()
            ?: return E2EEResetResult.Aborted("No current user", "authorize")

        // 1-2. There must be something to reset, and the caller must be able to
        // open it. Requiring the password IS the authorization step: it proves the
        // person driving this can already read the vault they are about to
        // destroy, so a borrowed unlocked session cannot trigger it.
        val get = chatApiService.getE2EEVault(bearer(token))
        if (get.code() == 404) {
            return E2EEResetResult.Aborted("This account has no vault to reset", "authorize")
        }
        val dto = get.body()?.takeIf { get.isSuccessful }
            ?: return E2EEResetResult.Aborted("Vault fetch failed: HTTP ${get.code()}", "authorize")

        val ct = VaultCrypto.unb64(dto.vaultCiphertextB64)
            ?: return E2EEResetResult.Aborted("Bad vault ciphertext", "authorize")
        val salt = VaultCrypto.unb64(dto.pwSaltB64)
            ?: return E2EEResetResult.Aborted("Bad vault salt", "authorize")
        val wrapped = VaultCrypto.unb64(dto.pwWrappedMasterB64)
            ?: return E2EEResetResult.Aborted("Bad wrapped master", "authorize")
        VaultCrypto.unlockVault(userId, password, dto.vaultVersion, ct, salt, dto.pwParams, wrapped)
            ?: return E2EEResetResult.Aborted("Password does not unlock this vault", "authorize")

        val pub = tokenManager.getE2EEPublicKey(userId).getOrNull()
        val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull()
        if (pub.isNullOrBlank() || priv.isNullOrBlank()) {
            return E2EEResetResult.Aborted("Missing local E2EE keys", "authorize")
        }

        // A recovery started at sign-in may still be in flight. It holds the OLD
        // key and could re-import the old generation after the local discard, so
        // it is stopped here - before anything is torn down - rather than raced.
        pendingHistoryRecovery?.cancel()
        pendingHistoryRecovery = null

        // 3. Revoke peers BEFORE the recovery slot is freed. Every outcome other
        // than "provably nothing left to revoke" aborts while the account is still
        // completely untouched: nothing has been invalidated, no key has been
        // minted, and the old master key remains authoritative.
        val ownDeviceId = tokenManager.getOrCreateDeviceId().getOrNull()
        when (val revocation = revokeOtherDevices(token, ownDeviceId)) {
            is RevocationOutcome.NothingToRevoke, is RevocationOutcome.AllRevoked -> Unit
            is RevocationOutcome.EnumerationFailed -> return E2EEResetResult.Aborted(
                "Could not check which devices are signed in, so the reset was stopped " +
                    "before anything changed. Check your connection and try again.",
                "enumerate-devices"
            )
            is RevocationOutcome.PartiallyRevoked -> return E2EEResetResult.Aborted(
                "Could not sign out " + revocation.failed.size + " other device(s), so the " +
                    "reset was stopped before anything changed. Those devices would have " +
                    "kept access to your old encryption key.",
                "revoke-devices"
            )
        }

        // 4. INVALIDATE FIRST. Mandatory - see the failure contract on
        // MkReplacement.IntentionalReset. Still nothing irreversible.
        val invalidated = runCatching {
            historyRecovery.get().invalidateHistoryRecoveryBeforeMkReplacement(
                HistoryKeyringRecoverySync.MkReplacement.IntentionalReset
            )
        }.getOrElse { Result.failure(it) }
        if (invalidated.isFailure) {
            return E2EEResetResult.Aborted(
                "Could not clear the old recovery data, so the reset was stopped before " +
                    "anything changed: " + (invalidated.exceptionOrNull()?.message ?: "unknown"),
                "invalidate"
            )
        }

        // 5. Mint. createVault already accepts the target version, so no
        // VaultCrypto change is needed. This also produces the ONLY copy of the
        // new recovery key that will ever exist.
        val next = dto.vaultVersion + 1
        val built = VaultCrypto.createVault(
            userId = userId,
            password = password,
            pubHex = pub,
            privHex = priv,
            ownSenderKeys = tokenManager.exportVaultSenderKeys().getOrElse { emptyMap() },
            peerPubs = tokenManager.exportVaultPeerPubs().getOrElse { emptyMap() },
            peerSenderKeys = tokenManager.exportVaultPeerSenderKeys().getOrElse { emptyMap() },
            vaultVersion = next,
            directRatchets = tokenManager.exportVaultDirectRatchets().getOrElse { emptyMap() },
        ) ?: return E2EEResetResult.Aborted("Could not create the new vault", "mint")

        // 6. POINT OF NO RETURN.
        val commit = try {
            val put = chatApiService.putE2EEVault(
                bearer(token),
                E2EEVaultPutRequest(
                    vaultVersion = built.vaultVersion,
                    protocolVersion = VaultCrypto.PROTOCOL_VERSION,
                    suite = VaultCrypto.SUITE_VAULT_AEAD,
                    vaultCiphertextB64 = VaultCrypto.b64(built.vaultCiphertext),
                    pwKdf = VaultCrypto.KDF_ARGON2ID,
                    pwSaltB64 = VaultCrypto.b64(built.pwSalt),
                    pwParams = built.pwParamsJson,
                    pwWrappedMasterB64 = VaultCrypto.b64(built.pwWrappedMaster),
                    rkKdf = if (built.rkWrappedMaster != null) VaultCrypto.KDF_HKDF_SHA256 else "",
                    rkSaltB64 = built.rkSalt?.let { VaultCrypto.b64(it) } ?: "",
                    rkWrappedMasterB64 = built.rkWrappedMaster?.let { VaultCrypto.b64(it) } ?: "",
                    expectedVersion = dto.vaultVersion
                )
            )
            when {
                put.isSuccessful -> CommitOutcome.Committed(put.body())
                // 409 is NOT proof of non-commit for this request. OkHttp retries
                // on connection failure by default, and the retry replays the same
                // expected_version - so a write that committed and then lost its
                // reply comes back as a conflict on the second attempt. A genuine
                // concurrent writer produces the same code. Neither is
                // distinguishable here, so it must be treated as unknown rather
                // than as "nothing happened", which would discard the only copy of
                // the new recovery key.
                put.code() == 409 -> CommitOutcome.Unknown(
                    IllegalStateException("vault PUT conflicted; commit state unknown")
                )
                // Everything else - 400, 401, 413, 5xx - is returned before or
                // instead of the database write, so the vault provably did not move.
                else -> CommitOutcome.Rejected(put.code())
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Structured cancellation must not be converted into a generic error,
            // and cancellation HERE is genuinely ambiguous: the request may have
            // reached the server. Treat it exactly like any other lost reply.
            CommitOutcome.Unknown(ce)
        } catch (e: Exception) {
            CommitOutcome.Unknown(e)
        }

        if (commit is CommitOutcome.Rejected) {
            // A status code is proof the server did not change the vault, so the
            // old master key - and the user's existing recovery key - are intact.
            return E2EEResetResult.Aborted(
                "The new vault could not be saved (HTTP " + commit.code + "). Nothing was " +
                    "changed and your existing encryption key is still in use; recovery " +
                    "data that was cleared will be re-created automatically.",
                "persist"
            )
        }

        // AMBIGUITY IS RESOLVED, NOT GUESSED.
        //
        // A lost reply leaves the client unable to say whether the write landed,
        // and guessing either way is harmful: assume committed and a device may
        // start sealing state under a master key the account does not have;
        // assume rejected and the only copy of the new recovery key is discarded
        // while the server holds the vault it opens.
        //
        // The protocol already carries the answer. GET /e2ee/vault reports the
        // authoritative vault_version, and this reset knows exactly which version
        // it was trying to create. One read collapses most ambiguous cases into a
        // determinate one, using existing metadata and no new endpoint.
        val settled = if (commit is CommitOutcome.Unknown) {
            resolveAmbiguousCommit(token, built.vaultVersion, commit)
        } else {
            commit
        }

        if (settled is CommitOutcome.Rejected) {
            // The re-read proved the vault never moved, so nothing irreversible
            // happened to it and the account's existing recovery key still opens
            // it. The freshly minted one is worthless and must not be presented.
            return E2EEResetResult.Aborted(
                "The new vault could not be saved and nothing was changed. Your existing " +
                    "encryption key is still in use; recovery data that was cleared will be " +
                    "re-created automatically.",
                "persist"
            )
        }

        // From here the vault may or may not have changed, and in both cases the
        // freshly minted recovery key is the only one that can open it. Everything
        // below runs NonCancellable so a navigation away cannot discard it, and
        // the outcome is also parked on the repository so a destroyed ViewModel
        // does not take it down with it.
        return withContext(NonCancellable) {
            finishAfterCommit(userId, settled, built)
        }
    }

    /**
     * Turns an unknown commit into a determinate one where the server can say.
     *
     * Deliberately a single read with no retry loop: this runs while the network
     * is already misbehaving, and a client that cannot get an answer must stay
     * honestly unsure rather than keep asking. Anything other than a clear answer
     * leaves the outcome [CommitOutcome.Unknown], which is handled conservatively
     * downstream.
     */
    private suspend fun resolveAmbiguousCommit(
        token: String,
        expectedVersion: Int,
        unknown: CommitOutcome.Unknown,
    ): CommitOutcome {
        val get = runCatching { chatApiService.getE2EEVault(bearer(token)) }.getOrNull()
            ?: return unknown
        val dto = get.body()?.takeIf { get.isSuccessful } ?: return unknown
        return when {
            // Exactly the version this reset was creating: our write landed.
            dto.vaultVersion == expectedVersion -> CommitOutcome.Committed(dto)
            // Still behind: the write provably never took effect.
            dto.vaultVersion < expectedVersion -> CommitOutcome.Rejected(409)
            // Someone moved it further along; whether ours landed first is not
            // knowable from here, so it stays unknown.
            else -> unknown
        }
    }

    /**
     * The post-commit tail. Runs NonCancellable and always yields a result that
     * carries [BuiltVault.recoveryKeyDisplay].
     */
    private suspend fun finishAfterCommit(
        userId: String,
        commit: CommitOutcome,
        built: VaultCrypto.BuiltVault,
    ): E2EEResetResult {
        val key = built.recoveryKeyDisplay
        val ambiguous = commit is CommitOutcome.Unknown

        val newMk = built.mk
        if (newMk == null) {
            return park(userId,
                E2EEResetResult.IncompleteAfterReplacement(
                    "Your encryption key was replaced on the server, but it could not be " +
                        "activated on this device. Save the recovery key below and sign in again.",
                    "activate",
                    key
                )
            )
        }

        // 7. Discard the retired generation locally BEFORE the new key goes live,
        // so no window exists in which the new session could read old roots.
        val discarded = runCatching { historyKeyring.get().discardForMkReplacement() }
            .getOrElse { Result.failure(it) }

        // 8. Activate.
        val dtoOut = (commit as? CommitOutcome.Committed)?.dto ?: E2EEVaultDto(
            vaultVersion = built.vaultVersion,
            pwKdf = VaultCrypto.KDF_ARGON2ID,
            pwSaltB64 = VaultCrypto.b64(built.pwSalt),
            pwParams = built.pwParamsJson,
            pwWrappedMasterB64 = VaultCrypto.b64(built.pwWrappedMaster),
            rkKdf = if (built.rkWrappedMaster != null) VaultCrypto.KDF_HKDF_SHA256 else "",
            rkSaltB64 = built.rkSalt?.let { VaultCrypto.b64(it) } ?: "",
            rkWrappedMasterB64 = built.rkWrappedMaster?.let { VaultCrypto.b64(it) } ?: ""
        )
        // The candidate key is installed so the session stays usable and the user
        // can be shown the outcome - but it is marked unverified, so nothing may
        // seal durable state under it until a login confirms the server agrees.
        rememberSession(newMk, built.vaultVersion, dtoOut, verified = !ambiguous)

        if (ambiguous) {
            return park(userId,
                E2EEResetResult.PossiblyCompleted(
                    "The connection dropped while saving your new encryption key, so we " +
                        "cannot tell whether the reset finished. Save the recovery key below - " +
                        "if the reset did go through, it is the only one that works.",
                    key
                )
            )
        }

        if (discarded.isFailure) {
            return park(userId,
                E2EEResetResult.IncompleteAfterReplacement(
                    "Your encryption key was replaced, but old local history data could not " +
                        "be fully removed. It is unreadable either way. Save the recovery key below.",
                    "discard",
                    key
                )
            )
        }

        // 9. Claim the recovery slot immediately. Between the invalidation and
        // this publish the slot is unowned, and a device that somehow still has a
        // live session could occupy it with retired material. Publishing the new
        // (empty) keyring closes that window instead of leaving it open until the
        // next archive happens to be written.
        //
        // upload() rather than uploadIfChanged(): the latter deliberately
        // swallows failures so that a sync problem can never break messaging,
        // which is right for the ordinary path and useless here - it would let a
        // failed publish be reported as a fully converged reset. A disabled
        // feature reports HistoryArchiveDisabledException, which genuinely means
        // "nothing to publish" and is not an error.
        val published = runCatching { historyRecovery.get().upload() }
            .getOrElse { Result.failure(it) }
        val publishSettled = published.isSuccess ||
            published.exceptionOrNull() is
                com.messenger.app.data.encryption.history.HistoryArchiveDisabledException
        if (!publishSettled) {
            return park(userId,
                E2EEResetResult.IncompleteAfterReplacement(
                    "Your encryption key was replaced, but the new recovery data could not " +
                        "be uploaded yet. It will be retried automatically. Save the recovery " +
                        "key below.",
                    "publish",
                    key
                )
            )
        }

        Log.i(TAG, "E2EE vault reset to v" + built.vaultVersion)
        // Parked like every other post-commit outcome. Success is the COMMON path
        // and the key still has to reach a human: if the caller's scope died while
        // this ran, the return value goes nowhere, and this is the only remaining
        // copy. The ViewModel acknowledges it as soon as it has shown it, so a
        // normally-consumed result is never shown twice.
        return park(userId, E2EEResetResult.Success(key))
    }

    /**
     * Records a post-commit outcome on the repository itself.
     *
     * The repository is a singleton; the Settings ViewModel is not. If the user
     * navigates away mid-reset the ViewModel dies, and without this the only copy
     * of the new recovery key would die with it. Parking it here lets the screen
     * pick it up again when it is next shown.
     *
     * This survives ViewModel death, NOT process death - see the reported
     * limitations.
     */
    private fun park(userId: String, result: E2EEResetResult): E2EEResetResult {
        pendingResetOutcomeOwner = userId
        _pendingResetOutcome.value = result
        return result
    }

    /**
     * GATE 6.1 INTEGRATION POINT - where an intentional E2EE reset must attach.
     *
     * No reset flow exists in this tree, and none is invented here. What follows
     * is the contract the future one has to honour, recorded where the code it
     * constrains actually lives.
     *
     * Reachability today: [createAndUpload] is the only path that mints a master
     * key, and it is reached only after `GET /e2ee/vault` answers 404. There is no
     * `DELETE /e2ee/vault` route, and a vault row disappears only with the account
     * itself - which cascades the recovery row at the same time. So no reachable
     * path can replace the MK of an account that already has a vault, on either
     * the Android or the Qt client.
     *
     * A reset therefore has to use PUT /e2ee/vault's UPDATE branch, and must run:
     *
     *   1. require an unlocked session and an explicit user acknowledgement that
     *      existing encrypted history becomes unreadable;
     *   2. revoke every OTHER device first - an unrevoked peer still holding the
     *      old key can claim the freed recovery slot (see
     *      TestKeyring_StaleWriterCanWinAnUnclaimedSlotButOnlyBeforeItIsClaimed);
     *   3. invalidateHistoryRecoveryBeforeMkReplacement(IntentionalReset) and
     *      ABORT the whole reset if it fails - see its failure contract;
     *   4. mint the new key with VaultCrypto.createVault(vaultVersion = current+1),
     *      which already takes the version as a parameter, so no change to
     *      VaultCrypto is required;
     *   5. PUT the vault with expectedVersion = current  <- POINT OF NO RETURN;
     *   6. only now rememberSession(newMk);
     *   7. clear the local keyring, its cache, and its generation marker;
     *   8. publish the new keyring immediately, so the freed slot stops being
     *      claimable by a straggler;
     *   9. show the new recovery key once.
     *
     * Steps 3 and 6 are the ordering this gate exists to guarantee: the blob dies
     * before the key that cannot open it goes live.
     */

    /**
     * Whether [mk] is the key this session already holds.
     *
     * PUT /e2ee/vault's update branch replaces vault_ciphertext AND the wrapped
     * master, and the server cannot tell a rewrap from a replacement - it never
     * sees MK. So the only place that distinction can be enforced is here, at the
     * call site, and only by checking that the key being sealed is the one already
     * in use.
     *
     * Every current update-branch caller is a rewrap: a password change, a
     * recovery-key rewrap, or a vault-contents refresh. If a future change ever
     * routes a freshly minted key through one of them, the write is refused and
     * logged instead of silently replacing the account's MK and stranding its
     * recovery blob.
     */
    private fun isRewrapOfSessionMk(mk: ByteArray, operation: String): Boolean {
        val current = sessionMk
        if (current != null && current.contentEquals(mk)) return true
        // Deliberately a refusal, not a throw. A logout can null sessionMk while a
        // vault refresh is in flight, and that race must fail the upload the same
        // way every other precondition here does - not raise into a coroutine that
        // has no handler.
        Log.w(
            TAG,
            "$operation refused: the key being written is not the session key. The vault " +
                "update branch REWRAPS the existing MK only; minting a new one must go " +
                "through the MK-replacement boundary so the recovery blob is invalidated first."
        )
        return false
    }

    private fun rememberSession(
        mk: ByteArray,
        version: Int,
        dto: E2EEVaultDto,
        /**
         * False only where the server could not confirm it holds this key. Every
         * other caller reached the key by opening server-authoritative ciphertext,
         * which is proof in itself.
         */
        verified: Boolean = true,
    ) {
        sessionMk = mk
        sessionMkVerified = verified
        sessionVaultVersion = version
        sessionWraps = SessionWraps(
            pwKdf = dto.pwKdf.ifBlank { VaultCrypto.KDF_ARGON2ID },
            pwSaltB64 = dto.pwSaltB64,
            pwParams = dto.pwParams,
            pwWrappedMasterB64 = dto.pwWrappedMasterB64,
            rkKdf = dto.rkKdf,
            rkSaltB64 = dto.rkSaltB64,
            rkWrappedMasterB64 = dto.rkWrappedMasterB64
        )
        // LAST, deliberately. The background coroutine reads sessionMk, so
        // triggering it before the assignment would race and see a locked vault.
        recoverHistoryKeyringInBackground()
    }

    private suspend fun rewrapPasswordAndUpload(
        token: String,
        userId: String,
        newPassword: String,
        mk: ByteArray,
        dto: E2EEVaultDto
    ): Boolean {
        if (!isRewrapOfSessionMk(mk, "rewrapPasswordAndUpload")) return false
        val wrap = VaultCrypto.wrapMasterWithPassword(userId, newPassword, mk) ?: return false
        val (salt, wrapped, paramsJson) = wrap
        val expected = dto.vaultVersion
        val next = expected + 1
        val pub = tokenManager.getE2EEPublicKey(userId).getOrNull() ?: return false
        val priv = tokenManager.getE2EEPrivateKey(userId).getOrNull() ?: return false
        val plaintext = VaultCrypto.VaultPlaintext(
            identityPubHex = pub,
            identityPrivHex = priv,
            ownSenderKeys = tokenManager.exportVaultSenderKeys().getOrElse { emptyMap() },
            peerPubs = tokenManager.exportVaultPeerPubs().getOrElse { emptyMap() },
            peerSenderKeys = tokenManager.exportVaultPeerSenderKeys().getOrElse { emptyMap() },
            directRatchets = tokenManager.exportVaultDirectRatchets().getOrElse { emptyMap() }
        )
        val sealed = VaultCrypto.resealVault(userId, mk, plaintext, next) ?: return false
        val put = chatApiService.putE2EEVault(
            bearer(token),
            E2EEVaultPutRequest(
                vaultVersion = next,
                protocolVersion = VaultCrypto.PROTOCOL_VERSION,
                suite = VaultCrypto.SUITE_VAULT_AEAD,
                vaultCiphertextB64 = VaultCrypto.b64(sealed),
                pwKdf = VaultCrypto.KDF_ARGON2ID,
                pwSaltB64 = VaultCrypto.b64(salt),
                pwParams = paramsJson,
                pwWrappedMasterB64 = VaultCrypto.b64(wrapped),
                rkKdf = dto.rkKdf,
                rkSaltB64 = dto.rkSaltB64,
                rkWrappedMasterB64 = dto.rkWrappedMasterB64,
                expectedVersion = expected
            )
        )
        if (put.isSuccessful) {
            rememberSession(mk, next, put.body() ?: dto.copy(
                vaultVersion = next,
                pwSaltB64 = VaultCrypto.b64(salt),
                pwParams = paramsJson,
                pwWrappedMasterB64 = VaultCrypto.b64(wrapped)
            ))
            Log.i(TAG, "Rewrapped vault under new password")
            return true
        }
        Log.w(TAG, "Password rewrap upload failed HTTP ${put.code()}")
        return false
    }

    private suspend fun registerDevice(token: String) {
        val userId = tokenManager.getCurrentUserId().getOrNull() ?: return
        val deviceId = tokenManager.getOrCreateDeviceId().getOrNull() ?: return
        val pub = tokenManager.getE2EEPublicKey(userId).getOrNull().orEmpty()
        if (pub.isBlank()) return

        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        val body = E2EEDeviceRegisterRequest(
            deviceId = deviceId,
            name = name,
            platform = "android",
            publicKey = pub
        )
        runCatching {
            chatApiService.registerE2EEDevice(bearer(token), body)
        }.onFailure { Log.w(TAG, "Device register failed", it) }
            .onSuccess { r ->
                if (!r.isSuccessful) Log.w(TAG, "Device register HTTP ${r.code()}")
                else Log.d(TAG, "E2EE device registered")
            }
    }
}
