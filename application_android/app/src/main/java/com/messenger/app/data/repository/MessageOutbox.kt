package com.messenger.app.data.repository

import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.OutboxState
import com.messenger.app.data.model.SendMessageResponseData
import com.messenger.app.data.remote.api.ChatApiService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.min

/** Where outbox rows live: the signed-in account's Room database in production, memory in tests. */
interface OutboxStore {
    suspend fun insert(item: OutboxEntity)
    suspend fun get(clientMessageId: String): OutboxEntity?
    suspend fun due(now: Long): List<OutboxEntity>
    suspend fun markAccepted(clientMessageId: String, serverId: String, at: Long)
    suspend fun markRetry(clientMessageId: String, attempts: Int, nextAttemptAt: Long, error: String)
    suspend fun markFailed(clientMessageId: String, attempts: Int, error: String)
    suspend fun pruneAccepted(olderThan: Long)
}

/** The outcome of one POST of a stored request body. */
sealed class OutboxAttempt {
    /** The server durably stored the ciphertext (or already had it: an idempotent replay). */
    data class Accepted(val data: SendMessageResponseData) : OutboxAttempt()

    /** The server answered with an error status. */
    data class Rejected(val httpCode: Int, val error: String) : OutboxAttempt()

    /** No answer at all: offline, DNS, connect/read failure. */
    data class Unreachable(val cause: String) : OutboxAttempt()
}

/** Sends one stored request body, byte for byte. Knows nothing about WebSockets. */
fun interface OutboxTransport {
    suspend fun post(requestJson: String): OutboxAttempt
}

/**
 * The production transport: `POST /messages` with the stored JSON as the raw body, so every retry
 * of one logical message carries the identical ciphertext and client_message_id. Any exception
 * (connect, DNS, timeout, unreadable response) is [OutboxAttempt.Unreachable]: the outcome is
 * unknown, and re-sending the same client_message_id is exactly the safe answer to that.
 */
