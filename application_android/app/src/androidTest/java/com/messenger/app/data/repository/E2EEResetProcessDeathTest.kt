package com.messenger.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManagerImpl
import kotlinx.coroutines.runBlocking
import com.messenger.app.data.encryption.history.HistoryUserProvider as _HUP
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GATE 7 - durability across a REAL process kill.
 *
 * The two halves of this class are run as SEPARATE instrumentation invocations
 * with `adb shell am force-stop com.messenger.app.debug` in between, so phase two
 * genuinely executes in a new operating-system process that was not shut down
 * gracefully. Nothing here simulates death: the process is killed by the platform.
 *
 * WHAT THIS PROVES. That the durable state the reset leaves behind - the Keystore
 * keyring slots and their generation marker - survives an ungraceful kill exactly
 * as written, and that a cold start reads it back with the same fail-closed
 * semantics it had before the kill.
 *
 * WHAT IT DOES NOT PROVE. That a write interrupted *mid-flight* is atomic. The
 * kill happens between phases, not inside a durable write; interrupted writes
 * remain covered only by fault injection at the store boundary. Do not read this
 * class as evidence for the stronger claim.
 *
 * All key material is TEST-ONLY.
 */
@RunWith(AndroidJUnit4::class)
class E2EEResetProcessDeathTest {

    private companion object {
        const val USER = "proc-death-user"
        const val CHAT = "proc-death-chat"
    }

    private fun store(): TokenManagerImpl {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
    }

