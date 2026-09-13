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
import kotlinx.coroutines.CompletableDeferred
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
 * PHASE 58 - a session reset must not leak the outgoing session's in-flight recovery handle.
 *
 * WHAT WENT WRONG. Phase 56 gave a running recovery a `CompletableDeferred` so concurrent callers
 * could join it instead of being told a fabricated `success(false)`. `HistoryKeyringRecoverySync`
 * is a `@Singleton` shared across account switches, and `resetSession()` - which sign-out calls -
 * clears the keyring cache, the one-shot flag and the revision counters, but was never taught about
 * that new handle. So a sign-out landing while a recovery was still running left the handle behind,
 * and the NEXT account's first `ensureRecovered()` joined the OUTGOING account's attempt and was
 * handed its outcome. Its own recovery never ran.
 *
 * No key material crosses that seam - only a `Result<Boolean>` - and a cross-account import is
 * independently impossible because the recovery AAD binds the user id. The damage is that the
 * incoming account silently skips recovery for the session.
 *
 * THE SECOND, HIDDEN HALF. Clearing the handle in `resetSession()` is not sufficient on its own,
 * and is actually what makes the second defect reachable: the worker's `finally` cleared the handle
 * BLINDLY. Before this phase that was safe, because the field was only ever replaced after a worker
 * had nulled it. Once a reset can null it too, an outgoing worker finishing late would wipe the
 * INCOMING session's handle - and a caller arriving after that would find no handle, see the
 * incoming worker's latched flag, and be told `success(false)` again. That is Phase 54 Finding 1
 * returning through the back door. [oldWorkerFinishingLateMustNotWipeTheNewSessionHandle] pins it.
 *
 * Determinism: every interleaving is forced with latches and runBlocking's single-threaded event
 * loop, where `yield()` is a real hand-off. No sleeps.
 *
 * All key material here is TEST-ONLY.
 */
class SessionResetInFlightIsolationTest {

    private companion object {
        const val USER_A = "account-a"
        const val USER_B = "account-b"
        const val CHAT = "chat-1"
    }

    // ------------------------------------------------------------------ fakes

    /** The signed-in account, switchable the way a sign-out/sign-in switches it. */
    private class SwitchableUser(@Volatile var user: String = USER_A) : HistoryUserProvider {
        override suspend fun currentUserId(): String = user
    }

    private class DeviceStore : HistoryKeyringStore {
        private val blobs = mutableMapOf<String, ByteArray>()
        private val caches = mutableMapOf<String, ByteArray>()
        private val gens = mutableMapOf<String, Long>()

        override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray) =
            Result.success(Unit).also { blobs[owner] = sealed.copyOf() }

        override suspend fun loadHistoryKeyring(owner: String) = Result.success(blobs[owner]?.copyOf())
        override suspend fun deleteHistoryKeyring(owner: String) =
            Result.success(Unit).also { blobs.remove(owner) }

        override suspend fun saveHistoryKeyringCache(owner: String, plain: ByteArray) =
            Result.success(Unit).also { caches[owner] = plain.copyOf() }

        override suspend fun loadHistoryKeyringCache(owner: String) = Result.success(caches[owner]?.copyOf())
        override suspend fun deleteHistoryKeyringCache(owner: String) =
            Result.success(Unit).also { caches.remove(owner) }

        override suspend fun saveHistoryKeyringGeneration(owner: String, generation: Long) =
            Result.success(Unit).also { gens[owner] = generation }

