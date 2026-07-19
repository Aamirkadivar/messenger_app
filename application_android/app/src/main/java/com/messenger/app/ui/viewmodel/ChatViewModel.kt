package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.encryption.MessageEncryption
import com.messenger.app.data.local.entity.MessageEntity
import com.messenger.app.data.model.*
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.remote.websocket.WebSocketManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * UI state for a conversation
 */
data class ConversationUiState(
    val conversations: List<ConversationItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

/**
 * UI state for chat (messages)
 */
data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val isTyping: Boolean = false,
    val typingUser: String? = null,
    val error: String? = null,
    val isMessageSending: Boolean = false
)

/**
 * UI representation of a chat message
 */
data class ChatMessageUi(
    val id: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val type: String,
    val isSent: Boolean,
    val isDelivered: Boolean,
    val isRead: Boolean,
    val isDeleted: Boolean,
    val timestamp: Long,
    val reactions: List<Reaction> = emptyList(),
    val fileId: String? = null,
    val isEncrypted: Boolean = true
)

/**
 * UI representation of a conversation item
 */
data class ConversationItem(
    val id: String,
    val name: String,
    val type: String,
    val avatarUrl: String? = null,
    val lastMessage: String? = null,
    val lastMessageAt: Long? = null,
    val unreadCount: Int = 0,
    val participants: List<Participant> = emptyList(),
    val isGroup: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeen: Long? = null
)

/**
 * ViewModel for chat/messaging functionality
 */
