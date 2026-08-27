package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.model.GroupDto
import com.messenger.app.data.model.GroupMemberDto
import com.messenger.app.data.model.GroupRole
import com.messenger.app.data.model.UserSearchResult
import android.net.Uri
import com.messenger.app.data.repository.AvatarRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.GroupRepository
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupInfoUiState(
    val isLoading: Boolean = true,
    val group: GroupDto? = null,
    val currentUserId: String = "",
    val isMuted: Boolean = false,
    val error: String? = null,
    /** Non-null while an action targeting a specific member is in flight. */
    val busyMemberId: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    /** Set when the group is gone for this user (left or deleted). */
    val exited: Boolean = false
) {
    val members: List<GroupMemberDto> get() = group?.members.orEmpty()

    /** Admins sort first, then alphabetically - matches how Telegram lists them. */
    val sortedMembers: List<GroupMemberDto>
        get() = members.sortedWith(
            compareByDescending<GroupMemberDto> { it.groupRole == GroupRole.ADMIN }
                .thenBy { it.bestName().lowercase() }
        )

    val myRole: GroupRole
        get() = members.firstOrNull { it.id == currentUserId }?.groupRole ?: GroupRole.MEMBER

    val isAdmin: Boolean get() = myRole == GroupRole.ADMIN

    /**
     * Only the owner may delete. The server enforces this too; mirroring it here
     * keeps the destructive action hidden rather than offering it and failing.
     */
    val isOwner: Boolean get() = group?.ownerId != null && group.ownerId == currentUserId

    fun isOwner(memberId: String): Boolean = group?.ownerId == memberId
}