        override suspend fun loadHistoryKeyringGeneration(owner: String) = Result.success(gens[owner])
        override suspend fun deleteHistoryKeyringGeneration(owner: String) =
            Result.success(Unit).also { gens.remove(owner) }
    }

    private enum class OpenMode { OK, LOCKED, THROW }

    /**
     * Vault + recovery transport for whichever account is currently signed in.
     *
     * The park is per-account so account A can be held inside recovery while account B runs to
     * completion - which is the whole point of the cross-session tests.
     */
    private class Vault(
        private val users: SwitchableUser,
        @Volatile var openMode: OpenMode = OpenMode.OK,
    ) : HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        val entered = mutableMapOf(USER_A to CompletableDeferred<Unit>(), USER_B to CompletableDeferred<Unit>())
        val release = mutableMapOf(USER_A to CompletableDeferred<Unit>(), USER_B to CompletableDeferred<Unit>())
        val parked = mutableMapOf(USER_A to false, USER_B to false)
        val opens = mutableMapOf(USER_A to AtomicInteger(0), USER_B to AtomicInteger(0))

        /**
         * Accounts that have signed out.
         *
         * Production clears sessionMk in the same breath as resetSession, so an outgoing worker
         * released afterwards can no longer open anything. Modelling that keeps this rig honest:
         * without it the fake would be more permissive than the real vault.
         */
        val signedOut = mutableSetOf<String>()

        private fun seal(domain: String, owner: String, p: ByteArray) =
            Result.success((domain + "|" + owner + "|").toByteArray(Charsets.UTF_8) + p.copyOf())

        private fun open(domain: String, owner: String, sealed: ByteArray): Result<ByteArray> {
            val prefix = (domain + "|" + owner + "|").toByteArray(Charsets.UTF_8)
            if (sealed.size < prefix.size ||
                !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)
            ) {
                // This is the AAD binding standing in for the real one: a blob sealed for another
                // account simply does not open. Untouched by this phase.
                return Result.failure(IllegalStateException("failed authentication"))
            }
            return Result.success(sealed.copyOfRange(prefix.size, sealed.size))
        }

        override suspend fun sealHistoryKeyring(p: ByteArray) = seal("local", users.user, p)
        override suspend fun openHistoryKeyring(s: ByteArray) = open("local", users.user, s)
        override suspend fun sealForRecovery(p: ByteArray) = seal("recovery", users.user, p)

        override suspend fun openFromRecovery(s: ByteArray): Result<ByteArray> {
            val owner = users.user
            opens.getValue(owner).incrementAndGet()
            if (parked.getValue(owner)) {
                entered.getValue(owner).complete(Unit)
                release.getValue(owner).await()
            }
            if (owner in signedOut) {
                return Result.failure(IllegalStateException("history keyring cannot be opened: vault is locked"))
            }
            return when (openMode) {
                OpenMode.OK -> open("recovery", owner, s)
                OpenMode.LOCKED ->
                    Result.failure(IllegalStateException("history keyring cannot be opened: vault is locked"))
                OpenMode.THROW -> throw IllegalStateException("recovery open raised")
            }
        }
    }

    /** Serves a per-account keyring blob, so the two accounts cannot be confused. */
    private class Server(private val users: SwitchableUser) : ChatApiService by mockk(relaxed = true) {
        val blobs = mutableMapOf<String, ByteArray>()
        val versions = mutableMapOf(USER_A to 0, USER_B to 0)
        val gets = mutableMapOf(USER_A to AtomicInteger(0), USER_B to AtomicInteger(0))

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            val owner = users.user
            gets.getValue(owner).incrementAndGet()
            val b = blobs[owner] ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(
                HistoryKeyringDto(versions.getValue(owner), Base64.getEncoder().encodeToString(b))
            )
        }

        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            val owner = users.user
            if (body.expectedVersion != versions.getValue(owner)) {
                return Response.error(409, ResponseBody.create(null, ""))
            }
            blobs[owner] = Base64.getDecoder().decode(body.ciphertextB64)
            versions[owner] = versions.getValue(owner) + 1
            return Response.success(HistoryKeyringPutResponse(versions.getValue(owner), true))
        }
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    // ------------------------------------------------------------------ rig

    private val feature = HistoryArchiveFeature { true }

    private class Rig(
        val users: SwitchableUser,
        val store: DeviceStore,
        val vault: Vault,
        val server: Server,
        val keyring: HistoryKeyringRepository,
        val sync: HistoryKeyringRecoverySync,
    )

    /**
     * One singleton recovery sync, exactly as production has, plus a server holding a recoverable
     * keyring for [seedFor] accounts.
     */
    private suspend fun rig(vararg seedFor: String): Rig {
        val users = SwitchableUser(USER_A)
        val store = DeviceStore()
        val server = Server(users)

        for (owner in seedFor) {
            val seedUsers = SwitchableUser(owner)
            val seedVault = Vault(seedUsers)
            val seedServer = Server(seedUsers)
            val seedRepo = HistoryKeyringRepository(
                seedVault, DeviceStore(), seedUsers, feature
            )
            assertTrue(seedRepo.ensureRoot(CHAT).isSuccess)
            HistoryKeyringRecoverySync(seedRepo, seedVault, seedServer, Tokens(), feature)
                .uploadIfChanged()
            server.blobs[owner] = assertNotNullBytes(seedServer.blobs[owner])
        }

        val vault = Vault(users)
        val keyring = HistoryKeyringRepository(vault, store, users, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
        return Rig(users, store, vault, server, keyring, sync)
    }

    private fun assertNotNullBytes(b: ByteArray?): ByteArray {
        assertNotNull("fixture: a seeded account must have a server blob", b)
        return b!!
    }

    private fun label(r: Result<Boolean>) = when {
        r.isFailure -> "failure"
        r.getOrNull() == true -> "success(true)"
        else -> "success(false)"
    }

    private suspend fun safely(block: suspend () -> Result<Boolean>): Result<Boolean> =
        runCatching { block() }.getOrElse { Result.failure(it) }

    /** Simulates sign-out: exactly what E2EEVaultRepository.clearSessionSecrets does here. */
    private fun signOutAndSwitchTo(rig: Rig, account: String) {
        rig.vault.signedOut.add(rig.users.user)   // production nulls sessionMk here too
        rig.sync.resetSession()
        rig.users.user = account
    }

    // ================================================================== the defect

    /**
     * THE PHASE 57 GAP. A sign-out during a running recovery must not hand the next account the
     * outgoing account's outcome.
     *
     * A recovers successfully (its server blob opens). B has no server blob, so B's own recovery is
     * a legitimate `success(false)`. The two outcomes are deliberately different, so "B received A's
     * answer" is directly observable rather than inferred from counters.
     */
    @Test
    fun resetClearsTheStaleHandleSoTheNextAccountRecoversForItself() = runBlocking {
        val rig = rig(USER_A)
        rig.vault.parked[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()      // A is inside recovery

                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                // A is released after its session ended, so it can no longer open anything -
                // exactly what production does by clearing sessionMk alongside the reset. Its
                // outcome is a failure, and that is precisely the outcome B must NOT inherit.
                val aResult = a.await()
                assertTrue("the superseded attempt settles as a failure", aResult.isFailure)
                assertEquals(
                    "B must run its OWN recovery and find nothing, not inherit A's outcome",
                    "success(false)", label(b.await())
                )
            }
        }
        assertEquals("A ran exactly one recovery", 1, rig.vault.opens.getValue(USER_A).get())
        assertEquals("B ran its own keyring GET", 1, rig.server.gets.getValue(USER_B).get())
    }

    /**
     * THE HIDDEN SECOND HALF. An outgoing worker finishing late must not wipe the incoming
     * session's handle.
     *
     * Without an identity-aware cleanup, A's `finally` clears the field that now holds B's handle,
     * and a caller arriving while B is still running finds no handle, sees B's latched flag, and is
     * told `success(false)` - Phase 54 Finding 1, back through the reset path.
     */
    @Test
    fun oldWorkerFinishingLateMustNotWipeTheNewSessionHandle() = runBlocking {
        val rig = rig(USER_A, USER_B)
        rig.vault.parked[USER_A] = true
        rig.vault.parked[USER_B] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_B).await()      // B now owns the handle

                // A finishes LATE, after B installed its own handle. Whatever A's outcome is,
                // it must not touch B's handle.
                rig.vault.release.getValue(USER_A).complete(Unit)
                a.await()

                // A third caller arrives while B is still parked. It must still be able to join B.
                val c = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_B).complete(Unit)

                val bResult = b.await()
                assertEquals("B recovers its own keyring", "success(true)", label(bResult))
                assertEquals(
                    "a caller arriving during B must join B, not be fobbed off with success(false)",
                    label(bResult), label(c.await())
                )
                assertEquals(
                    "and it must not have started a recovery of its own",
                    1, rig.vault.opens.getValue(USER_B).get()
                )
            }
        }
    }

    // ================================================================== old joiners

    /** A joiner attached before the reset must still settle with A's real result. */
    @Test
    fun aJoinerAttachedBeforeTheResetStillSettles() = runBlocking {
        val rig = rig(USER_A)
        rig.vault.parked[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a1 = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                val a2 = async { safely { rig.sync.ensureRecovered() } }
                yield()                                        // A2 joins A's handle

                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                val aResult = a1.await()
                assertEquals(
                    "the pre-reset joiner still receives exactly its own worker's result",
                    label(aResult), label(a2.await())
                )
                assertEquals("and B is unaffected", "success(false)", label(b.await()))
            }
        }
        assertEquals("A ran once", 1, rig.vault.opens.getValue(USER_A).get())
    }

    /** Four joiners, one worker, a reset in the middle, and a second session - no contamination. */
    @Test
    fun multipleJoinersAndANewSessionCoexist() = runBlocking {
        val rig = rig(USER_A)
        rig.vault.parked[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a1 = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                val joiners = (1..3).map { async { safely { rig.sync.ensureRecovered() } } }
                repeat(4) { yield() }

                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                val aResult = a1.await()
                for (j in joiners.awaitAll()) {
                    assertEquals(
                        "every pre-reset joiner receives exactly its own worker's result",
                        label(aResult), label(j)
                    )
                }
                assertEquals("B receives its own", "success(false)", label(b.await()))
            }
        }
        assertEquals("A execution", 1, rig.vault.opens.getValue(USER_A).get())
        assertEquals("B execution", 1, rig.server.gets.getValue(USER_B).get())
    }

    // ================================================================== failure shapes

    @Test
    fun resetDuringAReturnedFailureLeavesNoStaleResult() = runBlocking {
        val rig = rig(USER_A, USER_B)
        rig.vault.parked[USER_A] = true
        rig.vault.openMode = OpenMode.LOCKED

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                signOutAndSwitchTo(rig, USER_B)
                rig.vault.openMode = OpenMode.OK               // B's session is healthy

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                assertTrue("A failed", a.await().isFailure)
                assertEquals("B must not inherit A's failure", "success(true)", label(b.await()))
            }
        }
        assertEquals(1, rig.vault.opens.getValue(USER_B).get())
    }

    @Test
    fun resetDuringAThrownFailureLeavesNoStaleResult() = runBlocking {
        val rig = rig(USER_A, USER_B)
        rig.vault.parked[USER_A] = true
        rig.vault.openMode = OpenMode.THROW

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                val a2 = async { safely { rig.sync.ensureRecovered() } }
                yield()

                signOutAndSwitchTo(rig, USER_B)
                rig.vault.openMode = OpenMode.OK

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                // Phase 55: a raise arrives as a returned failure, and nothing escapes the boundary.
                assertTrue("A's raise is surfaced as a failure", a.await().isFailure)
                assertTrue("A's joiner settles too", a2.await().isFailure)
                assertEquals("B is independent", "success(true)", label(b.await()))
            }
        }
    }

    // ================================================================== account isolation

    /** Neither account may end up holding the other's roots. */
    @Test
    fun neitherAccountImportsTheOtherKeyring() = runBlocking {
        val rig = rig(USER_A, USER_B)

        assertEquals("success(true)", label(rig.sync.ensureRecovered()))
        val aKeyring = rig.keyring.load().getOrNull()
        assertNotNull("A recovered its own root", aKeyring!!.latest(CHAT))

        signOutAndSwitchTo(rig, USER_B)
        assertEquals("success(true)", label(rig.sync.ensureRecovered()))
        assertNotNull("B recovered its own root", rig.keyring.load().getOrNull()!!.latest(CHAT))

        // Each account opened only its own blob; a foreign blob does not authenticate.
        assertEquals(1, rig.vault.opens.getValue(USER_A).get())
        assertEquals(1, rig.vault.opens.getValue(USER_B).get())
    }

    // ================================================================== Phase 56 regressions

    @Test
    fun noDuplicateRecoveryWithinASession_2_4_8_16_32() = runBlocking {
        val rows = mutableListOf<String>()
        for (n in listOf(2, 4, 8, 16, 32)) {
            val rig = rig(USER_A)
            rig.vault.parked[USER_A] = true

            val results = withTimeout(120_000) {
                coroutineScope {
                    val worker = async { safely { rig.sync.ensureRecovered() } }
                    rig.vault.entered.getValue(USER_A).await()
                    val others = (2..n).map { async { safely { rig.sync.ensureRecovered() } } }
                    repeat(n) { yield() }
                    rig.vault.release.getValue(USER_A).complete(Unit)
                    listOf(worker.await()) + others.awaitAll()
                }
            }
            rows += "n=%-3d executions=%d keyringGETs=%d callers=%d wrong=%d".format(
                n, rig.vault.opens.getValue(USER_A).get(), rig.server.gets.getValue(USER_A).get(),
                results.size, results.count { label(it) != "success(true)" }
            )
            assertEquals("n=$n: one execution", 1, rig.vault.opens.getValue(USER_A).get())
            assertEquals("n=$n: one keyring GET", 1, rig.server.gets.getValue(USER_A).get())
            assertEquals("n=$n: every caller gets the real outcome", 0,
                results.count { label(it) != "success(true)" })
        }
        println("[phase58 fan-out]\n  " + rows.joinToString("\n  "))
    }

    @Test
    fun postSuccessOneShotSurvivesTheFix() = runBlocking {
        val rig = rig(USER_A)
        assertEquals("success(true)", label(rig.sync.ensureRecovered()))
        assertEquals(
            "a later caller still gets the one-shot answer",
            "success(false)", label(rig.sync.ensureRecovered())
        )
        assertEquals(1, rig.vault.opens.getValue(USER_A).get())
    }

    @Test
    fun postEmptyOneShotSurvivesTheFix() = runBlocking {
        val rig = rig()                                   // no server blob for anyone
        assertEquals("success(false)", label(rig.sync.ensureRecovered()))
        repeat(3) { rig.sync.ensureRecovered() }
        assertEquals("404 still latches", 1, rig.server.gets.getValue(USER_A).get())
    }

    /** Phase 56 cancellation semantics must be untouched by this phase. */
    @Test
    fun aCancelledJoinerStillLeavesTheWorkerAlone() = runBlocking {
        val rig = rig(USER_A)
        rig.vault.parked[USER_A] = true

        val worker = withTimeout(60_000) {
            coroutineScope {
                val w = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                val joinerJob = Job()
                launch(joinerJob) { rig.sync.ensureRecovered() }
                yield()
                joinerJob.cancel()
                yield()

                rig.vault.release.getValue(USER_A).complete(Unit)
                w.await()
            }
        }
        assertEquals("the worker completed normally", "success(true)", label(worker))
        assertEquals(1, rig.vault.opens.getValue(USER_A).get())
        assertNotNull(rig.keyring.load().getOrNull()!!.latest(CHAT))
    }

    /** A reset must not cancel the worker it supersedes. */
    @Test
    fun resetDoesNotCancelTheOutgoingWorker() = runBlocking {
        val rig = rig(USER_A)
        rig.vault.parked[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                signOutAndSwitchTo(rig, USER_B)

                rig.vault.release.getValue(USER_A).complete(Unit)
                val r = a.await()
                // It settles as a failure because its session is gone, NOT because the reset
                // cancelled it: the distinguishing evidence is that it returned a value at all,
                // and that the value is the vault refusal rather than the interruption marker the
                // finally block produces when a worker is torn down mid-flight.
                assertTrue("the superseded worker must still settle", r.isFailure)
                assertTrue(
                    "and must have completed its own work rather than being cancelled: " +
                        r.exceptionOrNull()?.message,
                    r.exceptionOrNull()?.message?.contains("interrupted before it completed") != true
                )
            }
        }
    }
}
