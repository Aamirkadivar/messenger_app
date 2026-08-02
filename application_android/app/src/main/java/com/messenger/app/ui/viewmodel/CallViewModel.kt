package com.messenger.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.data.call.CallRepository
import com.messenger.app.data.call.CallStatus
import com.messenger.app.data.call.CallUiState
import com.messenger.app.data.repository.ChatRepository
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
    private val chatRepository: ChatRepository
) : ViewModel() {

    val state: StateFlow<CallUiState> = callRepository.state

    init {
        // An incoming call's invite doesn't necessarily carry the caller's
        // display name - resolve it from the already-cached chat list rather
        // than requiring every caller to have it in hand.
        state
            .map { it.chatId to it.status }
            .distinctUntilChanged()
            .onEach { (chatId, status) ->
                if (status != CallStatus.INCOMING_RINGING || chatId.isBlank()) return@onEach
                if (state.value.peerName.isNotBlank()) return@onEach
                val chat = runCatching {
                    chatRepository.loadCachedChats().firstOrNull { it.id == chatId }
                }.getOrNull()
                val name = chat?.otherUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: chat?.otherUser?.username
                if (!name.isNullOrBlank()) callRepository.updatePeerName(name)
            }
            .launchIn(viewModelScope)
    }

    fun startCall(chatId: String, calleeId: String, calleeName: String) {
        if (calleeId.isBlank()) return
        callRepository.startOutgoingCall(chatId, calleeId, calleeName)
    }

    fun acceptCall() = callRepository.acceptCall()
    fun rejectCall() = callRepository.rejectCall()
    fun endCall() = callRepository.endCall()
    fun toggleMute() = callRepository.toggleMute()
    fun toggleSpeaker() = callRepository.toggleSpeaker()
    fun dismissEnded() = callRepository.dismissEnded()
}
