package com.messenger.app.data.repository

import com.messenger.app.data.encryption.history.HistoryArchiveFeature
import com.messenger.app.data.encryption.history.HistoryKeyring
import com.messenger.app.data.encryption.history.HistoryKeyringRecoveryTransport
import com.messenger.app.data.encryption.history.HistoryKeyringStore
import com.messenger.app.data.encryption.history.HistoryKeyringVault
import com.messenger.app.data.encryption.history.HistoryRootEntry
import com.messenger.app.data.encryption.history.HistoryUserProvider
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.di.AppModule
import com.messenger.app.security.TokenManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * GATE 5 PHASE 5 - two devices of one account converging, over REAL HTTP.
 *
 * The convergence rules were previously proven only against hand-written fakes
 * of [ChatApiService], which bypass Retrofit, OkHttp, the interceptor chain, and
 * the JSON contract entirely. Here both devices are ordinary
 * [HistoryKeyringRecoverySync] instances wired to the production OkHttp client
 * and talking to a [MockWebServer] over a loopback socket.
 *
 * The server in this test implements the SAME optimistic-concurrency contract as
 * the Go handler - create-only at version 0, replace only when the caller names
 * the current version, 409 otherwise. It is not a substitute for the backend's
 * own coverage: those semantics are proven against real PostgreSQL in
 * `keyring_recovery_db_test.go`, including under genuine goroutine contention.
 * What is proven HERE is that two real clients, speaking real HTTP against that
 * contract, converge - and that is a claim fakes could not support.
 *
 * All key material is TEST-ONLY.
 */
class HistoryMultiDeviceConvergenceTest {

    private companion object {
        const val USER = "converge-user"
        const val CHAT_A = "converge-chat-a"
        const val CHAT_B = "converge-chat-b"
    }

    // ------------------------------------------------------------ the server

    /** One versioned blob, with the Go handler's CAS rules. */
    private class KeyringServer {
        var blob: ByteArray? = null
        var version = 0
        val puts = AtomicInteger()
        val gets = AtomicInteger()

        fun dispatcher() = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
                return when (request.method) {
                    "GET" -> {
                        gets.incrementAndGet()
                        val b = blob
                            ?: return MockResponse().setResponseCode(404)
                                .setBody("""{"error":"not found"}""")
                        MockResponse().setResponseCode(200).setBody(
                            """{"version":$version,"ciphertext_b64":"${Base64.getEncoder().encodeToString(b)}"}"""
                        )
                    }
                    "PUT" -> {
                        puts.incrementAndGet()
                        val body = request.body.readUtf8()
                        val expected = Regex("\"expected_version\":(\\d+)")
                            .find(body)!!.groupValues[1].toInt()
                        val ct = Regex("\"ciphertext_b64\":\"([^\"]*)\"")
                            .find(body)!!.groupValues[1]
                        if (expected != version) {
                            MockResponse().setResponseCode(409).setBody(
                                """{"error":"version conflict","server_version":$version}"""
                            )
                        } else {
                            blob = Base64.getDecoder().decode(ct)
                            version += 1
                            MockResponse().setResponseCode(200)
                                .setBody("""{"version":$version,"created":${version == 1}}""")
                        }
                    }
                    "DELETE" -> {
                        blob = null; version = 0
                        MockResponse().setResponseCode(200).setBody("""{"deleted":true}""")
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
    }

    // ------------------------------------------------------------ the device

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

    /** Account-bound, mirroring the AAD binding both real copies carry. */
    private class Vault(private val user: String) :
        HistoryKeyringVault, HistoryKeyringRecoveryTransport {
        private fun tag(domain: Int) = domain xor (user.hashCode() and 0x7F)
        private fun mask(b: ByteArray, t: Int) =
            ByteArray(b.size) { i -> (b[i].toInt() xor t).toByte() }
        private fun seal(d: Int, p: ByteArray) =
            Result.success(byteArrayOf(tag(d).toByte()) + mask(p, tag(d)))
        private fun open(d: Int, s: ByteArray) =
            if (s.isNotEmpty() && s[0] == tag(d).toByte())
                Result.success(mask(s.copyOfRange(1, s.size), tag(d)))
            else Result.failure(IllegalStateException("authentication failed"))
        override suspend fun sealHistoryKeyring(plaintext: ByteArray) = seal(0x11, plaintext)
        override suspend fun openHistoryKeyring(sealed: ByteArray) = open(0x11, sealed)
        override suspend fun sealForRecovery(plaintext: ByteArray) = seal(0x22, plaintext)
        override suspend fun openFromRecovery(sealed: ByteArray) = open(0x22, sealed)
    }

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getOrCreateDeviceId(): Result<String> = Result.success("device-x")
        override suspend fun getAccessToken(): Result<String?> = Result.success("t")
    }

    /** One device: its own storage, its own sync, sharing the account's server. */
    private inner class Device(val name: String) {
        val store = Store()
        val vault = Vault(USER)
        val feature = HistoryArchiveFeature.Enabled
        val keyring = HistoryKeyringRepository(vault, store, HistoryUserProvider { USER }, feature)
        val sync = HistoryKeyringRecoverySync(keyring, vault, api, Tokens(), feature)
    }

    private lateinit var web: MockWebServer
    private lateinit var backend: KeyringServer
    private lateinit var api: ChatApiService

