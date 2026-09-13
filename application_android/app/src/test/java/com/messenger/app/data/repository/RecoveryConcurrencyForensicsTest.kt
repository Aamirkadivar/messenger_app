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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * PHASE 54 - forensic audit of concurrent history-keyring recovery. FINDINGS ONLY, NO FIX.
 *
 * These tests do not assert what the code SHOULD do. They pin what it ACTUALLY does, so that a
 * later phase deciding whether to change it can see the real behaviour rather than re-derive it.
 *
 * THE SHAPE UNDER AUDIT. `ensureRecovered()` reads its one-shot flag before taking its own lock:
 *
 *     if (recoveryAttempted) return Result.success(false)   // outside the gate
 *     return gate.withLock {
 *         if (recoveryAttempted) return@withLock Result.success(false)
 *         recoveryAttempted = true                          // set BEFORE the work runs
 *         recoverInternal()...onFailure { recoveryAttempted = false }
 *     }
 *
 * The flag is @Volatile, so this is not a visibility problem - it is a check-then-act whose window
 * is the whole of `recoverInternal()`: a network round trip plus an AEAD open. Any caller arriving
 * in that window is told `success(false)` immediately instead of waiting for the outcome.
 *
 * WHY `success(false)` IS THE INTERESTING VALUE. The Boolean is "did this import anything", so
 * `success(false)` legitimately means "nothing to recover" (a 404). It is NOT a failure, and the one
 * production consumer that branches on the result - ArchiveSync - only refuses on `isFailure`. So a
 * racing caller does not merely lose information; it takes the SUCCESS path.
 *
 * Determinism: interleavings are forced with a latch inside the transport, never with sleeps.
 *
 * All key material here is TEST-ONLY.
 */
class RecoveryConcurrencyForensicsTest {

