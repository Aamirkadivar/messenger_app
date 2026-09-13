package com.messenger.app.data.local.dao

import com.messenger.app.data.local.AccountCacheHolder
import com.messenger.app.data.local.entity.CachedChatEntity
import com.messenger.app.data.local.entity.ChatSyncStateEntity
import com.messenger.app.data.local.entity.ConversationEntity
import com.messenger.app.data.local.entity.OutboxEntity
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Account-scoped cache facades.
 *
 * These deliberately do NOT implement the Room DAO interfaces. If they did, a
 * caller could reach an unowned write through the interface type and the
 * compiler would not object. Here every write takes the owning account as its
 * first parameter, so an unowned write does not compile.
 *
 * READS resolve the current account at call time and return empty when nobody
 * is authenticated. A read captured under one account and used under another
 * simply sees the new account, which is correct - there is no object anywhere
 * holding a previous account's handle.
 *
 * WRITES take `owner`: the account captured when the operation STARTED, before
 * any network or WebSocket work. Resolving the account at commit time was the
 * Gate 22 defect - a reply that began under A and landed after a switch to B
 * wrote A's rows into B's namespace. The owner is verified against the active
 * account immediately before the handle is used, and the write is refused if
 * they differ. Refused means dropped: it is never redirected into the account
 * that happens to be signed in.
 */

// ------------------------------------------------------------------ messages

class ScopedMessageDao(private val holder: AccountCacheHolder) {

    private fun <T> scopedFlow(empty: T, block: suspend () -> Flow<T>): Flow<T> = flow {
        if (holder.current() == null) emitAll(flowOf(empty)) else emitAll(block())
    }

    // ---- reads
    fun getMessagesByConversation(convId: String, limit: Int = 20, offset: Int = 0): Flow<List<MessageEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.messageDao().getMessagesByConversation(convId, limit, offset) }

    fun getMessagesBefore(convId: String, beforeTimestamp: Long, limit: Int = 20): Flow<List<MessageEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.messageDao().getMessagesBefore(convId, beforeTimestamp, limit) }

    fun getMessagesAfter(convId: String, afterTimestamp: Long, limit: Int = 20): Flow<List<MessageEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.messageDao().getMessagesAfter(convId, afterTimestamp, limit) }

    fun getAllMessagesByConversationFlow(convId: String): Flow<List<MessageEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.messageDao().getAllMessagesByConversationFlow(convId) }

    fun getMessageCount(convId: String): Flow<Int> =
        scopedFlow(0) { holder.current()!!.messageDao().getMessageCount(convId) }

    fun searchMessages(query: String): Flow<List<MessageEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.messageDao().searchMessages(query) }

    suspend fun getAllMessagesByConversation(convId: String): List<MessageEntity> =
        holder.current()?.messageDao()?.getAllMessagesByConversation(convId) ?: emptyList()

    suspend fun getMessageById(messageId: String): MessageEntity? =
        holder.current()?.messageDao()?.getMessageById(messageId)

    suspend fun getLatestMessage(convId: String): MessageEntity? =
        holder.current()?.messageDao()?.getLatestMessage(convId)

    suspend fun getArchiveState(messageId: String): String? =
        holder.current()?.messageDao()?.getArchiveState(messageId)

    // ---- writes (owner captured at operation start)
    suspend fun insertMessage(owner: String, message: MessageEntity): Long =
        holder.requireOwnedBy(owner).messageDao().insertMessage(message)

    suspend fun insertMessages(owner: String, messages: List<MessageEntity>) =
        holder.requireOwnedBy(owner).messageDao().insertMessages(messages)

    suspend fun updateMessage(owner: String, message: MessageEntity) =
        holder.requireOwnedBy(owner).messageDao().updateMessage(message)

    suspend fun updateMessageStatus(owner: String, messageId: String, status: String) =
        holder.requireOwnedBy(owner).messageDao().updateMessageStatus(messageId, status)

    suspend fun deleteMessage(owner: String, messageId: String) =
        holder.requireOwnedBy(owner).messageDao().deleteMessage(messageId)

    suspend fun deleteMessagesByConversation(owner: String, convId: String) =
        holder.requireOwnedBy(owner).messageDao().deleteMessagesByConversation(convId)

    suspend fun setArchive(owner: String, messageId: String, ciphertext: String, rootVersion: Int, state: String) =
        holder.requireOwnedBy(owner).messageDao().setArchive(messageId, ciphertext, rootVersion, state)

    suspend fun setPlaintext(owner: String, messageId: String, content: String) =
        holder.requireOwnedBy(owner).messageDao().setPlaintext(messageId, content)

    suspend fun clearArchive(owner: String, messageId: String, state: String) =
        holder.requireOwnedBy(owner).messageDao().clearArchive(messageId, state)
}

