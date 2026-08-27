package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.ArchiveCipher
import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryContext
import com.messenger.app.data.encryption.history.HistoryKeyring
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryRootEntry
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.model.HistoryKeyringDto
import com.messenger.app.data.model.HistoryKeyringPutRequest
import com.messenger.app.data.model.HistoryKeyringPutResponse
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64

/**
 * GATE 5 PHASE 2 - recovery wired into the application lifecycle, JVM.
 *
 * Uses hand-written fakes rather than relaxed mocks throughout: the previous
 * phase established that MockK cannot produce `Result` (an inline value class),
 * and a fake server also lets these tests assert what actually crossed the wire.
 *
 * All key material is TEST-ONLY.
 */
class HistoryRecoveryLifecycleTest {

    private companion object {
        const val USER = "user-1"
        const val CHAT = "chat-1"
        const val OTHER = "chat-2"
        const val TEXT = "phase 2 canary"
    }

    // ------------------------------------------------------------------ fakes

    private class DeviceStore : HistoryKeyringStore {
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

    private class Vault(var user: String?, var locked: Boolean = false) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun seal(domain: String, p: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            return Result.success(("$domain|$u|").toByteArray(Charsets.UTF_8) + p.copyOf())
        }
        private fun open(domain: String, sealed: ByteArray): Result<ByteArray> {
            if (locked) return Result.failure(IllegalStateException("vault is locked"))
            val u = user ?: return Result.failure(IllegalStateException("no user"))
            val prefix = ("$domain|$u|").toByteArray(Charsets.UTF_8)
            if (sealed.size < prefix.size ||
                !sealed.copyOfRange(0, prefix.size).contentEquals(prefix)
            ) return Result.failure(IllegalStateException("failed authentication"))
            return Result.success(sealed.copyOfRange(prefix.size, sealed.size))
        }
        override suspend fun sealHistoryKeyring(p: ByteArray) = seal("local", p)
        override suspend fun openHistoryKeyring(s: ByteArray) = open("local", s)
        override suspend fun sealForRecovery(p: ByteArray) = seal("recovery", p)
        override suspend fun openFromRecovery(s: ByteArray) = open("recovery", s)
    }

    /** An in-memory stand-in for the Phase 1 endpoints, with real version semantics. */
    private class FakeServer : ChatApiService by mockk(relaxed = true) {
        var blob: ByteArray? = null
        var version = 0
        var offline = false
        var puts = 0
        var gets = 0

        override suspend fun getHistoryKeyring(token: String): Response<HistoryKeyringDto> {
            gets++
            if (offline) throw java.io.IOException("offline")
            val b = blob ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(
                HistoryKeyringDto(version, Base64.getEncoder().encodeToString(b))
            )
        }

        override suspend fun putHistoryKeyring(
            token: String,
            body: HistoryKeyringPutRequest,
        ): Response<HistoryKeyringPutResponse> {
            puts++
            if (offline) throw java.io.IOException("offline")
            val incoming = Base64.getDecoder().decode(body.ciphertextB64)
            if (body.expectedVersion != version) {
                return Response.error(409, ResponseBody.create(null, ""))
            }
            blob = incoming
            version += 1
            return Response.success(HistoryKeyringPutResponse(version, version == 1))
        }
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    private class Cipher : ArchiveCipher {
        override fun seal(historyRoot: ByteArray, ctx: HistoryContext, plaintext: ByteArray) =
            "sealed".toByteArray()
        override fun open(historyRoot: ByteArray, ctx: HistoryContext, sealed: ByteArray): ByteArray? = null
    }

    private class Rig(
        val store: DeviceStore = DeviceStore(),
        val server: FakeServer = FakeServer(),
        user: String? = USER,
        enabled: Boolean = true,
        locked: Boolean = false,
    ) {
        val vault = Vault(user, locked)
        val feature = HistoryArchiveFeature { enabled }
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { user }, HistoryArchiveFeature { true })
        val sync = HistoryKeyringRecoverySync(keyring, vault, server, Tokens(), feature)
        val archiver = MessageArchiver(keyring, Cipher(), feature)
        val archiveSync = ArchiveSync(archiver, server, Tokens(), feature, dagger.Lazy { sync })
        val coordinator = HistoryRotationCoordinator(keyring, dagger.Lazy { sync }, HistoryArchiveFeature { true })
    }

    // ------------------------------------------------------------------ upload lifecycle

    @Test
    fun rootCreationPublishesTheKeyring() = runBlocking {
        val r = Rig()
        r.archiveSync.sealAndUpload(USER, CHAT, "m1", TEXT)
        assertNotNull("minting a root must publish it", r.server.blob)
        assertEquals(1, r.server.version)
    }

    @Test
    fun anUnchangedKeyringIsNotRepublished() = runBlocking {
        val r = Rig()
        r.archiveSync.sealAndUpload(USER, CHAT, "m1", TEXT)
        val putsAfterFirst = r.server.puts
        // Same chat: ensureRoot returns the existing root without mutating.
        repeat(5) { r.archiveSync.sealAndUpload(USER, CHAT, "m$it", TEXT) }
        assertEquals("per-message archiving must not be per-message keyring traffic",
            putsAfterFirst, r.server.puts)
    }

