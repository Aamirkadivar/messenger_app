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
import org.json.JSONArray
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class CallStatus { IDLE, OUTGOING_RINGING, INCOMING_RINGING, CONNECTING, CONNECTED, ENDED }

data class CallParticipantUi(
    val userId: String,
    val name: String,
    val connected: Boolean = false,
    /** Remote peer's camera (via call:media); ignored for self. */
    val cameraOn: Boolean = true
)

data class CallUiState(
    val status: CallStatus = CallStatus.IDLE,
    val callId: String = "",
    val chatId: String = "",
    val peerUserId: String = "",
    val peerName: String = "",
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    /** Whether this call was placed as a video call. Fixed for the call's lifetime. */
    val isVideoCall: Boolean = false,
    /** Whether our own camera is currently capturing/sending. */
    val isCameraOn: Boolean = false,
    /** Whether the peer says their camera is on (via call:media). */
    val remoteCameraOn: Boolean = true,
    /** Wall-clock time the call connected, for an elapsed-time display. 0 until connected. */
    val connectedAtMs: Long = 0,
    val endReason: String = "",
    val isGroupCall: Boolean = false,
    val participants: List<CallParticipantUi> = emptyList(),
    /** Signed-in user id for this session (group grid excludes self). */
    val localUserId: String = ""
)

/**
 * One mesh edge inside a group call: its own PeerConnection and edge call_id,
 * sharing the session's local audio track with every other edge.
 */
private class PeerEdge(
    val peerUserId: String,
    var peerName: String,
    var edgeCallId: String = "",
    var peerConnection: PeerConnection? = null,
    var pendingOfferSdp: String? = null,
    val pendingRemoteCandidates: MutableList<IceCandidate> = mutableListOf(),
    var remoteDescriptionSet: Boolean = false,
    var connected: Boolean = false
)

