package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.model.ArchiveDto
import com.messenger.app.data.model.ArchiveListResponse
import com.messenger.app.data.model.HistoryKeyringDto
import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.model.HistoryKeyringPutResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * PHASE 56 - a caller arriving while recovery is running must receive that recovery's real outcome.
 *
 * WHAT WAS WRONG (Phase 54 Finding 1). `recoveryAttempted` is set BEFORE the work runs, and the
 * pre-lock fast path answered anyone who saw it set with `success(false)`. But `success(false)` is
 * not a neutral value - it is the legitimate answer for "recovery FINISHED and imported nothing",
 * which is what a 404 produces. A caller arriving mid-flight was therefore handed a
 * completed-and-empty story about work that had not happened yet. `ArchiveSync` refuses only on
 * `isFailure`, so that fabricated success suppressed its Phase 52 per-chat root check.
 *
 * THE FIX. The running recovery publishes a `CompletableDeferred` holding its eventual outcome.
 * A caller that finds one joins it and receives exactly what the worker received. Nothing else
 * moves: the one-shot still latches after a COMPLETED recovery, `success(false)` still means
 * completed-and-empty, and exactly one recovery executes per wave.
 *
 * WHY JOINING RATHER THAN JUST SERIALISING ON THE MUTEX. Removing the fast path so every caller
 * blocks on the gate does not work. After a success the joiner still finds the flag set and is
 * still told `success(false)`; after a failure the reset re-arms the flag, so every joiner runs
 * recovery AGAIN - trading a wrong answer for N network round trips. Joining is the only shape that
 * gives the right answer and keeps one execution.
 *
 * Determinism: interleavings are forced with a latch inside the transport and a barrier the joiners
 * signal, never with sleeps.
 *
 * All key material here is TEST-ONLY.
 */
class ConcurrentRecoverySemanticsTest {

    private companion object {
        const val USER = "user-1"
        const val CHAT = "chat-1"
        const val OTHER = "chat-2"
        val CT: String = Base64.getEncoder().encodeToString("sealed-bytes".toByteArray())
    }

    // ------------------------------------------------------------------ fakes

    private class DeviceStore : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        private var generation: Long? = null

