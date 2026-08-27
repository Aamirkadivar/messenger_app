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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * GATE 5 PHASE 5 - recovery correctness beyond the two blockers.
 *
 * Covers the re-arm mechanism, the degenerate-success responses that used to be
 * mistaken for "nothing stored", and the upload-acknowledgement window.
 *
 * All key material is TEST-ONLY.
 */
class HistoryRecoveryCorrectnessTest {

    private companion object {
        const val USER = "corr-user"
        const val CHAT = "corr-chat"
        const val OTHER = "corr-chat-2"
        /** MAX_UPLOAD_ATTEMPTS is 3; one extra allows for the convergence write. */
        const val MAX_REASONABLE_PUTS = 4
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
     * Models the ACCOUNT BINDING that production gets from the AAD.
     *
     * Both the device copy and the recovery copy are sealed under an AAD that
     * includes the user id, so a blob written by one account simply does not
     * authenticate under another. A fake that opens any blob regardless of owner
     * would quietly pass tests about account separation while proving nothing
     * about it, so the owner is bound here too - by tag rather than by real
     * cryptography, but bound.
     */
    private class Vault(private val user: () -> String?) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        constructor(fixed: String?) : this({ fixed })
        // Resolved per call, exactly as production resolves the AAD from the
        // currently signed-in user. Capturing it at construction would let a test
        // pair one account's vault with another account's session - a state the
        // real code cannot be in.
        private fun tag(domain: Int) = domain xor ((user()?.hashCode() ?: 0) and 0x7F)
        private fun mask(b: ByteArray, t: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor t).toByte() }
        private fun seal(domain: Int, plaintext: ByteArray): Result<ByteArray> {
            if (user() == null) return Result.failure(IllegalStateException("no mk"))
            val t = tag(domain)
            return Result.success(byteArrayOf(t.toByte()) + mask(plaintext, t))
        }
        private fun open(domain: Int, sealed: ByteArray): Result<ByteArray> {
            if (user() == null) return Result.failure(IllegalStateException("no mk"))
            val t = tag(domain)
            if (sealed.isEmpty() || sealed[0] != t.toByte()) {
                return Result.failure(IllegalStateException("authentication failed"))
            }
            return Result.success(mask(sealed.copyOfRange(1, sealed.size), t))
        }
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) = seal(0x11, plaintext)
        override suspend fun openHistoryKeyring(sealed: ByteArray) = open(0x11, sealed)
        override suspend fun sealForRecovery(plaintext: ByteArray) = seal(0x22, plaintext)
        override suspend fun openFromRecovery(sealed: ByteArray) = open(0x22, sealed)
    }

    /** Can serve the degenerate 2xx shapes a faulty or hostile server might. */
    private class Server : ChatApiService by mockk(relaxed = true) {
        var blob: ByteArray? = null
        var version = 0
        var puts = 0
        var gets = 0
        /** "nullBody", "blank", "emptyDecode", "badBase64", "notAKeyring", or null. */
        var degenerate: String? = null

        /**
         * Runs while the PUT is in flight - i.e. after the client exported and
         * sealed its bytes, before it learns the outcome. This is the mutation
         * race made deterministic: no threads, no timing, just the mutation
         * happening at exactly the instant that matters.
         */
        var duringPut: (suspend () -> Unit)? = null

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            gets++
            when (degenerate) {
                "nullBody" -> return Response.success(null)
                "blank" -> return Response.success(HistoryKeyringDto(3, "   "))
                "emptyDecode" -> return Response.success(HistoryKeyringDto(3, ""))
                "badBase64" -> return Response.success(HistoryKeyringDto(3, "!!!not base64!!!"))
                // Well-formed base64 that is not a keyring encoding at all.
                "notAKeyring" -> return Response.success(
                    HistoryKeyringDto(3, Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9, 9)))
                )
            }
            val b = blob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b)))
        }

        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            puts++
            duringPut?.let { hook -> duringPut = null; hook() }
            if (body.expectedVersion != version) return Response.error(409, ResponseBody.create(null, ""))
            blob = Base64.getDecoder().decode(body.ciphertextB64)
            version += 1
            return Response.success(HistoryKeyringPutResponse(version, version == 1))
        }
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    private class Rig(
        val store: Store = Store(),
        val server: Server = Server(),
        user: String? = USER,
    ) {
        val vault = Vault(user)
        val feature = HistoryArchiveFeature.Enabled
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
    }

    // ============================================ degenerate 2xx responses

    /**
     * Only 404 means absence. A 2xx carrying nothing usable is corruption, and
     * treating it as "nothing stored" let a faulty server silently suppress
     * recovery while archives were still being accepted.
     */
    @Test
    fun everyDegenerateSuccessFailsClosed() = runBlocking {
        for (shape in listOf("nullBody", "blank", "emptyDecode", "badBase64")) {
            val r = Rig()
            r.server.degenerate = shape
            val result = r.sync.ensureRecovered()
            assertTrue("a $shape response must be a failure, not absence", result.isFailure)
        }
    }

    @Test
    fun aRealAbsenceIsStillSuccess() = runBlocking {
        val r = Rig() // server holds nothing -> 404
        assertFalse(
            "404 is absence, and absence is not an error",
            r.sync.ensureRecovered().getOrThrow()
        )
    }

    @Test
    fun aDegenerateResponseNeverDiscardsLocalRoots() = runBlocking {
        val r = Rig()
        val mine = r.keyring.ensureRoot(CHAT).getOrThrow()
        r.server.degenerate = "blank"

        assertTrue(r.sync.ensureRecovered().isFailure)

        val after = r.keyring.load().getOrThrow().latest(CHAT)
        assertNotNull("a corrupt server response must not cost local roots", after)
        assertTrue(mine.root.contentEquals(after!!.root))
    }

    @Test
    fun wrongMkIsAFailureNotAnEmptyKeyring() = runBlocking {
        val r = Rig()
        r.server.blob = byteArrayOf(0x99.toByte(), 0x01) // will not open under this MK
        r.server.version = 1

        val result = r.sync.ensureRecovered()
        assertTrue(result.isFailure)
        assertNull(
            "an authentication failure must never surface as an empty keyring",
            r.keyring.load().getOrThrow().latest(CHAT)
        )
    }

    // ============================================ re-arm

    @Test
    fun refreshIsSafeWhenCalledRepeatedly() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        val versionAfterPublish = r.server.version

        repeat(5) { assertTrue(r.sync.refreshRecovery().isSuccess) }

        assertEquals(
            "an import that adds nothing must not bump the server version",
            versionAfterPublish, r.server.version
        )
        assertNotNull(r.keyring.load().getOrThrow().latest(CHAT))
    }

    @Test
    fun refreshUnionsRemoteAndLocalRoots() = runBlocking {
        val server = Server()
        val peer = Rig(server = server)
        peer.keyring.ensureRoot(OTHER).getOrThrow()
        peer.sync.uploadIfChanged().getOrThrow()

        val mine = Rig(server = server)
        val local = mine.keyring.ensureRoot(CHAT).getOrThrow()
        mine.sync.refreshRecovery().getOrThrow()

        val merged = mine.keyring.load().getOrThrow()
        assertNotNull("the remote root must arrive", merged.latest(OTHER))
        assertNotNull("and the local root must survive", merged.latest(CHAT))
        assertTrue(local.root.contentEquals(merged.latest(CHAT)!!.root))
    }

    /** Concurrent re-arms must serialise on the existing gate, not race. */
    @Test
    fun concurrentRefreshesDoNotRace() = runBlocking {
        val server = Server()
        val peer = Rig(server = server)
        peer.keyring.ensureRoot(OTHER).getOrThrow()
        peer.sync.uploadIfChanged().getOrThrow()
        val versionAfterPeer = server.version

        val mine = Rig(server = server)
        mine.keyring.ensureRoot(CHAT).getOrThrow()

        val results = (1..8).map { async { mine.sync.refreshRecovery() } }.awaitAll()
        assertTrue("no concurrent re-arm may fail", results.all { it.isSuccess })

        val merged = mine.keyring.load().getOrThrow()
        assertNotNull(merged.latest(CHAT))
        assertNotNull(merged.latest(OTHER))
        // One convergence write is expected because this device contributed a root
        // the server lacked; eight concurrent callers must not produce eight.
        assertEquals(
            "concurrent re-arms must not each bump the version",
            versionAfterPeer + 1, server.version
        )
    }

    @Test
    fun refreshPreservesBoundedRetry() = runBlocking {
        // A permanently conflicting server must give up rather than spin.
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.server.blob = byteArrayOf(0x99.toByte(), 0x02)
        r.server.version = 7 // every expectedVersion the client names will conflict

        assertTrue(r.sync.refreshRecovery().isFailure)
        assertTrue(
            "the client must not have hammered the server",
            r.server.puts <= MAX_REASONABLE_PUTS
        )
    }

    // ============================================ upload acknowledgement

    /**
     * A mutation landing while an upload is in flight was never sent, so it must
     * not be acknowledged as published - otherwise the next `uploadIfChanged`
     * sees an unchanged revision and skips it.
     */
    @Test
    fun aMutationDuringUploadIsStillPublishedAfterwards() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        // A second root appears after the first publish. It must reach the server.
        r.keyring.ensureRoot(OTHER).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()

        val published = r.server.blob!!
        val decoded = r.vault.openFromRecovery(published).getOrThrow()
        val keyring = com.messenger.app.data.encryption.history.HistoryKeyring
            .decode(decoded).getOrThrow()
        assertNotNull(keyring.latest(CHAT))
        assertNotNull("the later mutation must not be stranded", keyring.latest(OTHER))
    }

    @Test
    fun anUnchangedKeyringIsStillNotRepublished() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged().getOrThrow()
        val puts = r.server.puts

        repeat(5) { r.sync.uploadIfChanged().getOrThrow() }
        assertEquals("the revision short-circuit must still hold", puts, r.server.puts)
    }

    // ============================================ logout / account switch

    /**
     * A -> logout -> B in ONE process, which is the case that has no restart to
     * save it. Before this gate, `clearSessionSecrets` had no production caller,
     * so B inherited A's "recovery already attempted" flag and skipped its own
     * recovery for the whole session, and A's opened keyring stayed in memory.
     */
    @Test
    fun logoutClearsSessionStateSoASecondAccountRecoversProperly() = runBlocking {
        val server = Server()

        // Account A signs in, mints a root, publishes.
        val a = Rig(server = server, user = "user-a")
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.ensureRecovered().getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()

        // Logout: session state goes, durable material stays.
        val durableBefore = a.store.blob!!.copyOf()
        a.sync.resetSession()
        assertTrue(
            "logout must not destroy the sealed keyring",
            a.store.blob!!.contentEquals(durableBefore)
        )

        // Account B signs in on the same process. Its recovery must actually run.
        val getsBefore = server.gets
        val b = Rig(store = a.store, server = server, user = "user-b")
        b.sync.ensureRecovered()
        assertTrue(
            "B must perform its own recovery, not inherit A's one-shot flag",
            server.gets > getsBefore
        )
    }

    @Test
    fun aSecondAccountCannotReadTheFirstAccountsOpenedKeyring() = runBlocking {
        val store = Store()
        val a = Rig(store = store, user = "user-a")
        a.keyring.ensureRoot(CHAT).getOrThrow()
        assertNotNull(a.keyring.load().getOrThrow().latest(CHAT))

        a.sync.resetSession()

        // The cache on this device is bound to A, so B must not adopt it - and
        // the in-memory copy must have been dropped by the logout.
        val b = Rig(store = store, user = "user-b")
        assertNull(
            "B must not inherit A's roots from memory or from A's cache",
            b.keyring.load().getOrThrow().latest(CHAT)
        )
    }

    @Test
    fun resetSessionDropsTheInMemoryKeyring() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.keyring.load().getOrThrow()

        r.sync.resetSession()

        // Storage is untouched, so the same account simply reopens it.
        assertNotNull(r.keyring.load().getOrThrow().latest(CHAT))
        assertNotNull("durable material must survive a logout", r.store.blob)
    }

    // ============================================ mutation during upload

    /**
     * The publication race, made deterministic.
     *
     * Revision N is exported and sent. While the PUT is in flight, a new root is
     * minted, taking the device to N+1 - material the server has never seen. The
     * acknowledgement that comes back is for N, and it must acknowledge only N.
     * Marking N+1 as published would strand that root until some unrelated
     * mutation happened to move the revision again.
     */
    @Test
    fun aMutationDuringPutIsNeverAcknowledgedAsPublished() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()

        // The mutation lands after the bytes were exported, before the response.
        r.server.duringPut = { r.keyring.ensureRoot(OTHER).getOrThrow() }
        r.sync.uploadIfChanged().getOrThrow()

        // What the server holds is N: only the first chat.
        val onServerAfterFirst = decodeServerBlob(r)
        assertNotNull(onServerAfterFirst.latest(CHAT))
        assertNull("the mid-flight root cannot have been sent", onServerAfterFirst.latest(OTHER))

        // N+1 must still be dirty, so the very next publish carries it.
        val putsBefore = r.server.puts
        r.sync.uploadIfChanged().getOrThrow()
        assertTrue("the unpublished revision must still be publishable", r.server.puts > putsBefore)

        val onServerAfterSecond = decodeServerBlob(r)
        assertNotNull(onServerAfterSecond.latest(CHAT))
        assertNotNull("the mid-flight root must reach the server", onServerAfterSecond.latest(OTHER))
    }

    @Test
    fun repeatedMutationsDuringUploadAllEventuallyPublish() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.server.duringPut = { r.keyring.ensureRoot("mid-1").getOrThrow() }
        r.sync.uploadIfChanged().getOrThrow()

        r.server.duringPut = { r.keyring.ensureRoot("mid-2").getOrThrow() }
        r.sync.uploadIfChanged().getOrThrow()

        // A third pass with no interference must settle everything.
        r.sync.uploadIfChanged().getOrThrow()

        val onServer = decodeServerBlob(r)
        for (chat in listOf(CHAT, "mid-1", "mid-2")) {
            assertNotNull("$chat must have been published", onServer.latest(chat))
        }
    }

    private suspend fun decodeServerBlob(r: Rig): com.messenger.app.data.encryption.history.HistoryKeyring {
        val sealed = r.server.blob!!
        val plain = r.vault.openFromRecovery(sealed).getOrThrow()
        return com.messenger.app.data.encryption.history.HistoryKeyring.decode(plain).getOrThrow()
    }

    @Test
    fun aStructurallyInvalidBlobIsAFailure() = runBlocking {
        val r = Rig()
        r.server.degenerate = "notAKeyring"
        assertTrue(
            "base64 that decodes to non-keyring bytes must not be accepted",
            r.sync.ensureRecovered().isFailure
        )
    }

    // ============================================ A -> logout -> B, one process

    /**
     * The full same-process account switch, end to end.
     *
     * A signs in, mints and publishes. A logs out. B signs in on the same process
     * and must behave as a completely independent account: it must not see A's
     * roots from memory or from A's device cache, must not inherit A's one-shot
     * recovery flag or published-revision counter, must actually attempt its own
     * recovery, and must be able to import its own blob.
     */
    @Test
    fun accountSwitchInOneProcessKeepsTheTwoAccountsIndependent() = runBlocking {
        val store = Store()          // one physical device
        val serverA = Server()
        val serverB = Server()

        // --- account A
        val a = Rig(store = store, server = serverA, user = "user-a")
        val rootA = a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.ensureRecovered().getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()
        assertNotNull("precondition: A published", serverA.blob)
        val durableA = store.blob!!.copyOf()

        // --- logout: this is exactly what E2EEVaultRepository.clearSessionSecrets drives
        a.sync.resetSession()
        assertTrue(
            "logout must not destroy durable keyring material",
            store.blob!!.contentEquals(durableA)
        )

        // --- account B, same process, same device storage
        val b = Rig(store = store, server = serverB, user = "user-b")

        // 1. A's roots are not visible to B.
        assertNull(
            "B must not see A's roots",
            b.keyring.load().getOrThrow().latest(CHAT)
        )

        // 2. B does not inherit A's recovery state: its first recovery really runs.
        val getsBefore = serverB.gets
        b.sync.ensureRecovered()
        assertTrue("B must attempt its own recovery", serverB.gets > getsBefore)

        // 3. B can publish and re-import its own material independently.
        val rootB = b.keyring.ensureRoot(OTHER).getOrThrow()
        b.sync.uploadIfChanged().getOrThrow()
        assertNotNull("B must be able to publish its own blob", serverB.blob)

        val bAgain = Rig(store = Store(), server = serverB, user = "user-b")
        bAgain.sync.ensureRecovered().getOrThrow()
        val recovered = bAgain.keyring.load().getOrThrow()
        assertNotNull("B must recover its own roots", recovered.latest(OTHER))
        assertTrue(rootB.root.contentEquals(recovered.latest(OTHER)!!.root))
        assertNull("and B's recovery must never contain A's chat", recovered.latest(CHAT))

        // 4. A's published blob is not openable by B at all.
        assertTrue(
            "cross-account recovery must be cryptographically impossible",
            b.vault.openFromRecovery(serverA.blob!!).isFailure
        )
        assertNotNull(rootA)
    }

    /**
     * The same separation without a logout call at all.
     *
     * Relying on a lifecycle hook being invoked is weaker than checking, so the
     * in-memory keyring is tagged with the account it was opened for. Even if a
     * path reached a second account without clearing the session, the previous
     * account's roots must not be served.
     */
    @Test
    fun anAccountChangeWithoutLogoutStillCannotExposeTheOtherAccountsRoots() = runBlocking {
        val store = Store()
        var current = "user-a"
        // One vault and one repository, both reading the SAME signed-in account -
        // which is the only arrangement production can produce.
        val vault = Vault { current }
        val keyring = HistoryKeyringRepository(
            vault, store, HistoryUserProvider { current }, HistoryArchiveFeature.Enabled
        )
        keyring.ensureRoot(CHAT).getOrThrow()
        assertNotNull(keyring.load().getOrThrow().latest(CHAT))

        current = "user-b"
        val seen = keyring.load()

        // Either it refuses, or it returns an empty keyring - never A's roots.
        val leaked = seen.getOrNull()?.latest(CHAT)
        assertNull("the in-memory copy must not outlive the account it belongs to", leaked)
    }
}
