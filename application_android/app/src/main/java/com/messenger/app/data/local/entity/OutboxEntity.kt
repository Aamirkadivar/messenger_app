package com.messenger.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One outgoing message, persisted BEFORE its first transmission (schema v9).
 *
 * This row is what makes a send survive the ViewModel, navigation, the process and a lost ACK.
 * [requestJson] is the exact `POST /messages` body: ciphertext, protocol metadata and
 * [clientMessageId]. A retry re-sends those bytes unchanged - it never re-encrypts - and the
 * server resolves a repeated client_message_id to the message it already stored, so a retry
 * after a lost ACK cannot create a second logical message.
 *
 * Never plaintext. The body is sealed before this row is written, and nothing in it can be
 * turned back into the message text on this device (an MLS or ratchet sender holds no key for
 * its own ciphertext).
 */
@Entity(
    tableName = "outbox_messages",
    indices = [Index("chatId"), Index("state")]
)
data class OutboxEntity(
    @PrimaryKey val clientMessageId: String,
    val chatId: String,
    val chatType: String,
    val requestJson: String,
    /** [OutboxState] wire value. */
    val state: String,
    val createdAt: Long,
    val attempts: Int,
    /** Earliest time the next attempt may run (backoff). */
    val nextAttemptAt: Long,
    val lastError: String?,
    /** The server's canonical id, once ACCEPTED. */
    val serverMessageId: String?,
    val acceptedAt: Long?
)

/**
 * PENDING  stored here, not yet accepted by the server; retried automatically.
 * ACCEPTED the server durably stored the ciphertext. Never retransmitted again - in particular
 *          not because a recipient is offline, which the server handles.
 * FAILED   refused in a way a retry cannot fix on its own; waits for an explicit user retry.
 *          Never blocks other items.
 */
object OutboxState {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val FAILED = "FAILED"
}

/**
 * Per-chat synchronisation point (schema v9): the highest server `seq` this device has
 * ingested for [chatId]. Catch-up asks for `?after=lastSeq` until the server reports no more,
 * so messages that arrived while this device was offline are fetched however many there are.
 */
@Entity(tableName = "chat_sync_state")
data class ChatSyncStateEntity(
    @PrimaryKey val chatId: String,
    val lastSeq: Long,
    /**
     * Where an unfinished backward catch-up resumes (exclusive seq), or null when there is no gap
     * between the local cache and [lastSeq]. See ChatSyncPager.
     */
    val backfillBefore: Long?,
    val updatedAt: Long
)
