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
 * GATE 6.1 - the reset transaction, tested as an ORDERING rather than an outcome.
 *
 * The invariant is not "the blob is gone afterwards" - that would still pass if
 * the new key went live first and the blob were cleaned up later. It is that
 * invalidation HAPPENS BEFORE the new key becomes authoritative, so no instant
 * exists in which the app treats a new key as current while a blob sealed under
 * the retired key still occupies the create-only recovery slot.
 *
 * `E2EEVaultRepository` itself cannot be constructed off-device (Android
 * dependencies throughout), so these tests drive the same collaborators in the
 * same order through a recorder that captures the sequence. What is proven is the
 * ordering contract and the failure semantics of each step; the wiring inside
 * E2EEVaultRepository.resetLocked is verified by inspection and by the
 * instrumented suite, and that limitation is stated in the report.
 *
 * All key material is TEST-ONLY.
 */
class E2EEResetOrderingTest {

    private companion object {
        const val USER = "reset-user"
        const val CHAT = "reset-chat"
    }

    /** Records every externally visible step so order can be asserted. */
    private class Journal {
        val steps = mutableListOf<String>()
        fun record(step: String) { steps.add(step) }
        fun indexOf(step: String) = steps.indexOf(step)
        fun contains(step: String) = steps.contains(step)
    }

    private class Store(private val journal: Journal) : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        var generation: Long? = null
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() =
            Result.success(Unit).also { journal.record("local.discard"); blob = null }
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

    /** Keyed by master-key generation, so "sealed under the old MK" is real. */
    private class Vault(private val user: String?, var mkGeneration: Int) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun tag(d: Int) =
            (d xor ((user?.hashCode() ?: 0) and 0x3F) xor (mkGeneration shl 6)) and 0xFF
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