    @Test
    fun aNewChatRepublishes() = runBlocking {
        val r = Rig()
        r.archiveSync.sealAndUpload(USER, CHAT, "m1", TEXT)
        val before = r.server.version
        r.archiveSync.sealAndUpload(USER, OTHER, "m2", TEXT)
        assertTrue("a second chat's root must be published", r.server.version > before)
    }

    @Test
    fun rotationPublishesTheKeyring() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged()
        val before = r.server.version

        r.coordinator.onSecurityEvent(SecurityEvent.GroupMemberRemoved(CHAT, "alice")).getOrThrow()
        assertTrue("rotation must publish", r.server.version > before)
    }

    @Test
    fun deviceRevocationRotationPublishes() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        r.sync.uploadIfChanged()
        val before = r.server.version

        r.coordinator.onSecurityEvent(
            SecurityEvent.DeviceRevoked("dev-1"), listOf(CHAT)
        ).getOrThrow()
        assertTrue(r.server.version > before)
    }

    /** A publish failure is a synchronisation problem, never a messaging one. */
    @Test
    fun uploadFailureDoesNotFailTheLocalMutation() = runBlocking {
        val r = Rig()
        r.server.offline = true

        val root = r.keyring.ensureRoot(CHAT)
        assertTrue("the local mint must succeed regardless", root.isSuccess)
        assertTrue("publishing must not throw", r.sync.uploadIfChanged().isSuccess)
        assertNull(r.server.blob)

        // Back online: the next mutation converges without a queue or scheduler.
        r.server.offline = false
        r.keyring.rotate(CHAT).getOrThrow()
        r.sync.uploadIfChanged()
        assertNotNull("a later mutation must publish the accumulated keyring", r.server.blob)
    }

    @Test
    fun nothingIsPublishedBeforeLocalPersistenceSucceeds() = runBlocking {
        val r = Rig(locked = true)
        // A locked vault cannot persist, so there is nothing to publish.
        assertTrue(r.keyring.ensureRoot(CHAT).isFailure)
        r.sync.uploadIfChanged()
        assertNull("a failed mutation must never publish", r.server.blob)
        assertEquals(0, r.server.puts)
    }

    // ------------------------------------------------------------------ recovery lifecycle

    @Test
    fun freshInstallRecoversTheKeyring() = runBlocking {
        // Device one publishes.
        val shared = FakeServer()
        val first = Rig(server = shared)
        val originalRoot = first.keyring.ensureRoot(CHAT).getOrThrow().root
        first.sync.uploadIfChanged()
        assertNotNull(shared.blob)

        // Device two: empty storage, same account, MK available.
        val second = Rig(store = DeviceStore(), server = shared)
        assertEquals(0, second.keyring.load().getOrThrow().entries.size)

        assertTrue(second.sync.ensureRecovered().getOrThrow())
        assertArrayEquals(
            "the recovered root must match the original",
            originalRoot,
            second.keyring.load().getOrThrow().find(CHAT, 1)!!.root
        )
    }

    @Test
    fun recoveryHappensBeforeArchiveDownload() = runBlocking {
        val shared = FakeServer()
        val first = Rig(server = shared)
        first.keyring.ensureRoot(CHAT).getOrThrow()
        first.sync.uploadIfChanged()

        val second = Rig(store = DeviceStore(), server = shared)
        second.archiveSync.downloadFor(CHAT)
        assertNotNull(
            "the root must be present by the time archives are fetched",
            second.keyring.load().getOrThrow().find(CHAT, 1)
        )
    }

    @Test
    fun anExistingDeviceMergesRatherThanBeingOverwritten() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()

        // Another device with a root the server has never seen.
        val b = Rig(store = DeviceStore(), server = shared)
        val localOnly = b.keyring.ensureRoot(OTHER).getOrThrow().root

        b.sync.ensureRecovered().getOrThrow()
        val merged = b.keyring.load().getOrThrow()
        assertEquals(2, merged.entries.size)
        assertArrayEquals("the local-only root must survive", localOnly, merged.find(OTHER, 1)!!.root)
        assertNotNull("the remote-only root must be imported", merged.find(CHAT, 1))
    }

    @Test
    fun absentServerObjectContinuesNormally() = runBlocking {
        val r = Rig()
        r.keyring.ensureRoot(CHAT).getOrThrow()
        val before = r.keyring.load().getOrThrow()

        assertFalse("404 means nothing to recover, not an error", r.sync.ensureRecovered().getOrThrow())
        assertEquals(before.entries.size, r.keyring.load().getOrThrow().entries.size)
    }

    @Test
    fun recoveryIsIdempotentAndDoesNotLoop() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()
        val versionAfterPublish = shared.version

        val b = Rig(store = DeviceStore(), server = shared)
        repeat(4) { b.sync.ensureRecovered() }

        assertEquals("recovery must run once per session", 1, shared.gets)
        assertEquals(
            "an unchanged merge must not write back - that would be a GET/PUT loop",
            versionAfterPublish, shared.version
        )
    }

    @Test
    fun recoveryDoesNotMintOrRotate() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()

        val b = Rig(store = DeviceStore(), server = shared)
        b.sync.ensureRecovered().getOrThrow()

        val k = b.keyring.load().getOrThrow()
        assertEquals("no extra version may appear", 1, k.entries.size)
        assertEquals(1, k.latest(CHAT)!!.rootVersion)
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    fun wrongMkRecoveryFailsClosedAndDownloadIsSkipped() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()

        // Same account name, different key material - models a post-reset MK.
        val b = Rig(store = DeviceStore(), server = shared, user = USER)
        b.vault.user = "$USER-different-mk"

        assertTrue("a blob that will not authenticate is a failure", b.sync.ensureRecovered().isFailure)

        val page = b.archiveSync.downloadFor(CHAT)
        assertFalse("archives must not be fetched under an unresolved keyring", page.complete)
        assertTrue(page.records.isEmpty())
    }

    @Test
    fun conflictingRootsFailClosedAndLeaveLocalStateIntact() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()

        // A device holding DIFFERENT material for the same (chat, version).
        val b = Rig(store = DeviceStore(), server = shared)
        val localRoot = b.keyring.ensureRoot(CHAT).getOrThrow().root

        assertTrue("conflicting roots must abort recovery", b.sync.ensureRecovered().isFailure)
        b.keyring.clearCache()
        assertArrayEquals(
            "local state must be untouched by a refused recovery",
            localRoot, b.keyring.load().getOrThrow().find(CHAT, 1)!!.root
        )
    }

    @Test
    fun offlineRecoveryLeavesLocalStateIntact() = runBlocking {
        val r = Rig()
        val root = r.keyring.ensureRoot(CHAT).getOrThrow().root
        r.server.offline = true

        assertTrue(r.sync.ensureRecovered().isFailure)
        r.keyring.clearCache()
        assertArrayEquals(
            "an offline recovery must never destroy local roots",
            root, r.keyring.load().getOrThrow().find(CHAT, 1)!!.root
        )
    }

    @Test
    fun aTransientRecoveryFailureCanBeRetried() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()

        val b = Rig(store = DeviceStore(), server = shared)
        shared.offline = true
        assertTrue(b.sync.ensureRecovered().isFailure)

        shared.offline = false
        assertTrue("a transient failure must not disable recovery for the session",
            b.sync.ensureRecovered().getOrThrow())
    }

    // ------------------------------------------------------------------ flag boundary

    @Test
    fun flagOffPreventsAllRecoveryTraffic() = runBlocking {
        val r = Rig(enabled = false)
        r.keyring.ensureRoot(CHAT).getOrThrow()

        r.sync.uploadIfChanged()
        r.sync.ensureRecovered()
        r.archiveSync.sealAndUpload(USER, CHAT, "m1", TEXT)
        r.archiveSync.downloadFor(CHAT)

        assertEquals("no keyring PUT may occur while the flag is off", 0, r.server.puts)
        assertEquals("no keyring GET may occur while the flag is off", 0, r.server.gets)
        assertNull(r.server.blob)
    }

    @Test
    fun flagOffStillAllowsLocalKeyringUse() = runBlocking {
        val r = Rig(enabled = false)
        // Minting is a local operation and is not gated by the archive flag.
        val root = r.keyring.ensureRoot(CHAT).getOrThrow()
        assertEquals(32, root.root.size)
        assertNotNull(r.keyring.load().getOrThrow().find(CHAT, 1))
    }

    // ------------------------------------------------------------------ convergence

    @Test
    fun recoveryThatImportsRemoteRootsConvergesTheServerCopy() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()
        val versionAfterA = shared.version

        // B has a local-only root, so the merge is strictly larger than the server's.
        val b = Rig(store = DeviceStore(), server = shared)
        b.keyring.ensureRoot(OTHER).getOrThrow()
        b.sync.ensureRecovered().getOrThrow()

        assertTrue("the union must be published back", shared.version > versionAfterA)

        // A third device now recovers the full union.
        val c = Rig(store = DeviceStore(), server = shared)
        c.sync.ensureRecovered().getOrThrow()
        assertEquals(2, c.keyring.load().getOrThrow().entries.size)
    }

    @Test
    fun aConflictingConcurrentPublishIsMergedRatherThanClobbered() = runBlocking {
        val shared = FakeServer()
        val a = Rig(server = shared)
        a.keyring.ensureRoot(CHAT).getOrThrow()
        a.sync.uploadIfChanged()

        // B publishes a disjoint root while believing the server is empty.
        val b = Rig(store = DeviceStore(), server = shared)
        b.keyring.ensureRoot(OTHER).getOrThrow()
        b.sync.uploadIfChanged()

        // The server must now hold BOTH, not just the later writer's.
        val c = Rig(store = DeviceStore(), server = shared)
        c.sync.ensureRecovered().getOrThrow()
        val merged = c.keyring.load().getOrThrow()
        assertEquals("a conflicting publish must merge, never clobber", 2, merged.entries.size)
        assertNotNull(merged.find(CHAT, 1))
        assertNotNull(merged.find(OTHER, 1))
    }
}
