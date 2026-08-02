package com.messenger.app.data.call

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * org.webrtc's callback interfaces predate Kotlin's default-method-friendly
 * style and require implementing everything even when only one or two
 * callbacks matter for a given call - these adapters give every method a
 * no-op default so call sites only override what they use.
 */
open class SdpObserverAdapter(
    private val onCreate: ((SessionDescription) -> Unit)? = null,
    private val onSet: (() -> Unit)? = null,
    private val onFailure: ((String) -> Unit)? = null
) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) { onCreate?.invoke(desc) }
    override fun onSetSuccess() { onSet?.invoke() }
    override fun onCreateFailure(error: String) { onFailure?.invoke(error) }
    override fun onSetFailure(error: String) { onFailure?.invoke(error) }
}

abstract class PeerConnectionObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
}
