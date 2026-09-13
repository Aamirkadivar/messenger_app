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
 * PHASE 55 - a THROWN recovery failure must behave like a returned one.
 *
 * WHAT WAS WRONG. `ensureRecovered()` sets its one-shot flag before doing the work, and resets it
 * from an `.onFailure` applied to the Result that `recoverInternal()` returns:
 *
 *     recoveryAttempted = true
 *     recoverInternal().map { ... }.onFailure { recoveryAttempted = false }
 *
 * `recoverInternal()` unwraps its steps with `getOrElse`, which handles a failure it RETURNS but not
 * one it RAISES, and its inner block is `try { } finally { }` with no catch. So a raise - a Keystore
 * or JNI fault out of the recovery open, a decode, an import - left `withLock` before `.onFailure`
 * was ever applied. The flag stayed true for the whole session: recovery never retried, and every
 * later caller was told `success(false)`, which the one consumer that branches on this result reads
 * as "no failure" - so it stopped consulting its per-chat root check too.
 *
 * Phase 54 pinned that behaviour; this suite pins the corrected behaviour.
 *
 * WHY THE SEAM IS THE STORE. Phase 54 raised from the transport. This suite raises from the durable
 * store during the import instead, because that is the other production-reachable source and it
 * exercises a later point in `recoverInternal()` - past the fetch and past the open. Neither seam
 * requires touching the real crypto.
 *
 * SCOPE. Phase 54's Finding 1 - a concurrent caller being handed `success(false)` while another
 * recovery is still running - was deliberately left open by this phase and was closed in Phase 56;
 * [aConcurrentCallerNowReceivesTheRealFailure] now asserts the corrected behaviour while still
 * guarding this phase's own property, that a raised failure stays retryable.
 *
 * All key material here is TEST-ONLY.
 */
class ThrownRecoveryFailureLatchTest {

    private companion object {
        const val USER = "user-1"
        const val CHAT = "chat-1"
        const val OTHER = "chat-2"
        val CT: String = Base64.getEncoder().encodeToString("sealed-bytes".toByteArray())
    }

    // ------------------------------------------------------------------ fakes

    /**
     * The durable slots, able to fault the way a real Keystore can.
     *
     * [throwOnSave] raises from inside `importFromRecovery`, which is past the fetch and past the
     * recovery open - the deepest of the production-reachable raise points.
     */
    private class DeviceStore(@Volatile var throwOnSave: Boolean = false) : HistoryKeyringStore {
        var blob: ByteArray? = null
        var cache: ByteArray? = null
        private var generation: Long? = null

        override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray): Result<Unit> {
            if (throwOnSave) throw IllegalStateException("keystore unavailable")
            blob = sealed.copyOf()
            return Result.success(Unit)
        }

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

    private class Vault(@Volatile var openMode: OpenMode = OpenMode.OK) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var park = false

        /** How many times recovery actually reached the open - the retry counter. */
        val recoveryOpens = AtomicInteger(0)

        private fun seal(domain: String, p: ByteArray) =
            Result.success((domain + "|" + USER + "|").toByteArray(Charsets.UTF_8) + p.copyOf())

        private fun open(domain: String, sealed: ByteArray): Result<ByteArray> {
            val prefix = (domain + "|" + USER + "|").toByteArray(Charsets.UTF_8)
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

    private class Server : ChatApiService by mockk(relaxed = true) {
        var keyringBlob: ByteArray? = null
        var version = 0
        val archiveGets = AtomicInteger(0)
        val archives = mutableMapOf<String, List<ArchiveDto>>()

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            val b = keyringBlob ?: return Response.error(404, ResponseBody.create(null, ""))
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

    /**
     * Server holds a recoverable keyring; this device holds nothing.
     *
     * Seeding through a throwaway store is what makes "did recovery actually import anything" a
     * real question - a locally seeded root would be present whether recovery ran or not.
     */
    private suspend fun rig(seedServer: Boolean = true, seedLocalRootFor: String? = null): Rig {
        val store = DeviceStore()
        val server = Server()
        server.archives[CHAT] = listOf(ArchiveDto("m-chat1", CHAT, 1, 1, CT, "2026-09-01T00:00:00Z"))
        server.archives[OTHER] = listOf(ArchiveDto("m-other", OTHER, 1, 1, CT, "2026-09-01T00:00:00Z"))

        if (seedServer) {
            val seedVault = Vault()
            val seedRepo = HistoryKeyringRepository(
                seedVault, DeviceStore(), HistoryUserProvider { USER }, feature
            )
            assertTrue(seedRepo.ensureRoot(CHAT).isSuccess)
            HistoryKeyringRecoverySync(seedRepo, seedVault, server, Tokens(), feature).uploadIfChanged()
            assertNotNull("fixture: the server must hold a keyring blob", server.keyringBlob)
        }

        val vault = Vault()
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { USER }, feature)
        if (seedLocalRootFor != null) {
            assertTrue(keyring.ensureRoot(seedLocalRootFor).isSuccess)
        }
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
        val archiveSync = ArchiveSync(
            MessageArchiver(keyring, Cipher(), feature),
            server, Tokens(), feature, dagger.Lazy { sync }, keyring
        )
        return Rig(store, vault, server, keyring, sync, archiveSync)
    }

    /** What both production callers do: flatten a throw so the harness sees what they see. */
    private suspend fun safely(block: suspend () -> Result<Boolean>): Result<Boolean> =
        runCatching { block() }.getOrElse { Result.failure(it) }

    // ================================================================== the fix

    /**
     * A raised failure now arrives as `Result.failure`, and the reset really happens.
     *
     * The flag is private, so the proof that it was reset is behavioural and stronger than reading
     * it: a later call RE-EXECUTES recovery. Under the old code that second call short-circuited.
     */
    @Test
    fun thrownFailureBecomesAReturnedFailureAndClearsTheFlag() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.THROW

        val first = rig.sync.ensureRecovered()          // NOT wrapped: it must no longer raise
        assertTrue("a raised failure must arrive as Result.failure", first.isFailure)
        assertEquals("recovery ran once", 1, rig.vault.recoveryOpens.get())

        // The observable consequence of the reset.
        val second = rig.sync.ensureRecovered()
        assertTrue("the next call must genuinely retry", second.isFailure)
        assertEquals("and that retry must have executed", 2, rig.vault.recoveryOpens.get())
    }