data class AddMembersUiState(
    val query: String = "",
    val results: List<UserSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val selected: List<SelectedMember> = emptyList(),
    val isSubmitting: Boolean = false
)

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val avatarRepository: AvatarRepository,
    private val chatRepository: ChatRepository,
    private val mlsRepository: com.messenger.app.data.repository.MlsRepository,
    private val tokenManager: TokenManager,
    private val historyRotation: com.messenger.app.data.repository.HistoryRotationCoordinator
) : ViewModel() {

    companion object {
        private const val TAG = "GroupInfoViewModel"
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val _state = MutableStateFlow(GroupInfoUiState())
    val state: StateFlow<GroupInfoUiState> = _state.asStateFlow()

    private val _addMembers = MutableStateFlow(AddMembersUiState())
    val addMembers: StateFlow<AddMembersUiState> = _addMembers.asStateFlow()

    private var chatId: String = ""
    private var searchJob: Job? = null

    fun load(chatId: String) {
        this.chatId = chatId
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val myId = tokenManager.getCurrentUserId().getOrNull().orEmpty()
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _state.update { it.copy(isLoading = false, error = "Not signed in") }
                return@launch
            }
            groupRepository.getGroupInfo(token, chatId)
                .onSuccess { group ->
                    _state.update {
                        it.copy(isLoading = false, group = group, currentUserId = myId)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "getGroupInfo failed", e)
                    _state.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to load group")
                    }
                }
        }
    }

    private fun reload() = load(chatId)

    // ==================== Header ====================

    fun rename(name: String, description: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Group name is required") }
            return
        }
        withToken { token ->
            _state.update { it.copy(isBusy = true) }
            groupRepository.updateGroup(token, chatId, trimmed, description)
                .onSuccess {
                    _state.update { it.copy(isBusy = false, message = "Group updated") }
                    reload()
                }
                .onFailure { e ->
                    _state.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    /** Uploads a new group picture. Admin only - the server also enforces it. */
    fun setGroupPhoto(uri: Uri) {
        withToken { token ->
            _state.update { it.copy(isBusy = true) }
            avatarRepository.uploadGroupAvatar(token, chatId, uri)
                .onSuccess {
                    _state.update { it.copy(isBusy = false, message = "Group picture updated") }
                    reload()
                }
                .onFailure { e ->
                    _state.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    // ==================== Members ====================

    fun setRole(member: GroupMemberDto, role: GroupRole) {
        withToken { token ->
            _state.update { it.copy(busyMemberId = member.id) }
            groupRepository.setMemberRole(token, chatId, member.id, role)
                .onSuccess {
                    _state.update {
                        it.copy(
                            busyMemberId = null,
                            message = if (role == GroupRole.ADMIN) {
                                "${member.bestName()} is now an admin"
                            } else {
                                "${member.bestName()} is no longer an admin"
                            }
                        )
                    }
                    reload()
                }
                .onFailure { e ->
                    _state.update { it.copy(busyMemberId = null, error = e.message) }
                }
        }
    }

    fun removeMember(member: GroupMemberDto) {
        withToken { token ->
            _state.update { it.copy(busyMemberId = member.id) }
            groupRepository.removeMember(token, chatId, member.id)
                .onSuccess {
                    // Membership changed: rotate this chat's history root now, not at the next
                    // send. A failure leaves archiving blocked for the chat rather than letting it
                    // continue under the retired root; the removal itself already succeeded.
                    rotateHistoryFor(
                        com.messenger.app.data.repository.SecurityEvent.GroupMemberRemoved(
                            chatId, member.id
                        )
                    )
                    _state.update {
                        it.copy(busyMemberId = null, message = "${member.bestName()} removed")
                    }
                    reload()
                }
                .onFailure { e ->
                    _state.update { it.copy(busyMemberId = null, error = e.message) }
                }
        }
    }

    // ==================== Add members ====================

    fun searchUsers(query: String) {
        _addMembers.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _addMembers.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _addMembers.update { it.copy(isSearching = true) }
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            chatRepository.searchUsers(token, query)
                .onSuccess { results ->
                    // Hide people who are already in the group.
                    val existing = _state.value.members.map { it.id }.toSet()
                    _addMembers.update {
                        it.copy(results = results.filterNot { u -> u.id in existing }, isSearching = false)
                    }
                }
                .onFailure { _addMembers.update { it.copy(isSearching = false) } }
        }
    }

    fun toggleCandidate(user: UserSearchResult) {
        _addMembers.update { current ->
            val existing = current.selected.firstOrNull { it.id == user.id }
            current.copy(
                selected = if (existing != null) current.selected - existing
                else current.selected + SelectedMember(
                    id = user.id,
                    name = user.displayName?.takeIf { it.isNotBlank() } ?: user.username
                )
            )
        }
    }

    fun isCandidateSelected(userId: String) = _addMembers.value.selected.any { it.id == userId }

    fun confirmAddMembers(onDone: () -> Unit) {
        val selected = _addMembers.value.selected
        if (selected.isEmpty()) { onDone(); return }
        withToken { token ->
            _addMembers.update { it.copy(isSubmitting = true) }
            groupRepository.addMembers(token, chatId, selected.map { it.id })
                .onSuccess {
                    rotateHistoryFor(
                        com.messenger.app.data.repository.SecurityEvent.GroupMembersAdded(
                            chatId, selected.map { m -> m.id }
                        )
                    )
                    _addMembers.value = AddMembersUiState()
                    _state.update { it.copy(message = "Added ${selected.size} member(s)") }
                    // MLS-invite the new members too; before this they only
                    // ever got the Sender Keys path, so a group already on MLS
                    // was unreadable for anyone added after creation.
                    viewModelScope.launch {
                        mlsRepository.inviteMissingDevices(chatId)
                            .onFailure { e -> Log.w(TAG, "MLS invite after add: ${e.message}") }
                    }
                    reload()
                    onDone()
                }
                .onFailure { e ->
                    _addMembers.update { it.copy(isSubmitting = false) }
                    _state.update { it.copy(error = e.message) }
                }
        }
    }

    fun resetAddMembers() { _addMembers.value = AddMembersUiState() }

    // ==================== Settings ====================

    fun setMuted(muted: Boolean) {
        viewModelScope.launch {
            chatRepository.setChatMuted(chatId, muted)
            _state.update { it.copy(isMuted = muted) }
        }
    }

    fun leaveGroup() {
        withToken { token ->
            _state.update { it.copy(isBusy = true) }
            groupRepository.leaveGroup(token, chatId)
                .onSuccess {
                    rotateHistoryFor(
                        com.messenger.app.data.repository.SecurityEvent.GroupLeft(chatId)
                    )
                    _state.update { it.copy(isBusy = false, exited = true) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    fun deleteGroup() {
        withToken { token ->
            _state.update { it.copy(isBusy = true) }
            groupRepository.deleteGroup(token, chatId)
                .onSuccess { _state.update { it.copy(isBusy = false, exited = true) } }
                .onFailure { e ->
                    _state.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    /**
     * Abandons this group's MLS group and joins a fresh incarnation.
     *
     * The manual recovery action for a device that has lost its local MLS state.
     * Such a device cannot rejoin the existing tree, so the group is replaced and
     * every current device is Welcomed into the new one - which means messages
     * sent under the old group stop being decryptable.
     *
     * Deliberately manual: it is destructive to the old group, so it never runs
     * at startup and never during normal messaging. The repository additionally
     * bounds it to one attempt per chat per process, and a concurrent attempt
     * from another device is adopted rather than duplicated.
     */
    fun recoverMlsGroup(targetChatId: String = chatId) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null, message = null) }
            val ok = runCatching { chatRepository.recreateMlsV2Group(targetChatId) }
                .getOrElse { e ->
                    Log.e(TAG, "MLS recovery failed for $targetChatId", e)
                    _state.update {
                        it.copy(isBusy = false, error = e.message ?: "Encryption reset failed")
                    }
                    return@launch
                }
            _state.update {
                if (ok) {
                    it.copy(
                        isBusy = false,
                        message = "Encryption reset. This device rejoined the group; " +
                            "messages sent before now stay unreadable."
                    )
                } else {
                    it.copy(
                        isBusy = false,
                        error = "Could not reset encryption for this group. Check the " +
                            "connection, then restart the app before trying again."
                    )
                }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    private inline fun withToken(crossinline block: suspend (String) -> Unit) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _state.update { it.copy(error = "Not signed in") }
                return@launch
            }
            block(token)
        }
    }

    /**
     * Rotates the history root after a membership change.
     *
     * Best effort with respect to the USER's action - the membership change has already succeeded
     * server-side and must not be reported as failed - but never best effort with respect to
     * security: a failed rotation leaves the chat's archiving barrier raised, so nothing is sealed
     * under the root the change was meant to retire.
     */
    private suspend fun rotateHistoryFor(
        event: com.messenger.app.data.repository.SecurityEvent
    ) {
        historyRotation.onSecurityEvent(event).onFailure {
            Log.w(TAG, "history root rotation did not complete: ${it.message}")
        }
    }
}