    @Before
    fun setUp() {
        backend = KeyringServer()
        web = MockWebServer()
        web.dispatcher = backend.dispatcher()
        web.start()
        api = Retrofit.Builder()
            .baseUrl(web.url("/api/v1/"))
            .client(AppModule.provideOkHttpClient(mockk(relaxed = true), Tokens()))
            .addConverterFactory(
                AppModule.provideJson().asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(ChatApiService::class.java)
    }

    @After
    fun tearDown() {
        web.shutdown()
    }

    // ------------------------------------------------------------ convergence

    @Test
    fun eachDeviceEventuallyObtainsTheOthersRoot() = runBlocking {
        val a = Device("A")
        val b = Device("B")

        val r1 = a.keyring.ensureRoot(CHAT_A).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()

        val r2 = b.keyring.ensureRoot(CHAT_B).getOrThrow()
        // B publishes into a server that has already moved, so this exercises the
        // real conflict path: 409 -> GET -> open -> merge -> retry.
        b.sync.uploadIfChanged().getOrThrow()

        // B already holds both after its own merge.
        val onB = b.keyring.load().getOrThrow()
        assertNotNull("B must have adopted A's root", onB.latest(CHAT_A))
        assertNotNull(onB.latest(CHAT_B))

        // A picks up B's root on its next recovery.
        a.sync.refreshRecovery().getOrThrow()
        val onA = a.keyring.load().getOrThrow()
        assertNotNull(onA.latest(CHAT_A))
        assertNotNull("A must have adopted B's root", onA.latest(CHAT_B))

        // And the roots are the same bytes on both devices - a union that changed
        // material would be worse than one that failed.
        assertTrue(r1.root.contentEquals(onB.latest(CHAT_A)!!.root))
        assertTrue(r2.root.contentEquals(onA.latest(CHAT_B)!!.root))
    }

    @Test
    fun neitherDeviceReplacesTheOthersRoots() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        a.keyring.ensureRoot(CHAT_A).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()
        b.keyring.ensureRoot(CHAT_B).getOrThrow()
        b.sync.uploadIfChanged().getOrThrow()

        // Several rounds in both directions must be idempotent.
        repeat(3) {
            a.sync.refreshRecovery().getOrThrow()
            b.sync.refreshRecovery().getOrThrow()
        }

        for (d in listOf(a, b)) {
            val k = d.keyring.load().getOrThrow()
            assertEquals("device ${d.name} must hold exactly the union", 2, k.entries.size)
            assertNotNull(k.latest(CHAT_A))
            assertNotNull(k.latest(CHAT_B))
        }
    }

    @Test
    fun aDeviceContributingNothingCreatesNoServerVersion() = runBlocking {
        val a = Device("A")
        a.keyring.ensureRoot(CHAT_A).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()
        val settled = backend.version

        // A fresh device with nothing of its own only reads.
        val fresh = Device("fresh")
        fresh.sync.ensureRecovered().getOrThrow()
        repeat(3) { fresh.sync.refreshRecovery().getOrThrow() }

        assertEquals(
            "a pure import must not bump the server version",
            settled, backend.version
        )
        assertNotNull(fresh.keyring.load().getOrThrow().latest(CHAT_A))
    }

    @Test
    fun rotationsFromBothDevicesAllSurvive() = runBlocking {
        val a = Device("A")
        val b = Device("B")

        a.keyring.ensureRoot(CHAT_A).getOrThrow()
        a.keyring.rotate(CHAT_A).getOrThrow() // v2
        a.sync.uploadIfChanged().getOrThrow()

        b.sync.ensureRecovered().getOrThrow()
        b.keyring.rotate(CHAT_A).getOrThrow() // v3
        b.sync.uploadIfChanged().getOrThrow()

        a.sync.refreshRecovery().getOrThrow()
        val onA = a.keyring.load().getOrThrow()
        assertEquals(3, onA.latest(CHAT_A)!!.rootVersion)
        // Every earlier version must remain, or archives sealed under them stop
        // opening. This is the append-only guarantee surviving a real round trip.
        assertNotNull(onA.find(CHAT_A, 1))
        assertNotNull(onA.find(CHAT_A, 2))
        assertNotNull(onA.find(CHAT_A, 3))
    }

    /**
     * Two different roots claiming one `(chatId, rootVersion)` cannot both be
     * right. The import must fail as a unit rather than pick a winner, and the
     * device's own material must be exactly as it was.
     */
    @Test
    fun conflictingRootsForOneVersionFailAtomically() = runBlocking {
        val a = Device("A")
        val mine = a.keyring.ensureRoot(CHAT_A).getOrThrow()
        a.keyring.ensureRoot(CHAT_B).getOrThrow()
        val before = a.keyring.load().getOrThrow()

        // A rival keyring: same chat, same version, different material.
        val rival = HistoryKeyring.of(
            listOf(HistoryRootEntry(CHAT_A, mine.rootVersion, ByteArray(32) { 0x5A }))
        ).getOrThrow()

        val result = a.keyring.importFromRecovery(rival.encode())

        assertTrue("a conflict must not be resolved by choosing", result.isFailure)
        val after = a.keyring.load().getOrThrow()
        assertEquals("nothing may be partially applied", before.entries.size, after.entries.size)
        assertTrue(
            "the device's own root must be untouched",
            mine.root.contentEquals(after.latest(CHAT_A)!!.root)
        )
        assertNotNull("and unrelated chats must survive too", after.latest(CHAT_B))
    }

    @Test
    fun theRealConflictRetryPathIsExercised() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        a.keyring.ensureRoot(CHAT_A).getOrThrow()
        a.sync.uploadIfChanged().getOrThrow()
        val putsBefore = backend.puts.get()

        b.keyring.ensureRoot(CHAT_B).getOrThrow()
        b.sync.uploadIfChanged().getOrThrow()

        assertTrue(
            "B's first PUT must have been rejected and retried, not blind-written",
            backend.puts.get() >= putsBefore + 2
        )
        assertEquals("and exactly one write may have won each round", 2, backend.version)
    }
}
