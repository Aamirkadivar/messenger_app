package com.messenger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.messenger.app.data.local.entity.ChatSyncStateEntity
import com.messenger.app.data.local.entity.OutboxEntity

/** Durable outbox (schema v9). See [OutboxEntity]. */
@Dao
interface OutboxDao {

    /** ABORT, never REPLACE: a client_message_id is minted once and must never be overwritten. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: OutboxEntity)

    @Query("SELECT * FROM outbox_messages WHERE clientMessageId = :clientMessageId")
    suspend fun get(clientMessageId: String): OutboxEntity?

    @Query(
        "SELECT * FROM outbox_messages WHERE state = 'PENDING' AND nextAttemptAt <= :now " +
            "ORDER BY createdAt, clientMessageId"
    )
    suspend fun due(now: Long): List<OutboxEntity>

    @Query(
        "SELECT * FROM outbox_messages WHERE chatId = :chatId AND state != 'ACCEPTED' " +
            "ORDER BY createdAt, clientMessageId"
    )
    suspend fun unacceptedForChat(chatId: String): List<OutboxEntity>

    @Query("SELECT MIN(nextAttemptAt) FROM outbox_messages WHERE state = 'PENDING'")
    suspend fun earliestPendingAt(): Long?

    @Query(
        "UPDATE outbox_messages SET state = 'ACCEPTED', serverMessageId = :serverId, " +
            "acceptedAt = :at, lastError = NULL WHERE clientMessageId = :clientMessageId"
    )
    suspend fun markAccepted(clientMessageId: String, serverId: String, at: Long)

    /** Transient failure: stays PENDING, backs off. Never touches an ACCEPTED row. */
    @Query(
        "UPDATE outbox_messages SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, " +
            "lastError = :error WHERE clientMessageId = :clientMessageId AND state = 'PENDING'"
    )
    suspend fun markRetry(clientMessageId: String, attempts: Int, nextAttemptAt: Long, error: String)

    @Query(
        "UPDATE outbox_messages SET state = 'FAILED', attempts = :attempts, lastError = :error " +
            "WHERE clientMessageId = :clientMessageId AND state = 'PENDING'"
    )
    suspend fun markFailed(clientMessageId: String, attempts: Int, error: String)

    /** Explicit user retry of a FAILED item: same row, same client_message_id, same ciphertext. */
    @Query(
        "UPDATE outbox_messages SET state = 'PENDING', attempts = 0, nextAttemptAt = :now, " +
            "lastError = NULL WHERE clientMessageId = :clientMessageId AND state = 'FAILED'"
    )
    suspend fun resetForRetry(clientMessageId: String, now: Long): Int

    @Query("DELETE FROM outbox_messages WHERE state = 'ACCEPTED' AND acceptedAt < :olderThan")
    suspend fun pruneAccepted(olderThan: Long)
}

/** Per-chat sync points (schema v9). See [ChatSyncStateEntity]. */
@Dao
interface ChatSyncStateDao {

    @Query("SELECT * FROM chat_sync_state WHERE chatId = :chatId")
    suspend fun get(chatId: String): ChatSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChatSyncStateEntity)
}