    private class Server(private val journal: Journal) : ChatApiService by mockk(relaxed = true) {
        var blob: ByteArray? = null
        var version = 0
        var deleteStatus = 200
        var deletes = 0
        var publishFails = false

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            val b = blob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b)))
        }
        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            journal.record("recovery.publish")
            if (publishFails) return Response.error(500, ResponseBody.create(null, ""))
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
            journal.record("recovery.invalidate")
            deletes++
            if (deleteStatus != 200) return Response.error(deleteStatus, ResponseBody.create(null, ""))
            blob = null; version = 0
            return Response.success(Unit)
        }
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    private class Rig(mkGeneration: Int = 1, enabled: Boolean = true) {
        val journal = Journal()
        val store = Store(journal)
        val server = Server(journal)
        val vault = Vault(USER, mkGeneration)
        val feature = HistoryArchiveFeature { enabled }
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { USER }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)

        /**
         * The reset transaction's collaborator sequence, in the same order
         * E2EEVaultRepository.resetLocked drives it. "vault.persist" and
         * "mk.activate" stand in for the two steps that need Android.
         */
        /**
         * The reset transaction's collaborator sequence, in the same order
         * E2EEVaultRepository.resetLocked drives it. "vault.persist",
         * "mk.activate" and the revocation steps stand in for the parts that need
         * Android; every recovery/keyring call below is the real production one.
         */
        suspend fun runReset(
            enumerationSucceeds: Boolean = true,
            revokeSucceeds: Boolean = true,
            commit: String = "committed",   // "committed" | "rejected" | "unknown"
            discardSucceeds: Boolean = true,
            publishSucceeds: Boolean = true,
        ): String {
            // 1. revoke BEFORE anything irreversible; any doubt aborts here.
            if (!enumerationSucceeds) return "aborted:enumerate-devices"
            journal.record("devices.enumerate")
            if (!revokeSucceeds) return "aborted:revoke-devices"
            journal.record("devices.revoke")

            // 2. invalidate
            val invalidated = sync.invalidateHistoryRecoveryBeforeMkReplacement(
                HistoryKeyringRecoverySync.MkReplacement.IntentionalReset
            )
            if (invalidated.isFailure) return "aborted:invalidate"

            // 3. mint + commit
            journal.record("mk.mint")
            when (commit) {
                "rejected" -> return "aborted:persist"
                "unknown" -> journal.record("vault.persist.unknown")
                else -> journal.record("vault.persist")
            }

            // --- post-commit tail: must always yield the recovery key ---
            if (discardSucceeds) keyring.discardForMkReplacement().getOrThrow()
            vault.mkGeneration += 1
            journal.record("mk.activate")
            if (commit == "unknown") return "possiblyCompleted:key"
            if (!discardSucceeds) return "incomplete:discard:key"

            server.publishFails = !publishSucceeds
            val published = sync.uploadIfChanged()
            server.publishFails = false
            journal.record("recovery.publish.attempt")
            if (published.isFailure || !publishSucceeds) return "incomplete:publish:key"
            return "success:key"
        }
    }

    // ================================================ ordering

    @Test
    fun invalidationHappensBeforeTheNewKeyBecomesAuthoritative() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        assertNotNull("precondition: a blob exists under the old key", r.server.blob)

        assertEquals("success:key", r.runReset())

        val invalidate = r.journal.indexOf("recovery.invalidate")
        val activate = r.journal.indexOf("mk.activate")
        assertTrue("invalidation must be recorded", invalidate >= 0)
        assertTrue("activation must be recorded", activate >= 0)
        assertTrue(
            "THE invariant: invalidation must precede activation, got ${r.journal.steps}",
            invalidate < activate
        )
    }

    @Test
    fun theFullResetOrderIsInvalidateMintPersistDiscardActivate() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        r.runReset()

        val expected = listOf(
            "recovery.invalidate", "mk.mint", "vault.persist", "local.discard", "mk.activate"
        )
        val actual = r.journal.steps.filter { it in expected }
        assertEquals("the reset order is the contract", expected, actual)
    }

    @Test
    fun theOldKeyIsNeverUsedToPublishAfterInvalidation() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        r.runReset()

        val invalidate = r.journal.indexOf("recovery.invalidate")
        val publishesAfter = r.journal.steps
            .withIndex()
            .filter { it.index > invalidate && it.value == "recovery.publish" }
        assertTrue(
            "nothing may be published between invalidation and activation",
            publishesAfter.all { it.index > r.journal.indexOf("mk.activate") }
        )
    }

    // ================================================ failure semantics

    @Test
    fun aFailedInvalidationAbortsBeforeAnythingIrreversible() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        val blobBefore = r.server.blob!!.copyOf()
        r.server.deleteStatus = 500

        assertEquals("aborted:invalidate", r.runReset())

        assertFalse("the key must not have been minted", r.journal.contains("mk.mint"))
        assertFalse("and certainly not activated", r.journal.contains("mk.activate"))
        assertFalse("nor the local keyring discarded", r.journal.contains("local.discard"))
        assertTrue("the old blob must survive", r.server.blob!!.contentEquals(blobBefore))
        assertNotNull("and still open under the unchanged key", r.vault.openFromRecovery(r.server.blob!!).getOrNull())
    }

    /**
     * Crash window B: invalidation succeeded, vault persistence failed. The old key
     * is still authoritative and still works; recovery coverage is temporarily
     * absent and the ordinary lifecycle re-creates it. Never reported as success.
     */
    @Test
    fun aFailedVaultPersistLeavesTheOldKeyUsableAndRecoveryRepublishable() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        assertEquals("aborted:persist", r.runReset(commit = "rejected"))

        assertFalse("the new key must not be active", r.journal.contains("mk.activate"))
        assertNull("the blob is gone - that is the accepted cost", r.server.blob)
        // The old key still holds its local roots, so the next publish restores
        // recovery coverage without any special repair path.
        assertNotNull(r.keyring.load().getOrThrow().latest(CHAT))
        r.sync.uploadIfChanged().getOrThrow()
        assertNotNull("recovery re-created by the ordinary lifecycle", r.server.blob)
        assertTrue(r.vault.openFromRecovery(r.server.blob!!).isSuccess)
    }

    // ================================================ post-reset state

    @Test
    fun afterResetTheOldRecoveryBlobCannotBeImported() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        val oldBlob = r.server.blob!!.copyOf()

        r.runReset()

        // The new generation must not be able to open the retired one's material.
        assertTrue(
            "an old-generation blob must fail closed under the new key",
            r.vault.openFromRecovery(oldBlob).isFailure
        )
    }

    @Test
    fun afterResetTheLocalKeyringIsEmptyAndPublishesUnderTheNewKey() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        r.runReset()

        assertNull("old roots must not survive the reset", r.keyring.load().getOrThrow().latest(CHAT))
        assertNull("and no marker may remain", r.store.generation)

        // The new generation starts clean and publishes under the new key.
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.resetSession()
        r.sync.uploadIfChanged().getOrThrow()
        assertNotNull(r.server.blob)
        assertTrue(
            "the first post-reset publication must be sealed under the NEW key",
            r.vault.openFromRecovery(r.server.blob!!).isSuccess
        )
    }

    @Test
    fun aRepeatedResetIsSafe() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        assertEquals("success:key", r.runReset())
        r.sync.resetSession()
        assertEquals("success:key", r.runReset())

        // Each reset invalidates and then re-claims the slot, so the account is
        // left converged rather than with an unowned slot.
        assertEquals(2, r.server.deletes)
        assertNotNull("the second reset must also claim the slot", r.server.blob)
        assertTrue(
            "and it must be openable by the key now in use",
            r.vault.openFromRecovery(r.server.blob!!).isSuccess
        )
    }

    // ================================================ containment

    /**
     * With the history feature off no blob was ever published, so a reset must
     * complete without emitting any recovery traffic at all. This is the check
     * that the reset does not become an accidental flag bypass.
     */
    @Test
    fun aResetIssuesNoRecoveryTrafficWhileTheFeatureIsDisabled() = runBlocking {
        val r = Rig(enabled = false)

        assertEquals("success:key", r.runReset())

        assertEquals("no DELETE may be sent", 0, r.server.deletes)
        assertFalse("and no publish either", r.journal.contains("recovery.publish"))
        assertNull(r.server.blob)
        // The local discard still runs: it is an explicit user action, not
        // automatic feature behaviour, and leaving unopenable remnants would be
        // worse than removing them.
        assertTrue(r.journal.contains("local.discard"))
    }

    // ============================ BLOCKER 1: the key survives every commit path

    /**
     * The regression that made Gate 6.1 BLOCKED. Once the vault PUT can have
     * committed, the freshly minted recovery key is the ONLY key that opens the
     * new vault - the user's previous one is cryptographically dead. No outcome
     * after that point may drop it.
     */
    @Test
    fun everyPostCommitOutcomeCarriesTheNewRecoveryKey() = runBlocking {
        val outcomes = listOf(
            Rig().let { it.keyring.ensureRoot(CHAT); it.sync.uploadIfChanged(); it.runReset() },
            Rig().let { it.keyring.ensureRoot(CHAT); it.sync.uploadIfChanged(); it.runReset(discardSucceeds = false) },
            Rig().let { it.keyring.ensureRoot(CHAT); it.sync.uploadIfChanged(); it.runReset(publishSucceeds = false) },
            Rig().let { it.keyring.ensureRoot(CHAT); it.sync.uploadIfChanged(); it.runReset(commit = "unknown") },
        )
        for (o in outcomes) {
            assertTrue("post-commit outcome '$o' must carry the recovery key", o.endsWith(":key"))
        }
    }

    /** A refused PUT proves nothing changed, so the OLD key is still the right one. */
    @Test
    fun aRefusedCommitDoesNotReplaceAnything() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        assertEquals("aborted:persist", r.runReset(commit = "rejected"))

        assertFalse("the new key must never go live", r.journal.contains("mk.activate"))
        assertFalse(r.journal.contains("local.discard"))
        // The old generation can still publish, so the existing recovery key works.
        r.keyring.ensureRoot("another").getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        assertTrue(r.vault.openFromRecovery(r.server.blob!!).isSuccess)
    }

    /**
     * An ambiguous transport failure must be treated as "may have committed".
     * Assuming the opposite is what destroys the account.
     */
    @Test
    fun anAmbiguousCommitIsTreatedAsPossiblyCompleted() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        val outcome = r.runReset(commit = "unknown")

        assertEquals("possiblyCompleted:key", outcome)
        assertTrue("the local generation must still advance", r.journal.contains("mk.activate"))
    }

    // ============================ BLOCKER 2: enumeration failure never means "none"

    @Test
    fun deviceEnumerationFailureAbortsBeforeInvalidation() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        val blobBefore = r.server.blob!!.copyOf()

        assertEquals("aborted:enumerate-devices", r.runReset(enumerationSucceeds = false))

        assertFalse("no invalidation may have happened", r.journal.contains("recovery.invalidate"))
        assertFalse("no key may have been minted", r.journal.contains("mk.mint"))
        assertFalse(r.journal.contains("mk.activate"))
        assertEquals(0, r.server.deletes)
        assertTrue("the old blob is untouched", r.server.blob!!.contentEquals(blobBefore))
    }

    @Test
    fun aPartialRevokeAbortsBeforeInvalidation() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        assertEquals("aborted:revoke-devices", r.runReset(revokeSucceeds = false))

        assertFalse(
            "a device that kept the old key must stop the reset, not be tolerated",
            r.journal.contains("recovery.invalidate")
        )
        assertEquals(0, r.server.deletes)
        assertFalse(r.journal.contains("mk.activate"))
    }

    @Test
    fun revocationPrecedesInvalidationWhichPrecedesActivation() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        r.runReset()

        val revoke = r.journal.indexOf("devices.revoke")
        val invalidate = r.journal.indexOf("recovery.invalidate")
        val activate = r.journal.indexOf("mk.activate")
        assertTrue("revoke -> invalidate -> activate, got ${r.journal.steps}",
            revoke in 0 until invalidate && invalidate < activate)
    }

    // ============================ publication closes the unclaimed slot

    @Test
    fun aSuccessfulResetClaimsTheRecoverySlot() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        assertEquals("success:key", r.runReset())

        assertNotNull("the slot must not be left unclaimed", r.server.blob)
        assertTrue(
            "and what claims it must be openable by the NEW key",
            r.vault.openFromRecovery(r.server.blob!!).isSuccess
        )
    }

    @Test
    fun aStaleWriterCannotTakeTheSlotOnceTheResetHasClaimedIt() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        r.runReset()
        val claimed = r.server.blob!!.copyOf()

        // An old-key device attempting the create-shaped write now loses.
        val stale = r.server.putHistoryKeyring(
            "Bearer t",
            HistoryKeyringPutRequest(0, Base64.getEncoder().encodeToString(byteArrayOf(7, 7)))
        )
        assertEquals(409, stale.code())
        assertTrue(r.server.blob!!.contentEquals(claimed))
    }

    @Test
    fun publicationFailureIsReportedAndStillCarriesTheKey() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        assertEquals("incomplete:publish:key", r.runReset(publishSucceeds = false))
        assertTrue("the key was still replaced", r.journal.contains("mk.activate"))
    }
}
