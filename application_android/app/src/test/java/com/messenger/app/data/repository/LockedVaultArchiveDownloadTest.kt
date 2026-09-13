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
 * PHASE 52 - archive download while the vault is locked, on the strength of a locally held root.
 *
 * WHAT CHANGED AND WHY. `downloadFor` used to refuse outright whenever `ensureRecovered()` did not
 * succeed. Recovery opens an MK-sealed blob, so a locked vault fails it every time - and the refusal
 * then applied even to chats whose root this device already had. That gate was broader than the
 * thing it protects: opening an archive needs the per-chat root, and the root is readable while
 * locked from the Keystore cache that exists for exactly that purpose. So the fallback is now narrow
 * and chat-specific: recovery failure still refuses, UNLESS a root for THIS chat is already held.
 *
 * WHY THESE TESTS ARE BEHAVIOURAL AND NOT SOURCE ASSERTIONS. The sibling ordering suites assert on
 * source text because their subject sits behind lazysodium, which will not load in a plain JVM
 * test. Nothing on this path has that problem: the vault and the cipher are both interfaces, so the
 * production ArchiveSync, the production HistoryKeyringRepository and the production cache format
 * all run for real here. A locked vault is modelled the way the app actually experiences one -
 * every vault operation fails while the durable Keystore slots keep their contents - and an app
 * relaunch is modelled by constructing a second repository over the same store, which is what
 * discards the in-memory keyring and forces the cache read.
 *
 * The one thing these cannot cover is the real Keystore and the real AEAD; that is what the
 * on-device run is for.
 *
 * All key material here is TEST-ONLY.
 */
class LockedVaultArchiveDownloadTest {

    private companion object {
        const val USER = "user-1"

        /** The chat this device holds a root for. */
        const val CHAT = "chat-1"

        /** A chat it does not - the discriminating case for the whole change. */
        const val OTHER = "chat-2"
        const val MSG = "msg-1"
        val CT: String = Base64.getEncoder().encodeToString("sealed-bytes".toByteArray())
    }

    // ------------------------------------------------------------------ fakes

    /**
     * The durable slots on the device.
     *
     * These survive the simulated relaunch, because on real hardware they are Keystore-backed and
     * the cache slot is readable without MK - which is the entire premise of this phase.
     */
    private class DeviceStore(var throwOnCacheRead: Boolean = false) : HistoryKeyringStore {
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

        override suspend fun loadHistoryKeyringCache(owner: String): Result<ByteArray?> {
            // Deliberately a throw and not a Result.failure: this models the Keystore itself
            // faulting, which is the shape that would otherwise escape downloadFor and take the
            // message fetch down with it.
            if (throwOnCacheRead) throw IllegalStateException("keystore unavailable")
            return Result.success(cache?.copyOf())
        }

        override suspend fun deleteHistoryKeyringCache(owner: String) =
            Result.success(Unit).also { cache = null }

        override suspend fun saveHistoryKeyringGeneration(owner: String, generation: Long) =
            Result.success(Unit).also { this.generation = generation }

        override suspend fun loadHistoryKeyringGeneration(owner: String) = Result.success(generation)

        override suspend fun deleteHistoryKeyringGeneration(owner: String) =
            Result.success(Unit).also { generation = null }
    }

    /** A locked vault fails every operation; the durable store is untouched by that. */
    private class Vault(val user: String?, val locked: Boolean) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {

        private fun seal(domain: String, p: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            return Result.success((domain + "|" + u + "|").toByteArray(Charsets.UTF_8) + p.copyOf())
        }

        private fun open(domain: String, sealed: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
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
        override suspend fun openFromRecovery(s: ByteArray) = open("recovery", s)
    }

