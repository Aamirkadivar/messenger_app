package com.messenger.app.data.repository

import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.OutboxState
import com.messenger.app.data.model.SendMessageRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.di.AppModule
import com.messenger.app.security.TokenManager
import com.messenger.app.data.remote.TokenRefreshAuthenticator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PHASE 71 - the durable outbox state machine, over the PRODUCTION HTTP stack.
 *
 * [MessageOutbox] with the production [HttpOutboxTransport], the production OkHttp client (with its
 * X-Device-Id interceptor) and the production Json, against an in-process [MockWebServer] that
 * behaves like the Phase 71 backend: a client_message_id is stored ONCE, and a repeat of it is
 * answered with the message already stored. No WebSocket exists anywhere in this suite.
 *
 * Covers Android criteria 13 (HTTP send without a socket), 14 (retry reuses client_message_id),
 * 15 (retry reuses the stored ciphertext) and 16 (one stuck item does not block the others).
 */
class MessageOutboxTest {

    private class Tokens : TokenManager by mockk(relaxed = true) {
        override suspend fun getOrCreateDeviceId(): Result<String> = Result.success("device-under-test")
    }

    /** OutboxStore with exactly the OutboxDao's state rules. */
    private class MemoryStore : OutboxStore {
        val rows = ConcurrentHashMap<String, OutboxEntity>()
        override suspend fun insert(item: OutboxEntity) {
            check(rows.putIfAbsent(item.clientMessageId, item) == null) { "client_message_id reused" }
        }
        override suspend fun get(clientMessageId: String) = rows[clientMessageId]
        override suspend fun due(now: Long) = rows.values
            .filter { it.state == OutboxState.PENDING && it.nextAttemptAt <= now }
            .sortedWith(compareBy({ it.createdAt }, { it.clientMessageId }))
        override suspend fun markAccepted(clientMessageId: String, serverId: String, at: Long) {
            rows.computeIfPresent(clientMessageId) { _, r ->
                r.copy(state = OutboxState.ACCEPTED, serverMessageId = serverId, acceptedAt = at, lastError = null)
            }
        }
        override suspend fun markRetry(clientMessageId: String, attempts: Int, nextAttemptAt: Long, error: String) {
            rows.computeIfPresent(clientMessageId) { _, r ->
                if (r.state != OutboxState.PENDING) r
                else r.copy(attempts = attempts, nextAttemptAt = nextAttemptAt, lastError = error)
            }
        }
        override suspend fun markFailed(clientMessageId: String, attempts: Int, error: String) {
            rows.computeIfPresent(clientMessageId) { _, r ->
                if (r.state != OutboxState.PENDING) r
                else r.copy(state = OutboxState.FAILED, attempts = attempts, lastError = error)
            }
        }
        override suspend fun pruneAccepted(olderThan: Long) {
            rows.values.removeIf { it.state == OutboxState.ACCEPTED && (it.acceptedAt ?: 0) < olderThan }
        }
        /** OutboxDao.resetForRetry. */
        fun resetForRetry(clientMessageId: String, now: Long) {
            rows.computeIfPresent(clientMessageId) { _, r ->
                if (r.state != OutboxState.FAILED) r
                else r.copy(state = OutboxState.PENDING, attempts = 0, nextAttemptAt = now, lastError = null)
            }
        }
    }

