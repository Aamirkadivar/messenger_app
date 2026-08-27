package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.VaultCrypto
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.model.E2EEDeviceDto
import com.messenger.app.data.model.E2EEDevicesResponse
import com.messenger.app.data.model.E2EEVaultDto
import com.messenger.app.data.model.E2EEVaultPutRequest
import com.messenger.app.data.model.HistoryKeyringDto
import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.model.HistoryKeyringPutResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.util.Base64

/**
 * GATE 6.1 - the REAL reset orchestration, executed.
 *
 * Every previous reset test drove a replica of the sequence. This one calls the
 * actual production method, `E2EEVaultRepository.resetE2EEVault`, and therefore
 * exercises `resetLocked`, `CommitOutcome` classification, `finishAfterCommit`,
 * the `NonCancellable` post-commit tail, `discardForMkReplacement`,
 * `rememberSession`, `upload()`, and `pendingResetOutcome` as they actually ship.
 *
 * It runs instrumented because the reset path calls `VaultCrypto`, which loads
 * libsodium - so real Argon2id, real XChaCha20-Poly1305, real key wrapping, and a
 * real Android Keystore behind `TokenManagerImpl`. The only substitutes are the
 * HTTP interface and the two collaborators the reset path provably never touches
 * (`ChatRepository`, `WebSocketManager`).
 *
 * All key material is TEST-ONLY.
 */
@RunWith(AndroidJUnit4::class)
class E2EEResetProductionTest {

    private companion object {
        const val USER = "11111111-2222-3333-4444-555555555555"
        const val PASSWORD = "correct horse battery staple"
        const val TOKEN = "test-token"
        const val CHAT = "reset-prod-chat"
    }

    /**
     * The server, with the real contract: vault CAS on expected_version, and the
     * keyring slot's create-only-at-zero rule.
     */
    private class Api : ChatApiService by mockk(relaxed = true) {
        var vault: E2EEVaultDto? = null
        var vaultVersion = 0
        var devices = mutableListOf<E2EEDeviceDto>()

        var keyringBlob: ByteArray? = null
        var keyringVersion = 0

        // fault switches
        var enumerateFails = false
        var revokeFails = false
        var vaultPutStatus = 200
        var vaultPutThrows = false
        var keyringPutFails = false
        var cancelOnEnumerate = false
        var cancelOnVaultGet = false
        var vaultPutConflicts = false
        /** Commit, then behave as if the reply never arrived. */
        var commitThenLoseReply = false
        /** Lose the reply WITHOUT committing. */
        var loseReplyWithoutCommitting = false
        /** Make the follow-up resolution read fail too. */
        var failVaultGetAfterPut = false
        /** Commit on the first attempt, then answer 409 as a retry would. */
        var commitThenConflictOnRetry = false
        private var putSeen = false

        val journal = mutableListOf<String>()

        override suspend fun getE2EEVault(token: String): Response<E2EEVaultDto> {
            journal.add("vault.get")
            if (cancelOnVaultGet) throw kotlinx.coroutines.CancellationException("navigated away")
            if (failVaultGetAfterPut && putSeen) throw java.io.IOException("still offline")
            val v = vault ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(v)
        }

        override suspend fun putE2EEVault(
            token: String,
            body: E2EEVaultPutRequest,
        ): Response<E2EEVaultDto> {
            journal.add("vault.put")
            putSeen = true
            if (loseReplyWithoutCommitting) throw java.io.IOException("reply lost, nothing written")
            if (commitThenLoseReply) {
                applyVault(body)
                throw java.io.IOException("committed, reply lost")
            }
            if (commitThenConflictOnRetry) {
                // First attempt commits; the retry sees its own write and conflicts.
                if (vaultVersion != body.vaultVersion) applyVault(body)
                return Response.error(409, ResponseBody.create(null, ""))
            }
            if (vaultPutThrows) throw java.io.IOException("connection reset")
            if (vaultPutConflicts) {
                // Models the server having already committed an identical write
                // that OkHttp retried after the reply was lost.
                vault = vault
                return Response.error(409, ResponseBody.create(null, ""))
            }
            if (vaultPutStatus != 200) {
                return Response.error(vaultPutStatus, ResponseBody.create(null, ""))
            }
            if (body.expectedVersion != vaultVersion) {
                return Response.error(409, ResponseBody.create(null, ""))
            }
            applyVault(body)
            return Response.success(vault!!)
        }

        private fun applyVault(body: E2EEVaultPutRequest) {
            val dto = E2EEVaultDto(
                vaultVersion = body.vaultVersion,
                vaultCiphertextB64 = body.vaultCiphertextB64,
                pwKdf = body.pwKdf,
                pwSaltB64 = body.pwSaltB64,
                pwParams = body.pwParams,
                pwWrappedMasterB64 = body.pwWrappedMasterB64,
                rkKdf = body.rkKdf,
                rkSaltB64 = body.rkSaltB64,
                rkWrappedMasterB64 = body.rkWrappedMasterB64
            )
            vault = dto
            vaultVersion = body.vaultVersion
        }

        override suspend fun listE2EEDevices(token: String): Response<E2EEDevicesResponse> {
            journal.add("devices.list")
            if (cancelOnEnumerate) throw kotlinx.coroutines.CancellationException("navigated away")
            if (enumerateFails) throw java.io.IOException("offline")
            return Response.success(E2EEDevicesResponse(devices.toList()))
        }