/**
 * Owns the WebRTC PeerConnection for a 1:1 audio call end to end: signaling
 * (via [WebSocketManager]'s call:* messages), ICE/SDP negotiation, and the
 * local/remote audio tracks. A singleton (like ChatRepository/GroupRepository)
 * so the call survives navigating between screens - only ended explicitly or
 * by the other side hanging up.
 *
 * Group calls are a full mesh (max 4): one PeerConnection per remote
 * participant under a shared group session id, with glare resolved by
 * lexicographic user-id (lower offers to higher).
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

        /** Full-mesh cap including self (matches backend MaxGroupCallParticipants). */
        private const val MAX_GROUP_PARTICIPANTS = 4

        /**
         * Landscape capture (phone sideways / tablet). Matches Windows'
         * 640×360 Meet canvas.
         */
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 360
        private const val VIDEO_FPS = 30

        /**
         * Portrait capture when the phone is upright. Forcing 640×360 while
         * vertical crops the face heavily; sending a tall frame lets Windows
         * letterbox (black side bars) like Meet and keep more of the FOV.
         */
        private const val VIDEO_WIDTH_PORTRAIT = 360
        private const val VIDEO_HEIGHT_PORTRAIT = 640

        /** Same sizes, lower fps when encoding up to 3 mesh edges. */
        private const val GROUP_VIDEO_FPS = 15
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    /** Every local AudioTrack created for 1:1 or mesh edges (mute toggles all). */
    private val localAudioTracks = mutableListOf<AudioTrack>()

    /** Mesh edges for an active group call, keyed by remote user id. */
    private val peers = mutableMapOf<String, PeerEdge>()

    /**
     * Shared GL context for the whole video pipeline: camera capture textures,
     * the hardware encoder/decoder factories, and the UI's renderers all have
     * to use the same one or frames simply never appear. Never released - it's
     * one EGL context for the process lifetime, same as the factory.
     */
    val eglBase: EglBase by lazy { EglBase.create() }

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrackInternal: VideoTrack? = null
    /** Every local VideoTrack created for 1:1 or mesh edges (camera toggles all). */
    private val localVideoTracks = mutableListOf<VideoTrack>()

    /**
     * The video tracks live outside [CallUiState]: they're stateful native
     * objects, not values, and the UI needs them only to attach/detach
     * renderer sinks.
     */
    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    /** peerUserId → remote VideoTrack for group (and optionally 1:1) video. */
    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

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

    private fun applyRemoteCandidate(edge: PeerEdge, candidate: IceCandidate) {
        if (edge.peerConnection == null || !edge.remoteDescriptionSet) {
            edge.pendingRemoteCandidates.add(candidate)
            return
        }
        edge.peerConnection?.addIceCandidate(candidate)
    }

    private fun flushPendingRemoteCandidates(edge: PeerEdge) {
        if (edge.pendingRemoteCandidates.isEmpty()) return
        Log.d(TAG, "flushing ${edge.pendingRemoteCandidates.size} buffered ICE for ${edge.peerUserId}")
        val buffered = edge.pendingRemoteCandidates.toList()
        edge.pendingRemoteCandidates.clear()
        buffered.forEach { applyRemoteCandidate(edge, it) }
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
                if (_state.value.isGroupCall) {
                    if (peers.isEmpty()) break
                    peers.values.forEach { edge ->
                        val pc = edge.peerConnection ?: return@forEach
                        pc.getStats { report ->
                            report.statsMap.values
                                .filter { it.type == "inbound-rtp" || it.type == "outbound-rtp" }
                                .forEach { s ->
                                    val m = s.members
                                    Log.d(
                                        TAG,
                                        "RTP[${edge.peerUserId}] ${s.type}: packets=${m["packetsReceived"] ?: m["packetsSent"]} " +
                                            "bytes=${m["bytesReceived"] ?: m["bytesSent"]} kind=${m["kind"]}"
                                    )
                                }
                        }
                    }
                } else {
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
            // Hardware codecs where available, with WebRTC's built-in software
            // VP8/VP9 as fallback. VP8 is what the Windows client encodes and
            // decodes (libvpx), so the software fallback guarantees interop
            // even on devices with no usable hardware codec.
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** Places a call. [calleeName] is already known by the caller's UI (the open chat's title). */
    fun startOutgoingCall(chatId: String, calleeId: String, calleeName: String, video: Boolean = false) {
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
                peerName = calleeName,
                isVideoCall = video,
                isCameraOn = video,
                localUserId = myUserId
            )
            CallForegroundService.start(context)

            startAudioSession()
            val iceServers = fetchIceServers(token)
            createPeerConnection(iceServers, callId, calleeId)
            addLocalAudioTrack()
            if (video) addLocalVideoTrack()

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
                        "from_name" to myDisplayName,
                        // Video-ness is fixed at invite time - there is no
                        // mid-call renegotiation, so the callee needs to know
                        // before answering (both for its UI and to attach its
                        // own camera track to the offer's video m-line).
                        "video" to video
                    )
                )
            }, onFailure = { err ->
                Log.e(TAG, "createOffer failed: $err")
                endCallLocally("failed")
            }), MediaConstraints())
        }
    }

    /**
     * Starts a group call (full mesh). [members] is id→name for everyone who
     * should be in the session including self; capped at 4. [video] enables
     * camera capture on the same mesh (one VideoTrack per edge).
     */
    fun startGroupCall(
        chatId: String,
        groupName: String,
        members: List<Pair<String, String>>,
        video: Boolean = false
    ) {
        scope.launch {
            if (_state.value.status != CallStatus.IDLE) return@launch
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            myUserId = tokenManager.getCurrentUserId().getOrNull() ?: return@launch
            myDisplayName = resolveMyDisplayName()
            ensureFactory()

            val others = members.filter { it.first != myUserId && it.first.isNotBlank() }
            if (others.isEmpty()) {
                Log.w(TAG, "startGroupCall: no other members")
                return@launch
            }
            val cappedOthers = if (others.size > MAX_GROUP_PARTICIPANTS - 1) {
                Log.w(TAG, "startGroupCall: truncating ${others.size} others to ${MAX_GROUP_PARTICIPANTS - 1}")
                others.take(MAX_GROUP_PARTICIPANTS - 1)
            } else {
                others
            }
            val roster = buildList {
                add(CallParticipantUi(myUserId, myDisplayName.ifBlank { "You" }, connected = true))
                cappedOthers.forEach { (id, name) ->
                    add(CallParticipantUi(id, name.ifBlank { "Unknown" }))
                }
            }
            val participantIds = roster.map { it.userId }
            val callId = UUID.randomUUID().toString()

            _state.value = CallUiState(
                status = CallStatus.OUTGOING_RINGING,
                callId = callId,
                chatId = chatId,
                peerName = groupName,
                isVideoCall = video,
                isCameraOn = video,
                isGroupCall = true,
                participants = roster,
                localUserId = myUserId
            )
            CallForegroundService.start(context)
            startAudioSession()
            ensureLocalAudioTrack()
            if (video) ensureLocalVideoCapture()

            webSocketManager.sendCallSignal(
                "call:group_invite",
                mapOf(
                    "call_id" to callId,
                    "chat_id" to chatId,
                    "from_user_id" to myUserId,
                    "from_name" to myDisplayName,
                    "video" to video,
                    "participant_ids" to JSONArray(participantIds)
                )
            )
            // Initiator is already "in"; mesh edges form when others join.
            Log.d(TAG, "group call started $callId video=$video participants=$participantIds (token ok=${token.isNotBlank()})")
        }
    }

    /** Accepts the currently-ringing incoming call. */
    fun acceptCall() {
        val current = _state.value
        if (current.status != CallStatus.INCOMING_RINGING) return

        if (current.isGroupCall) {
            acceptGroupCall(current)
            return
        }

        val offerSdp = pendingOfferSdp ?: return

        scope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            myUserId = tokenManager.getCurrentUserId().getOrNull() ?: return@launch
            ensureFactory()

            _state.update { it.copy(status = CallStatus.CONNECTING, localUserId = myUserId.ifBlank { it.localUserId }) }

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
            if (current.isVideoCall) {
                addLocalVideoTrack()
                _state.update { it.copy(isCameraOn = localVideoTrackInternal != null) }
            }
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

    private fun acceptGroupCall(current: CallUiState) {
        scope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            myUserId = tokenManager.getCurrentUserId().getOrNull() ?: return@launch
            if (myDisplayName.isBlank()) myDisplayName = resolveMyDisplayName()
            ensureFactory()

            _state.update { s ->
                s.copy(
                    status = CallStatus.CONNECTING,
                    localUserId = myUserId.ifBlank { s.localUserId },
                    participants = s.participants.map { p ->
                        if (p.userId == myUserId) p.copy(connected = true) else p
                    }
                )
            }
            startAudioSession()
            ensureLocalAudioTrack()
            if (current.isVideoCall) {
                ensureLocalVideoCapture()
                _state.update { it.copy(isCameraOn = localVideoTrackInternal != null) }
            }

            val participantIds = (current.participants.map { it.userId } + myUserId)
                .distinct()
                .filter { it.isNotBlank() }

            webSocketManager.sendCallSignal(
                "call:group_join",
                mapOf(
                    "call_id" to current.callId,
                    "chat_id" to current.chatId,
                    "from_user_id" to myUserId,
                    "from_name" to myDisplayName,
                    "participant_ids" to JSONArray(participantIds)
                )
            )

            val iceServers = fetchIceServers(token)
            participantIds.filter { it != myUserId }.forEach { peerId ->
                val name = current.participants.firstOrNull { it.userId == peerId }?.name ?: "Unknown"
                ensureMeshEdge(peerId, name, iceServers)
            }
        }
    }

    /** Declines the currently-ringing incoming call. */
    fun rejectCall() {
        val current = _state.value
        if (current.status != CallStatus.INCOMING_RINGING) return
        if (current.isGroupCall) {
            // Dismiss without joining; notify the roster so they can drop us.
            val ids = current.participants.map { it.userId }.ifEmpty {
                listOfNotNull(myUserId.takeIf { it.isNotBlank() })
            }
            webSocketManager.sendCallSignal(
                "call:group_leave",
                mapOf(
                    "call_id" to current.callId,
                    "chat_id" to current.chatId,
                    "from_user_id" to myUserId,
                    "participant_ids" to JSONArray(ids),
                    "reason" to "declined"
                )
            )
            endCallLocally("declined")
            return
        }
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
            if (current.isGroupCall) {
                val ids = current.participants.map { it.userId }.ifEmpty {
                    peers.keys.toList() + listOfNotNull(myUserId.takeIf { it.isNotBlank() })
                }
                webSocketManager.sendCallSignal(
                    "call:group_leave",
                    mapOf(
                        "call_id" to current.callId,
                        "chat_id" to current.chatId,
                        "from_user_id" to myUserId,
                        "participant_ids" to JSONArray(ids.distinct()),
                        "reason" to "hangup"
                    )
                )
            } else {
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
        }
        endCallLocally("hangup")
    }

    fun toggleMute() {
        val newMuted = !_state.value.isMuted
        localAudioTracks.forEach { it.setEnabled(!newMuted) }
        _state.update { it.copy(isMuted = newMuted) }
    }

    fun toggleSpeaker() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val newSpeaker = !_state.value.isSpeakerOn
        // Same API-level caveat as startAudioSession - see routeToSpeaker.
        routeToSpeaker(audioManager, newSpeaker)
        _state.update { it.copy(isSpeakerOn = newSpeaker) }
    }

    /**
     * Turns our camera off/on within a video call. The track stays negotiated
     * (no renegotiation); off just stops capture, and peers are told via
     * call:media so they can show an avatar instead of a frozen last frame.
     */
    fun toggleCamera() {
        val current = _state.value
        if (!current.isVideoCall) return
        val newOn = !current.isCameraOn
        val (w, h, fps) = captureFormat()
        if (newOn) {
            if (videoSource == null) {
                ensureLocalVideoCapture()
                // Late-enable: attach a track to every live edge / 1:1 PC.
                if (current.isGroupCall) {
                    peers.values.forEach { edge -> addLocalVideoTrackTo(edge.peerConnection) }
                } else {
                    addLocalVideoTrack()
                }
            } else {
                runCatching { videoCapturer?.startCapture(w, h, fps) }
                    .onFailure { Log.w(TAG, "startCapture failed", it) }
            }
            localVideoTracks.forEach { it.setEnabled(true) }
            localVideoTrackInternal?.setEnabled(true)
        } else {
            localVideoTracks.forEach { it.setEnabled(false) }
            localVideoTrackInternal?.setEnabled(false)
            runCatching { videoCapturer?.stopCapture() }
                .onFailure { Log.w(TAG, "stopCapture failed", it) }
        }
        if (current.isGroupCall) {
            peers.values.forEach { edge ->
                webSocketManager.sendCallSignal(
                    "call:media",
                    mapOf(
                        "to_user_id" to edge.peerUserId,
                        "from_user_id" to myUserId,
                        "call_id" to edge.edgeCallId.ifBlank { current.callId },
                        "group_call_id" to current.callId,
                        "camera" to newOn
                    )
                )
            }
        } else {
            webSocketManager.sendCallSignal(
                "call:media",
                mapOf(
                    "to_user_id" to current.peerUserId,
                    "from_user_id" to myUserId,
                    "call_id" to current.callId,
                    "camera" to newOn
                )
            )
        }
        _state.update { it.copy(isCameraOn = newOn) }
    }

    /** Flips between front and back cameras. No-op for audio calls. */
    fun switchCamera() {
        if (!_state.value.isVideoCall) return
        videoCapturer?.switchCamera(null)
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

    private fun inActiveGroupSession(groupCallId: String): Boolean {
        val s = _state.value
        if (!s.isGroupCall || groupCallId.isBlank()) return false
        if (s.callId != groupCallId) return false
        return when (s.status) {
            CallStatus.OUTGOING_RINGING, CallStatus.CONNECTING, CallStatus.CONNECTED -> true
            // Ringing: buffer mesh offers but do not answer until accept.
            CallStatus.INCOMING_RINGING -> true
            else -> false
        }
    }

    private fun handleSignal(signal: IncomingCallSignal) {
        when (signal.type) {
            "call:group_invite" -> handleGroupInvite(signal)
            "call:group_join" -> handleGroupJoin(signal)
            "call:group_leave" -> handleGroupLeave(signal)

            "call:invite" -> {
                if (signal.groupCallId.isNotBlank()) {
                    handleMeshInvite(signal)
                    return
                }
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
                    peerName = signal.fromName,
                    isVideoCall = signal.video,
                    localUserId = myUserId
                )
                CallForegroundService.start(context)
            }

            "call:answer" -> {
                if (_state.value.isGroupCall) {
                    val edge = findEdge(signal) ?: return
                    edge.peerConnection?.setRemoteDescription(
                        SdpObserverAdapter(),
                        SessionDescription(SessionDescription.Type.ANSWER, signal.sdp)
                    )
                    edge.remoteDescriptionSet = true
                    flushPendingRemoteCandidates(edge)
                    maybeMarkConnecting()
                    return
                }
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
                if (_state.value.isGroupCall) {
                    val edge = findEdge(signal) ?: return
                    applyRemoteCandidate(
                        edge,
                        IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                    )
                    return
                }
                if (signal.callId != _state.value.callId) return
                applyRemoteCandidate(
                    IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                )
            }

            "call:reject" -> {
                if (_state.value.isGroupCall) return
                if (signal.callId != _state.value.callId) return
                endCallLocally("declined")
            }

            "call:end" -> {
                if (_state.value.isGroupCall) {
                    val edge = findEdge(signal) ?: return
                    tearMeshEdge(edge.peerUserId)
                    return
                }
                if (signal.callId != _state.value.callId) return
                endCallLocally(signal.reason.ifBlank { "ended" })
            }

            "call:media" -> {
                if (_state.value.isGroupCall) {
                    val from = signal.fromUserId
                    if (from.isBlank()) return
                    _state.update { s ->
                        s.copy(
                            participants = s.participants.map { p ->
                                if (p.userId == from) p.copy(cameraOn = signal.cameraOn) else p
                            }
                        )
                    }
                    return
                }
                if (signal.callId != _state.value.callId) return
                _state.update { it.copy(remoteCameraOn = signal.cameraOn) }
            }
        }
    }

    private fun handleGroupInvite(signal: IncomingCallSignal) {
        if (_state.value.status == CallStatus.ENDED) {
            idleResetJob?.cancel()
            _state.value = CallUiState()
        }
        if (_state.value.status != CallStatus.IDLE) {
            Log.d(TAG, "ignoring group_invite while busy")
            return
        }
        scope.launch {
            if (myUserId.isEmpty()) {
                myUserId = tokenManager.getCurrentUserId().getOrNull() ?: ""
            }
            if (myUserId.isNotBlank() && _state.value.localUserId.isBlank()) {
                _state.update { it.copy(localUserId = myUserId) }
            }
        }
        val ids = signal.participantIds.ifEmpty {
            listOfNotNull(signal.fromUserId.takeIf { it.isNotBlank() }, myUserId.takeIf { it.isNotBlank() })
        }.distinct()
        val participants = ids.map { id ->
            val name = when (id) {
                signal.fromUserId -> signal.fromName.ifBlank { "Unknown" }
                myUserId -> myDisplayName.ifBlank { "You" }
                else -> "Unknown"
            }
            CallParticipantUi(id, name, connected = id == signal.fromUserId)
        }
        _state.value = CallUiState(
            status = CallStatus.INCOMING_RINGING,
            callId = signal.callId,
            chatId = signal.chatId,
            peerUserId = signal.fromUserId,
            peerName = signal.fromName.ifBlank { "Group call" },
            isVideoCall = signal.video,
            isCameraOn = signal.video,
            isGroupCall = true,
            participants = participants,
            localUserId = myUserId
        )
        // Title prefers the chat name once CallViewModel resolves it.
        CallForegroundService.start(context)
    }

    private fun handleGroupJoin(signal: IncomingCallSignal) {
        val current = _state.value
        if (!current.isGroupCall) return
        if (current.callId != signal.callId) return
        if (current.status == CallStatus.INCOMING_RINGING || current.status == CallStatus.IDLE ||
            current.status == CallStatus.ENDED
        ) {
            // Not in the session yet (still ringing / idle) - ignore mesh setup.
            // Still update the roster so accept uses fresh participant_ids.
            if (current.status == CallStatus.INCOMING_RINGING) {
                upsertParticipantsFromJoin(signal)
            }
            return
        }

        upsertParticipantsFromJoin(signal)
        val peerId = signal.fromUserId
        if (peerId.isBlank() || peerId == myUserId) return
        val name = signal.fromName.ifBlank {
            current.participants.firstOrNull { it.userId == peerId }?.name ?: "Unknown"
        }
        scope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            ensureFactory()
            ensureLocalAudioTrack()
            val iceServers = fetchIceServers(token)
            ensureMeshEdge(peerId, name, iceServers)
        }
    }

    private fun upsertParticipantsFromJoin(signal: IncomingCallSignal) {
        _state.update { s ->
            val byId = s.participants.associateBy { it.userId }.toMutableMap()
            val ids = signal.participantIds.ifEmpty {
                byId.keys + signal.fromUserId
            }.distinct()
            ids.forEach { id ->
                if (id.isBlank()) return@forEach
                val existing = byId[id]
                val name = when {
                    id == signal.fromUserId && signal.fromName.isNotBlank() -> signal.fromName
                    existing != null -> existing.name
                    id == myUserId -> myDisplayName.ifBlank { "You" }
                    else -> "Unknown"
                }
                byId[id] = CallParticipantUi(
                    userId = id,
                    name = name,
                    connected = existing?.connected == true || id == signal.fromUserId || id == myUserId,
                    cameraOn = existing?.cameraOn ?: true
                )
            }
            if (signal.fromName.isNotBlank()) {
                byId[signal.fromUserId] = CallParticipantUi(
                    signal.fromUserId,
                    signal.fromName,
                    connected = byId[signal.fromUserId]?.connected == true,
                    cameraOn = byId[signal.fromUserId]?.cameraOn ?: true
                )
            }
            s.copy(participants = byId.values.toList())
        }
    }

    private fun handleGroupLeave(signal: IncomingCallSignal) {
        val current = _state.value
        if (!current.isGroupCall) {
            // Server may send group_leave(reason=too_large) to the initiator.
            if (signal.reason == "too_large" && current.status != CallStatus.IDLE) {
                endCallLocally("too_large")
            }
            return
        }
        if (current.callId != signal.callId && signal.callId.isNotBlank()) return

        if (signal.reason == "too_large") {
            endCallLocally("too_large")
            return
        }

        val leaver = signal.fromUserId
        if (leaver.isBlank()) return
        if (leaver == myUserId) {
            endCallLocally(signal.reason.ifBlank { "ended" })
            return
        }

        tearMeshEdge(leaver)
        _state.update { s ->
            s.copy(participants = s.participants.filter { it.userId != leaver })
        }

        // If nobody else remains, hang up locally.
        val others = _state.value.participants.filter { it.userId != myUserId }
        if (others.isEmpty() && _state.value.status != CallStatus.INCOMING_RINGING) {
            endCallLocally(signal.reason.ifBlank { "ended" })
        }
    }

    private fun handleMeshInvite(signal: IncomingCallSignal) {
        if (!inActiveGroupSession(signal.groupCallId)) {
            // Prefer only answering when already in that group session.
            Log.d(TAG, "ignoring mesh invite outside session group_call_id=${signal.groupCallId}")
            return
        }
        val peerId = signal.fromUserId
        if (peerId.isBlank() || peerId == myUserId) return

        val edge = peers.getOrPut(peerId) {
            PeerEdge(
                peerUserId = peerId,
                peerName = signal.fromName.ifBlank {
                    _state.value.participants.firstOrNull { it.userId == peerId }?.name ?: "Unknown"
                }
            )
        }
        if (signal.fromName.isNotBlank()) edge.peerName = signal.fromName
        edge.edgeCallId = signal.callId
        edge.pendingOfferSdp = signal.sdp

        // While still ringing, buffer only; answer after accept.
        if (_state.value.status == CallStatus.INCOMING_RINGING) return

        scope.launch { answerMeshEdge(edge) }
    }

    private fun findEdge(signal: IncomingCallSignal): PeerEdge? {
        if (signal.callId.isNotBlank()) {
            peers.values.firstOrNull { it.edgeCallId == signal.callId }?.let { return it }
        }
        if (signal.fromUserId.isNotBlank()) {
            peers[signal.fromUserId]?.let { return it }
        }
        return null
    }

    /**
     * Ensures a mesh PeerConnection to [peerId]. Glare rule: lexicographically
     * lower userId offers; higher waits for invite.
     */
    private fun ensureMeshEdge(
        peerId: String,
        peerName: String,
        iceServers: List<PeerConnection.IceServer>
    ) {
        if (peerId.isBlank() || peerId == myUserId) return
        val existing = peers[peerId]
        if (existing?.peerConnection != null) return

        val edge = existing ?: PeerEdge(peerUserId = peerId, peerName = peerName).also {
            peers[peerId] = it
        }
        if (peerName.isNotBlank()) edge.peerName = peerName

        // Buffered offer from glare winner / early invite - answer it.
        if (!edge.pendingOfferSdp.isNullOrBlank()) {
            scope.launch { answerMeshEdge(edge) }
            return
        }

        if (myUserId > peerId) {
            // Higher id waits for the lower id's invite.
            Log.d(TAG, "mesh edge wait for invite from $peerId")
            return
        }

        // We offer.
        val session = _state.value
        val edgeCallId = UUID.randomUUID().toString()
        edge.edgeCallId = edgeCallId
        ensureLocalAudioTrack()
        if (session.isVideoCall) ensureLocalVideoCapture()
        createMeshPeerConnection(edge, iceServers, edgeCallId)
        addLocalAudioTrackTo(edge.peerConnection)
        if (session.isVideoCall) addLocalVideoTrackTo(edge.peerConnection)

        edge.peerConnection?.createOffer(SdpObserverAdapter(onCreate = { offer ->
            edge.peerConnection?.setLocalDescription(SdpObserverAdapter(), offer)
            webSocketManager.sendCallSignal(
                "call:invite",
                mapOf(
                    "to_user_id" to peerId,
                    "from_user_id" to myUserId,
                    "chat_id" to session.chatId,
                    "call_id" to edgeCallId,
                    "group_call_id" to session.callId,
                    "sdp" to offer.description,
                    "from_name" to myDisplayName,
                    "video" to session.isVideoCall
                )
            )
        }, onFailure = { err ->
            Log.e(TAG, "mesh createOffer failed for $peerId: $err")
            tearMeshEdge(peerId)
        }), MediaConstraints())
    }

    private suspend fun answerMeshEdge(edge: PeerEdge) {
        val offerSdp = edge.pendingOfferSdp ?: return
        if (edge.peerConnection != null && edge.remoteDescriptionSet) return

        val token = tokenManager.getAccessToken().getOrNull() ?: return
        ensureFactory()
        val session = _state.value
        ensureLocalAudioTrack()
        if (session.isVideoCall) ensureLocalVideoCapture()
        val iceServers = fetchIceServers(token)

        if (edge.peerConnection == null) {
            createMeshPeerConnection(edge, iceServers, edge.edgeCallId)
        }

        // Offer first, then local track - same Unified Plan ordering as 1:1 accept.
        edge.peerConnection?.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        )
        edge.remoteDescriptionSet = true
        flushPendingRemoteCandidates(edge)
        addLocalAudioTrackTo(edge.peerConnection)
        if (session.isVideoCall) addLocalVideoTrackTo(edge.peerConnection)
        edge.pendingOfferSdp = null

        edge.peerConnection?.createAnswer(SdpObserverAdapter(onCreate = { answer ->
            edge.peerConnection?.setLocalDescription(SdpObserverAdapter(), answer)
            webSocketManager.sendCallSignal(
                "call:answer",
                mapOf(
                    "to_user_id" to edge.peerUserId,
                    "from_user_id" to myUserId,
                    "call_id" to edge.edgeCallId,
                    "group_call_id" to session.callId,
                    "sdp" to answer.description
                )
            )
        }, onFailure = { err ->
            Log.e(TAG, "mesh createAnswer failed for ${edge.peerUserId}: $err")
            tearMeshEdge(edge.peerUserId)
        }), MediaConstraints())
    }

    private fun createMeshPeerConnection(
        edge: PeerEdge,
        iceServers: List<PeerConnection.IceServer>,
        edgeCallId: String
    ) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val peerUserId = edge.peerUserId
        edge.peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                webSocketManager.sendCallSignal(
                    "call:ice_candidate",
                    mapOf(
                        "to_user_id" to peerUserId,
                        "from_user_id" to myUserId,
                        "call_id" to edgeCallId,
                        "group_call_id" to _state.value.callId,
                        "candidate" to candidate.sdp,
                        "sdp_mid" to (candidate.sdpMid ?: ""),
                        "sdp_mline_index" to candidate.sdpMLineIndex
                    )
                )
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver?.track()
                Log.d(TAG, "mesh onTrack from $peerUserId kind=${track?.kind()}")
                if (track is VideoTrack) {
                    _remoteVideoTracks.update { it + (peerUserId to track) }
                    _state.update { s ->
                        s.copy(
                            participants = s.participants.map { p ->
                                if (p.userId == peerUserId) p.copy(cameraOn = true) else p
                            }
                        )
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        edge.connected = true
                        startStatsLogging()
                        _state.update { s ->
                            val participants = s.participants.map {
                                if (it.userId == peerUserId) it.copy(connected = true) else it
                            }
                            val nextStatus = if (s.status != CallStatus.CONNECTED) {
                                CallStatus.CONNECTED
                            } else {
                                s.status
                            }
                            val connectedAt = if (s.connectedAtMs == 0L && nextStatus == CallStatus.CONNECTED) {
                                System.currentTimeMillis()
                            } else {
                                s.connectedAtMs
                            }
                            s.copy(
                                status = nextStatus,
                                connectedAtMs = connectedAt,
                                participants = participants
                            )
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        Log.w(TAG, "mesh edge FAILED with $peerUserId")
                        tearMeshEdge(peerUserId)
                    }
                    else -> {}
                }
            }
        })
    }

    private fun tearMeshEdge(peerId: String) {
        val edge = peers.remove(peerId) ?: return
        val pc = edge.peerConnection
        edge.peerConnection = null
        pc?.close()
        _remoteVideoTracks.update { it - peerId }
        _state.update { s ->
            s.copy(
                participants = s.participants.map {
                    if (it.userId == peerId) it.copy(connected = false) else it
                }
            )
        }
    }

    private fun tearAllMeshEdges() {
        val ids = peers.keys.toList()
        ids.forEach { tearMeshEdge(it) }
        peers.clear()
    }

    private fun maybeMarkConnecting() {
        _state.update {
            if (it.status == CallStatus.OUTGOING_RINGING) it.copy(status = CallStatus.CONNECTING) else it
        }
    }

    private fun endCallLocally(reason: String) {
        statsJob?.cancel()
        statsJob = null
        pendingOfferSdp = null
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
        tearAllMeshEdges()

        // The UI's renderer sinks are detached defensively (see CallScreen) -
        // clearing the flows first makes any still-composed renderer let go
        // of the tracks before the native objects go away with the PC.
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        _remoteVideoTracks.value = emptyMap()

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
        localAudioTracks.clear()
        disposeVideoCapture()
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
                isCameraOn = false,
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

            override fun onTrack(transceiver: RtpTransceiver) {
                // Fires (on both roles) when applying the remote description
                // activates a receiving transceiver. Audio is routed by WebRTC
                // itself; only video needs surfacing to the UI.
                val track = transceiver.receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "remote video track received: ${track.id()}")
                    _remoteVideoTrack.value = track
                    val peerId = _state.value.peerUserId
                    if (peerId.isNotBlank()) {
                        _remoteVideoTracks.update { it + (peerId to track) }
                    }
                }
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

    private fun ensureLocalAudioTrack() {
        if (audioSource != null) return
        val f = factory ?: return
        audioSource = f.createAudioSource(MediaConstraints())
    }

    /**
     * Adds a fresh AudioTrack from the shared [audioSource] to [pc].
     * The same MediaStreamTrack must not be attached to multiple
     * PeerConnections on Android WebRTC - one track per edge.
     */
    private fun addLocalAudioTrackTo(pc: PeerConnection?) {
        val f = factory ?: return
        val source = audioSource ?: return
        pc ?: return
        val track = f.createAudioTrack("audio_${UUID.randomUUID()}", source)
        track.setEnabled(!_state.value.isMuted)
        localAudioTracks.add(track)
        if (localAudioTrack == null) localAudioTrack = track
        pc.addTrack(track, listOf("call_stream"))
    }

    private fun addLocalAudioTrack() {
        ensureLocalAudioTrack()
        addLocalAudioTrackTo(peerConnection)
    }

    /**
     * Shared camera capture for the call: one capturer + VideoSource.
     * Each PeerConnection gets its own VideoTrack from this source via
     * [addLocalVideoTrackTo] (Android WebRTC forbids reusing one track across PCs).
     * Returns false if no camera is available.
     */
    private fun ensureLocalVideoCapture(): Boolean {
        if (videoSource != null) return true
        val f = factory ?: return false
        val capturer = createCameraCapturer() ?: run {
            Log.w(TAG, "no usable camera; continuing without local video")
            return false
        }
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("CallCaptureThread", eglBase.eglBaseContext)
        videoSource = f.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        val (w, h, fps) = captureFormat()
        try {
            capturer.startCapture(w, h, fps)
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed; continuing without local video", e)
            disposeVideoCapture()
            return false
        }
        // Preview track for the UI; may also be attached to the 1:1 PC.
        val preview = f.createVideoTrack("video_preview", videoSource).also {
            it.setEnabled(_state.value.isCameraOn)
        }
        localVideoTrackInternal = preview
        localVideoTracks.add(preview)
        _localVideoTrack.value = preview
        return true
    }

    private fun captureFormat(): Triple<Int, Int, Int> {
        val portrait = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val fps = if (_state.value.isGroupCall) GROUP_VIDEO_FPS else VIDEO_FPS
        return if (portrait) {
            Triple(VIDEO_WIDTH_PORTRAIT, VIDEO_HEIGHT_PORTRAIT, fps)
        } else {
            Triple(VIDEO_WIDTH, VIDEO_HEIGHT, fps)
        }
    }

    /**
     * Captures the front camera and adds it as a second track on the same
     * "call_stream" stream (1:1). On the answering side this must run *after*
     * setRemoteDescription for the same Unified Plan reason as audio (see
     * acceptCall). Failure is non-fatal: the call proceeds voice-only.
     */
    private fun addLocalVideoTrack() {
        val pc = peerConnection ?: return
        if (!ensureLocalVideoCapture()) return
        // Reuse the preview track for 1:1 so we don't send two encodings.
        val track = localVideoTrackInternal ?: return
        pc.addTrack(track, listOf("call_stream"))
    }

    /**
     * Adds a fresh VideoTrack from the shared [videoSource] to [pc].
     * The same MediaStreamTrack must not be attached to multiple
     * PeerConnections on Android WebRTC - one track per mesh edge.
     */
    private fun addLocalVideoTrackTo(pc: PeerConnection?) {
        val f = factory ?: return
        val source = videoSource ?: return
        pc ?: return
        val track = f.createVideoTrack("video_${UUID.randomUUID()}", source)
        track.setEnabled(_state.value.isCameraOn)
        localVideoTracks.add(track)
        pc.addTrack(track, listOf("call_stream"))
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val preferred = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
        return preferred?.let { enumerator.createCapturer(it, null) }
    }

    private fun disposeVideoCapture() {
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrackInternal = null
        localVideoTracks.clear()
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
