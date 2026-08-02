package com.messenger.app.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.model.ChatListItemDto
import com.messenger.app.data.model.MessageDto
import com.messenger.app.data.model.UserSearchResult
import com.messenger.app.data.repository.AttachmentRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.SessionExpiredException
import com.messenger.app.data.repository.VoiceRepository
import com.messenger.app.data.voice.VoicePlaybackState
import com.messenger.app.data.voice.VoicePlayer
import com.messenger.app.data.voice.VoiceRecorder
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
    val isRead: Boolean = false,
    /** "message" for a normal bubble, "system" for a centered notice (e.g. security code changed). */
    val messageKind: String = "message",
    /** Non-null for voice notes; the bubble renders a player instead of text. */
    val voiceUrl: String? = null,
    val voiceDurationMs: Long = 0,
    val voiceEncrypted: Boolean = false,
    /** Non-null for a file/image attachment; the bubble renders a preview/file row instead of text. */
    val attachmentUrl: String? = null,
    val attachmentName: String = "",
    val attachmentSize: Long = 0,
    val attachmentEncrypted: Boolean = false,
    val isImageAttachment: Boolean = false
) {
    val isVoice: Boolean get() = !voiceUrl.isNullOrBlank()
    val isAttachment: Boolean get() = !attachmentUrl.isNullOrBlank()
    val isSystem: Boolean get() = messageKind == "system"
}

data class ChatUiState(
    val chatId: String? = null,
    val chatName: String = "",
    val chatAvatarUrl: String? = null,
    val chatType: String = "direct",
    /** The other participant's user id - only meaningful for a direct chat; empty for a group. Needed to place a call. */
    val otherUserId: String = "",
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
    val isGroup: Boolean = false,
    /** Local-only: mute lives in the Room row, not on the server. */
    val isMuted: Boolean = false,
    /** Group picture for groups, the other person's picture for direct chats. */
    val avatarUrl: String? = null
)

/** Live state while the mic is open. */
data class RecordingUiState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0,
    val amplitude: Float = 0f
)