// ------------------------------------------------------------- conversations

class ScopedConversationDao(private val holder: AccountCacheHolder) {

    private fun <T> scopedFlow(empty: T, block: suspend () -> Flow<T>): Flow<T> = flow {
        if (holder.current() == null) emitAll(flowOf(empty)) else emitAll(block())
    }

    // ---- reads
    fun getAllConversationsFlow(): Flow<List<ConversationEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.conversationDao().getAllConversationsFlow() }

    fun getConversationFlow(convId: String): Flow<ConversationEntity?> =
        scopedFlow(null) { holder.current()!!.conversationDao().getConversationFlow(convId) }

    fun getDirectConversationsFlow(): Flow<List<ConversationEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.conversationDao().getDirectConversationsFlow() }

    fun getGroupConversationsFlow(): Flow<List<ConversationEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.conversationDao().getGroupConversationsFlow() }

    fun getUpdatedConversations(since: Long): Flow<List<ConversationEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.conversationDao().getUpdatedConversations(since) }

    suspend fun getAllConversations(): List<ConversationEntity> =
        holder.current()?.conversationDao()?.getAllConversations() ?: emptyList()

    suspend fun getConversationById(convId: String): ConversationEntity? =
        holder.current()?.conversationDao()?.getConversationById(convId)

    // ---- writes (owner captured at operation start)
    suspend fun insertConversation(owner: String, conversation: ConversationEntity) =
        holder.requireOwnedBy(owner).conversationDao().insertConversation(conversation)

    suspend fun insertConversations(owner: String, conversations: List<ConversationEntity>) =
        holder.requireOwnedBy(owner).conversationDao().insertConversations(conversations)

    suspend fun updateConversation(owner: String, conversation: ConversationEntity) =
        holder.requireOwnedBy(owner).conversationDao().updateConversation(conversation)

    suspend fun updateConversationMeta(
        owner: String, convId: String, type: String, name: String?, avatarUrl: String?
    ) = holder.requireOwnedBy(owner).conversationDao()
        .updateConversationMeta(convId, type, name, avatarUrl)

    suspend fun deleteConversation(owner: String, convId: String) =
        holder.requireOwnedBy(owner).conversationDao().deleteConversation(convId)

    suspend fun clearUnreadCount(owner: String, convId: String) =
        holder.requireOwnedBy(owner).conversationDao().clearUnreadCount(convId)

    suspend fun toggleMute(owner: String, convId: String, isMuted: Boolean) =
        holder.requireOwnedBy(owner).conversationDao().toggleMute(convId, isMuted)

    suspend fun toggleArchive(owner: String, convId: String, isArchived: Boolean) =
        holder.requireOwnedBy(owner).conversationDao().toggleArchive(convId, isArchived)
}

// -------------------------------------------------------------- cached chats

class ScopedCachedChatDao(private val holder: AccountCacheHolder) {

    /**
     * The chat list. This is the query that leaked most visibly before Gate 19:
     * it has no predicate at all, so it returned every cached chat-list entry on
     * the device regardless of who wrote it.
     */
    suspend fun getAllCached(): List<CachedChatEntity> =
        holder.current()?.cachedChatDao()?.getAllCached() ?: emptyList()

    suspend fun insertAll(owner: String, chats: List<CachedChatEntity>) =
        holder.requireOwnedBy(owner).cachedChatDao().insertAll(chats)

    suspend fun deleteCached(owner: String, chatId: String) =
        holder.requireOwnedBy(owner).cachedChatDao().deleteCached(chatId)
}

// --------------------------------------------------------------------- users

class ScopedUserDao(private val holder: AccountCacheHolder) {

    private fun <T> scopedFlow(empty: T, block: suspend () -> Flow<T>): Flow<T> = flow {
        if (holder.current() == null) emitAll(flowOf(empty)) else emitAll(block())
    }

