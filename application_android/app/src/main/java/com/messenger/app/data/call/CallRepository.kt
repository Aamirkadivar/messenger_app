package com.messenger.app.data.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.IncomingCallSignal
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.security.TokenManager
import com.messenger.app.service.CallForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class CallStatus { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, CONNECTED, ENDED }

data class CallUiState(
    val status: CallStatus = CallStatus.IDLE,
    val callId: String = "",
    val chatId: String = "",
    val peerUserId: String = "",
    val peerName: String = "",
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    /** Wall-clock time the call connected, for an elapsed-time display. 0 until connected. */
    val connectedAtMs: Long = 0,
    val endReason: String = ""
)

/**
 * Owns the WebRTC PeerConnection for a 1:1 audio call end to end: signaling
 * (via [WebSocketManager]'s call:* messages), ICE/SDP negotiation, and the
 * local/remote audio tracks. A singleton (like ChatRepository/GroupRepository)
 * so the call survives navigating between screens - only ended explicitly or
 * by the other side hanging up.
 *
 * The call's actual audio is DTLS-SRTP directly between the two devices
 * (WebRTC negotiates this itself from the SDP exchanged here) - this class
 * and the server only ever handle signaling, never media, so a call is no
 * more interceptable server-side than an E2EE message is.
 */