data class ChatListUiState(
    val chats: List<ChatListItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val voiceRepository: VoiceRepository,
    private val attachmentRepository: AttachmentRepository,
    private val voiceRecorder: VoiceRecorder,
    private val voicePlayer: VoicePlayer,
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

            // Mute is a local-only flag on the Room conversation row, so it has
            // to be merged in by hand - the server DTO knows nothing about it.
            val mutedIds = chatRepository.mutedChatIds()

            // Show cached chats immediately (works offline too), then refresh
            // from the network below - mirrors the windows_app cache-first
            // pattern so a cold start with no connectivity still shows something.
            val cached = chatRepository.loadCachedChats()
            if (cached.isNotEmpty()) {
                val cachedUiChats = cached.map { dto ->
                    val preview = chatRepository.decryptFor(
                        dto.id,
                        dto.lastMessage?.content ?: "",
                        dto.lastMessage?.encrypted ?: false,
                        dto.lastMessage?.senderId ?: "",
                        dto.lastMessage?.keyVersion ?: 0
                    )
                    toChatListItemUi(dto, preview, mutedIds)
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
                            dto.lastMessage?.encrypted ?: false,
                            dto.lastMessage?.senderId ?: "",
                            dto.lastMessage?.keyVersion ?: 0
                        )
                        toChatListItemUi(dto, preview, mutedIds)
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

    private fun toChatListItemUi(
        dto: ChatListItemDto,
        lastMessage: String,
        mutedIds: Set<String> = emptySet()
    ): ChatListItemUi {
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
            isGroup = isGroup,
            isMuted = mutedIds.contains(dto.id),
            avatarUrl = if (isGroup) dto.avatarUrl else dto.otherUser?.avatarUrl
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

                val isVoice = incoming.contentType == ChatRepository.VOICE_CONTENT_TYPE && !incoming.fileUrl.isNullOrBlank()
                val isAttachment = !isVoice &&
                    (incoming.contentType == ChatRepository.IMAGE_CONTENT_TYPE || incoming.contentType == ChatRepository.FILE_CONTENT_TYPE) &&
                    !incoming.fileUrl.isNullOrBlank()

                var text = if (isVoice || isAttachment) {
                    ""
                } else {
                    chatRepository.decryptFor(incoming.chatId, incoming.content, incoming.encrypted, incoming.senderId, incoming.keyVersion)
                }
                // A group message can arrive for a Sender Key we haven't fetched yet
                // (e.g. it rotated after we last synced) - one retry after a refetch
                // covers that without hammering the server on every message.
                if (!isVoice && !isAttachment && incoming.encrypted && text == ChatRepository.ENCRYPTED_PLACEHOLDER &&
                    chatRepository.chatTypeFor(incoming.chatId).equals("group", ignoreCase = true)
                ) {
                    tokenManager.getAccessToken().getOrNull()?.let { token ->
                        chatRepository.fetchGroupSenderKeys(token, incoming.chatId)
                        text = chatRepository.decryptFor(incoming.chatId, incoming.content, incoming.encrypted, incoming.senderId, incoming.keyVersion)
                    }
                }

                val state = _chatState.value
                if (incoming.chatId == state.chatId) {
                    // Being viewed live - append directly instead of just bumping the badge.
                    val message = ChatMessageUi(
                        id = incoming.messageId,
                        senderId = incoming.senderId,
                        senderName = state.chatName,
                        content = text,
                        timestamp = System.currentTimeMillis(),
                        isMine = false,
                        voiceUrl = if (isVoice) incoming.fileUrl else null,
                        voiceDurationMs = incoming.durationMs,
                        voiceEncrypted = isVoice && incoming.encrypted,
                        attachmentUrl = if (isAttachment) incoming.fileUrl else null,
                        attachmentName = if (isAttachment) incoming.fileName ?: "" else "",
                        attachmentSize = incoming.fileSize,
                        attachmentEncrypted = isAttachment && incoming.encrypted,
                        isImageAttachment = isAttachment && incoming.contentType == ChatRepository.IMAGE_CONTENT_TYPE
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

    fun openChat(chatId: String, chatName: String, isGroupHint: Boolean = false) {
        // Deliberately not leaving the previous chat's room here: loadChats()
        // joins every known chat's room up front precisely so notifications and
        // live badge/preview updates keep arriving for chats that aren't the
        // one currently open. Leaving on switch would undo that the moment you
        // navigate away from a chat.
        // isGroupHint (from the nav route) sets chatType immediately, so a
        // freshly created group's very first message - opened before the chat
        // list cache even has this chat yet - still picks Sender Key
        // encryption instead of silently falling back to a pairwise scheme
        // that has no key for a group and would send in the clear.
        val initialType = if (isGroupHint) "group" else "direct"
        _chatState.update { ChatUiState(chatId = chatId, chatName = chatName, chatType = initialType) }
        chatRepository.joinChatRoom(chatId)

        viewModelScope.launch {
            val myId = resolveCurrentUserId()

            // Show cached history immediately (works offline too), then refresh
            // from the network below - mirrors the windows_app cache-first
            // pattern for message history.
            // Header picture: look it up locally instead of passing an encoded
            // URL through the nav route.
            var isGroupChat = isGroupHint
            runCatching {
                chatRepository.loadCachedChats().firstOrNull { it.id == chatId }
            }.getOrNull()?.let { dto ->
                isGroupChat = dto.type.equals("group", true)
                val url = if (isGroupChat) dto.avatarUrl else dto.otherUser?.avatarUrl
                val otherId = if (isGroupChat) "" else (dto.otherUser?.id ?: dto.otherUserId ?: "")
                if (_chatState.value.chatId == chatId) {
                    _chatState.update {
                        it.copy(chatAvatarUrl = url, chatType = dto.type.ifBlank { "direct" }, otherUserId = otherId)
                    }
                }
            }

            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(error = "Not signed in") }
                return@launch
            }

            // A group's history can't be decrypted until we've fetched every
            // member's Sender Key - do that before touching any group message.
            if (isGroupChat) {
                chatRepository.fetchGroupSenderKeys(token, chatId)
            }

            // A direct chat's security code may have changed since we last saw
            // it (learned while loading the chat list) - surface it as a
            // system message the first time this chat is opened afterwards.
            if (chatRepository.takePendingSecurityNotice(chatId)) {
                addSecurityNoticeMessage(chatId)
            }

            val cachedMessages = chatRepository.loadCachedMessages(chatId)
            if (cachedMessages.isNotEmpty()) {
                val cachedHistory = cachedMessages.asReversed().map { dto ->
                    toChatMessageUi(chatId, dto, myId)
                }
                if (_chatState.value.chatId == chatId) {
                    _chatState.update { it.copy(messages = cachedHistory) }
                }
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

    /** Appends a small centered system notice - doesn't touch cache/server, display-only. */
    private fun addSecurityNoticeMessage(chatId: String) {
        if (_chatState.value.chatId != chatId) return
        val notice = ChatMessageUi(
            id = "system-${System.currentTimeMillis()}",
            senderId = "",
            senderName = "",
            content = "🔒 Your security code with ${_chatState.value.chatName} changed.",
            timestamp = System.currentTimeMillis(),
            isMine = false,
            messageKind = "system"
        )
        _chatState.update { it.copy(messages = it.messages + notice) }
    }

    private suspend fun toChatMessageUi(chatId: String, dto: MessageDto, myId: String): ChatMessageUi {
        val isMine = dto.senderId == myId
        val isVoice = dto.fileType == ChatRepository.VOICE_CONTENT_TYPE && !dto.fileUrl.isNullOrBlank()
        val isAttachment = !isVoice &&
            (dto.fileType == ChatRepository.IMAGE_CONTENT_TYPE || dto.fileType == ChatRepository.FILE_CONTENT_TYPE) &&
            !dto.fileUrl.isNullOrBlank()
        return ChatMessageUi(
            id = dto.id,
            senderId = dto.senderId,
            senderName = dto.sender?.displayName?.takeIf { it.isNotBlank() }
                ?: dto.sender?.username ?: "",
            // A voice note/attachment has no text body; decrypting the empty
            // content would just yield the "encrypted" placeholder.
            content = if (isVoice || isAttachment) "" else {
                chatRepository.decryptFor(chatId, dto.content, dto.encrypted, dto.senderId, dto.keyVersion)
            },
            timestamp = parseMessageTimestamp(dto.createdAt),
            isMine = isMine,
            isRead = isMine && !dto.readAt.isNullOrEmpty(),
            voiceUrl = if (isVoice) dto.fileUrl else null,
            voiceDurationMs = dto.durationMs,
            voiceEncrypted = isVoice && dto.encrypted,
            attachmentUrl = if (isAttachment) dto.fileUrl else null,
            attachmentName = if (isAttachment) dto.fileName ?: "" else "",
            attachmentSize = dto.fileSize,
            attachmentEncrypted = isAttachment && dto.encrypted,
            isImageAttachment = isAttachment && dto.fileType == ChatRepository.IMAGE_CONTENT_TYPE
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

            chatRepository.sendMessage(token = token, chatId = chatId, chatType = _chatState.value.chatType, plaintext = content)
                .onSuccess {
                    _chatState.update { it.copy(isSending = false) }
                }
                .onFailure { e ->
                    Log.e(TAG, "sendMessage failed", e)
                    _chatState.update { it.copy(isSending = false, error = e.message ?: "Failed to send") }
                }
        }
    }

    /**
     * Flips a chat's mute flag (long-press menu). Mute is local-only - it lives
     * in the Room conversation row, so nothing is sent to the server and the
     * new value is reflected in the list right away.
     */
    fun toggleMute(chatId: String) {
        viewModelScope.launch {
            val muted = _chatListState.value.chats.firstOrNull { it.id == chatId }?.isMuted ?: false
            chatRepository.setChatMuted(chatId, !muted)
            _chatListState.update { state ->
                state.copy(chats = state.chats.map { if (it.id == chatId) it.copy(isMuted = !muted) else it })
            }
        }
    }

    /** Clears a chat's unread badge without opening it (long-press menu). */
    fun markChatRead(chatId: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            chatRepository.markAsRead(token, chatId)
                .onSuccess {
                    _chatListState.update { state ->
                        state.copy(chats = state.chats.map { if (it.id == chatId) it.copy(unreadCount = 0) else it })
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "markChatRead failed", e)
                    if (e is SessionExpiredException) _sessionExpired.value = true
                }
        }
    }

    /**
     * Removes a chat from this user's list (long-press on a chat row).
     *
     * The row is dropped from the list immediately on success rather than
     * waiting for a refetch, so the gesture feels like it took effect.
     */
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatListState.update { it.copy(error = "Not signed in") }
                return@launch
            }
            chatRepository.deleteChat(token, chatId)
                .onSuccess {
                    _chatListState.update { state ->
                        state.copy(chats = state.chats.filterNot { it.id == chatId })
                    }
                    // Close the thread if it is the one being removed.
                    if (_chatState.value.chatId == chatId) {
                        _chatState.update { ChatUiState() }
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "deleteChat failed", e)
                    if (e is SessionExpiredException) _sessionExpired.value = true
                    _chatListState.update { it.copy(error = e.message ?: "Failed to delete chat") }
                }
        }
    }

    // ==================== Attachments ====================

    /** Reads, encrypts (when possible), uploads and posts a picked file/image. */
    fun sendAttachment(uri: Uri) {
        val chatId = _chatState.value.chatId ?: return

        viewModelScope.launch {
            _chatState.update { it.copy(isSending = true) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(isSending = false, error = "Not signed in") }
                return@launch
            }

            val picked = attachmentRepository.readPickedFile(uri).getOrElse { e ->
                _chatState.update { it.copy(isSending = false, error = e.message ?: "Could not read file") }
                return@launch
            }
            val contentType = attachmentRepository.classify(picked.name)

            attachmentRepository.upload(token, chatId, picked)
                .onSuccess { uploaded ->
                    chatRepository.sendAttachmentMessage(
                        token = token,
                        chatId = chatId,
                        chatType = _chatState.value.chatType,
                        fileUrl = uploaded.fileUrl,
                        fileName = picked.name,
                        fileSize = uploaded.fileSize,
                        contentType = contentType,
                        encrypted = uploaded.encrypted
                    )
                        .onSuccess {
                            _chatState.update { it.copy(isSending = false) }
                            openChat(chatId, _chatState.value.chatName)
                        }
                        .onFailure { e ->
                            _chatState.update {
                                it.copy(isSending = false, error = e.message ?: "Failed to send")
                            }
                        }
                }
                .onFailure { e ->
                    Log.e(TAG, "attachment upload failed", e)
                    _chatState.update {
                        it.copy(isSending = false, error = e.message ?: "Failed to send attachment")
                    }
                }
        }
    }

    /** Downloads (if needed) and decrypts an attachment for viewing/opening. Suspends - call from a coroutine scope. */
    suspend fun fetchAttachmentFile(message: ChatMessageUi): File? {
        val chatId = _chatState.value.chatId ?: return null
        val url = message.attachmentUrl ?: return null
        return attachmentRepository.fetchForView(
            chatId, message.id, url, message.attachmentName, message.attachmentEncrypted
        ).getOrNull()
    }

    // ==================== Voice notes ====================

    private val _recording = MutableStateFlow(RecordingUiState())
    val recording: StateFlow<RecordingUiState> = _recording.asStateFlow()

    val playback: StateFlow<VoicePlaybackState> get() = voicePlayer.state

    private var tickJob: Job? = null

    /** Begins recording. The caller must already hold RECORD_AUDIO. */
    fun startRecording() {
        if (_chatState.value.chatId == null) return
        if (!voiceRecorder.start()) {
            _chatState.update { it.copy(error = "Could not start recording") }
            return
        }
        _recording.value = RecordingUiState(isRecording = true)
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (voiceRecorder.isRecording) {
                _recording.update {
                    it.copy(
                        elapsedMs = voiceRecorder.elapsedMs(),
                        amplitude = voiceRecorder.amplitude()
                    )
                }
                delay(100)
            }
        }
    }

    /** Discards the recording without sending. */
    fun cancelRecording() {
        tickJob?.cancel()
        voiceRecorder.cancel()
        _recording.value = RecordingUiState()
    }

    /** Stops, encrypts, uploads and posts the voice note. */
    fun stopRecordingAndSend() {
        tickJob?.cancel()
        val chatId = _chatState.value.chatId
        val result = voiceRecorder.stop()
        _recording.value = RecordingUiState()

        if (chatId == null) return
        if (result == null) {
            _chatState.update { it.copy(error = "Hold to record - that was too short") }
            return
        }

        viewModelScope.launch {
            _chatState.update { it.copy(isSending = true) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(isSending = false, error = "Not signed in") }
                return@launch
            }

            voiceRepository.upload(token, chatId, result.file)
                .onSuccess { uploaded ->
                    chatRepository.sendVoiceMessage(
                        token = token,
                        chatId = chatId,
                        // The server derives the real type from the chat row, but
                        // don't claim "direct" for a group either.
                        chatType = _chatState.value.chatType,
                        fileUrl = uploaded.fileUrl,
                        durationMs = result.durationMs,
                        encrypted = uploaded.encrypted
                    )
                        .onSuccess {
                            _chatState.update { it.copy(isSending = false) }
                            openChat(chatId, _chatState.value.chatName)
                        }
                        .onFailure { e ->
                            _chatState.update {
                                it.copy(isSending = false, error = e.message ?: "Failed to send")
                            }
                        }
                }
                .onFailure { e ->
                    Log.e(TAG, "voice upload failed", e)
                    _chatState.update {
                        it.copy(isSending = false, error = e.message ?: "Failed to send voice note")
                    }
                }
        }
    }

    /** Play/pause a voice note, downloading and decrypting it on first play. */
    fun toggleVoicePlayback(message: ChatMessageUi) {
        val chatId = _chatState.value.chatId ?: return
        val url = message.voiceUrl ?: return

        // Already the active note - just toggle, no refetch.
        if (voicePlayer.state.value.messageId == message.id) {
            voicePlayer.toggle(message.id, java.io.File(""))
            return
        }

        viewModelScope.launch {
            voiceRepository.fetchForPlayback(chatId, message.id, url, message.voiceEncrypted)
                .onSuccess { file ->
                    voicePlayer.toggle(message.id, file)
                    startPlaybackTicker()
                }
                .onFailure { e ->
                    _chatState.update {
                        it.copy(error = e.message ?: "Could not play voice note")
                    }
                }
        }
    }

    private var playbackTicker: Job? = null

    private fun startPlaybackTicker() {
        playbackTicker?.cancel()
        playbackTicker = viewModelScope.launch {
            while (voicePlayer.state.value.isPlaying) {
                voicePlayer.syncPosition()
                delay(200)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        playbackTicker?.cancel()
        voiceRecorder.cancel()
        voicePlayer.stop()
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