    /** The same, raised from the store during the import rather than from the open. */
    @Test
    fun aKeystoreFaultDuringImportAlsoClearsTheFlag() = runBlocking {
        val rig = rig()
        rig.store.throwOnSave = true

        val first = rig.sync.ensureRecovered()
        assertTrue("a store fault must arrive as Result.failure", first.isFailure)

        rig.store.throwOnSave = false
        val second = rig.sync.ensureRecovered()
        assertTrue("recovery must succeed once the store is healthy again", second.isSuccess)
        assertEquals("both attempts executed", 2, rig.vault.recoveryOpens.get())
        assertNotNull(
            "and the keyring is genuinely recovered",
            rig.keyring.load().getOrNull()!!.latest(CHAT)
        )
    }

    /** §6 - the decisive behavioural proof: throw, then succeed. */
    @Test
    fun retryAfterAThrowReachesASuccessfulRecovery() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.THROW
        assertTrue(rig.sync.ensureRecovered().isFailure)

        rig.vault.openMode = OpenMode.OK
        val second = rig.sync.ensureRecovered()

        assertTrue("the retry must succeed", second.isSuccess)
        assertEquals("and must report that it imported", true, second.getOrNull())
        assertEquals("recovery executions", 2, rig.vault.recoveryOpens.get())
        assertNotNull(
            "final state is a usable keyring",
            rig.keyring.load().getOrNull()!!.latest(CHAT)
        )
    }

    /** §7 - repeated raises never latch. */
    @Test
    fun repeatedThrowsNeverLatchRecovery() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.THROW
        assertTrue(rig.sync.ensureRecovered().isFailure)
        assertTrue(rig.sync.ensureRecovered().isFailure)
        assertEquals(2, rig.vault.recoveryOpens.get())

        rig.vault.openMode = OpenMode.OK
        assertTrue("recovery is still reachable after two raises", rig.sync.ensureRecovered().isSuccess)
        assertEquals(3, rig.vault.recoveryOpens.get())
        assertNotNull(rig.keyring.load().getOrNull()!!.latest(CHAT))
    }

    // ================================================================== regressions

    /** §8 - an ordinary RETURNED failure behaves exactly as before. */
    @Test
    fun returnedFailureSemanticsAreUnchanged() = runBlocking {
        val rig = rig()
        rig.vault.openMode = OpenMode.LOCKED
        assertTrue(rig.sync.ensureRecovered().isFailure)
        assertTrue("a returned failure must still be retryable", rig.sync.ensureRecovered().isFailure)
        assertEquals("both attempts executed", 2, rig.vault.recoveryOpens.get())

        rig.vault.openMode = OpenMode.OK
        assertTrue(rig.sync.ensureRecovered().isSuccess)
        assertEquals(3, rig.vault.recoveryOpens.get())
    }

    /** §9 - success is untouched, including the later `success(false)` that Finding 1 leaves. */
    @Test
    fun successSemanticsAreUnchanged() = runBlocking {
        val rig = rig()
        val first = rig.sync.ensureRecovered()
        assertTrue(first.isSuccess)
        assertEquals(true, first.getOrNull())

        val second = rig.sync.ensureRecovered()
        assertTrue("a later caller still sees success", second.isSuccess)
        assertEquals(
            "and still false - Finding 1 is deliberately not addressed here",
            false, second.getOrNull()
        )
        assertEquals("the one-shot still holds after success", 1, rig.vault.recoveryOpens.get())
    }

    /** §10 - an absent server object is still "nothing to recover", not a failure. */
    @Test
    fun nothingToRecoverIsStillSuccessFalseAndDoesNotRetryForever() = runBlocking {
        val rig = rig(seedServer = false)
        val first = rig.sync.ensureRecovered()
        assertTrue("404 must not be a failure", first.isSuccess)
        assertEquals(false, first.getOrNull())

        repeat(4) { rig.sync.ensureRecovered() }
        assertEquals(
            "the one-shot must still latch on this path - no retry loop",
            0, rig.vault.recoveryOpens.get()
        )
    }

    // ================================================================== Phase 52 interaction

    /**
     * §11 - the restored failure signal reaches the per-chat decision again.
     *
     * ArchiveSync is untouched by this phase; this only proves the signal it depends on is honest
     * once more after a raised failure.
     */
    @Test
    fun afterAThrownFailureThePerChatGateIsReachedAgain() = runBlocking {
        val rig = rig(seedLocalRootFor = CHAT)
        rig.vault.openMode = OpenMode.THROW
        assertTrue(rig.sync.ensureRecovered().isFailure)

        // Recovery keeps failing, so every downloadFor now reaches the per-chat check.
        rig.vault.openMode = OpenMode.LOCKED

        val uncovered = rig.archiveSync.downloadFor(OTHER)
        assertEquals("no root for this chat - refused", 0, rig.server.archiveGets.get())
        assertTrue(uncovered.records.isEmpty())
        assertFalse(uncovered.complete)

        val covered = rig.archiveSync.downloadFor(CHAT)
        assertEquals("a root for this chat - allowed", 1, covered.records.size)
        assertEquals(1, rig.server.archiveGets.get())
    }

    // ================================================================== Finding 1 must remain

    /**
     * Phase 54 Finding 1, CLOSED IN PHASE 56.
     *
     * Phase 55 deliberately left it open, and this test asserted it was still present so the phase
     * could not close it as a side effect. Phase 56 closed it on purpose: the racing caller now
     * joins the running recovery instead of being told `success(false)` about it.
     *
     * The Phase 55 property this test also guards is unchanged and still asserted below: a raised
     * failure reaches BOTH callers as a failure, and the attempt stays retryable.
     */
    @Test
    fun aConcurrentCallerNowReceivesTheRealFailure() = runBlocking {
        val rig = rig()
        rig.vault.park = true
        rig.vault.openMode = OpenMode.THROW

        withTimeout(30_000) {
            coroutineScope {
                // Deliberately NOT Dispatchers.Default: on runBlocking's single-threaded event loop
                // a yield() is a real hand-off, so "the joiner has reached the join" is something
                // this test establishes rather than hopes for.
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.await()          // A is parked inside recovery

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()                            // B runs until it suspends on the in-flight result
                rig.vault.release.complete(Unit)

                assertTrue("the caller that ran it sees the failure", a.await().isFailure)
                assertTrue(
                    "PHASE 56: and so does the caller that merely arrived during it",
                    b.await().isFailure
                )
            }
        }
        assertEquals("still exactly one recovery execution", 1, rig.vault.recoveryOpens.get())

        // Phase 55: a raised failure still re-arms, so a later caller genuinely retries.
        rig.vault.park = false
        rig.vault.openMode = OpenMode.OK
        assertTrue(rig.sync.ensureRecovered().isSuccess)
        assertEquals(2, rig.vault.recoveryOpens.get())
    }

    // ================================================================== exception safety

    /**
     * §14 - what the converted failure carries, and what it leaves behind.
     *
     * The exception is the original one, so its message is whatever the faulting layer wrote. What
     * matters is that this path adds nothing: no key material is put into it here, and the recovery
     * plaintext is wiped by the existing `finally` regardless of how the block exits.
     */
    @Test
    fun theConvertedFailureCarriesNoSecretsAndLeavesNoPartialKeyring() = runBlocking {
        val rig = rig()
        rig.store.throwOnSave = true

        val failure = rig.sync.ensureRecovered()
        assertTrue(failure.isFailure)
        val text = failure.exceptionOrNull()!!.let { it.toString() + "|" + (it.message ?: "") }

        for (forbidden in listOf("root=", "mk=", "masterKey", "sessionMk", "ciphertext", "plaintext")) {
            assertTrue("the failure must not carry $forbidden: $text", !text.contains(forbidden))
        }
        // No base64/hex blob smuggled through the message either.
        assertTrue("the failure message must stay short and structural", text.length < 200)

        // A store that could not persist must not leave a half-imported keyring readable.
        rig.store.throwOnSave = false
        assertNull(
            "no partially imported root may survive a failed import",
            rig.keyring.load().getOrNull()?.latest(CHAT)
        )
    }
}