    /**
     * Serves the keyring endpoints and the archive list, and counts archive requests.
     *
     * That count is the assertion that matters throughout: "did this device reach for ciphertext it
     * could not have opened" is answerable only by watching the wire.
     */
    private class Server : ChatApiService by mockk(relaxed = true) {
        var keyringBlob: ByteArray? = null
        var version = 0
        var archiveGets = 0
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
            archiveGets++
            val rows = archives[chatId].orEmpty()
            return Response.success(ArchiveListResponse(rows, rows.size))
        }
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    /** Sealing is irrelevant here - nothing in this suite opens an archive. */
    private class Cipher : ArchiveCipher {
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) =
            "sealed".toByteArray()

        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? =
            null
    }

    // ------------------------------------------------------------------ rig

    private val feature = HistoryArchiveFeature { true }

    private fun repo(store: DeviceStore, vault: Vault) =
        HistoryKeyringRepository(vault, store, HistoryUserProvider { USER }, feature)

    /**
     * A device that minted a root for [CHAT] while unlocked, published it, and then locked.
     *
     * The second repository is the relaunch: it shares the durable store but has no in-memory
     * keyring, so the only way it can answer "do I hold a root for this chat" is the cache.
     */
    private class Locked(
        val store: DeviceStore,
        val server: Server,
        val keyring: HistoryKeyringRepository,
        val sync: ArchiveSync,
        val recovery: HistoryKeyringRecoverySync,
    )

    private fun lockedDeviceHoldingRootForChat(
        store: DeviceStore = DeviceStore(),
    ): Locked = runBlocking {
        val server = Server()
        server.archives[CHAT] = listOf(ArchiveDto(MSG, CHAT, 1, 1, CT, "2026-09-01T00:00:00Z"))

        val unlocked = Vault(USER, locked = false)
        val warm = repo(store, unlocked)
        assertTrue("fixture: the root must mint", warm.ensureRoot(CHAT).isSuccess)
        // Give the server a blob the locked device will fetch and fail to open. Without this the
        // GET 404s, recovery reports success because there is nothing to recover, and the gate is
        // never reached at all.
        HistoryKeyringRecoverySync(warm, unlocked, server, Tokens(), feature).uploadIfChanged()
        assertNotNull("fixture: the server must hold a keyring blob", server.keyringBlob)

        // --- relaunch, vault locked ---
        val lockedVault = Vault(USER, locked = true)
        val cold = repo(store, lockedVault)
        val recovery = HistoryKeyringRecoverySync(cold, lockedVault, server, Tokens(), feature)
        Locked(
            store,
            server,
            cold,
            ArchiveSync(
                MessageArchiver(cold, Cipher(), feature),
                server, Tokens(), feature, dagger.Lazy { recovery }, cold
            ),
            recovery,
        )
    }

    // ------------------------------------------------------------------ premise