class ChatViewModel(
    private val chatRepository: com.messenger.app.data.repository.ChatRepository,
    private val tokenManager: com.messenger.app.security.TokenManager,
    private val webSocketManager: WebSocketManager
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MESSAGES_PER_PAGE = 20
    }

    // Current user ID (set after login)
    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    fun setCurrentUserId(userId: String) {
        _currentUserId.value = userId
    }

    // ==================== Conversations ====================

    private val _conversationsState = MutableStateFlow(ConversationUiState())
    val conversationsState: StateFlow<ConversationUiState> = _conversationsState.asStateFlow()

    /**
     * Load conversations list
     */
    fun loadConversations(page: Int = 1) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.getConversations(token, page)
                        .onSuccess { response ->
                            val conversationItems = response.data.map { conv ->
                                ConversationItem(
                                    id = conv.id,
                                    name = conv.name,
                                    type = conv.type,
                                    avatarUrl = conv.avatarUrl,
                                    lastMessage = conv.lastMessage,
                                    lastMessageAt = conv.lastMessageAt,
                                    unreadCount = conv.unreadCount,
                                    participants = conv.participants ?: emptyList(),
                                    isGroup = conv.isGroup
                                )
                            }
                            _conversationsState.update {
                                it.copy(
                                    conversations = conversationItems,
                                    isLoading = false,
                                    hasMore = response.hasMore,
                                    error = null
                                )
                            }
                        }
                        .onFailure { e ->
                            _conversationsState.update {
                                it.copy(
                                    isLoading = false,
                                    error = e.message ?: "Failed to load conversations"
                                )
                            }
                        }
                },
                onFailure = { e ->
                    _conversationsState.update {
                        it.copy(
                            isLoading = false,
                            error = "Authentication required"
                        )
                    }
                }
            )
        }
    }

    /**
     * Load more conversations (pagination)
     */
    fun loadMoreConversations() {
        viewModelScope.launch {
            val currentState = _conversationsState.value
            if (currentState.isLoadingMore || !currentState.hasMore) return@launch

            _conversationsState.update { it.copy(isLoadingMore = true) }

            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    val nextPage = (currentState.conversations.size / 20) + 1
                    chatRepository.getConversations(token, nextPage)
                        .onSuccess { response ->
                            val newItems = response.data.map { conv ->
                                ConversationItem(
                                    id = conv.id,
                                    name = conv.name,
                                    type = conv.type,
                                    avatarUrl = conv.avatarUrl,
                                    lastMessage = conv.lastMessage,
                                    lastMessageAt = conv.lastMessageAt,
                                    unreadCount = conv.unreadCount,
                                    participants = conv.participants ?: emptyList(),
                                    isGroup = conv.isGroup
                                )
                            }
                            _conversationsState.update {
                                it.copy(
                                    conversations = it.conversations + newItems,
                                    isLoadingMore = false,
                                    hasMore = response.hasMore
                                )
                            }
                        }
                        .onFailure { e ->
                            _conversationsState.update {
                                it.copy(isLoadingMore = false)
                            }
                        }
                },
                onFailure = { }
            )
        }
    }

    // ==================== Chat / Messages ====================

    private val _chatState = MutableStateFlow<ChatUiState>(ChatUiState())
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    /**
     * Load messages for a conversation with pagination
     */
    fun loadMessages(conversationId: String, cursor: Long? = null) {
        viewModelScope.launch {
            _chatState.update { it.copy(isLoading = true, error = null) }

            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.getMessages(token, conversationId, MESSAGES_PER_PAGE, cursor)
                        .onSuccess { response ->
                            val chatMessages = response.data.map { msg ->
                                ChatMessageUi(
                                    id = msg.id,
                                    senderId = msg.senderId,
                                    senderName = "", // Will be resolved from participant list
                                    content = msg.encryptedContent,
                                    type = msg.type,
                                    isSent = msg.senderId == _currentUserId.value,
                                    isDelivered = msg.isDelivered,
                                    isRead = msg.isRead,
                                    isDeleted = msg.isDeleted,
                                    timestamp = msg.createdAt,
                                    reactions = emptyList(),
                                    fileId = msg.fileId,
                                    isEncrypted = true
                                )
                            }
                            _chatState.update {
                                it.copy(
                                    messages = chatMessages,
                                    isLoading = false,
                                    hasMore = response.hasMore,
                                    error = null
                                )
                            }
                        }
                        .onFailure { e ->
                            _chatState.update {
                                it.copy(
                                    isLoading = false,
                                    error = e.message ?: "Failed to load messages"
                                )
                            }
                        }
                },
                onFailure = {
                    _chatState.update {
                        it.copy(
                            isLoading = false,
                            error = "Authentication required"
                        )
                    }
                }
            )
        }
    }

    /**
     * Load more messages (older) - for infinite scroll up
     */
    fun loadMoreMessages(conversationId: String, oldestMessageId: String?) {
        viewModelScope.launch {
            if (oldestMessageId == null) return@launch

            val currentState = _chatState.value
            if (currentState.isLoadingMore || !currentState.hasMore) return@launch

            _chatState.update { it.copy(isLoadingMore = true) }

            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.getMessagesAround(token, conversationId, oldestMessageId, MESSAGES_PER_PAGE)
                        .onSuccess { messages ->
                            val newMessages = messages.map { msg ->
                                ChatMessageUi(
                                    id = msg.id,
                                    senderId = msg.senderId,
                                    senderName = "",
                                    content = msg.encryptedContent,
                                    type = msg.type,
                                    isSent = msg.senderId == _currentUserId.value,
                                    isDelivered = msg.isDelivered,
                                    isRead = msg.isRead,
                                    isDeleted = msg.isDeleted,
                                    timestamp = msg.createdAt,
                                    reactions = emptyList(),
                                    fileId = msg.fileId,
                                    isEncrypted = true
                                )
                            }
                            // Prepend older messages
                            _chatState.update {
                                it.copy(
                                    messages = newMessages + it.messages,
                                    isLoadingMore = false,
                                    hasMore = messages.size >= MESSAGES_PER_PAGE
                                )
                            }
                        }
                        .onFailure {
                            _chatState.update { it.copy(isLoadingMore = false) }
                        }
                },
                onFailure = {
                    _chatState.update { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

    /**
     * Send a message
     */
    fun sendMessage(
        conversationId: String,
        content: String,
        recipientId: String,
        type: String = "TEXT"
    ) {
        val currentUserId = _currentUserId.value ?: return
        viewModelScope.launch {
            _chatState.update { it.copy(isMessageSending = true) }

            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.sendMessage(
                        authToken = token,
                        conversationId = conversationId,
                        senderId = currentUserId,
                        recipientId = recipientId,
                        content = content,
                        type = type
                    )
                        .onSuccess { message ->
                            val chatMessage = ChatMessageUi(
                                id = message.id,
                                senderId = message.senderId,
                                senderName = "",
                                content = message.encryptedContent,
                                type = message.type,
                                isSent = true,
                                isDelivered = false,
                                isRead = false,
                                isDeleted = false,
                                timestamp = message.createdAt,
                                reactions = emptyList(),
                                fileId = message.fileId,
                                isEncrypted = true
                            )
                            _chatState.update {
                                it.copy(
                                    messages = it.messages + chatMessage,
                                    isMessageSending = false
                                )
                            }
                        }
                        .onFailure { e ->
                            _chatState.update {
                                it.copy(
                                    isMessageSending = false,
                                    error = e.message ?: "Failed to send message"
                                )
                            }
                        }
                },
                onFailure = {
                    _chatState.update {
                        it.copy(
                            isMessageSending = false,
                            error = "Authentication required"
                        )
                    }
                }
            )
        }
    }

    /**
     * Delete a message
     */
    fun deleteMessage(conversationId: String, messageId: String) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.deleteMessage(token, conversationId, messageId)
                        .onSuccess {
                            _chatState.update {
                                it.copy(
                                    messages = it.messages.map { msg ->
                                        if (msg.id == messageId) {
                                            msg.copy(content = "This message was deleted", isDeleted = true)
                                        } else msg
                                    }
                                )
                            }
                        }
                },
                onFailure = { }
            )
        }
    }

    /**
     * Mark message as read
     */
    fun markAsRead(conversationId: String, messageId: String) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.markAsRead(token, conversationId, messageId)
                },
                onFailure = { }
            )
        }
    }

    // ==================== Reactions ====================

    /**
     * Add reaction to message
     */
    fun addReaction(conversationId: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.addReaction(conversationId, messageId, emoji)
        }
    }

    /**
     * Remove reaction from message
     */
    fun removeReaction(conversationId: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.removeReaction(conversationId, messageId, emoji)
        }
    }

    // ==================== Typing Indicators ====================

    /**
     * Send typing indicator
     */
    fun setTypingStatus(conversationId: String, isTyping: Boolean) {
        webSocketManager.sendTypingIndicator(conversationId, isTyping)
    }

    // ==================== Presence ====================

    /**
     * Set user online status
     */
    fun setOnlineStatus(isOnline: Boolean) {
        webSocketManager.sendPresenceUpdate(isOnline)
    }

    // ==================== Conversation Management ====================

    /**
     * Get conversation details
     */
    fun getConversation(conversationId: String) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.getConversation(token, conversationId)
                        .onSuccess { response ->
                            // Update conversation in list
                        }
                },
                onFailure = { }
            )
        }
    }

    /**
     * Create a new group conversation
     */
    fun createGroupChat(
        name: String,
        participantIds: List<String>
    ) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    val request = CreateConversationRequest(
                        name = name,
                        type = "GROUP",
                        participantIds = participantIds,
                        isGroup = true
                    )
                    chatRepository.createConversation(token, request)
                        .onSuccess { response ->
                            // Navigate to new group chat
                        }
                },
                onFailure = { }
            )
        }
    }

    /**
     * Add member to group
     */
    fun addMemberToGroup(conversationId: String, userId: String) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.addMember(token, conversationId, userId)
                },
                onFailure = { }
            )
        }
    }

    /**
     * Remove member from group
     */
    fun removeMemberFromGroup(conversationId: String, userId: String) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.removeMember(token, conversationId, userId)
                },
                onFailure = { }
            )
        }
    }

    // ==================== User Search ====================

    /**
     * Search users for adding to groups
     */
    fun searchUsers(query: String, limit: Int = 20) {
        viewModelScope.launch {
            tokenManager.getAuthToken().fold(
                onSuccess = { token ->
                    chatRepository.searchUsers(token, query, limit)
                        .onSuccess { users ->
                            // Return users for UI display
                        }
                },
                onFailure = { }
            )
        }
    }

    // ==================== WebSocket Events ====================

    /**
     * Handle incoming WebSocket messages
     */
    fun handleWebSocketMessage(message: WebSocketMessage) {
        when (message.type) {
            "message" -> {
                // New message received
                val msg = message.payload
                val chatMessage = ChatMessageUi(
                    id = msg["messageId"]?.toString() ?: "",
                    senderId = msg["senderId"]?.toString() ?: "",
                    senderName = "",
                    content = msg["encryptedContent"]?.toString() ?: "",
                    type = msg["type"]?.toString() ?: "TEXT",
                    isSent = false,
                    isDelivered = false,
                    isRead = false,
                    isDeleted = false,
                    timestamp = (msg["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    reactions = emptyList(),
                    isEncrypted = true
                )
                _chatState.update {
                    it.copy(messages = it.messages + chatMessage)
                }
            }
            "typing" -> {
                val isTyping = msg["isTyping"] as? Boolean ?: false
                val userId = msg["userId"]?.toString() ?: ""
                _chatState.update {
                    it.copy(isTyping = isTyping, typingUser = userId)
                }
            }
            "presence" -> {
                val userId = msg["userId"]?.toString() ?: ""
                val status = msg["status"]?.toString() ?: "OFFLINE"
                // Update presence for user
            }
            "read_receipt" -> {
                val messageId = msg["messageId"]?.toString() ?: ""
                val readAt = (msg["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                // Update read status in messages
            }
            "reaction" -> {
                val messageId = msg["messageId"]?.toString() ?: ""
                val emoji = msg["emoji"]?.toString() ?: ""
                val action = msg["action"]?.toString() ?: "ADD"
                // Update reactions
            }
            else -> {
                Log.w(TAG, "Unknown WebSocket message type: ${message.type}")
            }
        }
    }

    // ==================== Cleanup ====================

    override fun onCleared() {
        super.onCleared()
        // Clean up resources
    }
}