        override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray) =
            Result.success(Unit).also { blob = sealed.copyOf() }

        override suspend fun loadHistoryKeyring(owner: String) = Result.success(blob?.copyOf())
        override suspend fun deleteHistoryKeyring(owner: String) =
            Result.success(Unit).also { blob = null }

        override suspend fun saveHistoryKeyringCache(owner: String, plain: ByteArray) =
            Result.success(Unit).also { cache = plain.copyOf() }

        override suspend fun loadHistoryKeyringCache(owner: String) = Result.success(cache?.copyOf())
        override suspend fun deleteHistoryKeyringCache(owner: String) =
            Result.success(Unit).also { cache = null }

        override suspend fun saveHistoryKeyringGeneration(owner: String, generation: Long) =
            Result.success(Unit).also { this.generation = generation }

        override suspend fun loadHistoryKeyringGeneration(owner: String) = Result.success(generation)
        override suspend fun deleteHistoryKeyringGeneration(owner: String) =
            Result.success(Unit).also { generation = null }
    }

    private enum class OpenMode { OK, LOCKED, THROW }

    private class Vault(
        val user: String = USER,
        @Volatile var openMode: OpenMode = OpenMode.OK,
    ) : HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var park = false

        val recoveryOpens = AtomicInteger(0)

        private fun seal(domain: String, p: ByteArray) =
            Result.success((domain + "|" + user + "|").toByteArray(Charsets.UTF_8) + p.copyOf())

        private fun open(domain: String, sealed: ByteArray): Result<ByteArray> {
            val prefix = (domain + "|" + user + "|").toByteArray(Charsets.UTF_8)
            if (sealed.size < prefix.size ||
                !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)
            ) {
                return Result.failure(IllegalStateException("failed authentication"))
            }
            return Result.success(sealed.copyOfRange(prefix.size, sealed.size))
        }

        override suspend fun sealHistoryKeyring(p: ByteArray) = seal("local", p)
        override suspend fun openHistoryKeyring(s: ByteArray) = open("local", s)
        override suspend fun sealForRecovery(p: ByteArray) = seal("recovery", p)

        override suspend fun openFromRecovery(s: ByteArray): Result<ByteArray> {
            recoveryOpens.incrementAndGet()
            if (park) {
                entered.complete(Unit)
                release.await()
            }
            return when (openMode) {
                OpenMode.OK -> open("recovery", s)
                OpenMode.LOCKED ->
                    Result.failure(IllegalStateException("history keyring cannot be opened: vault is locked"))
                OpenMode.THROW -> throw IllegalStateException("recovery open raised")
            }
        }
    }

    private class Server(val user: String = USER) : ChatApiService by mockk(relaxed = true) {
        var keyringBlob: ByteArray? = null
        var version = 0

        @Volatile
        var serve404 = false

        val keyringGets = AtomicInteger(0)
        val archiveGets = AtomicInteger(0)
        val archives = mutableMapOf<String, List<ArchiveDto>>()

        /**
         * A second park point, at the fetch.
         *
         * The 404 path returns from recoverInternal before the recovery open is ever reached, so the
         * vault's park cannot hold it. Holding it here is the only way to make "nothing to recover"
         * genuinely concurrent rather than accidentally sequential.
         */
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var park = false

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            keyringGets.incrementAndGet()
            if (park) {
                entered.complete(Unit)
                release.await()
            }
            val b = keyringBlob.takeIf { !serve404 }
                ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(
                HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b))
            )
        }

        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            if (body.expectedVersion != version) {
                return Response.error(409, ResponseBody.create(null, ""))
            }
            keyringBlob = Base64.getDecoder().decode(body.ciphertextB64)
            version += 1
            return Response.success(HistoryKeyringPutResponse(version, version == 1))
        }

        override suspend fun listArchives(
            token: String,
            chatId: String?,
            since: String?,
            sinceId: String?,
        ): Response<ArchiveListResponse> {
            archiveGets.incrementAndGet()
            val rows = archives[chatId].orEmpty()
            return Response.success(ArchiveListResponse(rows, rows.size))
        }
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    private class Cipher : ArchiveCipher {
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) =
            "sealed".toByteArray()

        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? =
            null
    }

    // ------------------------------------------------------------------ rig

    private val feature = HistoryArchiveFeature { true }

    private class Rig(
        val store: DeviceStore,
        val vault: Vault,
        val server: Server,
        val keyring: HistoryKeyringRepository,
        val sync: HistoryKeyringRecoverySync,
        val archiveSync: ArchiveSync,
    )

    private suspend fun rig(
        user: String = USER,
        seedServer: Boolean = true,
        seedLocalRootFor: String? = null,
    ): Rig {
        val store = DeviceStore()
        val server = Server(user)
        server.archives[CHAT] = listOf(ArchiveDto("m-chat1", CHAT, 1, 1, CT, "2026-09-01T00:00:00Z"))
        server.archives[OTHER] = listOf(ArchiveDto("m-other", OTHER, 1, 1, CT, "2026-09-01T00:00:00Z"))

        if (seedServer) {
            val seedVault = Vault(user)
            val seedRepo = HistoryKeyringRepository(
                seedVault, DeviceStore(), HistoryUserProvider { user }, feature
            )
            assertTrue(seedRepo.ensureRoot(CHAT).isSuccess)
            HistoryKeyringRecoverySync(seedRepo, seedVault, server, Tokens(), feature).uploadIfChanged()
            assertNotNull("fixture: the server must hold a keyring blob", server.keyringBlob)
        }

        val vault = Vault(user)
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, feature)
        if (seedLocalRootFor != null) assertTrue(keyring.ensureRoot(seedLocalRootFor).isSuccess)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
        val archiveSync = ArchiveSync(
            MessageArchiver(keyring, Cipher(), feature),
            server, Tokens(), feature, dagger.Lazy { sync }, keyring
        )
        return Rig(store, vault, server, keyring, sync, archiveSync)
    }

    private fun label(r: Result<Boolean>) = when {
        r.isFailure -> "failure"
        r.getOrNull() == true -> "success(true)"
        else -> "success(false)"
    }

    /** Both production callers flatten a throw; the harness sees what they see. */
    private suspend fun safely(block: suspend () -> Result<Boolean>): Result<Boolean> =
        runCatching { block() }.getOrElse { Result.failure(it) }

    /**
     * Runs one worker parked inside recovery plus [joiners] concurrent callers.
     *
     * The joiners each signal a barrier immediately before calling `ensureRecovered`, and the
     * worker is released only once every one of them has signalled - so the wave is genuinely
     * concurrent rather than accidentally sequential.
     */
    private suspend fun wave(
        rig: Rig,
        joiners: Int,
        /** Park at the fetch instead of the open - the only point the 404 path passes through. */
        atFetch: Boolean = false,
    ): Pair<Result<Boolean>, List<Result<Boolean>>> {
        if (atFetch) rig.server.park = true else rig.vault.park = true
        return coroutineScope {
            val worker = async { safely { rig.sync.ensureRecovered() } }
            // The worker owns the claim and is inside recovery.
            if (atFetch) rig.server.entered.await() else rig.vault.entered.await()

            val others = (1..joiners).map { async { safely { rig.sync.ensureRecovered() } } }
            // One hand-off per joiner is enough for each to run up to its suspension point on the
            // in-flight result, because this is a single-threaded event loop.
            repeat(joiners + 1) { yield() }

            if (atFetch) rig.server.release.complete(Unit) else rig.vault.release.complete(Unit)
            worker.await() to others.awaitAll()
        }
    }

    // ================================================================== §9 / §11 semantics

    @Test
    fun concurrentCallersObserveASuccessfulRecovery() = runBlocking {
        val rig = rig()
        val (a, others) = withTimeout(60_000) { wave(rig, joiners = 3) }

        assertEquals("the worker imported", "success(true)", label(a))
        for (r in others) {
            assertEquals(
                "a joiner must receive the real outcome, not a fabricated success(false)",
                "success(true)", label(r)
            )
        }
        assertEquals("exactly one recovery executed", 1, rig.vault.recoveryOpens.get())
        assertEquals("and exactly one keyring GET", 1, rig.server.keyringGets.get())
    }

    @Test
    fun concurrentCallersObserveAReturnedFailure() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.LOCKED
        val (a, others) = withTimeout(60_000) { wave(rig, joiners = 3) }

        assertTrue("the worker failed", a.isFailure)
        for (r in others) assertTrue("every joiner must see the failure", r.isFailure)
        assertEquals("exactly one recovery executed", 1, rig.vault.recoveryOpens.get())

        // §12 - and the wave leaves recovery retryable.
        rig.vault.park = false
        rig.vault.openMode = OpenMode.OK
        assertTrue("a fresh call after the wave retries", rig.sync.ensureRecovered().isSuccess)
        assertEquals("that retry is the second execution", 2, rig.vault.recoveryOpens.get())
    }

    @Test
    fun concurrentCallersObserveAThrownFailure() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.THROW
        val (a, others) = withTimeout(60_000) { wave(rig, joiners = 3) }

        assertTrue("the worker failed", a.isFailure)
        for (r in others) assertTrue("every joiner must see the failure", r.isFailure)
        assertEquals(1, rig.vault.recoveryOpens.get())

        // Phase 55 must remain intact: a raised failure still re-arms.
        rig.vault.park = false
        rig.vault.openMode = OpenMode.OK
        assertTrue("Phase 55: still retryable after a raise", rig.sync.ensureRecovered().isSuccess)
        assertEquals(2, rig.vault.recoveryOpens.get())
        assertNotNull(rig.keyring.load().getOrNull()!!.latest(CHAT))
    }

    /** §14 - completed-and-empty is still success(false), and now it is EARNED. */
    @Test
    fun concurrentCallersObserveNothingToRecover() = runBlocking {
        val rig = rig()
        rig.server.serve404 = true
        val (a, others) = withTimeout(60_000) { wave(rig, joiners = 3, atFetch = true) }

        assertEquals("404 completes empty", "success(false)", label(a))
        for (r in others) assertEquals("joiners agree", "success(false)", label(r))
        assertEquals("one execution", 1, rig.server.keyringGets.get())
    }

    /** §9 - a caller arriving AFTER a completed recovery keeps the existing one-shot answer. */
    @Test
    fun aCallerArrivingAfterSuccessStillGetsTheOneShotAnswer() = runBlocking {
        val rig = rig()
        assertEquals("success(true)", label(rig.sync.ensureRecovered()))
        assertEquals(
            "post-completion semantics are unchanged by this phase",
            "success(false)", label(rig.sync.ensureRecovered())
        )
        assertEquals(1, rig.vault.recoveryOpens.get())
    }

    // ================================================================== §10 fan-out

    @Test
    fun fanOut_2_4_8_16_32_oneExecutionAndNoFabricatedResults() = runBlocking {
        val rows = mutableListOf<String>()
        for (n in listOf(2, 4, 8, 16, 32)) {
            val rig = rig()
            val (a, others) = withTimeout(120_000) { wave(rig, joiners = n - 1) }

            assertEquals("n=$n worker", "success(true)", label(a))
            val wrong = others.count { label(it) != "success(true)" }
            rows += "n=%-3d recoveryExecutions=%d keyringGETs=%d joiners=%d fabricated=%d"
                .format(n, rig.vault.recoveryOpens.get(), rig.server.keyringGets.get(), others.size, wrong)
            assertEquals("n=$n: exactly one recovery execution", 1, rig.vault.recoveryOpens.get())
            assertEquals("n=$n: exactly one keyring GET", 1, rig.server.keyringGets.get())
            assertEquals("n=$n: no joiner may receive a fabricated result", 0, wrong)

            // §17 - a follower told "success" must find the keyring genuinely usable.
            assertNotNull(
                "n=$n: the keyring is usable once followers are released",
                rig.keyring.load().getOrNull()!!.latest(CHAT)
            )
        }
        println("[phase56 fan-out]\n  " + rows.joinToString("\n  "))
    }

    // ================================================================== §15 cancellation

    /**
     * The worker's coroutine is cancelled while a joiner waits.
     *
     * Recovery runs in the caller's coroutine and this class owns no scope, so the work genuinely
     * stops. The contract that matters is that the joiner is neither stranded nor cancelled: it
     * receives an ordinary failure, and the next caller can retry.
     */
    @Test
    fun workerCancelledWhileAJoinerWaits_joinerIsNeitherStrandedNorCancelled() = runBlocking {
        val rig = rig()
        rig.vault.park = true

        withTimeout(60_000) {
            coroutineScope {
                val workerJob = Job()
                var workerStarted = false
                launch(Dispatchers.Default + workerJob) {
                    workerStarted = true
                    rig.sync.ensureRecovered()
                }
                rig.vault.entered.await()
                assertTrue(workerStarted)

                val joiner = async { safely { rig.sync.ensureRecovered() } }
                yield()              // the joiner reaches the in-flight result and suspends

                workerJob.cancel()   // the owner goes away mid-flight

                // The joiner returns a VALUE at all, which is the point: it was settled rather
                // than left waiting on a deferred nobody will ever complete.
                val r = withTimeout(30_000) { joiner.await() }
                assertTrue("the joiner must be settled, not stranded", r.isFailure)
                // What it carries is the worker's cancellation, delivered as a failure value by the
                // Phase 55 boundary catch rather than as a raised CancellationException. So the
                // joiner is not itself cancelled - it completed normally and can act on the result,
                // which for the one production consumer means falling back to its per-chat check.
                assertNotNull("the failure must name a cause", r.exceptionOrNull())
            }
        }

        // §19 - no stale in-flight state: the next caller genuinely retries.
        rig.vault.park = false
        assertTrue("recovery must be retryable after a cancelled worker", rig.sync.ensureRecovered().isSuccess)
        assertEquals("the retry really executed", 2, rig.vault.recoveryOpens.get())
        assertNotNull(rig.keyring.load().getOrNull()!!.latest(CHAT))
    }

    /** A joiner giving up must not disturb the worker. */
    @Test
    fun joinerCancelledWhileWorkerRuns_workerIsUnaffected() = runBlocking {
        val rig = rig()
        rig.vault.park = true

        val worker = withTimeout(60_000) {
            coroutineScope {
                val w = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.await()

                val joinerJob = Job()
                launch(joinerJob) { rig.sync.ensureRecovered() }
                yield()              // the joiner suspends on the in-flight result
                joinerJob.cancel()
                yield()

                rig.vault.release.complete(Unit)
                w.await()
            }
        }

        assertEquals("the worker completed normally", "success(true)", label(worker))
        assertEquals("one execution", 1, rig.vault.recoveryOpens.get())
        assertNotNull(
            "and the recovery really landed",
            rig.keyring.load().getOrNull()!!.latest(CHAT)
        )
    }

    // ================================================================== §16 Phase 52 interaction

    /**
     * The reason the race mattered: a concurrent `downloadFor` used to skip the per-chat check.
     *
     * ArchiveSync is untouched. With the honest failure signal restored, a covered chat is still
     * allowed through the Phase 52 fallback.
     */
    @Test
    fun aConcurrentDownloadForACoveredChatStillReachesThePhase52Fallback() = runBlocking {
        val rig = rig(seedLocalRootFor = CHAT)
        rig.vault.park = true
        rig.vault.openMode = OpenMode.LOCKED   // authoritative recovery unavailable

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.await()

                val b = async { rig.archiveSync.downloadFor(CHAT) }
                yield()
                rig.vault.release.complete(Unit)

                assertTrue(a.await().isFailure)
                val page = b.await()
                assertEquals("the covered chat is downloaded via the cached root", 1, page.records.size)
            }
        }
        assertEquals("archive GET for the covered chat", 1, rig.server.archiveGets.get())
    }

    /** And an uncovered chat is still refused, concurrently. */
    @Test
    fun aConcurrentDownloadForAnUncoveredChatIsStillRefused() = runBlocking {
        val rig = rig(seedLocalRootFor = CHAT)
        rig.vault.park = true
        rig.vault.openMode = OpenMode.LOCKED

        assertNull(
            "precondition: no root for the uncovered chat",
            rig.keyring.load().getOrNull()?.latest(OTHER)
        )

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.await()

                val b = async { rig.archiveSync.downloadFor(OTHER) }
                yield()
                rig.vault.release.complete(Unit)

                assertTrue(a.await().isFailure)
                assertTrue("the uncovered chat must stay refused", b.await().records.isEmpty())
            }
        }
        assertEquals("nothing reached the wire for the uncovered chat", 0, rig.server.archiveGets.get())
    }

    // ================================================================== §18 account isolation

    @Test
    fun concurrentRecoveryForTwoAccountsStaysIsolated() = runBlocking {
        val a = rig(user = "account-a")
        val b = rig(user = "account-b")
        b.vault.openMode = OpenMode.LOCKED       // one account fails, the other must not care

        withTimeout(60_000) {
            coroutineScope {
                val ra = async(Dispatchers.Default) { safely { a.sync.ensureRecovered() } }
                val rb = async(Dispatchers.Default) { safely { b.sync.ensureRecovered() } }
                assertEquals("account A recovered", "success(true)", label(ra.await()))
                assertTrue("account B failed independently", rb.await().isFailure)
            }
        }
        assertNotNull("A holds its root", a.keyring.load().getOrNull()!!.latest(CHAT))
        assertNull("B holds nothing", b.keyring.load().getOrNull()?.latest(CHAT))
        assertEquals(1, a.vault.recoveryOpens.get())
        assertEquals(1, b.vault.recoveryOpens.get())
    }

    // ================================================================== §19 cleanup

    /** After every terminal state the next legitimate attempt must be possible. */
    @Test
    fun everyTerminalStateLeavesRecoveryUsableAgain() = runBlocking {
        // returned failure -> retry -> success
        val r1 = rig()
        r1.vault.openMode = OpenMode.LOCKED
        assertTrue(r1.sync.ensureRecovered().isFailure)
        r1.vault.openMode = OpenMode.OK
        assertTrue(r1.sync.ensureRecovered().isSuccess)

        // thrown failure -> retry -> success
        val r2 = rig()
        r2.vault.openMode = OpenMode.THROW
        assertTrue(safely { r2.sync.ensureRecovered() }.isFailure)
        r2.vault.openMode = OpenMode.OK
        assertTrue(r2.sync.ensureRecovered().isSuccess)

        // success -> latched, no further execution
        val r3 = rig()
        assertTrue(r3.sync.ensureRecovered().isSuccess)
        repeat(3) { r3.sync.ensureRecovered() }
        assertEquals("the one-shot still holds after success", 1, r3.vault.recoveryOpens.get())

        // 404 -> latched, no further execution
        val r4 = rig()
        r4.server.serve404 = true
        assertEquals("success(false)", label(r4.sync.ensureRecovered()))
        repeat(3) { r4.sync.ensureRecovered() }
        assertEquals("404 also latches", 1, r4.server.keyringGets.get())
    }
}
