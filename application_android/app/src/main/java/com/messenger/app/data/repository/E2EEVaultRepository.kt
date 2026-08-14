package com.messenger.app.data.repository

import android.os.Build
import android.util.Log
import com.messenger.app.data.encryption.VaultCrypto
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val webSocketManager: WebSocketManager
) {
    companion object {
        private const val TAG = "E2EEVaultRepository"
        private const val VAULT_REFRESH_DEBOUNCE_MS = 2_000L
        private const val VAULT_REFRESH_MAX_RETRIES = 3
        private const val VAULT_PULL_DEBOUNCE_MS = 2_000L
    }

    private val mutex = Mutex()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingRefresh: Job? = null
    @Volatile private var lastPullAtMs: Long = 0

    init {
        refreshScope.launch {
            webSocketManager.connectionState.collect { state ->
                if (state != WebSocketManager.ConnectionState.CONNECTED) return@collect
                val token = tokenManager.getAccessToken().getOrNull() ?: return@collect
                pullAndMergeVault(token, force = true)
            }
        }
    }

    /** Session MK after unlock/create — needed to refresh vault ciphertext. */
    @Volatile private var sessionMk: ByteArray? = null
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
        pendingRefresh?.cancel()
        pendingRefresh = null
        sessionMk = null
        sessionVaultVersion = 0
        sessionWraps = null
        pairingSessionId = null
        pairingEphPriv = null
    }

    fun hasSessionMk(): Boolean = sessionMk != null

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
            if (dto.vaultVersion == sessionVaultVersion) return@withContext true
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

    private fun rememberSession(mk: ByteArray, version: Int, dto: E2EEVaultDto) {
        sessionMk = mk
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
    }

    private suspend fun rewrapPasswordAndUpload(
        token: String,
        userId: String,
        newPassword: String,
        mk: ByteArray,
        dto: E2EEVaultDto
    ): Boolean {
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
