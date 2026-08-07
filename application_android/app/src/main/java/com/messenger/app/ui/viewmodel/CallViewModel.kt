package com.messenger.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.call.CallRepository
import com.messenger.app.data.call.CallStatus
import com.messenger.app.data.call.CallUiState
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.GroupRepository
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin Compose-facing wrapper around [CallRepository], which is the actual
 * owner of call state (a singleton, so the call survives navigating between
 * screens). Created once at the top of the nav graph (see MainActivity) so
 * an incoming call can show its overlay regardless of which screen is open.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    companion object {
        private const val TAG = "CallViewModel"
    }

    val state: StateFlow<CallUiState> = callRepository.state

    /** Native video tracks + shared EGL context, for the overlay's renderers. */
    val localVideoTrack = callRepository.localVideoTrack
    val remoteVideoTrack = callRepository.remoteVideoTrack
    val remoteVideoTracks = callRepository.remoteVideoTracks
    val eglBaseContext: org.webrtc.EglBase.Context get() = callRepository.eglBase.eglBaseContext

    init {
        // An incoming call's invite doesn't necessarily carry the caller's
        // display name - resolve it from the already-cached chat list rather
        // than requiring every caller to have it in hand. For group calls the
        // chat title is the group name.
        state
            .map { Triple(it.chatId, it.status, it.isGroupCall) }
            .distinctUntilChanged()
            .onEach { (chatId, status, isGroupCall) ->
                if (status != CallStatus.INCOMING_RINGING || chatId.isBlank()) return@onEach
                if (!isGroupCall && state.value.peerName.isNotBlank()) return@onEach
                val chat = runCatching {
                    chatRepository.loadCachedChats().firstOrNull { it.id == chatId }
                }.getOrNull()
                val name = if (isGroupCall) {
                    chat?.name?.takeIf { it.isNotBlank() }
                } else {
                    chat?.otherUser?.displayName?.takeIf { it.isNotBlank() }
                        ?: chat?.otherUser?.username
                }
                if (!name.isNullOrBlank()) callRepository.updatePeerName(name)
            }
            .launchIn(viewModelScope)
    }

    fun startCall(chatId: String, calleeId: String, calleeName: String, video: Boolean = false) {
        if (calleeId.isBlank()) return
        callRepository.startOutgoingCall(chatId, calleeId, calleeName, video)
    }

    /**
     * Fetches group members then starts a mesh call. Caps are enforced inside
     * [CallRepository.startGroupCall]. Pass [video] true for group video.
     */
    fun startGroupCall(chatId: String, groupName: String, video: Boolean = false) {
        if (chatId.isBlank()) return
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            val myId = tokenManager.getCurrentUserId().getOrNull().orEmpty()
            groupRepository.getGroupInfo(token, chatId)
                .onSuccess { group ->
                    val members = group.members.map { member ->
                        member.id to member.bestName()
                    }
                    if (members.none { it.first != myId }) {
                        Log.w(TAG, "startGroupCall: no other members in $chatId")
                        return@onSuccess
                    }
                    callRepository.startGroupCall(
                        chatId = chatId,
                        groupName = groupName.ifBlank { group.name }.ifBlank { "Group" },
                        members = members,
                        video = video
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "startGroupCall: getGroupInfo failed", e)
                }
        }
    }

    fun acceptCall() = callRepository.acceptCall()
    fun rejectCall() = callRepository.rejectCall()
    fun endCall() = callRepository.endCall()
    fun toggleMute() = callRepository.toggleMute()
    fun toggleSpeaker() = callRepository.toggleSpeaker()
    fun toggleCamera() = callRepository.toggleCamera()
    fun switchCamera() = callRepository.switchCamera()
    fun dismissEnded() = callRepository.dismissEnded()
}
