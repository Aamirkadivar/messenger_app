package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveDisabledException
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.model.ArchiveListResponse
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
 * GATE 5 PHASE 5 - the feature flag as a COMPLETE containment boundary.
 *
 * The previous flag suite only exercised [MessageArchiver], and that is exactly
 * why the hole survived: rotation reaches [HistoryKeyringRepository] straight
 * from ViewModels without passing through the archiver, so an ordinary security
 * event - accepting a changed peer identity, revoking a device - minted and
 * durably persisted history roots while the feature was switched off.
 *
 * These tests drive the REAL [HistoryRotationCoordinator] and the repository's
 * own mutation entry points, and assert on durable storage rather than on return
 * values, because "returned an error" and "wrote nothing" are different claims.
 *
 * All key material is TEST-ONLY.
 */
class HistoryFlagContainmentTest {

    private companion object {
        const val USER = "flag-user"
        const val CHAT = "flag-chat"
    }

    /** Counts every durable write so "nothing was persisted" is an assertion. */
    private class WatchedStore : HistoryKeyringStore {
        var authoritative: ByteArray? = null
        var cache: ByteArray? = null
        var generation: Long? = null
        var writes = 0

        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { writes++; authoritative = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(authoritative?.copyOf())
        override suspend fun deleteHistoryKeyring() =
            Result.success(Unit).also { writes++; authoritative = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { writes++; cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() =
            Result.success(Unit).also { writes++; cache = null }
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { writes++; this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { writes++; generation = null }
    }

    private class Vault : HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun mask(b: ByteArray, tag: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor tag).toByte() }
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x11) + mask(plaintext, 0x11))
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x11.toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), 0x11))
            else Result.failure(IllegalStateException("not sealed"))
        override suspend fun sealForRecovery(plaintext: ByteArray) =
            Result.success(byteArrayOf(0x22) + mask(plaintext, 0x22))
        override suspend fun openFromRecovery(sealed: ByteArray) =
            if (sealed.isNotEmpty() && sealed[0] == 0x22.toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), 0x22))
            else Result.failure(IllegalStateException("not a recovery blob"))
    }

    /** Counts every request so "no recovery traffic" is an assertion. */
    private class WatchedServer : ChatApiService by mockk(relaxed = true) {
        var blob: ByteArray? = null
        var version = 0
        var gets = 0
        var puts = 0
        var deletes = 0
        var archiveGets = 0

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            gets++
            val b = blob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b)))
        }
        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            puts++
            if (body.expectedVersion != version) return Response.error(409, ResponseBody.create(null, ""))
            blob = Base64.getDecoder().decode(body.ciphertextB64)
            version += 1
            return Response.success(HistoryKeyringPutResponse(version, version == 1))
        }
        override suspend fun deleteHistoryKeyring(token: String): Response<Unit> {
            deletes++
            blob = null; version = 0
            return Response.success(Unit)
        }
        override suspend fun listArchives(
            token: String, chatId: String?, since: String?, sinceId: String?,
        ): Response<ArchiveListResponse> {
            archiveGets++
            return Response.success(ArchiveListResponse(emptyList(), 0))
        }
        val total: Int get() = gets + puts + deletes + archiveGets
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    private class Cipher : com.messenger.app.data.encryption.history.ArchiveCipher {
        var sealCalls = 0
        override fun seal(
            historyRoot: ByteArray,
            ctx: com.messenger.app.data.encryption.history.HistoryContext,
            plaintext: ByteArray,
        ): ByteArray { sealCalls++; return "sealed".toByteArray() }
        override fun open(
            historyRoot: ByteArray,
            ctx: com.messenger.app.data.encryption.history.HistoryContext,
            sealed: ByteArray,
        ): ByteArray? = null
    }

    private class Rig(enabled: Boolean) {
        val store = WatchedStore()
        val server = WatchedServer()
        val feature = HistoryArchiveFeature { enabled }
        val keyring = HistoryKeyringRepository(Vault(), store, HistoryUserProvider { USER }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, Vault(), server, Tokens(), feature)
        val coordinator = HistoryRotationCoordinator(keyring, dagger.Lazy { sync }, feature)
        val cipher = Cipher()
        val archiver = MessageArchiver(keyring, cipher, feature)
        /** The real object ChatRepository drives for both archive directions. */
        val archiveSync = ArchiveSync(archiver, server, Tokens(), feature, dagger.Lazy { sync })
    }

    // ================================================ the flag is still OFF

    @Test
    fun theProductionDefaultIsOff() {
        assertFalse(HistoryArchiveFeature.DEFAULT_ENABLED)
        assertFalse(HistoryArchiveFeature.Default.isEnabled())
    }

    // ================================================ OFF: nothing is minted

    /** The exact production path that used to escape the flag. */
    @Test
    fun aSecurityEventMintsNoRootWhenDisabled() = runBlocking {
        val r = Rig(enabled = false)

        // This is what ChatViewModel does when a changed peer identity is accepted.
        val outcome = r.coordinator.onSecurityEvent(
            SecurityEvent.DirectPeerIdentityAccepted(CHAT, "fingerprint")
        ).getOrThrow()

        assertTrue("nothing may rotate", outcome.rotated.isEmpty())
        assertTrue("and nothing may be reported as failed either", outcome.failed.isEmpty())
        assertEquals("no durable write may occur", 0, r.store.writes)
        assertNull(r.store.authoritative)
        assertNull(r.store.cache)
        assertNull("not even the generation marker may move", r.store.generation)
        assertEquals("no recovery traffic at all", 0, r.server.total)
    }

    @Test
    fun everyRotationEventShapeIsContained() = runBlocking {
        val r = Rig(enabled = false)
        val events = listOf(
            SecurityEvent.GroupMemberRemoved(CHAT, "alice"),
            SecurityEvent.DirectPeerIdentityAccepted(CHAT, "fp"),
            SecurityEvent.DeviceRevoked("device-9"),
        )
        for (e in events) {
            r.coordinator.onSecurityEvent(e, allChatIds = listOf(CHAT)).getOrThrow()
        }
        assertEquals(0, r.store.writes)
        assertEquals(0, r.server.total)
    }

    /** The repository guards itself, so a direct caller cannot bypass the coordinator. */
    @Test
    fun theRepositoryRefusesEveryMutationWhenDisabled() = runBlocking {
        val r = Rig(enabled = false)

        assertTrue(r.keyring.ensureRoot(CHAT).exceptionOrNull() is HistoryArchiveDisabledException)
        assertTrue(r.keyring.rotate(CHAT).exceptionOrNull() is HistoryArchiveDisabledException)
        assertTrue(
            r.keyring.importFromRecovery(byteArrayOf(1, 0, 0, 0, 0))
                .exceptionOrNull() is HistoryArchiveDisabledException
        )
        r.keyring.markRotationRequired(CHAT)
        assertFalse("a barrier is state too", r.keyring.isRotationRequired(CHAT))

        assertEquals(0, r.store.writes)
    }

    @Test
    fun noRecoveryTrafficOccursWhenDisabled() = runBlocking {
        val r = Rig(enabled = false)

        r.sync.ensureRecovered().getOrThrow()
        r.sync.refreshRecovery().getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        r.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).getOrThrow()
        assertTrue(r.sync.upload().isFailure)

        assertEquals("GET", 0, r.server.gets)
        assertEquals("PUT", 0, r.server.puts)
        assertEquals("DELETE", 0, r.server.deletes)
        assertEquals(0, r.server.total)
    }

    /**
     * The specific regression from the Phase 5 audit: creating a vault must not
     * emit a recovery DELETE while the feature is off.
     */
    @Test
    fun vaultCreationIssuesNoDeleteWhenDisabled() = runBlocking {
        val r = Rig(enabled = false)
        // This is precisely what E2EEVaultRepository.createAndUpload invokes.
        assertTrue(r.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).isSuccess)
        assertEquals("a disabled feature must not talk to the server", 0, r.server.deletes)
    }

    @Test
    fun readingHistoryIsNotGatedByTheFlag() = runBlocking {
        // Roots minted while enabled must stay readable if the flag is later
        // switched off - reading is not a mutation, and history already written
        // must not become inaccessible.
        val on = Rig(enabled = true)
        on.keyring.ensureRoot(CHAT).getOrThrow()

        val off = HistoryKeyringRepository(
            Vault(), on.store, HistoryUserProvider { USER }, HistoryArchiveFeature { false }
        )
        assertNotNull("existing roots must remain readable", off.load().getOrThrow().latest(CHAT))
        assertNotNull(off.find(CHAT, 1).getOrThrow())
    }

    // ================================================ ON: behaviour preserved

    @Test
    fun rotationStillWorksWhenEnabled() = runBlocking {
        val r = Rig(enabled = true)
        r.keyring.ensureRoot(CHAT).getOrThrow()

        val outcome = r.coordinator.onSecurityEvent(
            SecurityEvent.GroupMemberRemoved(CHAT, "alice")
        ).getOrThrow()

        assertEquals(setOf(CHAT), outcome.rotated)
        assertEquals(2, r.keyring.load().getOrThrow().latest(CHAT)!!.rootVersion)
        assertNotNull("and the rotated keyring must be published", r.server.blob)
    }

    @Test
    fun theDedupSetIsNotPoisonedByDisabledEvents() = runBlocking {
        // An event dropped while disabled must not count as "already handled",
        // or enabling the flag would leave that boundary permanently un-rotated.
        val store = WatchedStore()
        val server = WatchedServer()
        val vault = Vault()
        var enabled = false
        val feature = HistoryArchiveFeature { enabled }
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { USER }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
        val coordinator = HistoryRotationCoordinator(keyring, dagger.Lazy { sync }, feature)

        val event = SecurityEvent.GroupMemberRemoved(CHAT, "alice")
        coordinator.onSecurityEvent(event).getOrThrow()
        assertEquals(0, store.writes)

        enabled = true
        keyring.ensureRoot(CHAT).getOrThrow()
        val outcome = coordinator.onSecurityEvent(event).getOrThrow()

        assertFalse("the earlier no-op must not have consumed the dedup key", outcome.duplicate)
        assertEquals(setOf(CHAT), outcome.rotated)
    }

    // ================================================ lifecycle, not just the archiver

    /**
     * The archive lifecycle as ChatRepository actually drives it.
     *
     * The earlier containment test drove [MessageArchiver] directly, which is one
     * layer below where the app calls in. [ArchiveSync] is the real entry point
     * for both directions - sealing an outgoing message and fetching a chat's
     * archives - so a flag that contains the archiver but leaks through here would
     * still be a hole.
     */
    @Test
    fun theRealArchiveLifecycleIsFullyContainedWhenDisabled() = runBlocking {
        val r = Rig(enabled = false)

        // Outbound: sealing a message must archive nothing and upload nothing.
        val sealed = r.archiveSync.sealAndUpload(USER, CHAT, "m1", "a message")
        assertNull("a disabled feature must not produce archive fields", sealed)

        // Inbound: fetching a chat's archives must not even ask.
        val page = r.archiveSync.downloadFor(CHAT)
        assertTrue(page.records.isEmpty())

        assertEquals("the AEAD must never be reached", 0, r.cipher.sealCalls)
        assertEquals("no durable keyring write", 0, r.store.writes)
        assertNull(r.store.generation)
        assertEquals("no archive traffic", 0, r.server.archiveGets)
        assertEquals("no recovery traffic of any kind", 0, r.server.total)
    }

    /**
     * The same lifecycle with the flag ON, so the OFF assertions above are known
     * to be measuring containment rather than a path that never works.
     */
    @Test
    fun theRealArchiveLifecycleWorksWhenEnabled() = runBlocking {
        val r = Rig(enabled = true)

        val sealed = r.archiveSync.sealAndUpload(USER, CHAT, "m1", "a message")
        assertNotNull("the same call must work when enabled", sealed)
        assertEquals(1, r.cipher.sealCalls)
        assertTrue("the keyring must have been persisted", r.store.writes > 0)
        assertNotNull("and stamped with a generation", r.store.generation)

        val page = r.archiveSync.downloadFor(CHAT)
        assertTrue("recovery resolved (404), so the download may proceed", page.complete)
        assertTrue(r.server.archiveGets > 0)
    }

    /**
     * D: after a malformed recovery response, archive download must stay blocked.
     * Containment and fail-closed are separate properties, and this is the point
     * where they meet.
     */
    @Test
    fun aMalformedRecoveryBlobBlocksArchiveDownload() = runBlocking {
        val r = Rig(enabled = true)
        // Present but unopenable: not absence, so recovery must fail.
        r.server.blob = byteArrayOf(0x99.toByte(), 0x01)
        r.server.version = 1

        val page = r.archiveSync.downloadFor(CHAT)

        assertEquals("no archive may be fetched while the keyring is unresolved", 0, r.server.archiveGets)
        assertTrue(page.records.isEmpty())
        assertFalse("and an aborted pass must not claim completion", page.complete)
    }
}
