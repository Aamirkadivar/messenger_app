package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.model.HistoryKeyringDto
import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.model.HistoryKeyringPutResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64

/**
 * GATE 6 - the master-key replacement boundary.
 *
 * The invariant being defended: whenever an existing vault's MK is intentionally
 * replaced, the old recovery blob must become unusable BEFORE the new MK becomes
 * active. The blob is sealed under the retired key and sits in a create-only
 * slot, so leaving it behind strands recovery for that account permanently.
 *
 * The mirror-image invariant matters just as much and is easier to get wrong: an
 * ORDINARY rewrap - password change, recovery-key rewrap, vault-contents refresh
 * - keeps the same MK and must never touch the blob. Deleting on those paths
 * would destroy perfectly good recovery material on a routine operation.
 *
 * NOTE ON SCOPE. There is no user-facing E2EE reset flow in this tree, and this
 * suite does not invent one. It tests the boundary primitive and the paths that
 * must not reach it. See [aResetMustNotToleratePartialInvalidation] for the
 * contract a future reset flow is required to honour.
 *
 * All key material is TEST-ONLY.
 */
class MkReplacementBoundaryTest {

    private companion object {
        const val USER = "mk-user"
        const val CHAT = "mk-chat"
    }

    private class Store : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        var generation: Long? = null
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() = Result.success(Unit).also { cache = null }
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    /**
     * Keyed by MASTER KEY as well as account, so "sealed under the old MK" is a
     * real property here rather than an assumption. A blob written under mk=1
     * genuinely does not open under mk=2, which is what makes the stale-writer
     * tests below mean something.
     */
    private class Vault(private val user: String?, var mkGeneration: Int) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun tag(domain: Int) =
            (domain xor ((user?.hashCode() ?: 0) and 0x3F) xor (mkGeneration shl 6)) and 0xFF
        private fun mask(b: ByteArray, t: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor t).toByte() }
        private fun seal(d: Int, p: ByteArray): Result<ByteArray> {
            if (user == null) return Result.failure(IllegalStateException("no mk"))
            return Result.success(byteArrayOf(tag(d).toByte()) + mask(p, tag(d)))
        }
        private fun open(d: Int, s: ByteArray): Result<ByteArray> {
            if (user == null) return Result.failure(IllegalStateException("no mk"))
            if (s.isEmpty() || s[0] != tag(d).toByte()) {
                return Result.failure(IllegalStateException("authentication failed"))
            }
            return Result.success(mask(s.copyOfRange(1, s.size), tag(d)))
        }
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) = seal(0x11, plaintext)
        override suspend fun openHistoryKeyring(sealed: ByteArray) = open(0x11, sealed)
        override suspend fun sealForRecovery(plaintext: ByteArray) = seal(0x22, plaintext)
        override suspend fun openFromRecovery(sealed: ByteArray) = open(0x22, sealed)
    }

    private class Server : ChatApiService by mockk(relaxed = true) {
        var blob: ByteArray? = null
        var version = 0
        var deletes = 0
        var deleteStatus = 200
        var offline = false

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            if (offline) throw java.io.IOException("offline")
            val b = blob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b)))
        }
        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            if (offline) throw java.io.IOException("offline")
            // Mirrors the Go handler: create-only at 0, replace only at current.
            if (body.expectedVersion == 0) {
                if (blob != null) return Response.error(409, ResponseBody.create(null, ""))
                blob = Base64.getDecoder().decode(body.ciphertextB64); version = 1
                return Response.success(HistoryKeyringPutResponse(1, true))
            }
            if (body.expectedVersion != version) return Response.error(409, ResponseBody.create(null, ""))
            blob = Base64.getDecoder().decode(body.ciphertextB64); version += 1
            return Response.success(HistoryKeyringPutResponse(version, false))
        }
        override suspend fun deleteHistoryKeyring(token: String): Response<Unit> {
            if (offline) throw java.io.IOException("offline")
            deletes++
            if (deleteStatus != 200) return Response.error(deleteStatus, ResponseBody.create(null, ""))
            blob = null; version = 0
            return Response.success(Unit)
        }
    }

    private class Tokens(private val token: String? = "t") : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success(token)
    }

    private class Device(
        val store: Store = Store(),
        val server: Server = Server(),
        user: String? = USER,
        mkGeneration: Int = 1,
        enabled: Boolean = true,
        token: String? = "t",
    ) {
        val vault = Vault(user, mkGeneration)
        val feature = HistoryArchiveFeature { enabled }
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(token), feature)
    }

    private suspend fun reset(d: Device) =
        d.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset
        )

    private suspend fun mint(d: Device) =
        d.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.FreshAccountMint
        )

    // ============================================ Invariant A: reset invalidates

    @Test
    fun anIntentionalResetRemovesTheBlobBeforeTheNewKeyCouldUseIt() = runBlocking {
        val d = Device()
        d.keyring.ensureRoot(CHAT).getOrThrow()
        d.sync.uploadIfChanged().getOrThrow()
        assertNotNull("precondition: a blob exists under the old MK", d.server.blob)

        reset(d).getOrThrow()

        assertNull("the old-MK blob must be gone", d.server.blob)
        assertEquals(0, d.server.version)
        assertEquals(1, d.server.deletes)
    }

    /** After invalidation the slot is free, so the new MK creates cleanly at v1. */
    @Test
    fun theNewKeyCanPublishIntoTheFreedSlot() = runBlocking {
        val old = Device(mkGeneration = 1)
        old.keyring.ensureRoot(CHAT).getOrThrow()
        old.sync.uploadIfChanged().getOrThrow()
        reset(old).getOrThrow()

        // Same account and device storage, a brand-new master key.
        val fresh = Device(store = Store(), server = old.server, mkGeneration = 2)
        fresh.keyring.ensureRoot(CHAT).getOrThrow()
        fresh.sync.uploadIfChanged().getOrThrow()

        assertNotNull(fresh.server.blob)
        assertEquals("a create, not a conflicted replace", 1, fresh.server.version)
        assertTrue(
            "and the new blob must open under the NEW key",
            fresh.vault.openFromRecovery(fresh.server.blob!!).isSuccess
        )
    }

    /** The stranding this whole boundary exists to prevent. */
    @Test
    fun withoutInvalidationTheNewKeyWouldBeStrandedForever() = runBlocking {
        val old = Device(mkGeneration = 1)
        old.keyring.ensureRoot(CHAT).getOrThrow()
        old.sync.uploadIfChanged().getOrThrow()
        // Deliberately SKIP invalidation - this is the counterfactual.

        val fresh = Device(store = Store(), server = old.server, mkGeneration = 2)
        fresh.keyring.ensureRoot(CHAT).getOrThrow()
        fresh.sync.uploadIfChanged()

        // The create conflicts, the conflict path fetches, and the fetched blob
        // cannot be opened under the new key - so publication can never converge.
        assertTrue(fresh.sync.upload().isFailure)
        assertTrue(
            "the stale blob is still occupying the slot",
            fresh.vault.openFromRecovery(fresh.server.blob!!).isFailure
        )
        // And it must NOT read as "no recovery exists".
        assertTrue(
            "a stale old-MK blob must fail closed, never look like absence",
            fresh.sync.ensureRecovered().isFailure
        )
    }

    // ============================================ Invariants B & C: no speculative delete

    /**
     * The paths that keep the SAME MK. None of them may reach the boundary.
     *
     * This is asserted at the only place it can be: the recovery sync sees no
     * deletion traffic at all while those operations run. The vault-side half of
     * the guarantee - that those callers cannot even pass an honest argument -
     * is enforced by [HistoryKeyringRecoverySync.MkReplacement] having no case
     * that describes a rewrap.
     */
    @Test
    fun ordinaryRewrapAndRefreshNeverInvalidateRecovery() = runBlocking {
        val d = Device()
        d.keyring.ensureRoot(CHAT).getOrThrow()
        d.sync.uploadIfChanged().getOrThrow()
        val blobBefore = d.server.blob!!.copyOf()
        val versionBefore = d.server.version

        // Everything a same-MK lifetime does: republish, re-arm, recover, rotate.
        d.sync.uploadIfChanged().getOrThrow()
        d.sync.refreshRecovery().getOrThrow()
        d.sync.ensureRecovered().getOrThrow()
        d.keyring.rotate(CHAT).getOrThrow()
        d.sync.uploadIfChanged().getOrThrow()

        assertEquals("no rewrap-shaped operation may delete", 0, d.server.deletes)
        assertNotNull("the blob must still exist", d.server.blob)
        assertTrue("and it must still open under the unchanged MK",
            d.vault.openFromRecovery(d.server.blob!!).isSuccess)
        assertTrue("the rotation must have advanced it, not removed it",
            d.server.version > versionBefore)
        assertFalse(d.server.blob!!.contentEquals(blobBefore))
    }

    /** Failures are never a reason to invalidate. */
    @Test
    fun noFailureModeInvalidatesTheBlob() = runBlocking {
        // A blob that will not open under this key.
        val d = Device(mkGeneration = 2)
        val other = Device(store = Store(), server = d.server, mkGeneration = 1)
        other.keyring.ensureRoot(CHAT).getOrThrow()
        other.sync.uploadIfChanged().getOrThrow()

        assertTrue(d.sync.ensureRecovered().isFailure)   // wrong-MK open failure
        assertTrue(d.sync.refreshRecovery().isFailure)   // repeated failure
        d.server.offline = true
        assertTrue(d.sync.upload().isFailure)            // network failure
        d.server.offline = false

        assertEquals(
            "not one failure path may reach the invalidation boundary",
            0, d.server.deletes
        )
        assertNotNull(d.server.blob)
    }

    // ============================================ Invariant E: failure semantics

    /**
     * The contract a future reset flow MUST honour: if invalidation fails, the
     * new MK must never be installed. The primitive reports the failure; the
     * caller is what has to abort, so this pins the reported outcome.
     */
    @Test
    fun aResetMustNotToleratePartialInvalidation() = runBlocking {
        val http = Device()
        http.keyring.ensureRoot(CHAT).getOrThrow()
        http.sync.uploadIfChanged().getOrThrow()
        http.server.deleteStatus = 500

        val result = reset(http)
        assertTrue("a failed invalidation must be reported, not swallowed", result.isFailure)
        assertNotNull("and the blob must still be there", http.server.blob)

        val offline = Device()
        offline.server.offline = true
        assertTrue("a thrown transport error must surface too", reset(offline).isFailure)
        assertEquals("nothing may be assumed deleted after a throw", 0, offline.server.deletes)
    }

    @Test
    fun invalidationWithoutASessionFails() = runBlocking {
        val d = Device(token = null)
        assertTrue(reset(d).isFailure)
        assertEquals("nothing may be sent unauthenticated", 0, d.server.deletes)
    }

    /**
     * A fresh-account mint tolerates failure because its precondition guarantees
     * nothing exists - GET /vault answered 404, so no MK ever existed, so no
     * device could ever have published a blob.
     */
    @Test
    fun aFreshAccountMintIsANoOpAgainstAnEmptySlot() = runBlocking {
        val d = Device()
        assertTrue(mint(d).isSuccess)
        assertEquals(1, d.server.deletes)
        assertNull(d.server.blob)

        // Repeating it stays safe.
        assertTrue(mint(d).isSuccess)
    }

    // ============================================ Feature-flag containment

    @Test
    fun aDisabledFeatureIssuesNoInvalidationTraffic() = runBlocking {
        val d = Device(enabled = false)
        assertTrue(reset(d).isSuccess)
        assertTrue(mint(d).isSuccess)
        assertEquals("a disabled feature must not talk to the server", 0, d.server.deletes)
        assertNull(d.server.blob)
    }

    // ============================================ Multi-device / stale writers

    /**
     * Device B still holds the OLD key after A resets. B must not be able to
     * publish old-MK material that A can never open.
     *
     * With B revoked - which the reset sequence performs first - every keyring
     * route answers 403 and this cannot happen at all. This test covers the
     * residual window BEFORE revocation takes effect, and shows the outcome is
     * recoverable rather than permanent.
     */
    @Test
    fun aStaleOldKeyWriterCanBeUndoneByInvalidatingAgain() = runBlocking {
        val server = Server()
        val a = Device(server = server, mkGeneration = 1)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()

        reset(a).getOrThrow()
        assertNull(server.blob)

        // B, unrevoked and still on the old key, wins the empty slot.
        val b = Device(store = Store(), server = server, mkGeneration = 1)
        b.keyring.ensureRoot("b-chat").getOrThrow()
        b.sync.uploadIfChanged().getOrThrow()
        assertNotNull("B occupied the slot", server.blob)

        // A, now on the new key, cannot open or converge with B's blob.
        val aNew = Device(store = Store(), server = server, mkGeneration = 2)
        assertTrue(aNew.sync.ensureRecovered().isFailure)
        assertTrue(aNew.sync.upload().isFailure)

        // The remedy uses the same primitive and needs no new protocol.
        reset(aNew).getOrThrow()
        aNew.keyring.ensureRoot(CHAT).getOrThrow()
        aNew.sync.uploadIfChanged().getOrThrow()

        assertEquals(1, server.version)
        assertTrue(
            "the account converges on the new key",
            aNew.vault.openFromRecovery(server.blob!!).isSuccess
        )
    }

    /** A stale writer that knows an old VERSION cannot clobber the new blob. */
    @Test
    fun aStaleVersionedWriterCannotClobberTheNewBlob() = runBlocking {
        val server = Server()
        val a = Device(server = server, mkGeneration = 1)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()
        a.keyring.rotate(CHAT).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()
        val staleVersion = server.version

        reset(a).getOrThrow()
        val aNew = Device(store = Store(), server = server, mkGeneration = 2)
        aNew.keyring.ensureRoot(CHAT).getOrThrow()
        aNew.sync.uploadIfChanged().getOrThrow()
        val newBlob = server.blob!!.copyOf()

        // A stale device replaying the version it last saw must be rejected.
        val stale = server.putHistoryKeyring(
            "Bearer t",
            HistoryKeyringPutRequest(staleVersion, Base64.getEncoder().encodeToString(byteArrayOf(7, 7)))
        )
        assertEquals(409, stale.code())
        assertTrue("the new blob must be untouched", server.blob!!.contentEquals(newBlob))
    }
}