    /**
     * The premise of the whole phase, asserted rather than assumed: while locked, recovery is
     * impossible, and yet the root is still there.
     */
    @Test
    fun aLockedVaultBlocksRecoveryYetTheCachedRootSurvives() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        assertTrue(
            "a locked vault must not be able to complete keyring recovery",
            rig.recovery.ensureRecovered().isFailure
        )
        val keyring = rig.keyring.load()
        assertTrue("the cache must still be readable without MK", keyring.isSuccess)
        assertNotNull(
            "the root minted before locking must survive the relaunch",
            keyring.getOrNull()!!.latest(CHAT)
        )
    }

    // ------------------------------------------------------------------ the change

    /** Recovery failed, but this chat's root is held - the download proceeds. */
    @Test
    fun downloadProceedsWhileLockedWhenARootForThisChatIsHeld() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        val page = rig.sync.downloadFor(CHAT)
        assertEquals("the archive must have been requested", 1, rig.server.archiveGets)
        assertEquals("and its records collected", 1, page.records.size)
        assertEquals(MSG, page.records[0].messageId)
        assertTrue("a short page is still the definitive end", page.complete)
    }

    /**
     * The discriminating case. The keyring is present and non-empty, but holds nothing for THIS
     * chat, so the original refusal must stand - and no request may leave the device.
     */
    @Test
    fun downloadIsStillRefusedForAChatWithNoLocalRoot() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        val page = rig.sync.downloadFor(OTHER)
        assertEquals("no archive may be fetched for an uncovered chat", 0, rig.server.archiveGets)
        assertTrue(page.records.isEmpty())
        assertFalse("a refused sync is never complete", page.complete)
    }

    /**
     * Recovery impossible AND nothing held locally - the fail-closed path is untouched.
     *
     * The device's own copies are gone (a cleared cache, or a keyring that never reached this
     * install) while the server still holds a blob it cannot open until the vault is unlocked. This
     * is the case the original gate existed for, and the fallback must not rescue it.
     *
     * Note what is deliberately NOT tested here: a device with no server blob either. That case
     * never reaches the fallback at all - a 404 means there is nothing to recover, so
     * `ensureRecovered()` succeeds vacuously and the download proceeds down the unchanged path.
     * That is pre-existing behaviour from the ordering work, untouched by this phase.
     */
    @Test
    fun downloadIsStillRefusedWhenRecoveryFailsAndNoRootIsHeld() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        rig.store.cache = null
        rig.store.blob = null

        assertTrue(
            "fixture: recovery must be impossible while locked",
            rig.recovery.ensureRecovered().isFailure
        )
        assertNull(
            "fixture: nothing is held for this chat any more",
            rig.keyring.load().getOrNull()?.latest(CHAT)
        )

        val page = rig.sync.downloadFor(CHAT)
        assertEquals("a device holding nothing must fetch nothing", 0, rig.server.archiveGets)
        assertTrue(page.records.isEmpty())
        assertFalse(page.complete)
    }

    /**
     * The ordinary path is unchanged. With the vault open, recovery succeeds and the fallback is
     * never consulted - this phase must not alter the unlocked case in any way.
     */
    @Test
    fun anUnlockedDeviceStillDownloadsThroughSuccessfulRecovery() = runBlocking {
        val store = DeviceStore()
        val server = Server()
        server.archives[CHAT] = listOf(ArchiveDto(MSG, CHAT, 1, 1, CT, "2026-09-01T00:00:00Z"))
        val vault = Vault(USER, locked = false)
        val keyring = repo(store, vault)
        assertTrue(keyring.ensureRoot(CHAT).isSuccess)
        val recovery = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
        val sync = ArchiveSync(
            MessageArchiver(keyring, Cipher(), feature),
            server, Tokens(), feature, dagger.Lazy { recovery }, keyring
        )

        val page = sync.downloadFor(CHAT)
        assertEquals(1, server.archiveGets)
        assertEquals(1, page.records.size)
        assertTrue(page.complete)
    }

    /**
     * The fallback must never throw into messaging. It runs inside the message fetch, so a Keystore
     * fault has to cost the archive sync and nothing else.
     */
    @Test
    fun aThrowingKeystoreDegradesToRefusalRatherThanAnException() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        rig.store.throwOnCacheRead = true
        val page = rig.sync.downloadFor(CHAT)   // must not throw
        assertEquals("an unreadable keyring must fetch nothing", 0, rig.server.archiveGets)
        assertTrue(page.records.isEmpty())
        assertFalse(page.complete)
    }

    /**
     * The decision is made per call, not per session. One instance must serve a covered chat and
     * refuse an uncovered one, in either order, with no state carried between them.
     */
    @Test
    fun theDecisionIsMadePerChatAndNotCachedAcrossChats() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()

        assertTrue(rig.sync.downloadFor(OTHER).records.isEmpty())
        assertEquals("the refused chat made no request", 0, rig.server.archiveGets)

        assertEquals("the covered chat still proceeds", 1, rig.sync.downloadFor(CHAT).records.size)
        assertEquals(1, rig.server.archiveGets)

        assertTrue(
            "and the refusal still holds afterwards",
            rig.sync.downloadFor(OTHER).records.isEmpty()
        )
        assertEquals("no extra request", 1, rig.server.archiveGets)
    }

    // ------------------------------------------------------------------ durability

    /**
     * Proceeding must not WRITE anything.
     *
     * A locked device cannot seal, so any attempt to persist the keyring here would fail - and a
     * failed persist that discarded or truncated the cache would destroy the only copy of the root
     * readable without MK. The gate is a read, and this pins that it stays one.
     */
    @Test
    fun proceedingWhileLockedLeavesTheDurableKeyringSlotsUntouched() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        val cacheBefore = rig.store.cache!!.copyOf()
        val blobBefore = rig.store.blob!!.copyOf()

        rig.sync.downloadFor(CHAT)

        assertTrue(
            "the readable-without-MK cache must survive a locked download",
            rig.store.cache!!.contentEquals(cacheBefore)
        )
        assertTrue(
            "and the authoritative sealed copy must not be rewritten either",
            rig.store.blob!!.contentEquals(blobBefore)
        )
        assertNotNull(
            "the root must still be there afterwards",
            rig.keyring.load().getOrNull()!!.latest(CHAT)
        )
    }

    /** Repeating the pass is safe and returns the same thing - no cursor is persisted. */
    @Test
    fun repeatingALockedDownloadIsIdempotent() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        val first = rig.sync.downloadFor(CHAT)
        val second = rig.sync.downloadFor(CHAT)
        assertEquals(2, rig.server.archiveGets)
        assertEquals(first.records.map { it.messageId }, second.records.map { it.messageId })
        assertEquals(first.complete, second.complete)
    }

    /**
     * Concurrent passes must neither deadlock nor disagree.
     *
     * Worth pinning because the fallback introduces a second lock into this path: `ensureRecovered`
     * takes the recovery gate and the keyring mutex beneath it, and `holdsRootFor` then takes the
     * keyring mutex on its own. It does so only AFTER recovery has returned and released the gate,
     * so the two are never held in opposing orders - but that is an invariant, not an accident, and
     * a mixed covered/uncovered fan-out is what would expose it if it were broken.
     *
     * The total request count is deliberately NOT asserted here. Under concurrency an uncovered
     * chat can still reach the wire, for a reason that has nothing to do with this fallback - see
     * [aConcurrentCallerNoLongerBypassesTheFallback].
     */
    @Test
    fun concurrentPassesAgreeAndDoNotDeadlock() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        val results = kotlinx.coroutines.withTimeout(30_000) {
            kotlinx.coroutines.coroutineScope {
                (1..12).map { i ->
                    async(kotlinx.coroutines.Dispatchers.Default) {
                        val chat = if (i % 2 == 0) CHAT else OTHER
                        chat to rig.sync.downloadFor(chat).records.size
                    }
                }.awaitAll()
            }
        }
        for ((chat, size) in results) {
            if (chat == CHAT) {
                assertEquals("every covered pass must see the archive", 1, size)
            } else {
                assertEquals("every uncovered pass must see nothing", 0, size)
            }
        }
        assertTrue("the covered chat must have reached the wire", rig.server.archiveGets >= 6)
    }

    /**
     * A PRE-EXISTING race (Phase 54 Finding 1), pinned here because this phase's fallback was the
     * obvious thing to blame for it and was not the cause. CLOSED IN PHASE 56.
     *
     * `ensureRecovered()` reads its one-shot flag OUTSIDE its lock and sets the flag before doing
     * the work, so a second caller arriving while the first is still in flight is told
     * `Result.success(false)` - recovery "succeeded". `downloadFor` then takes the ordinary path and
     * never consults the local keyring at all.
     *
     * The chat used here has NO local root, so the Phase 52 condition always refused it; what used
     * to let the request through was the spurious success, not the fallback. Phase 56 made the
     * racing caller join the running recovery and receive its real outcome, so the refusal now
     * holds under concurrency too - which is what this test asserts.
     */
    @Test
    fun aConcurrentCallerNoLongerBypassesTheFallback() = runBlocking {
        val store = DeviceStore()
        val server = Server()
        server.archives[OTHER] = listOf(ArchiveDto("m-other", OTHER, 1, 1, CT, "2026-09-01T00:00:00Z"))

        // Seed a root for CHAT only, and give the server a blob, exactly as the other fixtures do.
        val unlocked = Vault(USER, locked = false)
        val warm = repo(store, unlocked)
        assertTrue(warm.ensureRoot(CHAT).isSuccess)
        HistoryKeyringRecoverySync(warm, unlocked, server, Tokens(), feature).uploadIfChanged()

        // A locked vault whose recovery attempt parks until released, so the race is deterministic
        // rather than timing-dependent.
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val parking = object : HistoryKeyringVault, HistoryKeyringRecoveryTransport {
            private val locked = Vault(USER, locked = true)
            override suspend fun sealHistoryKeyring(p: ByteArray) = locked.sealHistoryKeyring(p)
            override suspend fun openHistoryKeyring(s: ByteArray) = locked.openHistoryKeyring(s)
            override suspend fun sealForRecovery(p: ByteArray) = locked.sealForRecovery(p)
            override suspend fun openFromRecovery(s: ByteArray): Result<ByteArray> {
                entered.complete(Unit)
                release.await()
                return locked.openFromRecovery(s)
            }
        }
        val cold = HistoryKeyringRepository(parking, store, HistoryUserProvider { USER }, feature)
        val recovery = HistoryKeyringRecoverySync(cold, parking, server, Tokens(), feature)
        val sync = ArchiveSync(
            MessageArchiver(cold, Cipher(), feature),
            server, Tokens(), feature, dagger.Lazy { recovery }, cold
        )

        assertNull(
            "fixture: the chat under test must have no local root",
            cold.load().getOrNull()?.latest(OTHER)
        )

        kotlinx.coroutines.withTimeout(30_000) {
            kotlinx.coroutines.coroutineScope {
                val first = async(kotlinx.coroutines.Dispatchers.Default) { sync.downloadFor(OTHER) }
                entered.await()
                // The racing pass now JOINS the running recovery instead of being answered about
                // it, so it must be launched concurrently - a blocking call here would simply wait
                // for a release that only happens below.
                val second = async(kotlinx.coroutines.Dispatchers.Default) { sync.downloadFor(OTHER) }
                release.complete(Unit)

                assertEquals(
                    "PHASE 56: the racing caller now receives the real failure and is refused",
                    0, second.await().records.size
                )
                first.await()
            }
        }

        assertEquals("PHASE 56: nothing reached the wire for the uncovered chat", 0, server.archiveGets)
        assertNull(
            "and it still holds no root for that chat",
            cold.load().getOrNull()?.latest(OTHER)
        )
    }

    // ------------------------------------------------------------------ counterfactual

    /**
     * The guard has to be chat-specific, and this proves the weaker version would have been wrong
     * rather than merely less precise.
     *
     * On this exact fixture the two candidate conditions disagree: "a keyring exists" is TRUE for
     * [OTHER] while "a root for this chat exists" is FALSE. Had the fallback been written as the
     * former, [OTHER] would have downloaded ciphertext this device cannot open - pinning it
     * first-writer-wins into a SEALED row. The observed request count settles which condition
     * production actually implements.
     */
    @Test
    fun theWeakerKeyringExistsConditionWouldHaveLetAnUncoveredChatThrough() = runBlocking {
        val rig = lockedDeviceHoldingRootForChat()
        val keyring = rig.keyring.load()

        assertTrue("counterfactual condition: a keyring exists", keyring.isSuccess)
        assertTrue("and it is not empty", keyring.getOrNull()!!.entries.isNotEmpty())
        assertNull(
            "production condition: no root for the uncovered chat",
            keyring.getOrNull()!!.latest(OTHER)
        )

        rig.sync.downloadFor(OTHER)
        assertEquals(
            "production must follow the chat-specific condition, not the weaker one",
            0, rig.server.archiveGets
        )
    }
}