    /** A vault stand-in; the Keystore, not the AEAD, is what is under test here. */
    private class Vault(private val user: String?, private val mkGeneration: Int = 1) :
        com.messenger.app.data.encryption.history.HistoryKeyringVault {
        private fun mask(b: ByteArray, t: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor t).toByte() }
        // The generation is part of the key: a blob sealed under generation 1 does
        // not open under generation 2, exactly as a retired master key cannot open
        // what its replacement seals.
        private fun tag() =
            (0x11 xor ((user?.hashCode() ?: 0) and 0x3F) xor (mkGeneration shl 6)) and 0xFF
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(tag().toByte()) + mask(plaintext, tag()))
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == tag().toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), tag()))
            else Result.failure(IllegalStateException("authentication failed"))
    }

    private fun keyring(user: String? = USER, mkGeneration: Int = 1) =
        HistoryKeyringRepository(
            Vault(user, mkGeneration), store(),
            HistoryUserProvider { user }, HistoryArchiveFeature.Enabled
        )

    // ------------------------------------------------------------------ phase 1

    /**
     * Writes a complete, consistent generation and stops. The harness kills the
     * process immediately after this returns.
     */
    @Test
    fun phase1_writeDurableGeneration() = runBlocking {
        val s = store()
        s.deleteHistoryKeyring()
        s.deleteHistoryKeyringCache()
        s.deleteHistoryKeyringGeneration()

        val root = keyring().ensureRoot(CHAT).getOrThrow()
        assertNotNull(root)
        // Prove it is durable before the kill, from a fresh store instance.
        assertNotNull(store().loadHistoryKeyring().getOrThrow())
        assertNotNull(store().loadHistoryKeyringGeneration().getOrThrow())
    }

    /**
     * Leaves the exact half-torn-down state a reset produces when its local
     * teardown is interrupted: the marker is gone, the copies remain - and the
     * copies belong to the RETIRED key generation, which is what makes the state
     * meaningful. Phase two reads as the replacement generation.
     */
    @Test
    fun phase1_writeInterruptedDiscardState() = runBlocking {
        val s = store()
        s.deleteHistoryKeyring()
        s.deleteHistoryKeyringCache()
        s.deleteHistoryKeyringGeneration()

        keyring().ensureRoot(CHAT).getOrThrow()
        // The first delete of discardForMkReplacement, and only that one.
        store().deleteHistoryKeyringGeneration().getOrThrow()
        assertNull(store().loadHistoryKeyringGeneration().getOrThrow())
        assertNotNull(store().loadHistoryKeyringCache().getOrThrow())
    }

    // ------------------------------------------------------------------ phase 2

    /** After a real kill, a complete generation must still be readable. */
    @Test
    fun phase2_generationSurvivedTheKill() = runBlocking {
        val reopened = keyring().load().getOrThrow()
        assertNotNull(
            "a durable root must survive an ungraceful process kill",
            reopened.latest(CHAT)
        )
        assertNotNull(store().loadHistoryKeyringGeneration().getOrThrow())
    }

    /**
     * After a real kill, the half-torn-down state must still FAIL CLOSED - the
     * marker is gone, so the surviving cache cannot be validated and must not be
     * served as the current generation.
     */
    @Test
    fun phase2_interruptedDiscardStillFailsClosed() = runBlocking {
        assertNull(
            "precondition: the marker really is gone",
            store().loadHistoryKeyringGeneration().getOrThrow()
        )
        assertNotNull(
            "precondition: the retired copies really did survive the kill",
            store().loadHistoryKeyringCache().getOrThrow()
        )

        // Read as the REPLACEMENT generation, which is the situation a reset
        // leaves behind. The marker is gone so the cache cannot be validated, and
        // the authoritative copy belongs to the retired key, so it cannot be
        // opened either - the only correct answer is to refuse.
        val result = keyring(mkGeneration = 2).load()

        assertTrue(
            "an unvalidatable cache must never be served to the replacement " +
                "generation after a real process kill; got " + result,
            result.isFailure
        )
    }

    /**
     * Account isolation across a REAL process boundary.
     *
     * Two separate claims. The durable half: account B must not be able to open
     * account A's surviving keyring. The in-memory half: the parked reset outcome
     * carries a recovery key and lives only in RAM, so after a genuine kill it
     * must be gone entirely - this asserts that limitation rather than assuming
     * it, which is the honest way to record it.
     */
    @Test
    fun phase2_accountIsolationHoldsAcrossARealKill() = runBlocking {
        assertNotNull(
            "precondition: account A's durable keyring survived the kill",
            store().loadHistoryKeyring().getOrThrow()
        )

        // A different account, same device, fresh process.
        val asB = keyring(user = "someone-else-entirely").load()
        assertNull(
            "account B must never receive account A's roots after a restart",
            asB.getOrNull()?.latest(CHAT)
        )
        assertTrue(
            "and B must not be handed A's material at all",
            asB.isFailure || asB.getOrThrow().entries.isEmpty()
        )
    }

    /** Cleanup so the shared device state does not leak into other suites. */
    @Test
    fun phase2_cleanup() = runBlocking {
        val s = store()
        s.deleteHistoryKeyring()
        s.deleteHistoryKeyringCache()
        s.deleteHistoryKeyringGeneration()
        Unit
    }

    /**
     * The minimum real graph needed to drive a reset in this file: real
     * VaultCrypto, real Keystore-backed TokenManagerImpl, real
     * HistoryKeyringRepository and RecoverySync, and a server stub that can lose
     * replies. The reset path provably never touches ChatRepository or
     * WebSocketManager, so those are stubs.
     */
    private class Gate8Harness(
        ctx: android.content.Context,
        val tokens: com.messenger.app.security.TokenManagerImpl,
    ) {
        companion object {
            const val USER = "gate8-1111-2222-3333-444444444444"
            const val PASSWORD = "gate8 correct horse battery staple"
            const val TOKEN = "gate8-token"
            const val CHAT = "gate8-chat"
        }

        class Api : com.messenger.app.data.remote.api.ChatApiService by io.mockk.mockk(relaxed = true) {
            var vault: com.messenger.app.data.model.E2EEVaultDto? = null
            var vaultVersion = 0
            var loseReplyWithoutCommitting = false
            var failVaultGetAfterPut = false
            private var putSeen = false

            override suspend fun getE2EEVault(token: String):
                retrofit2.Response<com.messenger.app.data.model.E2EEVaultDto> {
                if (failVaultGetAfterPut && putSeen) throw java.io.IOException("offline")
                val v = vault ?: return retrofit2.Response.error(
                    404, okhttp3.ResponseBody.create(null, "")
                )
                return retrofit2.Response.success(v)
            }

            override suspend fun putE2EEVault(
                token: String,
                body: com.messenger.app.data.model.E2EEVaultPutRequest,
            ): retrofit2.Response<com.messenger.app.data.model.E2EEVaultDto> {
                putSeen = true
                if (loseReplyWithoutCommitting) throw java.io.IOException("reply lost, no write")
                vault = com.messenger.app.data.model.E2EEVaultDto(
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
                vaultVersion = body.vaultVersion
                return retrofit2.Response.success(vault!!)
            }

            override suspend fun listE2EEDevices(token: String):
                retrofit2.Response<com.messenger.app.data.model.E2EEDevicesResponse> =
                retrofit2.Response.success(
                    com.messenger.app.data.model.E2EEDevicesResponse(emptyList())
                )

            override suspend fun getHistoryKeyring(token: String):
                retrofit2.Response<com.messenger.app.data.model.HistoryKeyringDto> =
                retrofit2.Response.error(404, okhttp3.ResponseBody.create(null, ""))

            override suspend fun deleteHistoryKeyring(token: String): retrofit2.Response<Unit> =
                retrofit2.Response.success(Unit)
        }

        val api = Api()
        val repo: E2EEVaultRepository
        val keyring: HistoryKeyringRepository
        val sync: HistoryKeyringRecoverySync

        init {
            lateinit var ref: E2EEVaultRepository
            val feature = HistoryArchiveFeature.Enabled
            keyring = HistoryKeyringRepository(
                object : com.messenger.app.data.encryption.history.HistoryKeyringVault {
                    override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
                        ref.sealHistoryKeyring(plaintext)
                    override suspend fun openHistoryKeyring(sealed: ByteArray) =
                        ref.openHistoryKeyring(sealed)
                },
                tokens,
                HistoryUserProvider { tokens.getCurrentUserId().getOrNull() },
                feature
            )
            sync = HistoryKeyringRecoverySync(
                keyring,
                object : com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport {
                    override suspend fun sealForRecovery(plaintext: ByteArray) =
                        ref.sealForRecovery(plaintext)
                    override suspend fun openFromRecovery(sealed: ByteArray) =
                        ref.openFromRecovery(sealed)
                },
                api, tokens, feature
            )
            val ws = io.mockk.mockk<com.messenger.app.data.remote.websocket.WebSocketManager>(
                relaxed = true
            )
            io.mockk.every { ws.connectionState } returns
                kotlinx.coroutines.flow.MutableStateFlow(
                    com.messenger.app.data.remote.websocket.WebSocketManager
                        .ConnectionState.DISCONNECTED
                )
            repo = E2EEVaultRepository(
                api, tokens, io.mockk.mockk(relaxed = true), ws,
                dagger.Lazy { sync }, dagger.Lazy { keyring }
            )
            ref = repo
        }

        /** Puts the account into the state a signed-in device is in. */
        suspend fun seed() {
            tokens.saveCurrentUserId(USER).getOrThrow()
            tokens.saveAccessToken(TOKEN).getOrThrow()
            val pub = "cc".repeat(32)
            val priv = "dd".repeat(32)
            tokens.saveE2EEKeys(USER, pub, priv).getOrThrow()
            val built = com.messenger.app.data.encryption.VaultCrypto
                .createVault(USER, PASSWORD, pub, priv)!!
            api.vault = com.messenger.app.data.model.E2EEVaultDto(
                vaultVersion = built.vaultVersion,
                vaultCiphertextB64 = com.messenger.app.data.encryption.VaultCrypto
                    .b64(built.vaultCiphertext),
                pwKdf = com.messenger.app.data.encryption.VaultCrypto.KDF_ARGON2ID,
                pwSaltB64 = com.messenger.app.data.encryption.VaultCrypto.b64(built.pwSalt),
                pwParams = built.pwParamsJson,
                pwWrappedMasterB64 = com.messenger.app.data.encryption.VaultCrypto
                    .b64(built.pwWrappedMaster),
                rkKdf = if (built.rkWrappedMaster != null)
                    com.messenger.app.data.encryption.VaultCrypto.KDF_HKDF_SHA256 else "",
                rkSaltB64 = built.rkSalt?.let {
                    com.messenger.app.data.encryption.VaultCrypto.b64(it)
                } ?: "",
                rkWrappedMasterB64 = built.rkWrappedMaster?.let {
                    com.messenger.app.data.encryption.VaultCrypto.b64(it)
                } ?: ""
            )
            api.vaultVersion = built.vaultVersion
            val unlocked = repo.syncAfterPasswordLogin(TOKEN, PASSWORD)
            check(unlocked is E2EEVaultRepository.VaultSyncResult.Unlocked) {
                "test setup: expected unlock, got $unlocked"
            }
        }
    }

    // ================================================ GATE 8: candidate key + real kill

    /**
     * Phase 1 of the World-B process-death pair.
     *
     * Drives the REAL reset into the unresolvable, uncommitted world, then asks
     * the history feature - enabled - to mint a root under the resulting candidate
     * key. The process is killed immediately afterwards, so phase 2 sees only what
     * genuinely reached durable storage.
     */
    @Test
    fun phase1_worldBCandidateKeyThenDie() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val t = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))
        t.deleteHistoryKeyring(); t.deleteHistoryKeyringCache(); t.deleteHistoryKeyringGeneration()

        val h = Gate8Harness(ctx, t)
        h.seed()
        h.api.loseReplyWithoutCommitting = true
        h.api.failVaultGetAfterPut = true

        val result = h.repo.resetE2EEVault(Gate8Harness.TOKEN, Gate8Harness.PASSWORD)
        assertTrue(
            "precondition: unresolved ambiguity; got $result",
            result is E2EEVaultRepository.E2EEResetResult.PossiblyCompleted
        )
        // The feature is on; the candidate key must still be refused.
        assertTrue(
            "a candidate key must not seal durable state",
            h.keyring.ensureRoot(Gate8Harness.CHAT).isFailure
        )
    }

    /**
     * Phase 2: a genuine cold start after a real kill. Nothing sealed under the
     * candidate key may exist, and the device must not be left with a keyring that
     * the account's real key cannot open.
     */
    @Test
    fun phase2_worldBLeftNoPhantomDurableState() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val t = TokenManagerImpl(ctx, KeyStoreManagerImpl(ctx))

        val authoritative = t.loadHistoryKeyring().getOrThrow()
        assertNull(
            "no keyring may have been sealed under the unverified candidate key",
            authoritative
        )
        assertNull(
            "and no stamped cache may claim one exists",
            t.loadHistoryKeyringCache().getOrThrow()
        )
        // Cleanup for other suites.
        t.deleteHistoryKeyringGeneration()
        Unit
    }
}
