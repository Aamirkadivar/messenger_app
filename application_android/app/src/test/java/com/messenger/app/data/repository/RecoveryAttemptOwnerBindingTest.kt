package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyring
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
 * PHASE 59 - a recovery attempt stays bound to the account it began for.
 *
 * WHAT WENT WRONG. Recovery runs in the caller's coroutine and can be suspended for a network round
 * trip and an AEAD open. Its persistence, however, asked WHO IS SIGNED IN NOW rather than WHO DID
 * THIS ATTEMPT START FOR: `HistoryKeyringRepository.loadLocked()` and `persistLocked()` both resolve
 * the owner through `HistoryUserProvider`, which production binds to
 * `tokenManager.getCurrentUserId()` - mutable, and changed by a sign-out/sign-in.
 *
 * So a worker that had already read its MK and user id, been released after an account switch, and
 * then reached the import would file the OUTGOING account's roots under the INCOMING account.
 *
 * WHY THE OTHER GUARDS DO NOT COVER IT. Two things narrow the window but do not close it. Sign-out
 * clears sessionMk, which stops a worker that has NOT yet read it; and the recovery AAD binds the
 * user id, which stops a blob being opened under the wrong account. Neither helps a worker that got
 * past both reads while A was still signed in - the plaintext is already in hand, and only the
 * persistence owner is wrong.
 *
 * WHERE THE PARK SITS, AND WHY. These tests release the worker AFTER its recovery open has already
 * succeeded, because that is the only interleaving the existing guards do not already refuse. A park
 * before the open would simply fail on the cleared MK and prove nothing.
 *
 * WHAT IS ASSERTED. Not the returned `Result` - the persisted bytes. Each account's durable slot is
 * decoded and checked for the chat its own root belongs to, so "A's roots landed in B" is observed
 * directly rather than inferred.
 *
 * All key material here is TEST-ONLY.
 */
class RecoveryAttemptOwnerBindingTest {

    private companion object {
        const val USER_A = "account-A"
        const val USER_B = "account-B"

        /** Distinct per account, so a stray root names the account it escaped from. */
        const val CHAT_A = "chat-A"
        const val CHAT_B = "chat-B"
    }

    // ------------------------------------------------------------------ fakes

    private class SwitchableUser(@Volatile var user: String = USER_A) : HistoryUserProvider {
        override suspend fun currentUserId(): String = user
    }

    /** Per-owner durable slots, so which account a write landed under is directly observable. */
    private class DeviceStore : HistoryKeyringStore {
        val blobs = mutableMapOf<String, ByteArray>()
        val caches = mutableMapOf<String, ByteArray>()
        private val gens = mutableMapOf<String, Long>()

        /** Every owner a durable keyring write was filed under, in order. */
        val writtenOwners = mutableListOf<String>()

        override suspend fun saveHistoryKeyring(owner: String, sealed: ByteArray): Result<Unit> {
            writtenOwners += owner
            blobs[owner] = sealed.copyOf()
            return Result.success(Unit)
        }

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
     * Vault + recovery transport.
     *
     * The park deliberately sits AFTER the open has produced its result: that models a worker which
     * already read MK and user id while its own account was signed in, which is the only case the
     * existing MK/AAD guards do not already refuse.
     */
    private class Vault(
        private val users: SwitchableUser,
        @Volatile var openMode: OpenMode = OpenMode.OK,
    ) : HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        val entered = mutableMapOf(USER_A to CompletableDeferred<Unit>(), USER_B to CompletableDeferred<Unit>())
        val release = mutableMapOf(USER_A to CompletableDeferred<Unit>(), USER_B to CompletableDeferred<Unit>())
        val parkAfterOpen = mutableMapOf(USER_A to false, USER_B to false)
        val opens = mutableMapOf(USER_A to AtomicInteger(0), USER_B to AtomicInteger(0))

        private fun seal(domain: String, owner: String, p: ByteArray) =
            Result.success((domain + "|" + owner + "|").toByteArray(Charsets.UTF_8) + p.copyOf())

