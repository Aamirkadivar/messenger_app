package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.model.ChatListItemDto
import com.messenger.app.data.model.MessageDto
import com.messenger.app.data.model.UserSearchResult
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.SessionExpiredException
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

/**
 * UI representation of a chat message.
 */
data class ChatMessageUi(
    val id: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long,
    val isMine: Boolean,
    val isRead: Boolean = false
)

data class ChatUiState(
    val chatId: String? = null,
    val chatName: String = "",
    val messages: List<ChatMessageUi> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null
)

data class UserSearchUiState(
    val query: String = "",
    val results: List<UserSearchResult> = emptyList(),
    val isSearching: Boolean = false
)

data class ChatListItemUi(
    val id: String,
    val name: String,
    val otherUserId: String,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Long,
    val isOnline: Boolean,
    val isGroup: Boolean = false
)

data class ChatListUiState(
    val chats: List<ChatListItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    /** Returns the signed-in user's id, loading it from TokenManager on first use. */
    private suspend fun resolveCurrentUserId(): String {
        _currentUserId.value?.let { return it }
        val id = tokenManager.getCurrentUserId().getOrNull() ?: ""
        _currentUserId.value = id
        return id
    }

    private val _chatState = MutableStateFlow(ChatUiState())
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private val _userSearchState = MutableStateFlow(UserSearchUiState())
    val userSearchState: StateFlow<UserSearchUiState> = _userSearchState.asStateFlow()

    private val _chatListState = MutableStateFlow(ChatListUiState())
    val chatListState: StateFlow<ChatListUiState> = _chatListState.asStateFlow()

    /**
     * Set when the server rejects our token. The chat list observes this and
     * sends the user to login rather than sitting on a list that can never
     * refresh - previously an expired session just showed stale cached chats
     * forever with no way back to the login screen.
     */
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * True when this device took over the account's E2EE identity key from
     * another device, so messages predating this sign-in can't be decrypted
     * here. Surfaced so the user gets an explanation rather than a list of
     * "Encrypted message" placeholders.
     */
    private val _keyTakeover = MutableStateFlow(false)
    val keyTakeover: StateFlow<Boolean> = _keyTakeover.asStateFlow()

    fun dismissKeyTakeover() { _keyTakeover.value = false }

    fun loadChats() {
        viewModelScope.launch {
            _chatListState.update { it.copy(isLoading = true, error = null) }

            // Show cached chats immediately (works offline too), then refresh
            // from the network below - mirrors the windows_app cache-first
            // pattern so a cold start with no connectivity still shows something.
            val cached = chatRepository.loadCachedChats()
            if (cached.isNotEmpty()) {
                val cachedUiChats = cached.map { dto ->
                    val preview = chatRepository.decryptFor(
                        dto.id,
                        dto.lastMessage?.content ?: "",
                        dto.lastMessage?.encrypted ?: false
                    )
                    toChatListItemUi(dto, preview)
                }
                _chatListState.update { it.copy(chats = cachedUiChats) }
            }

            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatListState.update { it.copy(isLoading = false, error = "Not signed in") }
                return@launch
            }
            // Make sure our public key is published and our private key cached
            // before we try to decrypt any message previews.
            chatRepository.ensureKeysPublished(token).onSuccess { status ->
                if (status is ChatRepository.KeyStatus.ReplacedAnotherDevicesKey) {
                    _keyTakeover.value = true
                }
            }
            chatRepository.getChats(token)
                .onSuccess { chats ->
                    val uiChats = chats.map { dto ->
                        val preview = chatRepository.decryptFor(
                            dto.id,
                            dto.lastMessage?.content ?: "",
                            dto.lastMessage?.encrypted ?: false
                        )
                        toChatListItemUi(dto, preview)
                    }
                    _chatListState.update { it.copy(isLoading = false, chats = uiChats) }
                    // Join every known chat's WebSocket room so real-time pushes (and
                    // notifications) arrive even for conversations that aren't
                    // currently open - joining only happens on-demand otherwise
                    // (see openChat()).
                    chats.forEach { chatRepository.joinChatRoom(it.id) }
                }
                .onFailure { e ->
                    Log.e(TAG, "getChats failed", e)
                    if (e is SessionExpiredException) {
                        _sessionExpired.value = true
                    }
                    _chatListState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load chats") }
                }
        }
    }

    private fun toChatListItemUi(dto: ChatListItemDto, lastMessage: String): ChatListItemUi {
        // A group is named by the group, not by whichever member the server
        // happened to put in other_user - for group chats the backend fills that
        // field with an arbitrary participant, so preferring it here labelled
        // groups with a random member's name.
        val isGroup = dto.type.equals("group", ignoreCase = true)
        val name = if (isGroup) {
            dto.name.takeIf { it.isNotBlank() } ?: "Group"
        } else {
            dto.otherUser?.displayName?.takeIf { it.isNotBlank() }
                ?: dto.otherUser?.username
                ?: dto.name.takeIf { it.isNotBlank() }
                ?: "Unknown"
        }
        return ChatListItemUi(
            id = dto.id,
            name = name,
            otherUserId = dto.otherUser?.id ?: dto.otherUserId ?: "",
            lastMessage = lastMessage,
            timestamp = formatChatTimestamp(dto.lastMessageAt ?: dto.updatedAt),
            unreadCount = dto.unreadCount,
            // A group has no presence of its own; showing other_user's dot would
            // report one arbitrary member as "the group being online".
            isOnline = if (isGroup) false else (dto.otherUser?.isOnline ?: dto.isOnline),
            isGroup = isGroup
        )
    }

    private fun formatChatTimestamp(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val dateTime = OffsetDateTime.parse(iso)
            dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))
        } catch (e: DateTimeParseException) {
            ""
        }
    }

    init {
        viewModelScope.launch {
            resolveCurrentUserId()
            // Ensure this device has an E2EE keypair and its public key is published.
            tokenManager.getAccessToken().getOrNull()?.let { chatRepository.ensureKeysPublished(it) }
        }

        // Establish (or reuse) the real-time connection and listen for messages
        // pushed to whichever chat room is currently joined.
        chatRepository.connectRealtime()

        chatRepository.incomingMessages
            .onEach { incoming ->
                val myId = resolveCurrentUserId()
                if (incoming.senderId == myId) return@onEach // our own echo, already shown optimistically

                val text = chatRepository.decryptFor(incoming.chatId, incoming.content, incoming.encrypted)

                val state = _chatState.value
                if (incoming.chatId == state.chatId) {
                    // Being viewed live - append directly instead of just bumping the badge.
                    val message = ChatMessageUi(
                        id = incoming.messageId,
                        senderId = incoming.senderId,
                        senderName = state.chatName,
                        content = text,
                        timestamp = System.currentTimeMillis(),
                        isMine = false
                    )
                    _chatState.update { it.copy(messages = it.messages + message) }
                } else {
                    // Not currently open - live-update the list's preview/badge so it
                    // doesn't just sit stale until the next full REST refetch.
                    _chatListState.update { listState ->
                        val idx = listState.chats.indexOfFirst { it.id == incoming.chatId }
                        if (idx == -1) return@update listState
                        val updated = listState.chats.toMutableList()
                        val item = updated[idx]
                        updated[idx] = item.copy(
                            lastMessage = text,
                            unreadCount = item.unreadCount + 1
                        )
                        listState.copy(chats = updated)
                    }
                }
            }
            .launchIn(viewModelScope)

        // Live "seen" updates: when the other participant reads this chat while
        // we have it open, flip every message we sent over to the seen state.
        chatRepository.readReceipts
            .onEach { receipt ->
                val myId = resolveCurrentUserId()
                if (receipt.readerId == myId) return@onEach
                val state = _chatState.value
                if (receipt.chatId != state.chatId) return@onEach
                _chatState.update {
                    it.copy(messages = it.messages.map { m -> if (m.isMine) m.copy(isRead = true) else m })
                }
            }
            .launchIn(viewModelScope)

        // Live online/offline dot - without this it only ever reflected reality
        // after the next full chat-list reload.
        chatRepository.presenceUpdates
            .onEach { presence ->
                _chatListState.update { listState ->
                    val idx = listState.chats.indexOfFirst { it.otherUserId == presence.userId }
                    if (idx == -1) return@update listState
                    val updated = listState.chats.toMutableList()
                    updated[idx] = updated[idx].copy(isOnline = presence.isOnline)
                    listState.copy(chats = updated)
                }
            }
            .launchIn(viewModelScope)
    }

    fun openChat(chatId: String, chatName: String) {
        // Deliberately not leaving the previous chat's room here: loadChats()
        // joins every known chat's room up front precisely so notifications and
        // live badge/preview updates keep arriving for chats that aren't the
        // one currently open. Leaving on switch would undo that the moment you
        // navigate away from a chat.
        _chatState.update { ChatUiState(chatId = chatId, chatName = chatName) }
        chatRepository.joinChatRoom(chatId)

        viewModelScope.launch {
            val myId = resolveCurrentUserId()

            // Show cached history immediately (works offline too), then refresh
            // from the network below - mirrors the windows_app cache-first
            // pattern for message history.
            val cachedMessages = chatRepository.loadCachedMessages(chatId)
            if (cachedMessages.isNotEmpty()) {
                val cachedHistory = cachedMessages.asReversed().map { dto ->
                    toChatMessageUi(chatId, dto, myId)
                }
                if (_chatState.value.chatId == chatId) {
                    _chatState.update { it.copy(messages = cachedHistory) }
                }
            }

            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(error = "Not signed in") }
                return@launch
            }

            chatRepository.getMessages(token, chatId)
                .onSuccess { response ->
                    // Server returns newest-first; reverse so oldest is first for display
                    val history = response.data.asReversed().map { dto -> toChatMessageUi(chatId, dto, myId) }
                    if (_chatState.value.chatId == chatId) {
                        _chatState.update { it.copy(messages = history) }
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "getMessages failed", e)
                    _chatState.update { it.copy(error = e.message ?: "Failed to load messages") }
                }

            chatRepository.markAsRead(token, chatId)
        }
    }

    private suspend fun toChatMessageUi(chatId: String, dto: MessageDto, myId: String): ChatMessageUi {
        val isMine = dto.senderId == myId
        return ChatMessageUi(
            id = dto.id,
            senderId = dto.senderId,
            senderName = dto.sender?.displayName?.takeIf { it.isNotBlank() }
                ?: dto.sender?.username ?: "",
            content = chatRepository.decryptFor(chatId, dto.content, dto.encrypted),
            timestamp = parseMessageTimestamp(dto.createdAt),
            isMine = isMine,
            isRead = isMine && !dto.readAt.isNullOrEmpty()
        )
    }

    private fun parseMessageTimestamp(iso: String?): Long =
        try {
            if (iso.isNullOrBlank()) System.currentTimeMillis()
            else OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

    /**
     * Start (or resume) a direct chat with another user.
     */
    fun startDirectChat(contactId: String, contactName: String, onOpened: (chatId: String) -> Unit) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(error = "Not signed in") }
                return@launch
            }
            chatRepository.getOrCreateDirectChat(token, contactId)
                .onSuccess { chat ->
                    openChat(chat.id, contactName)
                    onOpened(chat.id)
                }
                .onFailure { e ->
                    Log.e(TAG, "startDirectChat failed", e)
                    _chatState.update { it.copy(error = e.message ?: "Failed to start chat") }
                }
        }
    }

    fun sendMessage(content: String) {
        val chatId = _chatState.value.chatId ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            _chatState.update { it.copy(isSending = true) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(isSending = false, error = "Not signed in") }
                return@launch
            }
            val myId = resolveCurrentUserId()

            // Shown immediately; the real-time echo from the server is filtered
            // out in the incomingMessages collector above (see resolveCurrentUserId).
            val optimistic = ChatMessageUi(
                id = "local-${System.currentTimeMillis()}",
                senderId = myId,
                senderName = "Me",
                content = content,
                timestamp = System.currentTimeMillis(),
                isMine = true
            )
            _chatState.update { it.copy(messages = it.messages + optimistic) }

            chatRepository.sendMessage(token = token, chatId = chatId, chatType = "direct", plaintext = content)
                .onSuccess {
                    _chatState.update { it.copy(isSending = false) }
                }
                .onFailure { e ->
                    Log.e(TAG, "sendMessage failed", e)
                    _chatState.update { it.copy(isSending = false, error = e.message ?: "Failed to send") }
                }
        }
    }

    fun setTypingStatus(isTyping: Boolean) {
        val chatId = _chatState.value.chatId ?: return
        viewModelScope.launch {
            chatRepository.sendTypingIndicator(chatId, resolveCurrentUserId(), isTyping)
        }
    }

    fun searchUsers(query: String) {
        _userSearchState.update { it.copy(query = query) }
        if (query.isBlank()) {
            _userSearchState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        viewModelScope.launch {
            _userSearchState.update { it.copy(isSearching = true) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _userSearchState.update { it.copy(isSearching = false) }
                return@launch
            }
            chatRepository.searchUsers(token, query)
                .onSuccess { results ->
                    _userSearchState.update { it.copy(results = results, isSearching = false) }
                }
                .onFailure {
                    _userSearchState.update { it.copy(isSearching = false) }
                }
        }
    }
}