    // ---- reads
    fun getUserFlow(userId: String): Flow<UserEntity?> =
        scopedFlow(null) { holder.current()!!.userDao().getUserFlow(userId) }

    fun searchUsers(query: String): Flow<List<UserEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.userDao().searchUsers(query) }

    fun getOnlineUsersFlow(): Flow<List<UserEntity>> =
        scopedFlow(emptyList()) { holder.current()!!.userDao().getOnlineUsersFlow() }

    suspend fun getUserById(userId: String): UserEntity? =
        holder.current()?.userDao()?.getUserById(userId)

    suspend fun getUserByEmail(email: String): UserEntity? =
        holder.current()?.userDao()?.getUserByEmail(email)

    suspend fun getUserByUsername(username: String): UserEntity? =
        holder.current()?.userDao()?.getUserByUsername(username)

    // ---- writes (owner captured at operation start)
    suspend fun insertUser(owner: String, user: UserEntity) =
        holder.requireOwnedBy(owner).userDao().insertUser(user)

    suspend fun insertUsers(owner: String, users: List<UserEntity>) =
        holder.requireOwnedBy(owner).userDao().insertUsers(users)

    suspend fun updateUser(owner: String, user: UserEntity) =
        holder.requireOwnedBy(owner).userDao().updateUser(user)

    suspend fun updateUserStatus(owner: String, userId: String, status: String, lastSeen: Long?) =
        holder.requireOwnedBy(owner).userDao().updateUserStatus(userId, status, lastSeen)

    suspend fun deleteUser(owner: String, userId: String) =
        holder.requireOwnedBy(owner).userDao().deleteUser(userId)
}

// -------------------------------------------------------------------- outbox

/**
 * The durable outbox (Phase 71), under the same ownership rules as every other facade here: an
 * outgoing message belongs to the account that composed it, and a write captured under one account
 * is refused - not redirected - once another account is signed in.
 */
class ScopedOutboxDao(private val holder: AccountCacheHolder) {

    // ---- reads
    suspend fun get(clientMessageId: String): OutboxEntity? =
        holder.current()?.outboxDao()?.get(clientMessageId)

    suspend fun due(now: Long): List<OutboxEntity> =
        holder.current()?.outboxDao()?.due(now) ?: emptyList()

    suspend fun unacceptedForChat(chatId: String): List<OutboxEntity> =
        holder.current()?.outboxDao()?.unacceptedForChat(chatId) ?: emptyList()

    suspend fun earliestPendingAt(): Long? =
        holder.current()?.outboxDao()?.earliestPendingAt()

    // ---- writes (owner captured at operation start)
    suspend fun insert(owner: String, item: OutboxEntity) =
        holder.requireOwnedBy(owner).outboxDao().insert(item)

    suspend fun markAccepted(owner: String, clientMessageId: String, serverId: String, at: Long) =
        holder.requireOwnedBy(owner).outboxDao().markAccepted(clientMessageId, serverId, at)

    suspend fun markRetry(owner: String, clientMessageId: String, attempts: Int, nextAttemptAt: Long, error: String) =
        holder.requireOwnedBy(owner).outboxDao().markRetry(clientMessageId, attempts, nextAttemptAt, error)

    suspend fun markFailed(owner: String, clientMessageId: String, attempts: Int, error: String) =
        holder.requireOwnedBy(owner).outboxDao().markFailed(clientMessageId, attempts, error)

    suspend fun resetForRetry(owner: String, clientMessageId: String, now: Long): Int =
        holder.requireOwnedBy(owner).outboxDao().resetForRetry(clientMessageId, now)

    suspend fun pruneAccepted(owner: String, olderThan: Long) =
        holder.requireOwnedBy(owner).outboxDao().pruneAccepted(olderThan)
}

// ---------------------------------------------------------------- sync state

/** Per-chat sync points (Phase 71). Same ownership rules as above. */
class ScopedChatSyncStateDao(private val holder: AccountCacheHolder) {

    suspend fun get(chatId: String): ChatSyncStateEntity? =
        holder.current()?.chatSyncStateDao()?.get(chatId)

    suspend fun upsert(owner: String, state: ChatSyncStateEntity) =
        holder.requireOwnedBy(owner).chatSyncStateDao().upsert(state)
}