        private fun open(domain: String, owner: String, sealed: ByteArray): Result<ByteArray> {
            val prefix = (domain + "|" + owner + "|").toByteArray(Charsets.UTF_8)
            if (sealed.size < prefix.size ||
                !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)
            ) {
                return Result.failure(IllegalStateException("failed authentication"))
            }
            return Result.success(sealed.copyOfRange(prefix.size, sealed.size))
        }

        // The local (non-recovery) seal/open follow the CURRENT account, exactly as the real vault
        // does - they are reached from loadLocked/persistLocked, not from the attempt.
        override suspend fun sealHistoryKeyring(p: ByteArray) = seal("local", users.user, p)
        override suspend fun openHistoryKeyring(s: ByteArray) = open("local", users.user, s)
        override suspend fun sealForRecovery(p: ByteArray) = seal("recovery", users.user, p)

        override suspend fun openFromRecovery(s: ByteArray): Result<ByteArray> {
            val owner = users.user              // read while this account is still current
            opens.getValue(owner).incrementAndGet()
            val result = when (openMode) {
                OpenMode.OK -> open("recovery", owner, s)
                OpenMode.LOCKED ->
                    Result.failure(IllegalStateException("history keyring cannot be opened: vault is locked"))
                OpenMode.THROW -> throw IllegalStateException("recovery open raised")
            }
            if (parkAfterOpen.getValue(owner)) {
                entered.getValue(owner).complete(Unit)
                release.getValue(owner).await()
            }
            return result
        }
    }

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

    /** The mutable identity production reads: this is what the defect turned on. */
    private class Tokens(private val users: SwitchableUser) : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
        override suspend fun getCurrentUserId(): Result<String?> = Result.success(users.user)
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

    /** Seeds each named account's SERVER blob with a root for that account's own chat. */
    private suspend fun rig(vararg seed: Pair<String, String>): Rig {
        val users = SwitchableUser(USER_A)
        val store = DeviceStore()
        val server = Server(users)

        for ((owner, chat) in seed) {
            val seedUsers = SwitchableUser(owner)
            val seedVault = Vault(seedUsers)
            val seedServer = Server(seedUsers)
            val seedRepo = HistoryKeyringRepository(seedVault, DeviceStore(), seedUsers, feature)
            assertTrue(seedRepo.ensureRoot(chat).isSuccess)
            HistoryKeyringRecoverySync(seedRepo, seedVault, seedServer, Tokens(seedUsers), feature)
                .uploadIfChanged()
            val blob = seedServer.blobs[owner]
            assertNotNull("fixture: $owner must have a server blob", blob)
            server.blobs[owner] = blob!!
        }

        val vault = Vault(users)
        val keyring = HistoryKeyringRepository(vault, store, users, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(users), feature)
        return Rig(users, store, vault, server, keyring, sync)
    }

    /**
     * The chats whose roots are filed under [owner]'s durable slot.
     *
     * Reads the stored bytes directly and strips the fake's "local|owner|" envelope, so this
     * inspects what was actually persisted rather than what any API reports.
     */
    private fun persistedChats(rig: Rig, owner: String): Set<String> {
        val sealed = rig.store.blobs[owner] ?: return emptySet()
        val prefix = ("local|" + owner + "|").toByteArray(Charsets.UTF_8)
        if (sealed.size < prefix.size || !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            return emptySet()
        }
        val plain = sealed.copyOfRange(prefix.size, sealed.size)
        val keyring = HistoryKeyring.decode(plain).getOrNull() ?: return emptySet()
        return keyring.entries.map { it.chatId }.toSet()
    }

    private fun label(r: Result<Boolean>) = when {
        r.isFailure -> "failure"
        r.getOrNull() == true -> "success(true)"
        else -> "success(false)"
    }

    private suspend fun safely(block: suspend () -> Result<Boolean>): Result<Boolean> =
        runCatching { block() }.getOrElse { Result.failure(it) }

    /** Sign-out: production resets the sync and switches the identity the provider reports. */
    private fun signOutAndSwitchTo(rig: Rig, account: String) {
        rig.sync.resetSession()
        rig.users.user = account
    }

    // ================================================================== the defect

    /**
     * THE REGRESSION. A's attempt is released after the account has switched to B. Whatever A's
     * result is, A's roots must never be filed under B.
     */
    @Test
    fun anAttemptReleasedAfterAnAccountSwitchMustNotPersistUnderTheNewAccount() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        rig.vault.parkAfterOpen[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()   // A has opened; import not yet run

                signOutAndSwitchTo(rig, USER_B)

                rig.vault.release.getValue(USER_A).complete(Unit)
                a.await()
            }
        }

        assertFalse(
            "account B's durable keyring must never receive account A's root",
            persistedChats(rig, USER_B).contains(CHAT_A)
        )
        assertTrue(
            "and no durable write may be filed under B at all by A's attempt: " + rig.store.writtenOwners,
            rig.store.writtenOwners.none { it == USER_B }
        )
    }

    /** §18 - the direct proof that the mutable current user cannot steer the persistence owner. */
    @Test
    fun changingTheCurrentUserAfterRecoveryBeginsCannotChangeThePersistenceOwner() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        rig.vault.parkAfterOpen[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                // Only the mutable identity moves - no reset, no new session. This isolates the
                // owner lookup from every other lifecycle effect.
                rig.users.user = USER_B

                rig.vault.release.getValue(USER_A).complete(Unit)
                a.await()
            }
        }

        assertTrue(
            "every durable write must be filed under the attempt owner A: " + rig.store.writtenOwners,
            rig.store.writtenOwners.all { it == USER_A }
        )
        assertFalse("B must hold nothing", persistedChats(rig, USER_B).contains(CHAT_A))
    }

    // ================================================================== both accounts

    /** §12 - A in flight, switch, B recovers, both complete. Neither contaminates the other. */
    @Test
    fun bothAccountsRecoverTheirOwnRootsAcrossASwitch() = runBlocking {
        val rig = rig(USER_A to CHAT_A, USER_B to CHAT_B)
        rig.vault.parkAfterOpen[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                a.await()
                assertEquals("B recovers its own keyring", "success(true)", label(b.await()))
            }
        }

        val aChats = persistedChats(rig, USER_A)
        val bChats = persistedChats(rig, USER_B)
        // A's attempt is released after its own session ended, so it is ABANDONED rather than
        // persisted - the permitted safe outcome. The invariant that matters is the absence of
        // cross-account writes in either direction.
        assertFalse("A must hold none of B's roots", aChats.contains(CHAT_B))
        assertTrue("B holds its own root", bChats.contains(CHAT_B))
        assertFalse("B must hold none of A's roots", bChats.contains(CHAT_A))
        assertEquals(1, rig.vault.opens.getValue(USER_A).get())
        assertEquals(1, rig.vault.opens.getValue(USER_B).get())
    }

    /** §13 order 1 - the old worker finishes first. */
    @Test
    fun ownershipHoldsWhenTheOldWorkerCompletesFirst() = runBlocking {
        val rig = rig(USER_A to CHAT_A, USER_B to CHAT_B)
        rig.vault.parkAfterOpen[USER_A] = true
        rig.vault.parkAfterOpen[USER_B] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()
                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_B).await()

                rig.vault.release.getValue(USER_A).complete(Unit); a.await()
                rig.vault.release.getValue(USER_B).complete(Unit); b.await()
            }
        }
        assertOwnershipIntact(rig)
    }

    /** §13 order 2 - the new worker finishes first. */
    @Test
    fun ownershipHoldsWhenTheNewWorkerCompletesFirst() = runBlocking {
        val rig = rig(USER_A to CHAT_A, USER_B to CHAT_B)
        rig.vault.parkAfterOpen[USER_A] = true
        rig.vault.parkAfterOpen[USER_B] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()
                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_B).await()

                rig.vault.release.getValue(USER_B).complete(Unit); b.await()
                rig.vault.release.getValue(USER_A).complete(Unit); a.await()
            }
        }
        assertOwnershipIntact(rig)
    }

    private fun assertOwnershipIntact(rig: Rig) {
        val aChats = persistedChats(rig, USER_A)
        val bChats = persistedChats(rig, USER_B)
        assertFalse("A must hold none of B's roots", aChats.contains(CHAT_B))
        assertFalse("B must hold none of A's roots", bChats.contains(CHAT_A))
        assertTrue("B recovered its own", bChats.contains(CHAT_B))
    }

    // ================================================================== same-account regression

    /** §10 - the ordinary path is untouched: A stays signed in and imports exactly A's roots. */
    @Test
    fun theSameAccountPathStillImportsItsOwnRoots() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        assertEquals("success(true)", label(rig.sync.ensureRecovered()))
        assertTrue("A recovered its own root", persistedChats(rig, USER_A).contains(CHAT_A))
        assertTrue(
            "and every write was filed under A: " + rig.store.writtenOwners,
            rig.store.writtenOwners.all { it == USER_A }
        )
        assertNotNull(rig.keyring.load().getOrNull()!!.latest(CHAT_A))
    }

    // ================================================================== §14/§15/§16 regressions

    /** Phase 56 fan-out is unaffected by owner binding. */
    @Test
    fun phase56FanOutStillHoldsWithinOneSession() = runBlocking {
        val rows = mutableListOf<String>()
        for (n in listOf(2, 4, 8, 16, 32)) {
            val rig = rig(USER_A to CHAT_A)
            rig.vault.parkAfterOpen[USER_A] = true

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
            rows += "n=%-3d executions=%d keyringGETs=%d wrong=%d".format(
                n, rig.vault.opens.getValue(USER_A).get(), rig.server.gets.getValue(USER_A).get(),
                results.count { label(it) != "success(true)" }
            )
            assertEquals("n=$n: one execution", 1, rig.vault.opens.getValue(USER_A).get())
            assertEquals("n=$n: one keyring GET", 1, rig.server.gets.getValue(USER_A).get())
            assertEquals("n=$n: every caller gets the real outcome", 0,
                results.count { label(it) != "success(true)" })
        }
        println("[phase59 fan-out]\n  " + rows.joinToString("\n  "))
    }

    /** Phase 55/56 failure semantics survive: a failure stays retryable. */
    @Test
    fun failureSemanticsSurviveOwnerBinding() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        rig.vault.openMode = OpenMode.LOCKED
        assertTrue(rig.sync.ensureRecovered().isFailure)
        rig.vault.openMode = OpenMode.OK
        assertTrue("still retryable", rig.sync.ensureRecovered().isSuccess)
        assertEquals(2, rig.vault.opens.getValue(USER_A).get())
    }

    @Test
    fun thrownFailureSemanticsSurviveOwnerBinding() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        rig.vault.openMode = OpenMode.THROW
        assertTrue(safely { rig.sync.ensureRecovered() }.isFailure)
        rig.vault.openMode = OpenMode.OK
        assertTrue("still retryable after a raise", rig.sync.ensureRecovered().isSuccess)
    }

    @Test
    fun oneShotSemanticsSurviveOwnerBinding() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        assertEquals("success(true)", label(rig.sync.ensureRecovered()))
        assertEquals("success(false)", label(rig.sync.ensureRecovered()))
        assertEquals(1, rig.vault.opens.getValue(USER_A).get())

        val empty = rig()                                    // nothing on the server for anyone
        assertEquals("success(false)", label(empty.sync.ensureRecovered()))
        repeat(3) { empty.sync.ensureRecovered() }
        assertEquals("404 still latches", 1, empty.server.gets.getValue(USER_A).get())
    }

    /** Phase 58 must remain intact: a reset clears the stale handle. */
    @Test
    fun phase58ResetIsolationSurvivesOwnerBinding() = runBlocking {
        val rig = rig(USER_A to CHAT_A)
        rig.vault.parkAfterOpen[USER_A] = true

        withTimeout(60_000) {
            coroutineScope {
                val a = async { safely { rig.sync.ensureRecovered() } }
                rig.vault.entered.getValue(USER_A).await()

                signOutAndSwitchTo(rig, USER_B)

                val b = async { safely { rig.sync.ensureRecovered() } }
                yield()
                rig.vault.release.getValue(USER_A).complete(Unit)

                a.await()
                assertEquals(
                    "B must not inherit A's outcome - it has no server blob of its own",
                    "success(false)", label(b.await())
                )
            }
        }
        assertEquals("B ran its own keyring GET", 1, rig.server.gets.getValue(USER_B).get())
    }
}
