package com.messenger.app.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.model.ChatListItemDto
import com.messenger.app.data.model.ForwardMeta
import com.messenger.app.data.model.MessageDto
import com.messenger.app.data.model.UserSearchResult
import com.messenger.app.data.repository.AttachmentRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.SessionExpiredException
import com.messenger.app.data.repository.VoiceRepository
import com.messenger.app.data.voice.VoicePlaybackState
import com.messenger.app.data.voice.VoicePlayer
import com.messenger.app.data.voice.VoiceRecorder
import com.messenger.app.data.repository.RoundVideoRepository
import com.messenger.app.data.roundvideo.RoundRecorderState
import com.messenger.app.data.roundvideo.RoundVideoPlayerPool
import com.messenger.app.data.roundvideo.RoundLens
import com.messenger.app.data.roundvideo.RoundVideoRecorder
import com.messenger.app.ui.components.CaptureMode
import com.messenger.app.ui.components.RoundVideoUiState
import androidx.media3.exoplayer.ExoPlayer
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val isImageAttachment: Boolean = false,
    /** Non-null for a Telegram-style round video; the bubble renders a circle. */
    val videoUrl: String? = null,
    val videoThumbnailUrl: String? = null,
    val videoDurationMs: Long = 0,
    val videoEncrypted: Boolean = false,
    /** Sender Key version for group media decrypt; 0 for direct / cleartext. */
    val keyVersion: Int = 0,
    val isForwarded: Boolean = false,
    val forwardedFromName: String = "",
    /** Parent message id when this bubble is a reply; resolve quote locally. */
    val replyToId: String = ""
) {
    val isVoice: Boolean get() = !voiceUrl.isNullOrBlank()
    val isAttachment: Boolean get() = !attachmentUrl.isNullOrBlank()
    val isVideoNote: Boolean get() = !videoUrl.isNullOrBlank()
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

/** Composer capture state: which mode, and how a press-and-hold is going. */
data class CaptureUiState(
    val mode: CaptureMode = CaptureMode.VOICE,
    /** True while the round-video overlay is up. */
    val videoActive: Boolean = false,
    val isLocked: Boolean = false,
    val dragX: Float = 0f,
    val dragY: Float = 0f,
    val willCancel: Boolean = false,
    val willLock: Boolean = false,
    /** Human-readable progress while a recorded video is compressed and uploaded. */
    val sendStatus: String? = null
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
    private val roundVideoRecorder: RoundVideoRecorder,
    private val roundVideoRepository: RoundVideoRepository,
    private val playerPool: RoundVideoPlayerPool,
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

        // A message retracted for everyone must vanish here too, rather than
        // sitting on screen until the next refetch - and the chat-list preview
        // has to follow, or deleting the last message leaves a stale snippet.
        chatRepository.deletedMessages
            .onEach { deleted ->
                chatRepository.removeCachedMessage(deleted.messageId)
                removeMessageLocally(deleted.messageId, deleted.chatId)
            }
            .launchIn(viewModelScope)

        chatRepository.incomingMessages
            .onEach { incoming ->
                val myId = resolveCurrentUserId()
                if (incoming.senderId == myId) return@onEach // our own echo, already shown optimistically

                val isVoice = incoming.contentType == ChatRepository.VOICE_CONTENT_TYPE && !incoming.fileUrl.isNullOrBlank()
                val isVideoNote = incoming.contentType == ChatRepository.VIDEO_NOTE_CONTENT_TYPE &&
                    !incoming.fileUrl.isNullOrBlank()
                val isAttachment = !isVoice && !isVideoNote &&
                    (incoming.contentType == ChatRepository.IMAGE_CONTENT_TYPE || incoming.contentType == ChatRepository.FILE_CONTENT_TYPE) &&
                    !incoming.fileUrl.isNullOrBlank()

                var text = if (isVoice || isAttachment || isVideoNote) {
                    ""
                } else {
                    chatRepository.decryptFor(incoming.chatId, incoming.content, incoming.encrypted, incoming.senderId, incoming.keyVersion)
                }
                // A group message can arrive for a Sender Key we haven't fetched yet
                // (e.g. it rotated after we last synced) - one retry after a refetch
                // covers that without hammering the server on every message.
                if (!isVoice && !isAttachment && !isVideoNote && incoming.encrypted && text == ChatRepository.ENCRYPTED_PLACEHOLDER &&
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
                        isImageAttachment = isAttachment && incoming.contentType == ChatRepository.IMAGE_CONTENT_TYPE,
                        videoUrl = if (isVideoNote) incoming.fileUrl else null,
                        videoDurationMs = if (isVideoNote) incoming.durationMs else 0,
                        videoEncrypted = isVideoNote && incoming.encrypted,
                        keyVersion = incoming.keyVersion,
                        isForwarded = incoming.isForwarded,
                        forwardedFromName = incoming.forwardedFromName,
                        replyToId = incoming.replyToId
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
        val isVideoNote = dto.fileType == ChatRepository.VIDEO_NOTE_CONTENT_TYPE &&
            !dto.fileUrl.isNullOrBlank()
        val isAttachment = !isVoice && !isVideoNote &&
            (dto.fileType == ChatRepository.IMAGE_CONTENT_TYPE || dto.fileType == ChatRepository.FILE_CONTENT_TYPE) &&
            !dto.fileUrl.isNullOrBlank()
        return ChatMessageUi(
            id = dto.id,
            senderId = dto.senderId,
            senderName = dto.sender?.displayName?.takeIf { it.isNotBlank() }
                ?: dto.sender?.username ?: "",
            // A voice note/attachment has no text body; decrypting the empty
            // content would just yield the "encrypted" placeholder.
            content = if (isVoice || isAttachment || isVideoNote) "" else {
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
            isImageAttachment = isAttachment && dto.fileType == ChatRepository.IMAGE_CONTENT_TYPE,
            videoUrl = if (isVideoNote) dto.fileUrl else null,
            videoThumbnailUrl = if (isVideoNote) dto.thumbnailUrl else null,
            videoDurationMs = if (isVideoNote) dto.durationMs else 0,
            videoEncrypted = isVideoNote && dto.encrypted,
            keyVersion = dto.keyVersion,
            isForwarded = dto.isForwarded,
            forwardedFromName = dto.forwardedFromName,
            replyToId = dto.replyToId.orEmpty()
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
            val replyToId = _pendingReply.value?.id.orEmpty()

            // Shown immediately; the real-time echo from the server is filtered
            // out in the incomingMessages collector above (see resolveCurrentUserId).
            val optimistic = ChatMessageUi(
                id = "local-${System.currentTimeMillis()}",
                senderId = myId,
                senderName = "Me",
                content = content,
                timestamp = System.currentTimeMillis(),
                isMine = true,
                replyToId = replyToId
            )
            _chatState.update { it.copy(messages = it.messages + optimistic) }
            clearReply()

            chatRepository.sendMessage(
                token = token,
                chatId = chatId,
                chatType = _chatState.value.chatType,
                plaintext = content,
                replyToId = replyToId
            )
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
                    val replyToId = _pendingReply.value?.id.orEmpty()
                    clearReply()
                    chatRepository.sendAttachmentMessage(
                        token = token,
                        chatId = chatId,
                        chatType = _chatState.value.chatType,
                        fileUrl = uploaded.fileUrl,
                        fileName = picked.name,
                        fileSize = uploaded.fileSize,
                        contentType = contentType,
                        encrypted = uploaded.encrypted,
                        keyVersion = uploaded.keyVersion,
                        replyToId = replyToId
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
            chatId, message.id, url, message.attachmentName, message.attachmentEncrypted,
            message.senderId, message.keyVersion
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
        // A new take always starts unlocked, whichever way the last one ended.
        _capture.update { it.copy(isLocked = false, dragX = 0f, dragY = 0f, willCancel = false, willLock = false) }
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

    /**
     * Slide-up-to-lock for a voice note: the mic stays open after the finger
     * lifts, and the composer button turns into Send.
     */
    fun lockVoiceRecording() {
        _capture.update {
            it.copy(isLocked = true, dragX = 0f, dragY = 0f, willLock = false, willCancel = false)
        }
    }

    /** Discards the recording without sending. */
    fun cancelRecording() {
        tickJob?.cancel()
        voiceRecorder.cancel()
        _recording.value = RecordingUiState()
        _capture.update { it.copy(isLocked = false) }
    }

    /** Stops, encrypts, uploads and posts the voice note. */
    fun stopRecordingAndSend() {
        tickJob?.cancel()
        val chatId = _chatState.value.chatId
        val result = voiceRecorder.stop()
        _recording.value = RecordingUiState()
        _capture.update { it.copy(isLocked = false) }

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
                    val replyToId = _pendingReply.value?.id.orEmpty()
                    clearReply()
                    chatRepository.sendVoiceMessage(
                        token = token,
                        chatId = chatId,
                        // The server derives the real type from the chat row, but
                        // don't claim "direct" for a group either.
                        chatType = _chatState.value.chatType,
                        fileUrl = uploaded.fileUrl,
                        durationMs = result.durationMs,
                        encrypted = uploaded.encrypted,
                        keyVersion = uploaded.keyVersion,
                        replyToId = replyToId
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

    // ==================== Round video messages ====================

    private val _capture = MutableStateFlow(CaptureUiState())
    val capture: StateFlow<CaptureUiState> = _capture.asStateFlow()

    /** Live camera/recorder state, straight from the recorder module. */
    val recorderState: StateFlow<RoundRecorderState> = roundVideoRecorder.state

    /** Per-message playback state, keyed by message id. */
    private val _videoStates = MutableStateFlow<Map<String, RoundVideoUiState>>(emptyMap())
    val videoStates: StateFlow<Map<String, RoundVideoUiState>> = _videoStates.asStateFlow()

    /** The one borrowed player, held by whichever bubble is active. */
    private val _activePlayer = MutableStateFlow<ExoPlayer?>(null)
    val activePlayer: StateFlow<ExoPlayer?> = _activePlayer.asStateFlow()
    private var activeVideoId: String? = null
    private var videoTicker: Job? = null

    fun toggleCaptureMode() {
        _capture.update {
            it.copy(mode = if (it.mode == CaptureMode.VOICE) CaptureMode.VIDEO else CaptureMode.VOICE)
        }
    }

    /** Binds the overlay's PreviewView to the camera. */
    fun bindVideoPreview(
        owner: androidx.lifecycle.LifecycleOwner,
        provider: androidx.camera.core.Preview.SurfaceProvider
    ) {
        viewModelScope.launch { roundVideoRecorder.bindPreview(owner, provider) }
    }

    fun flipCamera() = roundVideoRecorder.switchCamera()

    /** Pause/resume a locked video take without ending it. */
    fun toggleVideoPause() = roundVideoRecorder.togglePause()

    /** Long-press began: show the overlay and start capturing. */
    fun startVideoRecording() {
        if (_chatState.value.chatId == null) return
        _capture.update { it.copy(videoActive = true, isLocked = false, dragX = 0f, dragY = 0f) }
        // The camera needs a moment to bind before the encoder will accept
        // frames; starting immediately produces a zero-length take.
        viewModelScope.launch {
            delay(180)
            if (_capture.value.videoActive && !roundVideoRecorder.start()) {
                _capture.update { it.copy(videoActive = false) }
                _chatState.update { it.copy(error = "Could not start the camera") }
            }
        }
    }

    fun onCaptureDrag(x: Float, y: Float) = _capture.update { it.copy(dragX = x, dragY = y) }
    fun onArmCancel(armed: Boolean) = _capture.update { it.copy(willCancel = armed) }
    fun onArmLock(armed: Boolean) = _capture.update { it.copy(willLock = armed) }

    /**
     * Finger lifted. Locked wins over cancel: someone who slid up to go
     * hands-free has already committed to keeping the take.
     */
    fun onCaptureRelease(cancelled: Boolean, locked: Boolean) {
        when {
            locked -> _capture.update {
                it.copy(isLocked = true, dragX = 0f, dragY = 0f, willLock = false, willCancel = false)
            }
            cancelled -> cancelVideoRecording()
            else -> stopVideoRecordingAndSend()
        }
    }

    fun cancelVideoRecording() {
        roundVideoRecorder.cancel()
        roundVideoRecorder.release()
        _capture.value = CaptureUiState(mode = _capture.value.mode)
    }

    /** Stops, compresses, encrypts, uploads and posts the round video. */
    fun stopVideoRecordingAndSend() {
        val chatId = _chatState.value.chatId
        // Close the overlay straight away - the container still has to be
        // finalised, and leaving the camera up during that reads as a freeze.
        _capture.value = CaptureUiState(mode = _capture.value.mode)

        viewModelScope.launch {
            // Must await finalisation *before* release(): release() tears the
            // camera down, and the file is not a valid MP4 until CameraX has
            // closed it.
            val file = roundVideoRecorder.stopAndAwait()
            roundVideoRecorder.release()

            if (chatId == null) return@launch
            if (file == null) {
                _chatState.update { it.copy(error = "Hold to record - that was too short") }
                return@launch
            }

            _chatState.update { it.copy(isSending = true) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(isSending = false, error = "Not signed in") }
                return@launch
            }

            roundVideoRepository.prepareAndUpload(token, chatId, file) { stage ->
                val label = when (stage) {
                    is RoundVideoRepository.SendStage.Compressing ->
                        "Compressing ${(stage.fraction * 100).toInt()}%"
                    is RoundVideoRepository.SendStage.Uploading ->
                        "Uploading ${(stage.fraction * 100).toInt()}%"
                    RoundVideoRepository.SendStage.Finishing -> "Finishing"
                }
                _capture.update { it.copy(sendStatus = label) }
            }
                .onSuccess { prepared ->
                    val replyToId = _pendingReply.value?.id.orEmpty()
                    clearReply()
                    chatRepository.sendVideoNoteMessage(
                        token = token,
                        chatId = chatId,
                        chatType = _chatState.value.chatType,
                        fileUrl = prepared.fileUrl,
                        thumbnailUrl = prepared.thumbnailUrl,
                        durationMs = prepared.durationMs,
                        encrypted = prepared.encrypted,
                        keyVersion = prepared.keyVersion,
                        replyToId = replyToId
                    )
                        .onSuccess {
                            _capture.update { it.copy(sendStatus = null) }
                            _chatState.update { it.copy(isSending = false) }
                            openChat(chatId, _chatState.value.chatName)
                        }
                        .onFailure { e ->
                            _capture.update { it.copy(sendStatus = null) }
                            _chatState.update {
                                it.copy(isSending = false, error = e.message ?: "Failed to send")
                            }
                        }
                }
                .onFailure { e ->
                    Log.e(TAG, "video note upload failed", e)
                    _capture.update { it.copy(sendStatus = null) }
                    _chatState.update {
                        it.copy(isSending = false, error = e.message ?: "Failed to send video message")
                    }
                }
        }
    }

    /**
     * Fetches a round video's poster frame so its bubble is never an empty
     * circle. Small enough to do for every video message on screen.
     */
    fun ensureVideoThumbnail(message: ChatMessageUi) {
        val chatId = _chatState.value.chatId ?: return
        val thumbUrl = message.videoThumbnailUrl
        if (thumbUrl.isNullOrBlank()) return
        if (_videoStates.value[message.id]?.thumbnailPath != null) return

        viewModelScope.launch {
            roundVideoRepository.fetchThumbnail(
                chatId, message.id, thumbUrl, message.videoEncrypted,
                message.senderId, message.keyVersion
            )
                .onSuccess { f -> updateVideo(message.id) { it.copy(thumbnailPath = f.absolutePath) } }
        }
    }

    /** Tap on a round bubble: download if needed, then play or pause. */
    fun toggleVideoPlayback(message: ChatMessageUi) {
        val chatId = _chatState.value.chatId ?: return
        val url = message.videoUrl ?: return

        if (activeVideoId == message.id) {
            val player = _activePlayer.value ?: return
            if (player.isPlaying) player.pause() else player.play()
            updateVideo(message.id) { it.copy(isPlaying = player.isPlaying) }
            return
        }

        // Switching bubbles: hand the previous one's player back first, so two
        // hardware decoders are never held at once.
        releaseActiveVideo()

        viewModelScope.launch {
            updateVideo(message.id) {
                it.copy(isDownloading = true, durationMs = message.videoDurationMs)
            }
            roundVideoRepository.fetchForPlayback(
                chatId, message.id, url, message.videoEncrypted,
                message.senderId, message.keyVersion
            ) { f -> updateVideo(message.id) { it.copy(downloadProgress = f) } }
                .onSuccess { file ->
                    val player = playerPool.acquire()
                    playerPool.prepareLooping(player, file, muted = false)
                    player.play()
                    activeVideoId = message.id
                    _activePlayer.value = player
                    playerPool.setActive(message.id)
                    updateVideo(message.id) { it.copy(isDownloading = false, isPlaying = true) }
                    startVideoTicker()
                }
                .onFailure { e ->
                    updateVideo(message.id) { it.copy(isDownloading = false, error = e.message) }
                    _chatState.update { it.copy(error = e.message ?: "Could not play video message") }
                }
        }
    }

    /** Drag-around-the-ring seek, as a 0..1 fraction. */
    fun seekVideo(messageId: String, fraction: Float) {
        if (activeVideoId != messageId) return
        val player = _activePlayer.value ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((duration * fraction).toLong())
    }

    private fun startVideoTicker() {
        videoTicker?.cancel()
        videoTicker = viewModelScope.launch {
            while (_activePlayer.value != null) {
                val player = _activePlayer.value ?: break
                val id = activeVideoId ?: break
                updateVideo(id) {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = player.duration.takeIf { d -> d > 0 } ?: it.durationMs,
                        isPlaying = player.isPlaying
                    )
                }
                delay(80)
            }
        }
    }

    fun releaseActiveVideo() {
        videoTicker?.cancel()
        videoTicker = null
        _activePlayer.value?.let { playerPool.release(it) }
        _activePlayer.value = null
        activeVideoId?.let { id -> updateVideo(id) { it.copy(isPlaying = false, positionMs = 0) } }
        activeVideoId = null
        playerPool.setActive(null)
    }

    private fun updateVideo(id: String, transform: (RoundVideoUiState) -> RoundVideoUiState) {
        _videoStates.update { map -> map + (id to transform(map[id] ?: RoundVideoUiState())) }
    }

    /**
     * Removes a message. [forEveryone] retracts it for both sides and is only
     * offered on your own messages; otherwise it disappears here alone.
     *
     * The row is dropped from the list immediately on success rather than
     * waiting for a refetch, so the action feels like it took effect.
     */
    fun deleteMessage(messageId: String, forEveryone: Boolean) {
        val chatId = _chatState.value.chatId ?: return
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(error = "Not signed in") }
                return@launch
            }
            chatRepository.deleteMessage(token, chatId, messageId, forEveryone)
                .onSuccess { removeMessageLocally(messageId, chatId) }
                .onFailure { e ->
                    Log.e(TAG, "deleteMessage failed", e)
                    if (e is SessionExpiredException) _sessionExpired.value = true
                    _chatState.update { it.copy(error = e.message ?: "Could not delete message") }
                }
        }
    }

    /**
     * Drops [messageId] from the open chat (when it is open) and refreshes
     * that chat's list preview from whatever remains. [chatId] is required
     * for remote deletions of a chat that isn't currently open.
     */
    private fun removeMessageLocally(messageId: String, chatId: String? = _chatState.value.chatId) {
        if (chatId != null && chatId == _chatState.value.chatId) {
            _chatState.update { state ->
                state.copy(messages = state.messages.filterNot { it.id == messageId })
            }
            // Keep the selection honest - otherwise the counter keeps counting
            // rows that no longer exist.
            if (_selectedMessageIds.value.contains(messageId)) {
                _selectedMessageIds.update { it - messageId }
            }
            applyChatListPreview(chatId, previewFromOpenChatMessages())
        } else if (chatId != null) {
            viewModelScope.launch {
                applyChatListPreview(chatId, chatRepository.latestMessagePreview(chatId))
            }
        }
    }

    private fun previewFromOpenChatMessages(): String {
        val last = _chatState.value.messages.lastOrNull { !it.isSystem } ?: return ""
        return when {
            last.isVoice -> "🎤 Voice message"
            last.isVideoNote -> "📹 Video message"
            last.isImageAttachment -> "📷 Photo"
            last.isAttachment -> "📎 ${last.attachmentName.ifBlank { "File" }}"
            else -> last.content
        }
    }

    private fun applyChatListPreview(chatId: String, preview: String) {
        _chatListState.update { listState ->
            val idx = listState.chats.indexOfFirst { it.id == chatId }
            if (idx == -1) return@update listState
            val updated = listState.chats.toMutableList()
            updated[idx] = updated[idx].copy(lastMessage = preview)
            listState.copy(chats = updated)
        }
    }

    // ==================== Message selection ====================

    private val _selectedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedMessageIds: StateFlow<Set<String>> = _selectedMessageIds.asStateFlow()

    /** Selection mode is simply "something is selected" - no separate flag to drift. */
    val inSelectionMode: StateFlow<Boolean> = _selectedMessageIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True when every selected message is one of ours, which is the only case
     * where "delete for everyone" is allowed. The server enforces this too;
     * this just avoids offering an action that would be refused.
     */
    val allSelectedAreMine: StateFlow<Boolean> = combine(
        _selectedMessageIds, _chatState
    ) { ids, state ->
        ids.isNotEmpty() && state.messages.filter { it.id in ids }.all { it.isMine }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleMessageSelection(messageId: String) {
        _selectedMessageIds.update { current ->
            if (messageId in current) current - messageId else current + messageId
        }
    }

    fun clearSelection() {
        _selectedMessageIds.value = emptySet()
    }

    fun selectAllMessages() {
        _selectedMessageIds.value = _chatState.value.messages
            .filterNot { it.isSystem }
            .map { it.id }
            .toSet()
    }

    /** Selects a single message so the forward-chat picker can open on it. */
    fun beginForward(messageId: String) {
        _selectedMessageIds.value = setOf(messageId)
    }

    // ==================== Reply ====================

    private val _pendingReply = MutableStateFlow<ChatMessageUi?>(null)
    val pendingReply: StateFlow<ChatMessageUi?> = _pendingReply.asStateFlow()

    fun beginReply(messageId: String) {
        val message = _chatState.value.messages.firstOrNull { it.id == messageId && !it.isSystem } ?: return
        _pendingReply.value = message
        clearSelection()
    }

    fun clearReply() {
        _pendingReply.value = null
    }

    /**
     * Re-sends every selected message into [targetChatId], stamping forward
     * attribution. Media is fetched/decrypted from the source chat, then
     * re-uploaded for the target so E2EE keys match the destination.
     *
     * Continues past per-message failures and surfaces a summary error.
     */
    fun forwardSelectedTo(targetChatId: String, targetChatType: String) {
        val sourceChatId = _chatState.value.chatId ?: return
        val selected = _selectedMessageIds.value
        if (selected.isEmpty()) return
        val messages = _chatState.value.messages.filter { it.id in selected && !it.isSystem }
        if (messages.isEmpty()) return

        viewModelScope.launch {
            _chatState.update { it.copy(isSending = true, error = null) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _chatState.update { it.copy(isSending = false, error = "Not signed in") }
                clearSelection()
                return@launch
            }

            val failures = mutableListOf<String>()
            for (message in messages) {
                val forward = ForwardMeta(
                    isForwarded = true,
                    fromName = message.senderName.ifBlank { "Unknown" },
                    fromMessageId = message.id
                )
                val result = runCatching {
                    when {
                        message.isVoice -> forwardVoice(
                            token, sourceChatId, targetChatId, targetChatType, message, forward
                        )
                        message.isVideoNote -> forwardVideoNote(
                            token, sourceChatId, targetChatId, targetChatType, message, forward
                        )
                        message.isAttachment -> forwardAttachment(
                            token, sourceChatId, targetChatId, targetChatType, message, forward
                        )
                        else -> chatRepository.sendMessage(
                            token = token,
                            chatId = targetChatId,
                            chatType = targetChatType,
                            plaintext = message.content,
                            forward = forward
                        ).getOrThrow()
                    }
                }
                if (result.isFailure) {
                    val err = result.exceptionOrNull()
                    Log.e(TAG, "forward failed for ${message.id}", err)
                    if (err is SessionExpiredException) {
                        _sessionExpired.value = true
                        break
                    }
                    failures += err?.message ?: message.id
                }
            }

            clearSelection()
            _chatState.update {
                it.copy(
                    isSending = false,
                    error = when {
                        failures.isEmpty() -> null
                        failures.size == 1 -> "Failed to forward: ${failures.first()}"
                        else -> "Failed to forward ${failures.size} messages"
                    }
                )
            }
        }
    }

    private suspend fun forwardVoice(
        token: String,
        sourceChatId: String,
        targetChatId: String,
        targetChatType: String,
        message: ChatMessageUi,
        forward: ForwardMeta
    ) {
        val url = message.voiceUrl ?: error("Voice note has no file")
        val cached = voiceRepository.fetchForPlayback(
            sourceChatId, message.id, url, message.voiceEncrypted,
            message.senderId, message.keyVersion
        ).getOrThrow()
        // upload() deletes its input; copy so the playback cache survives.
        val uploadCopy = File(
            cached.parentFile,
            "fwd_voice_${message.id}_${System.currentTimeMillis()}.m4a"
        )
        cached.copyTo(uploadCopy, overwrite = true)
        val uploaded = voiceRepository.upload(token, targetChatId, uploadCopy).getOrThrow()
        chatRepository.sendVoiceMessage(
            token = token,
            chatId = targetChatId,
            chatType = targetChatType,
            fileUrl = uploaded.fileUrl,
            durationMs = message.voiceDurationMs,
            encrypted = uploaded.encrypted,
            keyVersion = uploaded.keyVersion,
            forward = forward
        ).getOrThrow()
    }

    private suspend fun forwardVideoNote(
        token: String,
        sourceChatId: String,
        targetChatId: String,
        targetChatType: String,
        message: ChatMessageUi,
        forward: ForwardMeta
    ) {
        val url = message.videoUrl ?: error("Video note has no file")
        val cached = roundVideoRepository.fetchForPlayback(
            sourceChatId, message.id, url, message.videoEncrypted,
            message.senderId, message.keyVersion
        ).getOrThrow()
        // prepareAndUpload deletes its capture file; copy so the cache survives.
        val uploadCopy = File(
            cached.parentFile,
            "fwd_video_${message.id}_${System.currentTimeMillis()}.mp4"
        )
        cached.copyTo(uploadCopy, overwrite = true)
        val prepared = roundVideoRepository.prepareAndUpload(token, targetChatId, uploadCopy).getOrThrow()
        chatRepository.sendVideoNoteMessage(
            token = token,
            chatId = targetChatId,
            chatType = targetChatType,
            fileUrl = prepared.fileUrl,
            thumbnailUrl = prepared.thumbnailUrl,
            durationMs = prepared.durationMs,
            encrypted = prepared.encrypted,
            keyVersion = prepared.keyVersion,
            forward = forward
        ).getOrThrow()
    }

    private suspend fun forwardAttachment(
        token: String,
        sourceChatId: String,
        targetChatId: String,
        targetChatType: String,
        message: ChatMessageUi,
        forward: ForwardMeta
    ) {
        val url = message.attachmentUrl ?: error("Attachment has no file")
        val file = attachmentRepository.fetchForView(
            sourceChatId, message.id, url, message.attachmentName,
            message.attachmentEncrypted, message.senderId, message.keyVersion
        ).getOrThrow()
        val name = message.attachmentName.ifBlank { file.name }
        val bytes = file.readBytes()
        val picked = AttachmentRepository.PickedFile(name, bytes.size.toLong(), bytes)
        val contentType = attachmentRepository.classify(name)
        val uploaded = attachmentRepository.upload(token, targetChatId, picked).getOrThrow()
        chatRepository.sendAttachmentMessage(
            token = token,
            chatId = targetChatId,
            chatType = targetChatType,
            fileUrl = uploaded.fileUrl,
            fileName = name,
            fileSize = uploaded.fileSize,
            contentType = contentType,
            encrypted = uploaded.encrypted,
            keyVersion = uploaded.keyVersion,
            forward = forward
        ).getOrThrow()
    }

    /**
     * Deletes every selected message.
     *
     * Rows disappear as each request succeeds rather than optimistically: a
     * failed delete should not leave a message missing here but alive on the
     * server.
     */
    fun deleteSelectedMessages(forEveryone: Boolean) {
        val ids = _selectedMessageIds.value.toList()
        clearSelection()
        ids.forEach { deleteMessage(it, forEveryone) }
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
            voiceRepository.fetchForPlayback(
                chatId, message.id, url, message.voiceEncrypted,
                message.senderId, message.keyVersion
            )
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
        // Video decoders and the camera are scarce, shared resources - holding
        // either past the screen's lifetime starves the rest of the app.
        releaseActiveVideo()
        playerPool.releaseAll()
        roundVideoRecorder.release()
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