class HttpOutboxTransport(
    private val api: ChatApiService,
    private val accessToken: suspend () -> String?
) : OutboxTransport {
    override suspend fun post(requestJson: String): OutboxAttempt {
        val token = accessToken()
        if (token.isNullOrEmpty()) return OutboxAttempt.Rejected(401, "not signed in")
        return try {
            val response = api.sendMessageRaw(
                "Bearer $token", requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            val data = response.body()?.data
            if (response.isSuccessful && data != null) {
                OutboxAttempt.Accepted(data)
            } else {
                OutboxAttempt.Rejected(response.code(), response.errorBody()?.string()?.take(200).orEmpty())
            }
        } catch (e: Exception) {
            OutboxAttempt.Unreachable(e.javaClass.simpleName)
        }
    }
}

/**
 * The outbox state machine (Phase 71).
 *
 * Every item is written to the [OutboxStore] before its first transmission and carries its own
 * client_message_id and ciphertext. [drain] only ever re-sends those stored bytes, so:
 *
 *  - a retry is the SAME logical message: the server resolves a repeated client_message_id to the
 *    row it already holds, which is what makes a lost ACK harmless;
 *  - nothing is re-encrypted on retry, so no ratchet or MLS generation is spent twice;
 *  - WebSocket state is irrelevant - the HTTP endpoint is the durable path and is always tried.
 *
 * Failure classes:
 *
 *  - unreachable (no response)        PENDING, exponential backoff, never auto-FAILED: being offline
 *                                     for a day is not a reason to give up on a message.
 *  - 408 / 425 / 429 / 5xx            PENDING with backoff; FAILED after [MAX_SERVER_ATTEMPTS].
 *  - 401                              PENDING, attempt not counted; the pass stops, since every
 *                                     other item would be refused the same way until re-auth.
 *  - any other 4xx                    FAILED at once: a refusal (not a participant, blocked,
 *                                     invalid, conflicting id) that repeating cannot fix.
 *
 * Items are independent. A PENDING item backing off, or a FAILED one, never holds back the items
 * after it - there is no head-of-line blocking. Items are still attempted in creation order, so in
 * the ordinary case (everything succeeds, or everything is offline) send order is preserved.
 */
class MessageOutbox(private val clock: () -> Long = System::currentTimeMillis) {

    sealed class Event {
        abstract val clientMessageId: String
        abstract val chatId: String

        data class Accepted(
            override val clientMessageId: String,
            override val chatId: String,
            val data: SendMessageResponseData
        ) : Event()

        data class Failed(
            override val clientMessageId: String,
            override val chatId: String,
            val error: String
        ) : Event()
    }

    data class PassResult(val accepted: Int, val retrying: Int, val failed: Int, val stoppedForAuth: Boolean)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val mutex = Mutex()

    /** Persist a new PENDING item. Must happen before the first transmission. */
    suspend fun enqueue(store: OutboxStore, item: OutboxEntity) {
        require(item.state == OutboxState.PENDING) { "a new outbox item must be PENDING" }
        store.insert(item)
    }

    /**
     * One pass over every PENDING item that is due. Single-flight: concurrent callers queue behind
     * the lock and then find nothing left to do, so an item is never POSTed by two passes at once.
     * [onAccepted] runs after the item is durably marked ACCEPTED; its failure cannot undo that.
     */
    suspend fun drain(
        store: OutboxStore,
        transport: OutboxTransport,
        onAccepted: suspend (OutboxEntity, SendMessageResponseData) -> Unit = { _, _ -> }
    ): PassResult = mutex.withLock {
        val start = clock()
        runCatching { store.pruneAccepted(start - ACCEPTED_RETENTION_MS) }
        var accepted = 0
        var retrying = 0
        var failed = 0
        for (candidate in store.due(start)) {
            // Re-read: an explicit retry or an earlier pass may have moved it since the listing.
            val item = store.get(candidate.clientMessageId) ?: continue
            if (item.state != OutboxState.PENDING) continue

            when (val result = transport.post(item.requestJson)) {
                is OutboxAttempt.Accepted -> {
                    store.markAccepted(item.clientMessageId, result.data.id, clock())
                    runCatching { onAccepted(item, result.data) }
                    _events.tryEmit(Event.Accepted(item.clientMessageId, item.chatId, result.data))
                    accepted++
                }
                is OutboxAttempt.Rejected -> when {
                    result.httpCode == 401 -> {
                        store.markRetry(item.clientMessageId, item.attempts, clock() + AUTH_RETRY_MS, "unauthorized")
                        return@withLock PassResult(accepted, retrying + 1, failed, stoppedForAuth = true)
                    }
                    isRetryableHttp(result.httpCode) -> {
                        val attempts = item.attempts + 1
                        val reason = "HTTP ${result.httpCode}"
                        if (attempts >= MAX_SERVER_ATTEMPTS) {
                            store.markFailed(item.clientMessageId, attempts, reason)
                            _events.tryEmit(Event.Failed(item.clientMessageId, item.chatId, reason))
                            failed++
                        } else {
                            store.markRetry(item.clientMessageId, attempts, clock() + backoffMs(attempts), reason)
                            retrying++
                        }
                    }
                    else -> {
                        val reason = "HTTP ${result.httpCode}" + result.error.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                        store.markFailed(item.clientMessageId, item.attempts + 1, reason)
                        _events.tryEmit(Event.Failed(item.clientMessageId, item.chatId, reason))
                        failed++
                    }
                }
                is OutboxAttempt.Unreachable -> {
                    val attempts = item.attempts + 1
                    store.markRetry(item.clientMessageId, attempts, clock() + backoffMs(attempts), result.cause)
                    retrying++
                }
            }
        }
        PassResult(accepted, retrying, failed, stoppedForAuth = false)
    }

    companion object {
        /** Server-side (5xx/429/408/425) failures tolerated before an item is marked FAILED. */
        const val MAX_SERVER_ATTEMPTS = 8

        /** ACCEPTED rows are kept this long for UI reconciliation, then pruned. */
        const val ACCEPTED_RETENTION_MS = 24L * 60 * 60 * 1000

        private const val AUTH_RETRY_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        /** 2s, 4s, 8s, ... capped at 60s. */
        fun backoffMs(attempts: Int): Long = min(MAX_BACKOFF_MS, 1_000L shl min(attempts, 6))

        fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code >= 500
    }
}