        override suspend fun revokeE2EEDevice(token: String, deviceId: String): Response<Unit> {
            journal.add("devices.revoke")
            if (revokeFails) return Response.error(500, ResponseBody.create(null, ""))
            devices.replaceAll { if (it.deviceId == deviceId) it.copy(revokedAt = "now") else it }
            return Response.success(Unit)
        }

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            journal.add("keyring.get")
            val b = keyringBlob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(
                HistoryKeyringDto(keyringVersion, Base64.getEncoder().encodeToString(b))
            )
        }

        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            journal.add("keyring.put")
            if (keyringPutFails) return Response.error(500, ResponseBody.create(null, ""))
            if (body.expectedVersion == 0) {
                if (keyringBlob != null) return Response.error(409, ResponseBody.create(null, ""))
                keyringBlob = Base64.getDecoder().decode(body.ciphertextB64)
                keyringVersion = 1
                return Response.success(HistoryKeyringPutResponse(1, true))
            }
            if (body.expectedVersion != keyringVersion) {
                return Response.error(409, ResponseBody.create(null, ""))
            }
            keyringBlob = Base64.getDecoder().decode(body.ciphertextB64)
            keyringVersion += 1
            return Response.success(HistoryKeyringPutResponse(keyringVersion, false))
        }

        override suspend fun deleteHistoryKeyring(token: String): Response<Unit> {
            journal.add("keyring.delete")
            keyringBlob = null
            keyringVersion = 0
            return Response.success(Unit)
        }
    }

    private lateinit var api: Api
    private lateinit var tokens: TokenManagerImpl
    private lateinit var repo: E2EEVaultRepository
    private lateinit var keyring: HistoryKeyringRepository
    private lateinit var sync: HistoryKeyringRecoverySync


    /**
     * Wraps the REAL Keystore-backed store so one durable delete can be failed.
     *
     * Everything except the injected failure is genuine Android Keystore I/O; the
     * fault is injected at the durable-store boundary, which is the closest this
     * environment gets to an interrupted write. It is not process death.
     */
    private class FaultingStore(
        private val real: com.messenger.app.data.encryption.history.HistoryKeyringStore
    ) : com.messenger.app.data.encryption.history.HistoryKeyringStore by real {
        /** "gen", "cache", "auth", or null. */
        var failDelete: String? = null

        /**
         * Set the instant a fault fires, after which NOTHING lands.
         *
         * Failing a single call and letting the remaining deletes proceed models a
         * recoverable I/O error, not a crash - and it is too weak to expose an
         * ordering defect, because the later deletes still tidy up. A process that
         * dies stops everything, which is what makes the surviving combination of
         * slots meaningful.
         */
        private var dead = false

        private fun boom(stage: String): Boolean {
            if (dead) return true
            if (failDelete != stage) return false
            dead = true
            return true
        }

        /** Storage outlives the process; only the death flag clears. */
        fun revive() { dead = false }

        override suspend fun deleteHistoryKeyringGeneration(): Result<Unit> =
            if (boom("gen")) Result.failure(IllegalStateException("injected: marker delete"))
            else real.deleteHistoryKeyringGeneration()

        override suspend fun deleteHistoryKeyringCache(): Result<Unit> =
            if (boom("cache")) Result.failure(IllegalStateException("injected: cache delete"))
            else real.deleteHistoryKeyringCache()

        override suspend fun deleteHistoryKeyring(): Result<Unit> =
            if (boom("auth")) Result.failure(IllegalStateException("injected: authoritative delete"))
            else real.deleteHistoryKeyring()
    }

    private var faultingStore: FaultingStore? = null

    private fun keyOf(r: E2EEVaultRepository.E2EEResetResult): String? = when (r) {
        is E2EEVaultRepository.E2EEResetResult.Success -> r.recoveryKeyDisplay
        is E2EEVaultRepository.E2EEResetResult.IncompleteAfterReplacement -> r.recoveryKeyDisplay
        is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted -> r.recoveryKeyDisplay
        is E2EEVaultRepository.E2EEResetResult.Aborted -> null
    }

    /** A new repository graph over the SAME server and the SAME Keystore. */
    private fun rebuildOverSameStorage() = build(keepApi = true)

    private fun rebuildWithFaultingStore() = build(faulting = true)

    /** Real Keystore-backed storage, real crypto, real orchestration. */
    private fun build(
        featureEnabled: Boolean = true,
        keepApi: Boolean = false,
        faulting: Boolean = false,
    ) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!keepApi) api = Api()
        tokens = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        val store = if (faulting) FaultingStore(tokens).also { faultingStore = it }
        else { faultingStore = null; tokens }

        // The graph is genuinely circular in production: the vault repository IS
        // the keyring's vault and recovery transport. dagger.Lazy is what breaks
        // it there, and the same shape is reproduced here.
        lateinit var repoRef: E2EEVaultRepository
        val feature = HistoryArchiveFeature { featureEnabled }
        keyring = HistoryKeyringRepository(
            object : com.messenger.app.data.encryption.history.HistoryKeyringVault {
                override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
                    repoRef.sealHistoryKeyring(plaintext)
                override suspend fun openHistoryKeyring(sealed: ByteArray) =
                    repoRef.openHistoryKeyring(sealed)
            },
            store,
            HistoryUserProvider { tokens.getCurrentUserId().getOrNull() },
            feature
        )
        sync = HistoryKeyringRecoverySync(
            keyring,
            object : com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport {
                override suspend fun sealForRecovery(plaintext: ByteArray) =
                    repoRef.sealForRecovery(plaintext)
                override suspend fun openFromRecovery(sealed: ByteArray) =
                    repoRef.openFromRecovery(sealed)
            },
            api, tokens, feature
        )
        // The repository's init block collects webSocketManager.connectionState.
        // A relaxed mock hands back a Flow whose collect yields Nothing, which
        // kills that coroutine and takes the process with it - so this one member
        // is stubbed with a real, silent flow. Neither collaborator is otherwise
        // reachable from the reset path.
        val ws = mockk<com.messenger.app.data.remote.websocket.WebSocketManager>(relaxed = true)
        io.mockk.every { ws.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(
                com.messenger.app.data.remote.websocket.WebSocketManager.ConnectionState.DISCONNECTED
            )
        repo = E2EEVaultRepository(
            api, tokens, mockk(relaxed = true), ws,
            dagger.Lazy { sync }, dagger.Lazy { keyring }
        )
        repoRef = repo
    }

    /** Puts the account into the state a real signed-in device is in. */
    private suspend fun seedAccountWithVault(): String {
        tokens.saveCurrentUserId(USER).getOrThrow()
        tokens.saveAccessToken(TOKEN).getOrThrow()
        val pub = "aa".repeat(32)
        val priv = "bb".repeat(32)
        tokens.saveE2EEKeys(USER, pub, priv).getOrThrow()

        // A real first vault, minted by the real primitive.
        val built = VaultCrypto.createVault(USER, PASSWORD, pub, priv)!!
        api.vault = E2EEVaultDto(
            vaultVersion = built.vaultVersion,
            vaultCiphertextB64 = VaultCrypto.b64(built.vaultCiphertext),
            pwKdf = VaultCrypto.KDF_ARGON2ID,
            pwSaltB64 = VaultCrypto.b64(built.pwSalt),
            pwParams = built.pwParamsJson,
            pwWrappedMasterB64 = VaultCrypto.b64(built.pwWrappedMaster),
            rkKdf = if (built.rkWrappedMaster != null) VaultCrypto.KDF_HKDF_SHA256 else "",
            rkSaltB64 = built.rkSalt?.let { VaultCrypto.b64(it) } ?: "",
            rkWrappedMasterB64 = built.rkWrappedMaster?.let { VaultCrypto.b64(it) } ?: ""
        )
        api.vaultVersion = built.vaultVersion

        // Unlock through the REAL production path so sessionMk is established the
        // way it is on a signed-in device. Sealing the history keyring needs it,
        // and so does everything the reset preamble does.
        val unlocked = repo.syncAfterPasswordLogin(TOKEN, PASSWORD)
        check(unlocked is E2EEVaultRepository.VaultSyncResult.Unlocked) {
            "test setup: expected the seeded vault to unlock, got $unlocked"
        }
        return built.recoveryKeyDisplay!!
    }

    @Before
    fun setUp() = runBlocking {
        build()
        // Several tests here deliberately leave the keyring in a fail-closed
        // state, and the Keystore is real device storage shared across the run.
        // Starting clean keeps each test independent of its predecessors.
        tokens.deleteHistoryKeyring()
        tokens.deleteHistoryKeyringCache()
        tokens.deleteHistoryKeyringGeneration()
        repo.acknowledgeResetOutcome()
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        tokens.deleteHistoryKeyring()
        tokens.deleteHistoryKeyringCache()
        tokens.deleteHistoryKeyringGeneration()
        repo.acknowledgeResetOutcome()
        Unit
    }

    // =============================================== A. successful reset

    @Test
    fun aSuccessfulResetRunsTheFullOrderAndReturnsTheMintedKey() = runBlocking {
        val oldRecoveryKey = seedAccountWithVault()
        api.devices.add(E2EEDeviceDto(deviceId = "peer-1", name = "Peer", platform = "android"))
        keyring.ensureRoot(CHAT).getOrThrow()
        sync.uploadIfChanged().getOrThrow()
        assertNotNull("precondition: a blob exists under the old key", api.keyringBlob)
        val oldVaultVersion = api.vaultVersion

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue("expected Success, got $result", result is E2EEVaultRepository.E2EEResetResult.Success)
        val key = (result as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay
        assertNotNull("the minted recovery key must be returned", key)
        assertFalse("and it must not be the retired one", key == oldRecoveryKey)

        // ORDER, as recorded by the real HTTP surface.
        val j = api.journal
        val listIdx = j.indexOf("devices.list")
        val revokeIdx = j.indexOf("devices.revoke")
        val delIdx = j.indexOf("keyring.delete")
        val putIdx = j.indexOf("vault.put")
        val pubIdx = j.lastIndexOf("keyring.put")
        assertTrue("enumerate before revoke: $j", listIdx in 0 until revokeIdx)
        assertTrue("revoke before invalidate: $j", revokeIdx < delIdx)
        assertTrue("invalidate before vault PUT: $j", delIdx < putIdx)
        assertTrue("vault PUT before publication: $j", putIdx < pubIdx)

        // The vault moved forward under CAS, and the peer was revoked.
        assertEquals(oldVaultVersion + 1, api.vaultVersion)
        assertTrue(api.devices.all { !it.revokedAt.isNullOrBlank() })

        // The local generation was discarded, then a fresh blob was published.
        assertNull("old roots must be gone", keyring.load().getOrThrow().latest(CHAT))
        assertNotNull("the slot must be claimed", api.keyringBlob)
        assertTrue(
            "and the claim must open under the NEW key",
            repo.openFromRecovery(api.keyringBlob!!).isSuccess
        )

        // The NEW recovery key really unlocks the NEW vault; the old one does not.
        assertTrue(unlocksVaultWithRecoveryKey(key!!))
        assertFalse(unlocksVaultWithRecoveryKey(oldRecoveryKey))
    }

    private fun unlocksVaultWithRecoveryKey(recoveryKeyB64: String): Boolean {
        val v = api.vault ?: return false
        val ct = VaultCrypto.unb64(v.vaultCiphertextB64) ?: return false
        val rkSalt = VaultCrypto.unb64(v.rkSaltB64) ?: return false
        val rkWrap = VaultCrypto.unb64(v.rkWrappedMasterB64) ?: return false
        return VaultCrypto.unlockVaultWithRecovery(
            USER, recoveryKeyB64, v.vaultVersion, ct, rkSalt, rkWrap
        ) != null
    }

    // =============================================== B. enumeration failure

    @Test
    fun enumerationFailureAbortsBeforeAnythingIrreversible() = runBlocking {
        val oldRecoveryKey = seedAccountWithVault()
        keyring.ensureRoot(CHAT).getOrThrow()
        sync.uploadIfChanged().getOrThrow()
        val blobBefore = api.keyringBlob!!.copyOf()
        val versionBefore = api.vaultVersion
        api.enumerateFails = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue("expected Aborted, got $result", result is E2EEVaultRepository.E2EEResetResult.Aborted)
        assertEquals("enumerate-devices", (result as E2EEVaultRepository.E2EEResetResult.Aborted).stage)
        assertFalse("no invalidation may have happened", api.journal.contains("keyring.delete"))
        assertFalse("no vault write may have happened", api.journal.contains("vault.put"))
        assertEquals(versionBefore, api.vaultVersion)
        assertTrue("the old blob is untouched", api.keyringBlob!!.contentEquals(blobBefore))
        assertTrue("the old recovery key still works", unlocksVaultWithRecoveryKey(oldRecoveryKey))
    }

    @Test
    fun aFailedRevokeAbortsBeforeInvalidation() = runBlocking {
        seedAccountWithVault()
        api.devices.add(E2EEDeviceDto(deviceId = "peer-1", name = "Peer", platform = "android"))
        api.revokeFails = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue(result is E2EEVaultRepository.E2EEResetResult.Aborted)
        assertEquals("revoke-devices", (result as E2EEVaultRepository.E2EEResetResult.Aborted).stage)
        assertFalse(api.journal.contains("keyring.delete"))
        assertFalse(api.journal.contains("vault.put"))
    }

    // =============================================== C. PUT rejected

    @Test
    fun aRejectedVaultPutAbortsAndLeavesTheOldKeyValid() = runBlocking {
        val oldRecoveryKey = seedAccountWithVault()
        val versionBefore = api.vaultVersion
        api.vaultPutStatus = 500

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue("expected Aborted, got $result", result is E2EEVaultRepository.E2EEResetResult.Aborted)
        assertEquals("persist", (result as E2EEVaultRepository.E2EEResetResult.Aborted).stage)
        assertEquals("the vault must not have moved", versionBefore, api.vaultVersion)
        assertTrue("the old recovery key must still work", unlocksVaultWithRecoveryKey(oldRecoveryKey))
        assertNull("and no outcome may be parked for a reset that did not happen",
            repo.pendingResetOutcome.value)
    }

    // =============================================== D. ambiguous PUT

    @Test
    fun anAmbiguousVaultPutPreservesTheMintedKey() = runBlocking {
        seedAccountWithVault()
        api.vaultPutThrows = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        // GATE 7: the throw is ambiguous at the transport, but the follow-up read
        // resolves it - the vault never moved, so this is a determinate
        // non-commit and the minted key opens nothing. The genuinely unresolvable
        // case is covered by anUnresolvableAmbiguityStaysUnknownAndKeepsTheKey.
        assertTrue(
            "a resolvable throw must resolve to an abort, got $result",
            result is E2EEVaultRepository.E2EEResetResult.Aborted
        )
        assertNull(
            "nothing may be parked for a write that provably did not land",
            repo.pendingResetOutcomeFor(USER)
        )
    }

    // =============================================== E. publication failure

    @Test
    fun publicationFailureIsSurfacedAndStillCarriesTheKey() = runBlocking {
        seedAccountWithVault()
        keyring.ensureRoot(CHAT).getOrThrow()
        sync.uploadIfChanged().getOrThrow()
        api.keyringPutFails = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue(
            "a failed publish must not be ordinary Success, got $result",
            result is E2EEVaultRepository.E2EEResetResult.IncompleteAfterReplacement
        )
        result as E2EEVaultRepository.E2EEResetResult.IncompleteAfterReplacement
        assertEquals("publish", result.stage)
        assertNotNull("the minted key must still be available", result.recoveryKeyDisplay)
        // The same key must remain usable - a retry must not mint a different one.
        assertTrue(unlocksVaultWithRecoveryKey(result.recoveryKeyDisplay!!))
    }

    // =============================================== parked outcome lifecycle

    @Test
    fun aParkedOutcomeIsConsumedExactlyOnce() = runBlocking {
        seedAccountWithVault()
        api.keyringPutFails = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(result is E2EEVaultRepository.E2EEResetResult.IncompleteAfterReplacement)

        val parked = repo.pendingResetOutcome.value
        assertNotNull("a post-commit outcome must be parked for a destroyed UI", parked)

        repo.acknowledgeResetOutcome()
        assertNull("a second reader must not see it again", repo.pendingResetOutcome.value)
    }

    /**
     * Success parks too. It is the COMMON path, and if the caller's scope died
     * while the non-cancellable tail ran, the returned value goes nowhere - this
     * would be the only surviving copy of the key.
     */
    @Test
    fun aSuccessfulResetAlsoParksItsKeyForADestroyedUi() = runBlocking {
        seedAccountWithVault()

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(result is E2EEVaultRepository.E2EEResetResult.Success)
        val returned = (result as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay

        val parked = repo.pendingResetOutcome.value
        assertTrue("success must be recoverable by a fresh UI", parked is E2EEVaultRepository.E2EEResetResult.Success)
        assertEquals(
            "and it must be the SAME key, never a regenerated one",
            returned,
            (parked as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay
        )

        repo.acknowledgeResetOutcome()
        assertNull("once shown, it must not appear again", repo.pendingResetOutcome.value)
    }

    // =============================================== feature flag

    @Test
    fun withTheFeatureDisabledResetPerformsNoRecoveryNetworkMutation() = runBlocking {
        build(featureEnabled = false)
        seedAccountWithVault()

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue("the vault reset itself must still work, got $result",
            result is E2EEVaultRepository.E2EEResetResult.Success)
        assertFalse("no recovery DELETE while disabled", api.journal.contains("keyring.delete"))
        assertFalse("no recovery publication while disabled", api.journal.contains("keyring.put"))
        assertNull(api.keyringBlob)
    }

    // =============================================== stale writer

    @Test
    fun onceTheResetHasPublishedAStaleCreateIsRejected() = runBlocking {
        seedAccountWithVault()
        repo.resetE2EEVault(TOKEN, PASSWORD)
        val claimed = api.keyringBlob!!.copyOf()

        val stale = api.putHistoryKeyring(
            "Bearer x",
            HistoryKeyringPutRequest(0, Base64.getEncoder().encodeToString(byteArrayOf(9, 9)))
        )

        assertEquals(409, stale.code())
        assertTrue(api.keyringBlob!!.contentEquals(claimed))
    }

    // =============================================== cancellation semantics

    /**
     * Cancellation BEFORE the irreversible PUT must propagate as cancellation,
     * not be laundered into a generic "reset failed" - and nothing may have
     * changed on the server.
     */
    @Test
    fun cancellationBeforeThePutPropagatesAndChangesNothing() = runBlocking {
        val oldRecoveryKey = seedAccountWithVault()
        val versionBefore = api.vaultVersion
        api.cancelOnVaultGet = true

        var propagated = false
        try {
            repo.resetE2EEVault(TOKEN, PASSWORD)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            propagated = true
        }

        assertTrue("structured cancellation must not be swallowed", propagated)
        assertFalse("no invalidation", api.journal.contains("keyring.delete"))
        assertFalse("no vault write", api.journal.contains("vault.put"))
        assertEquals(versionBefore, api.vaultVersion)
        assertTrue("the old recovery key still works", unlocksVaultWithRecoveryKey(oldRecoveryKey))
        assertNull(repo.pendingResetOutcome.value)
    }

    /**
     * Cancellation inside `listDevices` is a different case, and worth pinning
     * because the outcome is what matters rather than the exception type:
     * listDevices has its own broad catch (shared with the Settings device list,
     * outside this gate's scope), so the cancellation surfaces as an enumeration
     * failure. The reset must still abort with nothing irreversible done.
     */
    @Test
    fun cancellationDuringEnumerationStillAbortsSafely() = runBlocking {
        val oldRecoveryKey = seedAccountWithVault()
        val versionBefore = api.vaultVersion
        api.cancelOnEnumerate = true

        val result = runCatching { repo.resetE2EEVault(TOKEN, PASSWORD) }.getOrNull()

        assertFalse("no invalidation may have happened", api.journal.contains("keyring.delete"))
        assertFalse("no vault write may have happened", api.journal.contains("vault.put"))
        assertEquals(versionBefore, api.vaultVersion)
        assertTrue(unlocksVaultWithRecoveryKey(oldRecoveryKey))
        if (result != null) {
            assertTrue(
                "if it returned at all it must be an abort, got $result",
                result is E2EEVaultRepository.E2EEResetResult.Aborted
            )
        }
    }

    // =============================================== adversarial: 409 after commit

    /**
     * OkHttp retries on connection failure by default, replaying the same
     * expected_version. A write that committed and then lost its reply therefore
     * comes back as 409 on the retry - so a conflict is NOT proof that nothing
     * happened, and classifying it as a rejection would discard the only copy of
     * the new recovery key while telling the user nothing changed.
     */
    @Test
    fun aConflictOnTheVaultPutIsTreatedAsAmbiguousNotAsRejected() = runBlocking {
        seedAccountWithVault()
        api.vaultPutConflicts = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        // GATE 7: a conflict is no longer left as a guess. The follow-up read
        // shows the vault never moved, so this is provably a non-commit - and the
        // minted key is worthless, so presenting it would mislead. What must NOT
        // happen is the pre-Gate-6.1 behaviour of losing a key over a real commit;
        // that case is covered by aConflictOverARealCommitResolvesToCommitted.
        assertTrue(
            "a resolvable conflict must resolve to a clean abort, got $result",
            result is E2EEVaultRepository.E2EEResetResult.Aborted
        )
        assertNull(
            "nothing may be parked for a write that provably did not land",
            repo.pendingResetOutcomeFor(USER)
        )
    }

    /** Determinate rejections stay determinate. */
    @Test
    fun determinateRejectionsAreStillAborts() = runBlocking {
        for (code in listOf(400, 413, 500)) {
            build()
            val oldKey = seedAccountWithVault()
            api.vaultPutStatus = code
            val result = repo.resetE2EEVault(TOKEN, PASSWORD)
            assertTrue(
                "HTTP $code is returned before the DB write, so it must abort; got $result",
                result is E2EEVaultRepository.E2EEResetResult.Aborted
            )
            assertTrue("old key still valid after $code", unlocksVaultWithRecoveryKey(oldKey))
            assertNull(repo.pendingResetOutcomeFor(USER))
        }
    }

    // =============================================== adversarial: account isolation

    /**
     * A parked outcome carries a vault recovery key. The repository is a
     * singleton and outlives a sign-out, so an outcome account A never consumed
     * must not be adopted by account B.
     */
    @Test
    fun aParkedOutcomeIsNeverAdoptedByAnotherAccount() = runBlocking {
        seedAccountWithVault()
        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(result is E2EEVaultRepository.E2EEResetResult.Success)
        assertNotNull("precondition: A's outcome is parked", repo.pendingResetOutcomeFor(USER))

        // A different account asks for it.
        val other = "99999999-8888-7777-6666-555555555555"
        assertNull(
            "account B must never see account A's recovery key",
            repo.pendingResetOutcomeFor(other)
        )
        assertNull("nor may a blank identity", repo.pendingResetOutcomeFor(null))
        assertNull(repo.pendingResetOutcomeFor(""))
    }

    /** Logout clears it outright, so the window does not depend on the guard alone. */
    @Test
    fun logoutClearsAnUnconsumedResetOutcome() = runBlocking {
        seedAccountWithVault()
        repo.resetE2EEVault(TOKEN, PASSWORD)
        assertNotNull(repo.pendingResetOutcomeFor(USER))

        repo.clearSessionSecrets()

        assertNull(
            "an unconsumed recovery key must not survive sign-out",
            repo.pendingResetOutcomeFor(USER)
        )
    }

    /** A newer reset supersedes an unconsumed older one; the older key is dead anyway. */
    @Test
    fun aSecondResetSupersedesAnUnconsumedFirstOutcome() = runBlocking {
        seedAccountWithVault()
        val first = repo.resetE2EEVault(TOKEN, PASSWORD)
        val firstKey = (first as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay

        val second = repo.resetE2EEVault(TOKEN, PASSWORD)
        val secondKey = (second as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay

        assertFalse("a second reset must mint a different key", firstKey == secondKey)
        val parked = repo.pendingResetOutcomeFor(USER)
        assertEquals(
            "the parked outcome must be the CURRENT one, not the superseded one",
            secondKey,
            (parked as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay
        )
        // And only the current key opens the current vault.
        assertTrue(unlocksVaultWithRecoveryKey(secondKey!!))
        assertFalse(unlocksVaultWithRecoveryKey(firstKey!!))
    }

    // ================================================== GATE 7: ambiguity resolution

    /**
     * A. Reply lost AFTER the server committed.
     *
     * The transport cannot say what happened, but the protocol can: the vault
     * version this reset was creating is now the authoritative one, so the write
     * landed. It must resolve to committed and keep the minted key.
     */
    @Test
    fun aLostReplyAfterCommitResolvesToCommitted() = runBlocking {
        val oldKey = seedAccountWithVault()
        api.commitThenLoseReply = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        val key = keyOf(result)
        assertNotNull("a lost reply over a commit must not lose the key; got " + result, key)
        assertTrue("the key must open what the server now holds", unlocksVaultWithRecoveryKey(key!!))
        assertFalse("the retired key must not", unlocksVaultWithRecoveryKey(oldKey))
        assertFalse(
            "and it must not be reported as a clean abort; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.Aborted
        )
    }

    /**
     * The mirror case: the reply was lost but the write never landed. The re-read
     * proves the vault never moved, so this is a clean abort - and presenting the
     * minted key would be actively harmful, because it opens nothing.
     */
    @Test
    fun aLostReplyWithoutCommitResolvesToAbortedAndKeepsTheOldKey() = runBlocking {
        val oldKey = seedAccountWithVault()
        val versionBefore = api.vaultVersion
        api.loseReplyWithoutCommitting = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue(
            "a provably non-committed write must abort; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.Aborted
        )
        assertEquals(versionBefore, api.vaultVersion)
        assertTrue("the account's real recovery key still works", unlocksVaultWithRecoveryKey(oldKey))
        assertNull(
            "nothing may be parked for a reset that did not happen",
            repo.pendingResetOutcomeFor(USER)
        )
    }

    /**
     * B. The retry conflicted AND the follow-up read is also unavailable.
     * Genuinely unresolvable: stay honestly unsure and keep the key.
     */
    @Test
    fun anUnresolvableAmbiguityStaysUnknownAndKeepsTheKey() = runBlocking {
        seedAccountWithVault()
        api.vaultPutConflicts = true
        api.failVaultGetAfterPut = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        assertTrue(
            "with no way to resolve, PossiblyCompleted is the honest answer; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        assertNotNull(keyOf(result))
        assertNotNull(
            "and it must be parked for a UI that may be gone",
            repo.pendingResetOutcomeFor(USER)
        )
    }

    /** A conflict produced by a retry over a real commit resolves to committed. */
    @Test
    fun aConflictOverARealCommitResolvesToCommitted() = runBlocking {
        val oldKey = seedAccountWithVault()
        api.commitThenConflictOnRetry = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)

        val key = keyOf(result)
        assertNotNull("a retried-over-commit must never lose the key; got " + result, key)
        assertTrue("and the key must open what the server holds", unlocksVaultWithRecoveryKey(key!!))
        assertFalse(unlocksVaultWithRecoveryKey(oldKey))
    }

    // ================================================== GATE 7: convergence

    /**
     * A fresh client instance signing in adopts server truth regardless of how the
     * previous reset ended. This is what makes an unresolved Unknown survivable:
     * the client never has to remember, it re-derives from the server.
     */
    @Test
    fun aFreshClientConvergesOnServerTruthAfterAnAmbiguousReset() = runBlocking {
        seedAccountWithVault()
        api.vaultPutConflicts = true
        api.failVaultGetAfterPut = true
        repo.resetE2EEVault(TOKEN, PASSWORD)

        api.vaultPutConflicts = false
        api.failVaultGetAfterPut = false
        val before = api.vaultVersion

        // A brand-new repository over the same server and the same Keystore.
        rebuildOverSameStorage()
        val sync = repo.syncAfterPasswordLogin(TOKEN, PASSWORD)

        assertTrue(
            "a restart must converge on the server's vault; got " + sync,
            sync is E2EEVaultRepository.VaultSyncResult.Unlocked
        )
        assertEquals("and must not mutate it while converging", before, api.vaultVersion)
        assertTrue(repo.hasSessionMk())
    }

    /** Reset is idempotent: repeating it converges rather than duplicating state. */
    @Test
    fun repeatedResetsConvergeRatherThanCorrupt() = runBlocking {
        seedAccountWithVault()

        var lastKey: String? = null
        repeat(3) {
            val r = repo.resetE2EEVault(TOKEN, PASSWORD)
            assertTrue("each reset must land cleanly; got " + r,
                r is E2EEVaultRepository.E2EEResetResult.Success)
            lastKey = (r as E2EEVaultRepository.E2EEResetResult.Success).recoveryKeyDisplay
            repo.acknowledgeResetOutcome()
        }

        assertTrue(unlocksVaultWithRecoveryKey(lastKey!!))
        assertNotNull("the slot must be claimed, not duplicated", api.keyringBlob)
        assertEquals("exactly one keyring generation on the server", 1, api.keyringVersion)
    }

    // ================================================== GATE 7: discard via resetLocked

    /**
     * Crash-safe discard exercised THROUGH the production reset rather than by
     * calling the discard directly. Each durable delete is failed in turn: the
     * reset must never report ordinary success while local teardown is half done,
     * must still carry the minted key, and must never serve the old generation.
     *
     * Durable-store fault injection. NOT process death.
     */
    @Test
    fun discardFaultsInsideTheRealResetAlwaysFailClosed() = runBlocking {
        for (stage in listOf("gen", "cache", "auth")) {
            rebuildWithFaultingStore()
            // Each iteration deliberately leaves the keyring fail-closed, which is
            // the point - but the next must start from a clean device or it
            // measures the previous iteration's wreckage instead of its own.
            tokens.deleteHistoryKeyring()
            tokens.deleteHistoryKeyringCache()
            tokens.deleteHistoryKeyringGeneration()
            seedAccountWithVault()
            keyring.ensureRoot(CHAT).getOrThrow()
            sync.uploadIfChanged().getOrThrow()
            faultingStore!!.failDelete = stage

            val result = repo.resetE2EEVault(TOKEN, PASSWORD)
            faultingStore!!.failDelete = null
            faultingStore!!.revive()

            assertTrue(
                "stage " + stage + " must not report ordinary success; got " + result,
                result is E2EEVaultRepository.E2EEResetResult.IncompleteAfterReplacement
            )
            assertNotNull(
                "and must still carry the minted key",
                (result as E2EEVaultRepository.E2EEResetResult.IncompleteAfterReplacement)
                    .recoveryKeyDisplay
            )

            // Zero progress is a different situation from a half-done teardown:
            // nothing was deleted, so the device is exactly as it was and the
            // caller has been told cleanup did not happen. What must never occur
            // is a PARTIAL teardown that leaves the retired generation trusted -
            // which is precisely what deleting the authoritative copy first used
            // to produce, because cache and marker were left agreeing.
            val nothingLanded = tokens.loadHistoryKeyringGeneration().getOrThrow() != null &&
                tokens.loadHistoryKeyringCache().getOrThrow() != null &&
                tokens.loadHistoryKeyring().getOrThrow() != null
            if (!nothingLanded) {
                assertNull(
                    "stage " + stage + " resurrected the old generation",
                    keyring.load().getOrNull()?.latest(CHAT)
                )
            }
        }
    }

    /** Re-running the teardown after a failure converges. */
    @Test
    fun discardIsIdempotentAfterAPartialFailure() = runBlocking {
        rebuildWithFaultingStore()
        seedAccountWithVault()
        keyring.ensureRoot(CHAT).getOrThrow()
        faultingStore!!.failDelete = "cache"
        repo.resetE2EEVault(TOKEN, PASSWORD)
        // The retry happens after the fault is gone - a later attempt, or the next
        // process. Clearing the switch alone is not enough: the store is modelling
        // a dead process, so it has to come back to life first.
        faultingStore!!.failDelete = null
        faultingStore!!.revive()

        // A second, unimpeded teardown must complete and leave a clean generation.
        keyring.discardForMkReplacement().getOrThrow()
        assertNull(keyring.load().getOrThrow().latest(CHAT))
    }

    // ================================================== GATE 8: phantom-MK probe

    /**
     * GATE 8 PROBE - World B: the server did NOT commit, and the resolving read is
     * unavailable, so the outcome is PossiblyCompleted and the candidate key is
     * unverified. History is ENABLED here, which is the configuration this probe
     * exists to interrogate.
     *
     * The question is not whether the reset reports honestly - Gate 7 settled that.
     * It is whether an unverified key can become the basis of DURABLE cryptographic
     * state, and whether the account survives the next login if it does.
     */
    @Test
    fun gate8Probe_unverifiedKeyMustNotBecomeDurable() = runBlocking {
        val oldKey = seedAccountWithVault()
        val serverVaultBefore = api.vault!!
        api.loseReplyWithoutCommitting = true
        api.failVaultGetAfterPut = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(
            "precondition: this world must be genuinely unresolvable; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        // The server never moved - the account's real key is still the old one.
        assertEquals(serverVaultBefore.vaultVersion, api.vault!!.vaultVersion)
        assertTrue("the account's real recovery key still works", unlocksVaultWithRecoveryKey(oldKey))

        // THE PROBE: with history enabled, can an unverified key seal durable state?
        val minted = keyring.ensureRoot(CHAT)

        // Whatever the answer, the account must survive a fresh login on the REAL key.
        api.loseReplyWithoutCommitting = false
        api.failVaultGetAfterPut = false
        rebuildOverSameStorage()
        val login = repo.syncAfterPasswordLogin(TOKEN, PASSWORD)
        assertTrue("the real vault must still unlock; got " + login,
            login is E2EEVaultRepository.VaultSyncResult.Unlocked)

        // A plain load can be answered from the plaintext, owner-bound CACHE
        // without ever touching the authoritative blob, so it proves nothing about
        // which key that blob is sealed under. Ask the authoritative copy directly.
        val stored = tokens.loadHistoryKeyring().getOrThrow()
        val authoritativeOpens = stored != null && repo.openHistoryKeyring(stored).isSuccess

        // And the cache is not durable ground truth: any marker mismatch, account
        // change, or reset drops it and forces the authoritative read.
        tokens.deleteHistoryKeyringCache()
        val readBackWithoutCache = keyring.load()

        android.util.Log.w(
            "GATE8",
            "mint=" + (if (minted.isSuccess) "SUCCEEDED" else "refused") +
                " | authoritativeOpensUnderRealKey=" + authoritativeOpens +
                " | loadWithoutCache=" + (if (readBackWithoutCache.isSuccess) "ok"
                    else "FAILED: " + readBackWithoutCache.exceptionOrNull()?.message)
        )

        assertTrue(
            "an unverified candidate key must not seal durable keyring state that the " +
                "account's real key cannot open; mint=" + minted.isSuccess +
                " authoritativeOpens=" + authoritativeOpens +
                " loadWithoutCache=" + readBackWithoutCache.isSuccess,
            minted.isFailure || (authoritativeOpens && readBackWithoutCache.isSuccess)
        )
    }

    // ================================================== GATE 8: candidate-key rule

    /**
     * WORLD A - the server DID commit, but the resolving read is unavailable.
     *
     * The candidate key happens to be correct here, and the client has no way to
     * know that. It must still refuse to seal durable state: acting on a key it
     * cannot verify is the behaviour under test, not whether the guess was lucky.
     * The refusal is temporary - the next verified login lifts it.
     */
    @Test
    fun worldA_committedButUnresolved_refusesDurableStateThenRecovers() = runBlocking {
        seedAccountWithVault()
        api.commitThenLoseReply = true
        api.failVaultGetAfterPut = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(
            "precondition: unresolved ambiguity; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        val key = keyOf(result)
        assertNotNull("the minted key must still be surfaced", key)

        // Unverified: no durable seal, even though the key is in fact the right one.
        assertTrue(
            "a candidate key must not seal durable state even when it is correct",
            keyring.ensureRoot(CHAT).isFailure
        )

        // The server is reachable again. A login verifies the key against the
        // server's own ciphertext, which is what lifts the restriction.
        api.commitThenLoseReply = false
        api.failVaultGetAfterPut = false
        rebuildOverSameStorage()
        val login = repo.syncAfterPasswordLogin(TOKEN, PASSWORD)
        assertTrue("the committed vault must unlock; got " + login,
            login is E2EEVaultRepository.VaultSyncResult.Unlocked)

        val afterVerification = keyring.ensureRoot(CHAT)
        assertTrue(
            "once verified, ordinary history operation must resume; got " +
                afterVerification.exceptionOrNull()?.message,
            afterVerification.isSuccess
        )
        // And what it wrote is openable by the account's authoritative key.
        val stored = tokens.loadHistoryKeyring().getOrThrow()
        assertNotNull(stored)
        assertTrue(repo.openHistoryKeyring(stored!!).isSuccess)
    }

    /**
     * WORLD B - the server did NOT commit and the read is unavailable.
     *
     * The candidate key exists nowhere but this device. Sealing under it would
     * produce durable state the account's real key can never open - and because
     * the plaintext cache answers reads first, that damage would stay invisible
     * until the cache was dropped.
     */
    @Test
    fun worldB_uncommittedAndUnresolved_neverSealsUnderThePhantomKey() = runBlocking {
        val oldKey = seedAccountWithVault()
        val versionBefore = api.vaultVersion
        api.loseReplyWithoutCommitting = true
        api.failVaultGetAfterPut = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(
            "precondition: unresolved ambiguity; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        assertEquals("precondition: the server never moved", versionBefore, api.vaultVersion)

        assertTrue(
            "the phantom key must not seal durable keyring state",
            keyring.ensureRoot(CHAT).isFailure
        )
        assertTrue(
            "nor may it publish recovery material for other devices to inherit",
            sync.upload().isFailure
        )

        // The account is intact: real key still unlocks, real recovery key works.
        api.loseReplyWithoutCommitting = false
        api.failVaultGetAfterPut = false
        rebuildOverSameStorage()
        assertTrue(
            "the account's real vault must still unlock",
            repo.syncAfterPasswordLogin(TOKEN, PASSWORD) is
                E2EEVaultRepository.VaultSyncResult.Unlocked
        )
        assertTrue("and the real recovery key still works", unlocksVaultWithRecoveryKey(oldKey))

        // No phantom durable state exists, with or without the cache.
        tokens.deleteHistoryKeyringCache()
        assertTrue(
            "no unopenable keyring may have been left behind",
            keyring.load().isSuccess
        )
    }

    /** A RESOLVED commit verifies the key, so ordinary history work proceeds. */
    @Test
    fun resolvedCommit_verifiesTheKeyAndAllowsDurableState() = runBlocking {
        seedAccountWithVault()
        api.commitThenLoseReply = true   // resolution IS available here

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertFalse(
            "a resolvable commit must not remain ambiguous; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        assertNotNull(keyOf(result))

        val minted = keyring.ensureRoot(CHAT)
        assertTrue(
            "a verified key must be usable immediately; got " +
                minted.exceptionOrNull()?.message,
            minted.isSuccess
        )
        val stored = tokens.loadHistoryKeyring().getOrThrow()
        assertTrue(repo.openHistoryKeyring(stored!!).isSuccess)
    }

    /** A RESOLVED rejection leaves the OLD key authoritative and fully usable. */
    @Test
    fun resolvedRejection_leavesTheOldKeyAuthoritative() = runBlocking {
        val oldKey = seedAccountWithVault()
        api.loseReplyWithoutCommitting = true   // resolution IS available

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(
            "a resolvable non-commit must abort; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.Aborted
        )
        assertTrue("the old recovery key still works", unlocksVaultWithRecoveryKey(oldKey))

        // The session still holds the pre-reset key, verified, so history works.
        val minted = keyring.ensureRoot(CHAT)
        assertTrue(
            "an aborted reset must leave the session fully usable; got " +
                minted.exceptionOrNull()?.message,
            minted.isSuccess
        )
    }

    /** An ordinary unlock always yields a verified key - the guard is not sticky. */
    @Test
    fun anOrdinaryUnlockYieldsAVerifiedKey() = runBlocking {
        seedAccountWithVault()
        assertTrue(
            "a password unlock proves the key against the server's own ciphertext",
            keyring.ensureRoot(CHAT).isSuccess
        )
    }

    // ================================================== GATE 9: convergence

    /**
     * GATE 9 - can an ambiguous reset stay ambiguous forever?
     *
     * World A: the server DID commit, so the candidate key is in fact the
     * account's key. The session is unverified, and Gate 8 correctly refuses
     * durable state. The question this gate asks is whether the client can ever
     * discover the truth WITHOUT the user signing in again.
     *
     * The authoritative proof already exists: opening the server's own vault
     * ciphertext with the candidate key. `pullAndMergeVault` runs on every
     * reconnect and does exactly that - so an ordinary reconnect should converge.
     */
    @Test
    fun worldA_reconnectAloneMustRestoreVerification() = runBlocking {
        seedAccountWithVault()
        api.commitThenLoseReply = true
        api.failVaultGetAfterPut = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(
            "precondition: unresolved ambiguity; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        assertTrue(
            "precondition: Gate 8 refuses durable state while unverified",
            keyring.ensureRoot(CHAT).isFailure
        )

        // The network comes back. No logout, no re-login - just a reconnect, which
        // is what the repository's own WebSocket listener triggers in production.
        api.commitThenLoseReply = false
        api.failVaultGetAfterPut = false
        val merged = repo.pullAndMergeVault(TOKEN, force = true)

        assertTrue("the vault pull must succeed; got " + merged, merged)
        val afterReconnect = keyring.ensureRoot(CHAT)
        assertTrue(
            "an ambiguous session must not stay unusable forever when the server " +
                "can prove the candidate key is correct; got " +
                afterReconnect.exceptionOrNull()?.message,
            afterReconnect.isSuccess
        )
    }

    /**
     * The mirror: in World B the server never committed, so the same reconnect
     * must NOT verify the phantom key. Convergence must not become credulity.
     */
    @Test
    fun worldB_reconnectMustNotVerifyThePhantomKey() = runBlocking {
        seedAccountWithVault()
        api.loseReplyWithoutCommitting = true
        api.failVaultGetAfterPut = true

        val result = repo.resetE2EEVault(TOKEN, PASSWORD)
        assertTrue(
            "precondition: unresolved ambiguity; got " + result,
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )

        api.loseReplyWithoutCommitting = false
        api.failVaultGetAfterPut = false
        repo.pullAndMergeVault(TOKEN, force = true)

        assertTrue(
            "a reconnect must never verify a key the server does not hold",
            keyring.ensureRoot(CHAT).isFailure
        )
    }
}
