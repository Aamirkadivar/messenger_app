package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.model.UserSearchResult
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.MlsRepository
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

/** A user picked for the new group. */
data class SelectedMember(
    val id: String,
    val name: String
)

data class CreateGroupUiState(
    val name: String = "",
    val description: String = "",
    val query: String = "",
    val searchResults: List<UserSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val selected: List<SelectedMember> = emptyList(),
    val isCreating: Boolean = false,
    val error: String? = null,
    /** Set once creation succeeds, so the UI can navigate into the new chat. */
    val createdChatId: String? = null
) {
    val trimmedName: String get() = name.trim()

    /**
     * A group needs a name and at least one other member - a "group" of one is
     * just a note to self, and the backend would happily create it.
     */
    val canCreate: Boolean
        get() = trimmedName.isNotEmpty() &&
            trimmedName.length <= GroupRepository.MAX_NAME_LENGTH &&
            selected.isNotEmpty() &&
            !isCreating

    val nameError: String?
        get() = if (trimmedName.length > GroupRepository.MAX_NAME_LENGTH) {
            "Name must be ${GroupRepository.MAX_NAME_LENGTH} characters or fewer"
        } else {
            null
        }
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val chatRepository: ChatRepository,
    private val mlsRepository: MlsRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    companion object {
        private const val TAG = "CreateGroupViewModel"
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state: StateFlow<CreateGroupUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }

    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    /** Debounced so typing doesn't fire a request per keystroke. */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()

        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(isSearching = true) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _state.update { it.copy(isSearching = false, error = "Not signed in") }
                return@launch
            }
            chatRepository.searchUsers(token, query)
                .onSuccess { results ->
                    _state.update { it.copy(searchResults = results, isSearching = false) }
                }
                .onFailure { e ->
                    Log.e(TAG, "searchUsers failed", e)
                    _state.update { it.copy(isSearching = false) }
                }
        }
    }

    fun toggleMember(user: UserSearchResult) {
        _state.update { current ->
            val existing = current.selected.firstOrNull { it.id == user.id }
            val updated = if (existing != null) {
                current.selected - existing
            } else {
                current.selected + SelectedMember(
                    id = user.id,
                    name = user.displayName?.takeIf { it.isNotBlank() } ?: user.username
                )
            }
            current.copy(selected = updated, error = null)
        }
    }

    fun removeMember(member: SelectedMember) {
        _state.update { it.copy(selected = it.selected - member) }
    }

    fun isSelected(userId: String): Boolean =
        _state.value.selected.any { it.id == userId }

    fun create() {
        val current = _state.value
        if (!current.canCreate) return

        viewModelScope.launch {
            _state.update { it.copy(isCreating = true, error = null) }
            val token = tokenManager.getAccessToken().getOrNull()
            if (token.isNullOrEmpty()) {
                _state.update { it.copy(isCreating = false, error = "Not signed in") }
                return@launch
            }

            groupRepository.createGroup(
                token = token,
                name = current.trimmedName,
                memberIds = current.selected.map { it.id },
                description = current.description
            )
                .onSuccess { group ->
                    // Join the room so live messages and membership events for
                    // the new group arrive immediately.
                    chatRepository.joinChatRoom(group.id)

                    // Establish MLS for this group: create it, then add every
                    // member by claiming a KeyPackage each. Best-effort — if a
                    // member has no KeyPackage published yet, the group simply
                    // stays on Sender Keys until they can be added, rather than
                    // failing group creation outright.
                    chatRepository.mls = mlsRepository
                    runCatching {
                        mlsRepository.createGroup(group.id).getOrThrow()
                        mlsRepository.inviteMissingDevices(group.id)
                    }.onFailure { Log.w(TAG, "MLS group setup failed: ${it.message}") }

                    _state.update { it.copy(isCreating = false, createdChatId = group.id) }
                }
                .onFailure { e ->
                    Log.e(TAG, "createGroup failed", e)
                    _state.update {
                        it.copy(isCreating = false, error = e.message ?: "Failed to create group")
                    }
                }
        }
    }

    /** Clears the one-shot navigation signal after the UI has consumed it. */
    fun consumeCreated() = _state.update { it.copy(createdChatId = null) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
