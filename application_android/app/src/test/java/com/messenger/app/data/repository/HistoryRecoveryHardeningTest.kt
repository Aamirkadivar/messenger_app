package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
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
 * GATE 5 PHASE 4 - recovery hardening, JVM.
 *
 * Three separable properties are pinned here:
 *
 *  1. [ArchiveSync.downloadFor] fails closed on BOTH shapes of recovery failure.
 *     A returned `Result.failure` and a thrown exception mean the same thing, and
 *     the old `runCatching{}.getOrNull()` collapsed the throw to `null`, which
 *     read as "no failure" and let the download proceed. Downloading archives
 *     before the keyring is resolved writes SEALED rows that can never be opened,
 *     and the apply step never overwrites a SEALED row - so that damage is
 *     permanent, which is why this warrants a dedicated suite.
 *
 *  2. Deletion of the server-side recovery blob is available and narrow.
 *
 *  3. Recovery can be explicitly re-armed within a live session without
 *     disturbing valid local keyring material.
 *
 * All key material is TEST-ONLY.
 */
class HistoryRecoveryHardeningTest {

    private companion object {
        const val USER = "user-h"
        const val CHAT = "chat-h"
    }

    // ------------------------------------------------------------------ fakes

    private open class Store : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        override suspend fun saveHistoryKeyring(sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }
        override suspend fun loadHistoryKeyring() = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring() = Result.success(Unit).also { blob = null }
        override suspend fun saveHistoryKeyringCache(plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }
        override suspend fun loadHistoryKeyringCache() = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache() = Result.success(Unit).also { cache = null }