    private companion object {
        const val USER = "user-1"

        /** A chat this device holds a root for. */
        const val CHAT = "chat-1"

        /** A chat it does not. */
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

    /** How the recovery open should behave, so each failure mode can be injected by name. */
    private enum class OpenMode { OK, LOCKED, AUTH_FAIL, TRANSPORT_THROW }

    /**
     * Vault + recovery transport with an optional park inside the recovery open.
     *
     * The park is what makes the interleaving deterministic: it holds a caller precisely inside
     * `recoverInternal()`, which is exactly the window during which `recoveryAttempted` is already
     * true and the outcome is still unknown.
     */
    private class Vault(
        val user: String? = USER,
        @Volatile var openMode: OpenMode = OpenMode.OK,
    ) : HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var park = false

        val recoveryOpens = AtomicInteger(0)

        private fun seal(domain: String, p: ByteArray): Result<ByteArray> {
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            return Result.success((domain + "|" + u + "|").toByteArray(Charsets.UTF_8) + p.copyOf())
        }

        private fun open(domain: String, sealed: ByteArray): Result<ByteArray> {
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            val prefix = (domain + "|" + u + "|").toByteArray(Charsets.UTF_8)
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
                OpenMode.AUTH_FAIL ->
                    Result.failure(IllegalStateException("failed authentication"))
                OpenMode.TRANSPORT_THROW -> throw IllegalStateException("transport blew up")
            }
        }
    }

    /** Counts everything that crosses the wire. */
    private class Server : ChatApiService by mockk(relaxed = true) {
        var keyringBlob: ByteArray? = null
        var version = 0

        @Volatile
        var keyringStatus = 200

        val keyringGets = AtomicInteger(0)
        val keyringPuts = AtomicInteger(0)
        val archiveGets = AtomicInteger(0)
        val archives = mutableMapOf<String, List<ArchiveDto>>()

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            keyringGets.incrementAndGet()
            if (keyringStatus == 403) return Response.error(403, ResponseBody.create(null, ""))
            if (keyringStatus == 500) throw java.io.IOException("transport timeout")
            val b = keyringBlob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(
                HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b))
            )
        }

        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            keyringPuts.incrementAndGet()
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

    private class Tokens(private val token: String? = "t") : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success(token)
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

    /**
     * A device with a server-side keyring blob it can normally open.
     *
     * [seedRootFor] mints a local root first, which is also what puts a blob on the server, so the
     * recovery path has something real to fetch rather than a vacuous 404.
     */
    private suspend fun rig(
        seedRootFor: String? = CHAT,
        token: String? = "t",
        /**
         * Publish the seed to the server but hand the device an EMPTY store, so "did recovery
         * import anything" is answerable - otherwise a locally seeded root makes the keyring
         * non-empty whether recovery ran or not.
         */
        seedServerOnly: Boolean = false,
    ): Rig = run {
        val store = DeviceStore()
        val server = Server()
        server.archives[CHAT] = listOf(ArchiveDto("m-chat1", CHAT, 1, 1, CT, "2026-09-01T00:00:00Z"))
        server.archives[OTHER] = listOf(ArchiveDto("m-other", OTHER, 1, 1, CT, "2026-09-01T00:00:00Z"))

        if (seedRootFor != null) {
            val warm = Vault()
            val seedStore = if (seedServerOnly) DeviceStore() else store
            val warmRepo = HistoryKeyringRepository(
                warm, seedStore, HistoryUserProvider { USER }, feature
            )
            assertTrue(warmRepo.ensureRoot(seedRootFor).isSuccess)
            HistoryKeyringRecoverySync(warmRepo, warm, server, Tokens(), feature).uploadIfChanged()
            assertNotNull("fixture: the server must hold a keyring blob", server.keyringBlob)
        }

        val vault = Vault()
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { USER }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(token), feature)
        val archiveSync = ArchiveSync(
            MessageArchiver(keyring, Cipher(), feature),
            server, Tokens(token), feature, dagger.Lazy { sync }, keyring
        )
        Rig(store, vault, server, keyring, sync, archiveSync)
    }

    /** Describes a Result<Boolean> in the three terms the audit cares about. */
    private fun label(r: Result<Boolean>) = when {
        r.isFailure -> "failure"
        r.getOrNull() == true -> "success(true)"
        else -> "success(false)"
    }

    // ================================================================== Case A

    @Test
    fun caseA_simultaneousCallers_recoverySucceeds() = runBlocking {
        val rig = rig()
        val results = withTimeout(30_000) {
            coroutineScope {
                (1..2).map { async(Dispatchers.Default) { rig.sync.ensureRecovered() } }.awaitAll()
            }
        }
        // Exactly one caller runs recovery; the other is told success(false).
        assertEquals("only one recovery execution", 1, rig.vault.recoveryOpens.get())
        assertTrue("neither caller sees a failure", results.none { it.isFailure })
        assertNotNull(
            "the keyring is usable for both",
            rig.keyring.load().getOrNull()!!.latest(CHAT)
        )
        println("[A] results=" + results.map(::label) + " keyringGets=" + rig.server.keyringGets.get())
    }

    @Test
    fun caseB_simultaneousCallers_recoveryFails() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.LOCKED
        val results = withTimeout(30_000) {
            coroutineScope {
                (1..2).map { async(Dispatchers.Default) { rig.sync.ensureRecovered() } }.awaitAll()
            }
        }
        // THE FINDING, in its mildest form: when recovery fails, a concurrent caller can still be
        // handed success(false) - the two callers disagree about the same single attempt.
        println("[B] results=" + results.map(::label) + " opens=" + rig.vault.recoveryOpens.get())
        assertTrue("at least one caller must see the real failure", results.any { it.isFailure })
    }

    // ================================================================== Case C - the core

    /**
     * The decisive interleaving: B arrives while A is still inside `recoverInternal()`.
     *
     * PHASE 54 FOUND: B did not block and did not learn A's outcome. It was told `success(false)` -
     * indistinguishable, to every caller, from "the server had nothing to recover".
     *
     * PHASE 56 FIXED IT: B now joins A's running recovery and receives exactly A's outcome. B still
     * runs no recovery of its own, so the wave is still one execution.
     */
    @Test
    fun caseC_lateCallerWhileRunning_joinsAndReceivesTheRealOutcome() = runBlocking {
        val rig = rig()
        rig.vault.park = true
        rig.vault.openMode = OpenMode.LOCKED   // A will ultimately FAIL

        withTimeout(30_000) {
            coroutineScope {
                val a = async { rig.sync.ensureRecovered() }
                rig.vault.entered.await()      // A is inside recovery

                // B now waits for the outcome, so it has to run concurrently - and on runBlocking's
                // single-threaded loop a yield() is a real hand-off, so it has genuinely joined
                // before A is released.
                val b = async { rig.sync.ensureRecovered() }
                yield()
                rig.vault.release.complete(Unit)

                val aResult = a.await()
                val bResult = b.await()
                assertTrue("A - the caller that actually ran it - sees the failure", aResult.isFailure)
                assertTrue("PHASE 56: B sees the same failure, not a fabricated success", bResult.isFailure)
                assertEquals("B ran no recovery of its own", 1, rig.vault.recoveryOpens.get())
                println("[C] A=" + label(aResult) + " B=" + label(bResult))
            }
        }
    }

    @Test
    fun caseD_lateCallerAfterSuccess_seesUsableKeyring() = runBlocking {
        val rig = rig()
        val first = rig.sync.ensureRecovered()
        val second = rig.sync.ensureRecovered()
        assertFalse(first.isFailure)
        assertEquals("the latched flag yields success(false)", "success(false)", label(second))
        assertNotNull(
            "and the keyring really is usable, so the answer is honest here",
            rig.keyring.load().getOrNull()!!.latest(CHAT)
        )
        assertEquals("no second recovery", 1, rig.vault.recoveryOpens.get())
    }

    @Test
    fun caseE_lateCallerAfterFailure_retrySemanticsIntact() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.LOCKED
        assertTrue(rig.sync.ensureRecovered().isFailure)
        // The flag was reset on failure, so a later caller genuinely retries.
        assertTrue("a sequential later caller must retry", rig.sync.ensureRecovered().isFailure)
        assertEquals("both attempts really ran", 2, rig.vault.recoveryOpens.get())
    }

    // ================================================================== fan-out

    @Test
    fun fanOut_2_4_8_16_32_callersWhileOneIsParked() = runBlocking {
        val rows = mutableListOf<String>()
        for (n in listOf(2, 4, 8, 16, 32)) {
            val rig = rig()
            rig.vault.park = true
            rig.vault.openMode = OpenMode.LOCKED

            val late = withTimeout(60_000) {
                coroutineScope {
                    val a = async { rig.sync.ensureRecovered() }
                    rig.vault.entered.await()
                    val others = (2..n).map { async { rig.sync.ensureRecovered() } }
                    repeat(n) { yield() }
                    rig.vault.release.complete(Unit)
                    a.await()
                    others.awaitAll()
                }
            }
            val falses = late.count { !it.isFailure && it.getOrNull() == false }
            rows += "n=%-3d recoveryExecutions=%d keyringGETs=%d lateCallers=%d successFalse=%d failures=%d"
                .format(n, rig.vault.recoveryOpens.get(), rig.server.keyringGets.get(),
                    late.size, falses, late.count { it.isFailure })
            assertEquals("exactly one recovery execution regardless of caller count",
                1, rig.vault.recoveryOpens.get())
            assertEquals(
                "PHASE 56: every late caller now receives the worker's real failure",
                late.size, late.count { it.isFailure })
            assertEquals("PHASE 56: and none is fobbed off with success(false)", 0, falses)
        }
        println("[fan-out]\n  " + rows.joinToString("\n  "))
    }

    // ================================================================== Phase 52 interaction

    /**
     * THE MATERIAL CONSEQUENCE (Phase 54). A racing caller skipped the Phase 52 per-chat root
     * check entirely, because that check is reached only on `isFailure`. Sequentially this chat was
     * refused (see [archiveDownload_sequential_uncoveredChatIsRefused]); under the race it was
     * downloaded.
     *
     * PHASE 56 CLOSED THIS: the racing caller receives the running recovery real failure, so the
     * per-chat check is reached and the refusal holds under concurrency too.
     */
    @Test
    fun archiveDownload_concurrent_uncoveredChatIsNowRefused() = runBlocking {
        val rig = rig(seedRootFor = CHAT)
        rig.vault.park = true
        rig.vault.openMode = OpenMode.LOCKED

        assertNull(
            "precondition: no root for the uncovered chat",
            rig.keyring.load().getOrNull()?.latest(OTHER)
        )

        withTimeout(30_000) {
            coroutineScope {
                val a = async { rig.archiveSync.downloadFor(CHAT) }
                rig.vault.entered.await()

                val b = async { rig.archiveSync.downloadFor(OTHER) }
                yield()
                rig.vault.release.complete(Unit)

                assertEquals(
                    "PHASE 56: the racing caller now sees the real failure and is refused",
                    0, b.await().records.size
                )
                a.await()
            }
        }
        // The counter is global and caller A legitimately fetched the COVERED chat, so exactly one
        // request is correct: A's. The uncovered caller added none.
        assertEquals(
            "only the covered chat may have reached the wire",
            1, rig.server.archiveGets.get()
        )
    }

    /** The same call, without the race, is refused - so the race is the only difference. */
    @Test
    fun archiveDownload_sequential_uncoveredChatIsRefused() = runBlocking {
        val rig = rig(seedRootFor = CHAT)
        rig.vault.openMode = OpenMode.LOCKED
        val page = rig.archiveSync.downloadFor(OTHER)
        assertEquals("no archive may be fetched for an uncovered chat", 0, rig.server.archiveGets.get())
        assertTrue(page.records.isEmpty())
        assertFalse(page.complete)
    }

    /** A covered chat is served either way - the race cannot suppress a legitimate download. */
    @Test
    fun archiveDownload_concurrent_coveredChatStillSucceeds() = runBlocking {
        val rig = rig(seedRootFor = CHAT)
        rig.vault.park = true
        rig.vault.openMode = OpenMode.LOCKED
        withTimeout(30_000) {
            coroutineScope {
                val a = async { rig.archiveSync.downloadFor(CHAT) }
                rig.vault.entered.await()
                val b = async { rig.archiveSync.downloadFor(CHAT) }
                yield()
                rig.vault.release.complete(Unit)
                assertEquals(1, b.await().records.size)
                a.await()
            }
        }
        assertTrue("the covered chat reached the wire", rig.server.archiveGets.get() >= 1)
    }

    /**
     * The security-relevant negative: the race changes only whether this device ASKS.
     *
     * Nothing here reaches into another account's rows, invents a root, or opens anything. The
     * records the racing caller receives are the ones the server chose to return for this session's
     * user, and it still cannot open them.
     */
    @Test
    fun theRaceGrantsNoKeyMaterialAndNoForeignRows() = runBlocking {
        val rig = rig(seedRootFor = CHAT)
        rig.vault.park = true
        rig.vault.openMode = OpenMode.LOCKED

        withTimeout(30_000) {
            coroutineScope {
                val a = async { rig.archiveSync.downloadFor(CHAT) }
                rig.vault.entered.await()
                val b = async { rig.archiveSync.downloadFor(OTHER) }
                yield()
                rig.vault.release.complete(Unit)
                // Every record is scoped to the requested chat by the client validator - and since
                // Phase 56 the uncovered chat is refused outright, so there are none at all.
                assertTrue("no record may belong to another chat", b.await().records.all { it.chatId == OTHER })
                a.await()
            }
        }
        // And the device still holds no root for that chat afterwards: nothing was minted, merged
        // or invented by taking the success path.
        assertNull(
            "the race must not create a root",
            rig.keyring.load().getOrNull()?.latest(OTHER)
        )
    }

    // ================================================================== failure injection

    @Test
    fun failureMatrix_concurrentCallersUnderEveryInjectedFailure() = runBlocking {
        val rows = mutableListOf<String>()

        // (name, configure, does this failure reach the recovery open?)
        val cases: List<Triple<String, (Rig) -> Unit, Boolean>> = listOf(
            Triple("valid recovery", { _: Rig -> }, true),
            Triple("vault locked", { r: Rig -> r.vault.openMode = OpenMode.LOCKED }, true),
            Triple("auth failure", { r: Rig -> r.vault.openMode = OpenMode.AUTH_FAIL }, true),
            Triple("transport throws", { r: Rig -> r.vault.openMode = OpenMode.TRANSPORT_THROW }, true),
            Triple("keyring GET 403", { r: Rig -> r.server.keyringStatus = 403 }, false),
            Triple("keyring GET timeout", { r: Rig -> r.server.keyringStatus = 500 }, false),
        )

        for ((name, configure, reachesOpen) in cases) {
            val rig = rig()
            configure(rig)
            // Park only when the injected failure lives at or after the recovery open. A failure at
            // the GET layer aborts before the park, and waiting on a latch that will never be
            // completed would hang rather than prove anything.
            rig.vault.park = reachesOpen
            val (aLabel, bLabel) = withTimeout(30_000) {
                coroutineScope {
                    // Both production callers wrap this in runCatching, because ensureRecovered
                    // can THROW as well as return a failure. The harness must do the same or it
                    // would be testing a contract no caller actually relies on.
                    val a = async(Dispatchers.Default) { safely { rig.sync.ensureRecovered() } }
                    if (reachesOpen) {
                        rig.vault.entered.await()
                        // Since Phase 56 a racing caller waits for the real outcome, so it must run
                        // concurrently with the release rather than before it.
                        val b = async { safely { rig.sync.ensureRecovered() } }
                        yield()
                        rig.vault.release.complete(Unit)
                        label(a.await()) to label(b.await())
                    } else {
                        label(a.await()) to "n/a (aborts before the recovery open)"
                    }
                }
            }
            rows += "%-22s runner=%-14s racingCaller=%s".format(name, aLabel, bLabel)
        }

        // 404 is deliberately separate: an absent server object is NOT a failure.
        run {
            val rig = rig(seedRootFor = null)
            val r = rig.sync.ensureRecovered()
            rows += "%-22s runner=%-14s racingCaller=%s".format("keyring GET 404", label(r), "n/a")
            assertFalse("404 must not be a failure", r.isFailure)
        }

        println("[failure matrix]\n  " + rows.joinToString("\n  "))

        // PHASE 56: the audit point inverts. Whenever the runner failed and a racing caller
        // existed, that caller must now have been told the same failure.
        assertTrue(
            "racing callers must observe the runner failure",
            rows.any { it.contains("runner=failure") && it.contains("racingCaller=failure") }
        )
        assertTrue(
            "and no racing caller may be fobbed off with success(false)",
            rows.none { it.contains("runner=failure") && it.contains("racingCaller=success(false)") }
        )
    }

    /**
     * Exactly what both production callers do: flatten a throw into the Result.
     *
     * `recoverInternal` unwraps its steps with `getOrElse`, which handles a RETURNED failure but not
     * a THROWN one, so a transport that raises escapes `ensureRecovered` entirely.
     */
    private suspend fun safely(block: suspend () -> Result<Boolean>): Result<Boolean> =
        runCatching { block() }.getOrElse { Result.failure(it) }

    /**
     * SECOND FINDING (Phase 54) - a THROWN failure latched the one-shot flag permanently.
     * FIXED IN PHASE 55; this test now pins the corrected behaviour.
     *
     * `recoveryAttempted = true` is set inside the gate, and the reset lives in an `.onFailure`
     * applied to the Result. When `recoverInternal()` threw instead of returning a failure, the
     * exception left `withLock` before `.onFailure` was ever applied, so the reset never ran.
     *
     * The session-wide consequence was larger than the transient race: recovery was disabled for
     * the rest of the session, and EVERY later caller - not just a concurrent one - was told
     * `success(false)`, which ArchiveSync reads as "no failure", so it never consulted its per-chat
     * root check again.
     *
     * Phase 55 flattens the throw into `Result.failure` at the `recoverInternal()` call, so the
     * existing reset runs and the next caller genuinely retries.
     */
    @Test
    fun aThrownFailureNoLongerLatchesTheOneShotFlag() = runBlocking {
        // Server holds the blob; this device holds nothing. So "was anything imported" is a real
        // question, and the answer must not be hidden behind success(false).
        val rig = rig(seedServerOnly = true)
        rig.vault.openMode = OpenMode.TRANSPORT_THROW
        assertNull("precondition: the device starts with no root", rig.keyring.load().getOrNull()?.latest(CHAT))

        val first = safely { rig.sync.ensureRecovered() }
        assertTrue("the throw is surfaced to the caller that ran it", first.isFailure)
        assertEquals("recovery really ran once", 1, rig.vault.recoveryOpens.get())

        // Now make recovery healthy again and retry, sequentially - no concurrency at all.
        rig.vault.openMode = OpenMode.OK
        val second = safely { rig.sync.ensureRecovered() }

        assertEquals(
            "PHASE 55: the flag was reset, so the retry actually recovers",
            "success(true)", label(second)
        )
        assertEquals(
            "PHASE 55: and the retry really executed",
            2, rig.vault.recoveryOpens.get()
        )
        assertNotNull(
            "PHASE 55: the keyring is genuinely imported",
            rig.keyring.load().getOrNull()?.latest(CHAT)
        )
    }

    /**
     * The same latch, viewed through the consumer that actually branches on the result.
     * FIXED IN PHASE 55; this test now pins the corrected behaviour.
     *
     * Before the fix, a thrown recovery failure meant an uncovered chat was downloaded for the rest
     * of the session, with no concurrency involved. Now the failure signal survives, so the Phase 52
     * per-chat gate is reached and refuses.
     */
    @Test
    fun afterAThrownFailureThePerChatGateIsReachedAgain() = runBlocking {
        val rig = rig(seedRootFor = CHAT)
        rig.vault.openMode = OpenMode.TRANSPORT_THROW
        assertTrue(safely { rig.sync.ensureRecovered() }.isFailure)

        assertNull(
            "precondition: still no root for the uncovered chat",
            rig.keyring.load().getOrNull()?.latest(OTHER)
        )
        rig.vault.openMode = OpenMode.LOCKED

        val page = rig.archiveSync.downloadFor(OTHER)
        assertEquals(
            "PHASE 55: the honest failure signal restores the per-chat refusal",
            0, page.records.size
        )
        assertEquals("PHASE 55: and nothing reached the wire", 0, rig.server.archiveGets.get())
    }

    // ================================================================== no-session

    @Test
    fun noSession_isAFailureAndStaysRetryable() = runBlocking {
        val rig = rig(token = null)
        val r = rig.sync.ensureRecovered()
        assertTrue("no usable session is a failure", r.isFailure)
        assertTrue("and remains retryable", rig.sync.ensureRecovered().isFailure)
    }
}