    /**
     * The Phase 71 server contract in miniature. Per client_message_id a script of behaviours is
     * consumed one request at a time; when it runs out the request is accepted. Acceptance stores
     * the id once; a repeat answers with the stored message (idempotent_replay).
     */
    private inner class Backend : Dispatcher() {
        val scripts = ConcurrentHashMap<String, ArrayDeque<String>>()
        val stored = ConcurrentHashMap<String, String>() // client_message_id -> server id
        val bodies = CopyOnWriteArrayList<String>()
        private var seq = 0L

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            bodies += body
            val obj = json.parseToJsonElement(body).jsonObject
            val cmid = obj["client_message_id"]!!.jsonPrimitive.content
            val chatId = obj["chat_id"]!!.jsonPrimitive.content
            when (scripts[cmid]?.removeFirstOrNull()) {
                "500" -> return MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}""")
                "400" -> return MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}""")
                "401" -> return MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                // Unreachable: the connection dies and the server kept nothing.
                "down" -> return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                // LOST ACK: the server stores the message, then the connection dies before the answer.
                "lost-ack" -> {
                    stored.computeIfAbsent(cmid) { UUID.randomUUID().toString() }
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
            }
            var replay = true
            val id = stored.computeIfAbsent(cmid) { replay = false; UUID.randomUUID().toString() }
            val s = synchronized(this) { ++seq }
            return MockResponse().setResponseCode(200).setBody(
                """{"message":"Message accepted","data":{"id":"$id","chat_id":"$chatId","sender_id":"me",""" +
                    """"content":"x","encrypted":true,"created_at":"2026-09-11T10:00:00Z","status":"accepted",""" +
                    """"seq":$s,"client_message_id":"$cmid","idempotent_replay":$replay}}"""
            )
        }
    }

    private val json: Json = AppModule.provideJson()
    private lateinit var server: MockWebServer
    private lateinit var backend: Backend
    private lateinit var transport: HttpOutboxTransport
    private lateinit var store: MemoryStore
    private var now = 1_000_000L
    private lateinit var outbox: MessageOutbox

    @Before
    fun setUp() {
        backend = Backend()
        server = MockWebServer().apply { dispatcher = backend; start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            // The production client. Its token-refresh authenticator declines here, as it does when
            // there is nothing to refresh, so a 401 surfaces instead of being replayed.
            .client(AppModule.provideOkHttpClient(
                mockk<TokenRefreshAuthenticator> { every { authenticate(any(), any()) } returns null },
                Tokens()
            ))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChatApiService::class.java)
        transport = HttpOutboxTransport(api) { "test-access-token" }
        store = MemoryStore()
        outbox = MessageOutbox(clock = { now })
    }

    @After
    fun tearDown() = server.shutdown()

    /** An outbox row exactly as ChatRepository.sendText writes it: sealed body, never plaintext. */
    private fun queued(chatId: String, createdAt: Long = now): OutboxEntity {
        val cmid = UUID.randomUUID().toString()
        val request = SendMessageRequest(
            chatId = chatId, chatType = "direct",
            content = UUID.randomUUID().toString().replace("-", "").repeat(3), // opaque ciphertext stand-in
            encrypted = true, encryptionVersion = 4, clientMessageId = cmid
        )
        return OutboxEntity(
            clientMessageId = cmid, chatId = chatId, chatType = "direct",
            requestJson = json.encodeToString(SendMessageRequest.serializer(), request),
            // Due at once, as sendText enqueues it; createdAt only orders the items.
            state = OutboxState.PENDING, createdAt = createdAt, attempts = 0, nextAttemptAt = 0,
            lastError = null, serverMessageId = null, acceptedAt = null
        )
    }

    private fun script(item: OutboxEntity, vararg steps: String) {
        backend.scripts[item.clientMessageId] = ArrayDeque(steps.toList())
    }

    /** Runs passes, jumping the clock past every backoff, until nothing is due or [max] passes ran. */
    private fun drainUntilSettled(max: Int = 30): Int = runBlocking {
        var passes = 0
        while (passes < max) {
            outbox.drain(store, transport)
            passes++
            val pending = store.rows.values.filter { it.state == OutboxState.PENDING }
            if (pending.isEmpty()) break
            now = pending.minOf { it.nextAttemptAt }
        }
        passes
    }

    // ------------------------------------------------------------------ 13

    @Test
    fun httpSendIsAcceptedWithNoWebSocketAnywhere() = runBlocking {
        // Nothing in this suite constructs a WebSocketManager: acceptance is HTTP alone.
        val item = queued("chat-1")
        outbox.enqueue(store, item)
        val pass = outbox.drain(store, transport)
        val row = store.rows.getValue(item.clientMessageId)
        println("P71 A13: accepted=${pass.accepted} state=${row.state} server posts=${backend.bodies.size}")
        assertEquals(OutboxState.ACCEPTED, row.state)
        assertEquals(backend.stored[item.clientMessageId], row.serverMessageId)
    }

    // ------------------------------------------------------------------ 14 + 15 + lost ACK

    @Test
    fun everyRetryResendsTheStoredBytesAndResolvesToOneMessage() {
        val item = queued("chat-1")
        runBlocking { outbox.enqueue(store, item) }
        // 5xx, then a LOST ACK (server stored it, client never heard), then a clean answer.
        script(item, "500", "lost-ack")
        val passes = drainUntilSettled()
        val row = store.rows.getValue(item.clientMessageId)
        println(
            "P71 A14/A15: passes=$passes posts=${backend.bodies.size} distinct bodies=${backend.bodies.toSet().size} " +
                "server messages=${backend.stored.size} state=${row.state}"
        )
        assertTrue("expected at least three attempts", backend.bodies.size >= 3)
        // 15: not re-encrypted - the exact stored JSON, byte for byte, every time.
        assertTrue(backend.bodies.all { it == item.requestJson })
        // 14: therefore the same client_message_id every time...
        assertEquals(1, backend.bodies.map { json.parseToJsonElement(it).jsonObject["client_message_id"] }.toSet().size)
        // ...and one logical message on the server, which the retry resolved to.
        assertEquals(1, backend.stored.size)
        assertEquals(OutboxState.ACCEPTED, row.state)
        assertEquals(backend.stored.getValue(item.clientMessageId), row.serverMessageId)
    }

    // ------------------------------------------------------------------ 16

    @Test
    fun aStuckOrRefusedItemNeverBlocksTheItemsAfterIt() = runBlocking {
        val refused = queued("chat-1", createdAt = now)          // 403 -> FAILED
        val afterRefused = queued("chat-1", createdAt = now + 1) // same chat, must still go
        val stuck = queued("chat-2", createdAt = now + 2)        // 503 -> PENDING, backing off
        val afterStuck = queued("chat-2", createdAt = now + 3)   // same chat, must still go
        listOf(refused, afterRefused, stuck, afterStuck).forEach { outbox.enqueue(store, it) }
        script(refused, "400")
        script(stuck, "500", "500", "500")

        outbox.drain(store, transport)
        val s = store.rows
        println(
            "P71 A16: refused=${s[refused.clientMessageId]!!.state} afterRefused=${s[afterRefused.clientMessageId]!!.state} " +
                "stuck=${s[stuck.clientMessageId]!!.state} afterStuck=${s[afterStuck.clientMessageId]!!.state}"
        )
        assertEquals(OutboxState.FAILED, s[refused.clientMessageId]!!.state)
        assertEquals(OutboxState.ACCEPTED, s[afterRefused.clientMessageId]!!.state)
        assertEquals(OutboxState.PENDING, s[stuck.clientMessageId]!!.state)
        assertEquals(OutboxState.ACCEPTED, s[afterStuck.clientMessageId]!!.state)
    }

    @Test
    fun serverErrorsFailAfterTheBoundButBeingOfflineNeverDoes() {
        val serverBroken = queued("chat-1")
        val offline = queued("chat-2")
        runBlocking { outbox.enqueue(store, serverBroken); outbox.enqueue(store, offline) }
        script(serverBroken, *Array(20) { "500" })
        // Long enough that even OkHttp's own transparent connection retries cannot exhaust it.
        script(offline, *Array(100) { "down" })
        drainUntilSettled(max = 20)
        val a = store.rows.getValue(serverBroken.clientMessageId)
        val b = store.rows.getValue(offline.clientMessageId)
        println("P71 A-bounds: 5xx item ${a.state} after ${a.attempts}; offline item ${b.state} after ${b.attempts}")
        assertEquals(OutboxState.FAILED, a.state)
        assertEquals(MessageOutbox.MAX_SERVER_ATTEMPTS, a.attempts)
        assertEquals("an unreachable server is never a reason to give up", OutboxState.PENDING, b.state)
        assertTrue(b.attempts >= 19)
    }

    @Test
    fun unauthorizedStopsThePassWithoutSpendingAttempts() = runBlocking {
        val first = queued("chat-1", createdAt = now)
        val second = queued("chat-1", createdAt = now + 1)
        outbox.enqueue(store, first)
        outbox.enqueue(store, second)
        script(first, "401")
        val pass = outbox.drain(store, transport)
        println("P71 A-401: stoppedForAuth=${pass.stoppedForAuth} posts=${backend.bodies.size}")
        assertTrue(pass.stoppedForAuth)
        assertEquals(0, store.rows.getValue(first.clientMessageId).attempts)
        assertEquals(OutboxState.PENDING, store.rows.getValue(second.clientMessageId).state)
        assertEquals("nothing after a 401 is attempted in that pass", 1, backend.bodies.size)
    }

    @Test
    fun anExplicitRetryOfAFailedItemIsStillTheSameMessage() {
        val item = queued("chat-1")
        runBlocking { outbox.enqueue(store, item) }
        script(item, "400")
        drainUntilSettled()
        assertEquals(OutboxState.FAILED, store.rows.getValue(item.clientMessageId).state)
        store.resetForRetry(item.clientMessageId, now) // the user tapped retry
        drainUntilSettled()
        val row = store.rows.getValue(item.clientMessageId)
        println("P71 A-retry: state=${row.state} posts=${backend.bodies.size} identical=${backend.bodies.toSet().size == 1}")
        assertEquals(OutboxState.ACCEPTED, row.state)
        assertTrue(backend.bodies.all { it == item.requestJson })
    }

    @Test
    fun theStoredBodyCarriesTheIdAndCiphertextButNoPlaintextField() {
        val item = queued("chat-1")
        val obj = json.parseToJsonElement(item.requestJson).jsonObject
        assertEquals(item.clientMessageId, obj["client_message_id"]!!.jsonPrimitive.content)
        assertEquals("true", obj["encrypted"]!!.jsonPrimitive.content)
        // Legacy sends that do not go through the outbox leave the field out entirely.
        val legacy = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(chatId = "c", content = "00", encrypted = true)
        )
        assertFalse(legacy.contains("client_message_id"))
    }
}