@Singleton
class CallRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatApiService: ChatApiService,
    private val webSocketManager: WebSocketManager,
    private val tokenManager: TokenManager,
    private val userDao: UserDao
) {
    companion object {
        private const val TAG = "CallRepository"

        /** How long the terminal "call ended" state stays up before resetting to IDLE. */
        private const val ENDED_DISPLAY_MS = 1_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    /** The remote offer's SDP, held between receiving call:invite and the user accepting. */
    private var pendingOfferSdp: String? = null

    /**
     * Trickled candidates that arrived before there was a PeerConnection with
     * a remote description to hold them. addIceCandidate() on a null or
     * remote-description-less connection is a silent no-op, so without this
     * the far end's first burst of candidates is lost outright - it starts
     * trickling as soon as it dials, while an incoming call here is still
     * only ringing. Lose those and ICE may never find a pair at all.
     */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    private fun applyRemoteCandidate(candidate: IceCandidate) {
        if (peerConnection == null || !remoteDescriptionSet) {
            pendingRemoteCandidates.add(candidate)
            return
        }
        peerConnection?.addIceCandidate(candidate)
    }

    private fun flushPendingRemoteCandidates() {
        if (pendingRemoteCandidates.isEmpty()) return
        Log.d(TAG, "flushing ${pendingRemoteCandidates.size} buffered ICE candidates")
        val buffered = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        buffered.forEach { applyRemoteCandidate(it) }
    }

    private var myUserId: String = ""

    /** Our own display name, sent with an invite so the callee can label it. */
    private var myDisplayName: String = ""

    /**
     * Best available name for the signed-in user, from the copy of their own
     * profile stored at login. Falls back to the username, then to empty -
     * the far end treats blank as "Unknown" rather than showing a raw id.
     */
    private suspend fun resolveMyDisplayName(): String {
        if (myUserId.isEmpty()) return ""
        val me = runCatching { userDao.getUserById(myUserId) }.getOrNull() ?: return ""
        return me.name.takeIf { it.isNotBlank() } ?: me.username
    }

    init {
        webSocketManager.callSignals
            .onEach(::handleSignal)
            .launchIn(scope)
    }

    private var statsJob: Job? = null

    /** Returns the terminal ENDED state to IDLE without needing the UI alive. */
    private var idleResetJob: Job? = null

    /**
     * Logs RTP counters every few seconds while a call is up.
     *
     * "frames delivered" from AudioTrack is not evidence that anything was
     * received - WebRTC's playback thread runs continuously and happily plays
     * silence. Only inbound-rtp packetsReceived/bytesReceived distinguishes
     * "the far end's audio is arriving" from "arriving but inaudible" from
     * "never arriving at all", and getStats() is the only way to see them.
     */
    private fun startStatsLogging() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(3000)
                val pc = peerConnection ?: break
                pc.getStats { report ->
                    report.statsMap.values
                        .filter { it.type == "inbound-rtp" || it.type == "outbound-rtp" }
                        .forEach { s ->
                            val m = s.members
                            Log.d(
                                TAG,
                                "RTP ${s.type}: packets=${m["packetsReceived"] ?: m["packetsSent"]} " +
                                    "bytes=${m["bytesReceived"] ?: m["bytesSent"]} " +
                                    "kind=${m["kind"]} ssrc=${m["ssrc"]} " +
                                    "audioLevel=${m["audioLevel"]} " +
                                    "concealed=${m["concealedSamples"]}"
                            )
                        }
                }
            }
        }
    }

    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Switches the device into voice-call audio mode for the duration of a call.
     *
     * WebRTC renders incoming audio on the voice-call stream, and Android only
     * makes that stream audible once the AudioManager is in
     * MODE_IN_COMMUNICATION. Without this the call connects, RTP arrives and
     * is decoded correctly, and the user still hears nothing - which is
     * exactly how this presented: audio from the phone played fine on the
     * desktop, but audio sent to the phone was silent. It also has to be set
     * for the speakerphone toggle to have any effect.
     */
    private fun startAudioSession() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        savedAudioMode = am.mode
        @Suppress("DEPRECATION")
        savedSpeakerphone = am.isSpeakerphoneOn

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .build()
                .also { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        am.mode = AudioManager.MODE_IN_COMMUNICATION

        routeToSpeaker(am, true)
        _state.update { it.copy(isSpeakerOn = true) }
    }

    /**
     * Forces playback to the loudspeaker (or back to the earpiece).
     *
     * setSpeakerphoneOn() is deprecated from API 31 and is simply ignored on
     * many devices, so on Android 12+ routing has to go through
     * setCommunicationDevice(). MODE_IN_COMMUNICATION otherwise defaults to
     * the earpiece, which is inaudible unless the phone is held to your ear -
     * indistinguishable from "the call has no audio" when it's on a desk.
     */
    private fun routeToSpeaker(am: AudioManager, speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                         else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = am.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) {
                val ok = am.setCommunicationDevice(device)
                Log.d(TAG, "routeToSpeaker($speaker) via setCommunicationDevice -> $ok")
                return
            }
            Log.w(TAG, "no communication device of type $wanted; falling back")
        }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speaker
        Log.d(TAG, "routeToSpeaker($speaker) via setSpeakerphoneOn (legacy)")
    }

    /** Hands the audio stack back to whatever owned it before the call. */
    private fun stopAudioSession() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Release our forced routing before restoring the previous mode,
        // otherwise the speaker override can outlive the call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        }
        am.mode = savedAudioMode
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = savedSpeakerphone

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        // Don't gather ICE candidates from VPN or loopback adapters.
        //
        // WebRTC follows Android's single "active network", which is the VPN
        // whenever one is up - even a split-tunnel VPN that deliberately
        // bypasses the LAN. Observed directly: with a tunnel active the phone
        // advertised exactly one UDP candidate, the tunnel's own 10.10.10.1,
        // and never offered wlan0 at all, so a peer on the same subnet had
        // nothing it could pair with and the call sat on "Connecting" until
        // it timed out. Skipping the tunnel lets the real Wi-Fi/cellular
        // interface be advertised instead.
        //
        // Values are the ADAPTER_TYPE_* bits from PeerConnectionFactory.Options;
        // they're package-private there, so they're spelled out here.
        val adapterTypeVpn = 1 shl 3
        val adapterTypeLoopback = 1 shl 4
        val options = PeerConnectionFactory.Options().apply {
            networkIgnoreMask = adapterTypeVpn or adapterTypeLoopback
        }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    /** Places a call. [calleeName] is already known by the caller's UI (the open chat's title). */
    fun startOutgoingCall(chatId: String, calleeId: String, calleeName: String) {
        scope.launch {
            if (_state.value.status != CallStatus.IDLE) return@launch
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            myUserId = tokenManager.getCurrentUserId().getOrNull() ?: return@launch
            // Resolved up front: the createOffer callback below is not a
            // coroutine, so it cannot do a suspending DAO lookup itself.
            myDisplayName = resolveMyDisplayName()
            ensureFactory()

            val callId = UUID.randomUUID().toString()
            _state.value = CallUiState(
                status = CallStatus.OUTGOING_RINGING,
                callId = callId,
                chatId = chatId,
                peerUserId = calleeId,
                peerName = calleeName
            )
            CallForegroundService.start(context)

            startAudioSession()
            val iceServers = fetchIceServers(token)
            createPeerConnection(iceServers, callId, calleeId)
            addLocalAudioTrack()

            peerConnection?.createOffer(SdpObserverAdapter(onCreate = { offer ->
                peerConnection?.setLocalDescription(SdpObserverAdapter(), offer)
                webSocketManager.sendCallSignal(
                    "call:invite",
                    mapOf(
                        "to_user_id" to calleeId,
                        "from_user_id" to myUserId,
                        "chat_id" to chatId,
                        "call_id" to callId,
                        "sdp" to offer.description,
                        // Who is calling. The callee cannot always derive this
                        // locally - it may hold no cached chat entry for us -
                        // and an incoming call then just reads "Unknown".
                        "from_name" to myDisplayName
                    )
                )
            }, onFailure = { err ->
                Log.e(TAG, "createOffer failed: $err")
                endCallLocally("failed")
            }), MediaConstraints())
        }
    }

    /** Accepts the currently-ringing incoming call. */
    fun acceptCall() {
        val current = _state.value
        if (current.status != CallStatus.INCOMING_RINGING) return
        val offerSdp = pendingOfferSdp ?: return

        scope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            myUserId = tokenManager.getCurrentUserId().getOrNull() ?: return@launch
            ensureFactory()

            _state.update { it.copy(status = CallStatus.CONNECTING) }

            startAudioSession()
            val iceServers = fetchIceServers(token)
            createPeerConnection(iceServers, current.callId, current.peerUserId)

            // Remote offer first, local track second. Under Unified Plan
            // addTrack() on a still-empty connection creates an unassociated
            // transceiver; applying the offer afterwards then builds its own
            // for the offer's m-line, and the answer can come out carrying a
            // rejected/inactive audio section. libdatachannel rejects that
            // with "Remote description has no active media" and the caller
            // tears the call down. Applying the offer first means addTrack()
            // attaches to the transceiver the offer already created.
            peerConnection?.setRemoteDescription(
                SdpObserverAdapter(),
                SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            )
            remoteDescriptionSet = true
            flushPendingRemoteCandidates()
            addLocalAudioTrack()
            peerConnection?.createAnswer(SdpObserverAdapter(onCreate = { answer ->
                peerConnection?.setLocalDescription(SdpObserverAdapter(), answer)
                webSocketManager.sendCallSignal(
                    "call:answer",
                    mapOf(
                        "to_user_id" to current.peerUserId,
                        "from_user_id" to myUserId,
                        "call_id" to current.callId,
                        "sdp" to answer.description
                    )
                )
            }, onFailure = { err ->
                Log.e(TAG, "createAnswer failed: $err")
                endCallLocally("failed")
            }), MediaConstraints())
        }
    }

    /** Declines the currently-ringing incoming call. */
    fun rejectCall() {
        val current = _state.value
        if (current.status != CallStatus.INCOMING_RINGING) return
        webSocketManager.sendCallSignal(
            "call:reject",
            mapOf(
                "to_user_id" to current.peerUserId,
                "from_user_id" to myUserId,
                "call_id" to current.callId,
                "reason" to "declined"
            )
        )
        endCallLocally("declined")
    }

    /** Ends the current call, however far along it is. Safe to call when idle. */
    fun endCall() {
        val current = _state.value
        if (current.status != CallStatus.IDLE && current.status != CallStatus.ENDED) {
            webSocketManager.sendCallSignal(
                "call:end",
                mapOf(
                    "to_user_id" to current.peerUserId,
                    "from_user_id" to myUserId,
                    "call_id" to current.callId,
                    "reason" to "hangup"
                )
            )
        }
        endCallLocally("hangup")
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        localAudioTrack?.setEnabled(!newMuted)
        _state.update { it.copy(isMuted = newMuted) }
    }

    fun toggleSpeaker() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val newSpeaker = !_state.value.isSpeakerOn
        // Same API-level caveat as startAudioSession - see routeToSpeaker.
        routeToSpeaker(audioManager, newSpeaker)
        _state.update { it.copy(isSpeakerOn = newSpeaker) }
    }

    /** Called by the UI once it's resolved the caller's display name from the chat list. */
    fun updatePeerName(name: String) {
        if (name.isBlank()) return
        _state.update { it.copy(peerName = name) }
    }

    /** Clears a terminal ENDED state back to IDLE once the UI has shown it. Call history remains server-side. */
    fun dismissEnded() {
        if (_state.value.status == CallStatus.ENDED) {
            _state.value = CallUiState()
        }
    }

    private fun handleSignal(signal: IncomingCallSignal) {
        when (signal.type) {
            "call:invite" -> {
                // ENDED is a terminal display state, not an active call, so it
                // must not make us look busy - a redial arriving inside that
                // window is exactly what someone does after a declined call.
                if (_state.value.status == CallStatus.ENDED) {
                    idleResetJob?.cancel()
                    _state.value = CallUiState()
                }
                if (_state.value.status != CallStatus.IDLE) {
                    // Already on a call - tell them we're busy rather than
                    // silently dropping the invite with no explanation.
                    webSocketManager.sendCallSignal(
                        "call:end",
                        mapOf(
                            "to_user_id" to signal.fromUserId,
                            "from_user_id" to myUserId,
                            "call_id" to signal.callId,
                            "reason" to "busy"
                        )
                    )
                    return
                }
                // Every reply we might send back (reject, busy, end) stamps
                // from_user_id with this. A process started by the background
                // service that has only ever *received* a call never ran the
                // paths that set it, so declining went out with an empty id.
                scope.launch {
                    if (myUserId.isEmpty()) {
                        myUserId = tokenManager.getCurrentUserId().getOrNull() ?: ""
                    }
                }
                pendingOfferSdp = signal.sdp
                _state.value = CallUiState(
                    status = CallStatus.INCOMING_RINGING,
                    callId = signal.callId,
                    chatId = signal.chatId,
                    peerUserId = signal.fromUserId,
                    peerName = signal.fromName
                )
                CallForegroundService.start(context)
            }

            "call:answer" -> {
                if (signal.callId != _state.value.callId) return
                peerConnection?.setRemoteDescription(
                    SdpObserverAdapter(),
                    SessionDescription(SessionDescription.Type.ANSWER, signal.sdp)
                )
                remoteDescriptionSet = true
                flushPendingRemoteCandidates()
                _state.update { it.copy(status = CallStatus.CONNECTING) }
            }

            "call:ice_candidate" -> {
                if (signal.callId != _state.value.callId) return
                applyRemoteCandidate(
                    IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                )
            }

            "call:reject" -> {
                if (signal.callId != _state.value.callId) return
                endCallLocally("declined")
            }

            "call:end" -> {
                if (signal.callId != _state.value.callId) return
                endCallLocally(signal.reason.ifBlank { "ended" })
            }
        }
    }

    private fun endCallLocally(reason: String) {
        statsJob?.cancel()
        statsJob = null
        pendingOfferSdp = null
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false

        // Detach from the field BEFORE closing. close() delivers
        // onConnectionChange(CLOSED) synchronously on this same thread, so
        // anything still reachable through peerConnection at that moment can
        // be re-entered; clearing it first makes a second pass a no-op
        // instead of unbounded recursion.
        val pc = peerConnection
        peerConnection = null
        pc?.close()

        audioSource?.dispose()
        audioSource = null
        localAudioTrack = null
        stopAudioSession()

        val previous = _state.value
        val wasActive = previous.status != CallStatus.IDLE
        _state.value = if (wasActive) {
            // Carry the peer's identity into the ENDED state. Building a fresh
            // CallUiState here dropped peerName, so for the ~1.5s the "call
            // ended" screen is up the name blanked out and the UI fell back to
            // "Unknown" - the contact appeared to change identity as the call
            // closed.
            previous.copy(
                status = CallStatus.ENDED,
                endReason = reason,
                isMuted = false,
                connectedAtMs = 0
            )
        } else {
            CallUiState()
        }

        // ENDED is only a display state, and it used to be cleared solely by
        // the on-screen overlay calling dismissEnded(). Declining from the
        // notification with the app closed means no overlay ever composes, so
        // the state stuck on ENDED and every later invite was answered
        // "busy" - the peer became permanently uncallable until the app was
        // reopened. Clearing it here makes the reset independent of the UI.
        if (wasActive) {
            val endedCallId = previous.callId
            idleResetJob?.cancel()
            idleResetJob = scope.launch {
                delay(ENDED_DISPLAY_MS)
                if (_state.value.status == CallStatus.ENDED && _state.value.callId == endedCallId) {
                    _state.value = CallUiState()
                }
            }
        }
    }

    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>, callId: String, peerUserId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                webSocketManager.sendCallSignal(
                    "call:ice_candidate",
                    mapOf(
                        "to_user_id" to peerUserId,
                        "from_user_id" to myUserId,
                        "call_id" to callId,
                        "candidate" to candidate.sdp,
                        "sdp_mid" to (candidate.sdpMid ?: ""),
                        "sdp_mline_index" to candidate.sdpMLineIndex
                    )
                )
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        startStatsLogging()
                        _state.update {
                            if (it.status != CallStatus.CONNECTED) {
                                it.copy(status = CallStatus.CONNECTED, connectedAtMs = System.currentTimeMillis())
                            } else it
                        }
                    }
                    // Only FAILED. CLOSED is the expected consequence of our
                    // own close() inside endCallLocally(), and reacting to it
                    // re-entered endCallLocally -> close() -> CLOSED -> ...
                    // until the stack overflowed and the process took SIGABRT.
                    PeerConnection.PeerConnectionState.FAILED -> {
                        if (_state.value.callId == callId) endCallLocally("failed")
                    }
                    else -> {}
                }
            }
        })
    }

    private fun addLocalAudioTrack() {
        val f = factory ?: return
        val constraints = MediaConstraints().apply {
            // Echo cancellation/noise suppression/auto-gain are all on by
            // default in WebRTC's audio pipeline; nothing to set here.
        }
        audioSource = f.createAudioSource(constraints)
        localAudioTrack = f.createAudioTrack("audio_track", audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("call_stream"))
    }

    private suspend fun fetchIceServers(token: String): List<PeerConnection.IceServer> {
        return try {
            val response = chatApiService.getIceServers("Bearer $token")
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.iceServers.map { dto ->
                    val builder = PeerConnection.IceServer.builder(dto.urls)
                    if (!dto.username.isNullOrEmpty()) builder.setUsername(dto.username)
                    if (!dto.credential.isNullOrEmpty()) builder.setPassword(dto.credential)
                    builder.createIceServer()
                }
            } else {
                Log.w(TAG, "getIceServers failed: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getIceServers error", e)
            emptyList()
        }
    }
}