        private var generation: Long? = null
        override suspend fun saveHistoryKeyringGeneration(generation: Long) =
            Result.success(Unit).also { this.generation = generation }
        override suspend fun loadHistoryKeyringGeneration() = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration() =
            Result.success(Unit).also { generation = null }
    }

    /**
     * A store that THROWS instead of returning a failure. This is the realistic
     * shape of the bug: a hardware-keystore fault surfaces as an exception, not
     * as a tidy `Result.failure`, and that is precisely the path that used to
     * slip past the guard.
     */
    private class ThrowingStore : Store() {
        override suspend fun loadHistoryKeyring(): Result<ByteArray?> =
            throw IllegalStateException("keystore unavailable")
    }

    private class Vault(var user: String?, var locked: Boolean = false) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun mask(b: ByteArray, tag: Int) = ByteArray(b.size) { i -> (b[i].toInt() xor tag).toByte() }
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) =
            if (locked) Result.failure(IllegalStateException("locked"))
            else Result.success(byteArrayOf(0x11) + mask(plaintext, 0x11))
        override suspend fun openHistoryKeyring(sealed: ByteArray) =
            if (locked) Result.failure(IllegalStateException("locked"))
            else if (sealed.isNotEmpty() && sealed[0] == 0x11.toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), 0x11))
            else Result.failure(IllegalStateException("not sealed"))
        // The recovery copy uses a DIFFERENT tag, mirroring the distinct AAD
        // domain in production: a device blob must not open as a recovery blob.
        override suspend fun sealForRecovery(plaintext: ByteArray) =
            if (locked || user == null) Result.failure(IllegalStateException("no mk"))
            else Result.success(byteArrayOf(0x22) + mask(plaintext, 0x22))
        override suspend fun openFromRecovery(sealed: ByteArray) =
            if (locked || user == null) Result.failure(IllegalStateException("no mk"))
            else if (sealed.isNotEmpty() && sealed[0] == 0x22.toByte())
                Result.success(mask(sealed.copyOfRange(1, sealed.size), 0x22))
            else Result.failure(IllegalStateException("not a recovery blob"))
    }

    private class FakeServer : ChatApiService by mockk(relaxed = true) {
        var blob: ByteArray? = null
        var version = 0
        var offline = false
        var deletes = 0
        var deleteStatus = 200
        /** The number that matters most in this suite. */
        var archiveGets = 0

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
            if (body.expectedVersion != version) return Response.error(409, ResponseBody.create(null, ""))
            blob = Base64.getDecoder().decode(body.ciphertextB64)
            version += 1
            return Response.success(HistoryKeyringPutResponse(version, version == 1))
        }

        override suspend fun deleteHistoryKeyring(token: String): Response<Unit> {
            if (offline) throw java.io.IOException("offline")
            deletes++
            if (deleteStatus != 200) return Response.error(deleteStatus, ResponseBody.create(null, ""))
            blob = null
            version = 0
            return Response.success(Unit)
        }

        override suspend fun listArchives(
            token: String,
            chatId: String?,
            since: String?,
            sinceId: String?,
        ): Response<ArchiveListResponse> {
            archiveGets++
            return Response.success(ArchiveListResponse(emptyList(), 0))
        }
    }

    private class Tokens(private val token: String? = "t") : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success(token)
    }

    private class Cipher : ArchiveCipher {
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) = "sealed".toByteArray()
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? = null
    }

    private class Rig(
        val store: Store = Store(),
        val server: FakeServer = FakeServer(),
        user: String? = USER,
        enabled: Boolean = true,
        token: String? = "t",
        /** When set, `recovery.get()` itself blows up - a provisioning failure. */
        lazyThrows: Boolean = false,
    ) {
        val vault = Vault(user)
        val feature = HistoryArchiveFeature { enabled }
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, HistoryArchiveFeature { true })
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(token), feature)
        val archiver = MessageArchiver(keyring, Cipher(), feature)
        val lazySync: dagger.Lazy<HistoryKeyringRecoverySync> =
            if (lazyThrows) dagger.Lazy { throw IllegalStateException("recovery unavailable") }
            else dagger.Lazy { sync }
        val archiveSync = ArchiveSync(archiver, server, Tokens(token), feature, lazySync)
    }

    // ============================================================ 1. fail closed

    /**
     * The headline regression. Recovery throws; the download must make ZERO
     * archive requests and must not claim completion.
     */
    @Test
    fun aThrownRecoveryExceptionProducesZeroArchiveGets() = runBlocking {
        val r = Rig(lazyThrows = true)
        val page = r.archiveSync.downloadFor(CHAT)

        assertEquals("a thrown recovery failure must stop the download dead", 0, r.server.archiveGets)
        assertTrue(page.records.isEmpty())
        assertFalse("an aborted sync must never report itself complete", page.complete)
    }

    /**
     * The same property via a realistic fault: the keystore throws mid-recovery.
     *
     * The server must actually hold a blob for this to bite - a 404 is resolved
     * without ever reading local storage, so the throwing store would never be
     * reached. A peer device publishes a genuine keyring first, which forces the
     * merge step, which is where the local read explodes.
     */
    @Test
    fun aThrowingKeystoreAlsoBlocksTheDownload() = runBlocking {
        val server = FakeServer()
        val peer = Rig(store = Store(), server = server)
        peer.keyring.ensureRoot(CHAT).getOrThrow()
        peer.sync.uploadIfChanged().getOrThrow()
        assertNotNull("precondition: a real blob is stored", server.blob)

        val r = Rig(store = ThrowingStore(), server = server)
        val page = r.archiveSync.downloadFor(CHAT)

        assertEquals("a throwing keystore must stop the download dead", 0, server.archiveGets)
        assertFalse(page.complete)
    }

    /** The already-handled shape, kept alongside so the pair cannot drift apart. */
    @Test
    fun aReturnedRecoveryFailureAlsoProducesZeroArchiveGets() = runBlocking {
        val r = Rig()
        r.server.blob = byteArrayOf(0x99.toByte(), 0x01, 0x02) // not openable under this MK
        r.server.version = 3

        val page = r.archiveSync.downloadFor(CHAT)
        assertEquals(0, r.server.archiveGets)
        assertFalse(page.complete)
    }

    /**
     * Both shapes must be indistinguishable to the caller. If they ever diverge
     * again, this fails even when the two tests above still pass individually.
     */
    @Test
    fun bothFailureShapesAreTreatedIdentically() = runBlocking {
        val thrownRig = Rig(lazyThrows = true)
        val thrown = thrownRig.archiveSync.downloadFor(CHAT)

        val returnedRig = Rig()
        returnedRig.server.blob = byteArrayOf(0x99.toByte())
        returnedRig.server.version = 1
        val returned = returnedRig.archiveSync.downloadFor(CHAT)

        assertEquals(thrown.complete, returned.complete)
        assertEquals(thrown.records.size, returned.records.size)
        assertEquals(thrownRig.server.archiveGets, returnedRig.server.archiveGets)
    }

    /** Success must still let the download through - fail-closed, not fail-always. */
    @Test
    fun successfulRecoveryStillPermitsTheDownload() = runBlocking {
        val r = Rig()
        val page = r.archiveSync.downloadFor(CHAT) // server 404s: nothing to recover
        assertTrue("a clean 404 is a resolved keyring, not a failure", r.server.archiveGets > 0)
        assertTrue(page.complete)
    }

    // ============================================================ 2. deletion

    @Test
    fun deleteRemovesTheServerBlobAndForgetsTheSession() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        assertNotNull("precondition: something is stored", r.server.blob)

        r.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).getOrThrow()

        assertNull("the stale blob must be gone", r.server.blob)
        assertEquals(1, r.server.deletes)
        // Session state was reset, so the next publish believes nothing exists -
        // which is exactly right, because nothing does.
        r.sync.uploadIfChanged().getOrThrow()
        assertEquals("the fresh publish must be a create at v1", 1, r.server.version)
    }

    @Test
    fun deleteWithNoStoredBlobSucceeds() = runBlocking {
        val r = Rig()
        // Idempotence matters: the fresh-MK path runs this on brand-new accounts.
        assertTrue(r.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).isSuccess)
        assertEquals(1, r.server.deletes)
    }

    @Test
    fun deleteSurfacesServerAndTransportFailures() = runBlocking {
        val http = Rig()
        http.server.deleteStatus = 500
        assertTrue("an HTTP error must not be reported as a deletion", http.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).isFailure)

        val offline = Rig()
        offline.server.offline = true
        assertTrue("a thrown transport error must surface too", offline.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).isFailure)
        assertEquals("nothing may be assumed deleted after a throw", 0, offline.server.deletes)
    }

    @Test
    fun deleteWithoutASessionFails() = runBlocking {
        val r = Rig(token = null)
        assertTrue("no token means no authenticated delete", r.sync.invalidateHistoryRecoveryBeforeMkReplacement(
            HistoryKeyringRecoverySync.MkReplacement.IntentionalReset).isFailure)
        assertEquals("nothing may be sent unauthenticated", 0, r.server.deletes)
    }

    /**
     * Deletion must never be reachable from a mere failure to open. A blob that
     * will not open looks identical to a retired MK at this level, and deleting
     * on that evidence would destroy a good blob during a transient fault.
     */
    @Test
    fun aFailedRecoveryNeverDeletesTheServerBlob() = runBlocking {
        val r = Rig()
        r.server.blob = byteArrayOf(0x99.toByte(), 0x42) // unopenable
        r.server.version = 2

        assertTrue(r.sync.ensureRecovered().isFailure)
        r.archiveSync.downloadFor(CHAT)
        r.sync.refreshRecovery()

        assertEquals("no failure path may delete", 0, r.server.deletes)
        assertNotNull("the blob must still be there", r.server.blob)
    }

    // ============================================================ 3. re-arm

    /**
     * Recovery is one-shot per session, so roots another device publishes
     * mid-session are invisible until the next unlock. Re-arming is the explicit
     * escape hatch.
     */
    @Test
    fun refreshPicksUpRootsPublishedAfterTheFirstAttempt() = runBlocking {
        val r = Rig()
        r.sync.ensureRecovered().getOrThrow()      // 404: nothing yet
        assertNull(r.keyring.load().getOrThrow().latest(CHAT))

        // Another device of the same account publishes a keyring holding CHAT.
        val peer = Rig(store = Store(), server = r.server)
        peer.keyring.ensureRoot(CHAT).getOrThrow()
        peer.sync.uploadIfChanged().getOrThrow()

        // Without a re-arm the one-shot flag hides it.
        assertFalse("the one-shot flag must still be latched", r.sync.ensureRecovered().getOrThrow())
        assertNull("so the peer root stays invisible", r.keyring.load().getOrThrow().latest(CHAT))

        assertTrue("the re-arm must import", r.sync.refreshRecovery().getOrThrow())
        assertNotNull("the peer root must now be present", r.keyring.load().getOrThrow().latest(CHAT))
    }

    @Test
    fun refreshNeverDiscardsValidLocalRoots() = runBlocking {
        val r = Rig()
        val mine = r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        repeat(3) { r.sync.refreshRecovery() }

        val after = r.keyring.load().getOrThrow().latest(CHAT)
        assertNotNull(after)
        assertEquals("a re-arm is a merge, never a re-mint", mine.rootVersion, after!!.rootVersion)
        assertTrue(mine.root.contentEquals(after.root))
    }

    @Test
    fun refreshRespectsTheFeatureFlag() = runBlocking {
        val r = Rig(enabled = false)
        assertFalse("a disabled feature must not talk to the server", r.sync.refreshRecovery().getOrThrow())
        assertEquals(0, r.server.deletes)
        assertNull(r.server.blob)
    }

    @Test
    fun refreshFailureKeepsArchivesBlocked() = runBlocking {
        val r = Rig()
        r.server.blob = byteArrayOf(0x99.toByte(), 0x07) // unopenable
        r.server.version = 1

        assertTrue(r.sync.refreshRecovery().isFailure)
        assertEquals("still no archive traffic", 0, r.server.archiveGets)
        assertFalse(r.archiveSync.downloadFor(CHAT).complete)
        assertEquals(0, r.server.archiveGets)
    }
}
