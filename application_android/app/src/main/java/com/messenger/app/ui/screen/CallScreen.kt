package com.messenger.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.messenger.app.data.call.CallParticipantUi
import com.messenger.app.data.call.CallStatus
import com.messenger.app.data.call.CallUiState
import com.messenger.app.ui.components.avatarColorFor
import com.messenger.app.ui.theme.AccentGreen
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen call overlay. Video calls use WhatsApp-style full-bleed tiles
 * on phone (group) / remote + portrait PiP (1:1). Audio keeps the avatar UI.
 */
@Composable
fun CallOverlay(
    state: CallUiState,
    localVideoTrack: VideoTrack?,
    remoteVideoTrack: VideoTrack?,
    remoteVideoTracks: Map<String, VideoTrack> = emptyMap(),
    eglContext: EglBase.Context,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onDismissEnded: () -> Unit
) {
    if (state.status == CallStatus.ENDED) {
        LaunchedEffect(state.callId, state.endReason) {
            delay(1500)
            onDismissEnded()
        }
    }

    val inVideoCall = state.isVideoCall &&
        (state.status == CallStatus.CONNECTING || state.status == CallStatus.CONNECTED)
    val inDirectVideo = inVideoCall && !state.isGroupCall
    val inGroupVideo = inVideoCall && state.isGroupCall
    val inGroupAudio = state.isGroupCall && !state.isVideoCall &&
        (state.status == CallStatus.CONNECTING || state.status == CallStatus.CONNECTED ||
            state.status == CallStatus.OUTGOING_RINGING || state.status == CallStatus.INCOMING_RINGING)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (inVideoCall || (state.isVideoCall && state.status == CallStatus.OUTGOING_RINGING))
                    Color.Black else MaterialTheme.colorScheme.background
            )
    ) {
        if (state.isVideoCall && state.status == CallStatus.OUTGOING_RINGING && localVideoTrack != null) {
            VideoRenderer(
                track = localVideoTrack,
                eglContext = eglContext,
                mirror = true,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        }
        if (inDirectVideo) {
            if (remoteVideoTrack != null && state.remoteCameraOn) {
                VideoRenderer(
                    track = remoteVideoTrack,
                    eglContext = eglContext,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (localVideoTrack != null && state.isCameraOn) {
                VideoRenderer(
                    track = localVideoTrack,
                    eglContext = eglContext,
                    mirror = true,
                    overlay = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .width(108.dp)
                        .height(152.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
        }
        if (inGroupVideo) {
            GroupVideoGrid(
                participants = state.participants,
                localUserId = state.localUserId,
                remoteTracks = remoteVideoTracks,
                localTrack = localVideoTrack,
                isCameraOn = state.isCameraOn,
                eglContext = eglContext,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))

            if (inDirectVideo) {
                Text(
                    state.peerName.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    statusLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                if (remoteVideoTrack == null || !state.remoteCameraOn) {
                    Spacer(Modifier.weight(0.6f))
                    CallerAvatar(name = state.peerName)
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            } else if (inGroupVideo) {
                Text(
                    state.peerName.ifBlank { "Group call" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    statusLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(Modifier.weight(1f))
            } else if (inGroupAudio) {
                Text(
                    state.peerName.ifBlank { "Group call" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    statusLabel(state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                GroupAudioParticipants(participants = state.participants, localUserId = state.localUserId)
                Spacer(Modifier.weight(1f))
            } else {
                CallerAvatar(name = state.peerName)
                Spacer(Modifier.height(20.dp))
                Text(
                    state.peerName.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isVideoCall && state.status == CallStatus.OUTGOING_RINGING) Color.White
                    else MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    statusLabel(state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.isVideoCall && state.status == CallStatus.OUTGOING_RINGING)
                        Color.White.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
            }

            when (state.status) {
                CallStatus.INCOMING_RINGING -> IncomingActions(onAccept = onAccept, onReject = onReject)
                CallStatus.OUTGOING_RINGING, CallStatus.CONNECTING, CallStatus.CONNECTED ->
                    InCallActions(
                        state = state,
                        onEnd = onEnd,
                        onToggleMute = onToggleMute,
                        onToggleSpeaker = onToggleSpeaker,
                        onToggleCamera = onToggleCamera,
                        onSwitchCamera = onSwitchCamera
                    )
                else -> {}
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun VideoRenderer(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean = false,
    overlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rendererRef = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    val attachedRef = remember { arrayOfNulls<VideoTrack>(1) }
    DisposableEffect(Unit) {
        onDispose {
            val view = rendererRef[0]
            if (view != null) {
                runCatching { attachedRef[0]?.removeSink(view) }
                view.release()
            }
            rendererRef[0] = null
            attachedRef[0] = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                if (overlay) setZOrderMediaOverlay(true)
                keepScreenOn = true
                rendererRef[0] = this
            }
        },
        update = { view ->
            if (attachedRef[0] !== track) {
                runCatching { attachedRef[0]?.removeSink(view) }
                runCatching { track?.addSink(view) }
                attachedRef[0] = track
            }
        }
    )
}
@Composable
private fun CallerAvatar(name: String, size: Dp = 120.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(avatarColorFor(name.ifBlank { "?" }), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase().ifBlank { "?" },
            style = if (size >= 100.dp) MaterialTheme.typography.headlineLarge
            else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupAudioParticipants(
    participants: List<CallParticipantUi>,
    localUserId: String
) {
    val others = participants.filter { it.userId != localUserId && it.userId.isNotBlank() }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        others.forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CallerAvatar(name = p.name, size = 72.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    p.name.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

/** WhatsApp-style group video: equal full-bleed splits + portrait self PiP. */
@Composable
private fun GroupVideoGrid(
    participants: List<CallParticipantUi>,
    localUserId: String,
    remoteTracks: Map<String, VideoTrack>,
    localTrack: VideoTrack?,
    isCameraOn: Boolean,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier
) {
    val remotes = remember(participants, localUserId, remoteTracks) {
        participants
            .filter { it.userId != localUserId && it.userId.isNotBlank() }
            .map { p ->
                WhatsAppRemote(
                    name = p.name.ifBlank { "Unknown" },
                    cameraOn = p.cameraOn,
                    track = remoteTracks[p.userId]
                )
            }
    }

    Box(modifier = modifier.background(Color.Black)) {
        when (remotes.size) {
            0 -> {
                if (localTrack != null && isCameraOn) {
                    VideoRenderer(
                        track = localTrack,
                        eglContext = eglContext,
                        mirror = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            1 -> {
                WhatsAppParticipantTile(
                    name = remotes[0].name,
                    cameraOn = remotes[0].cameraOn,
                    track = remotes[0].track,
                    eglContext = eglContext,
                    modifier = Modifier.fillMaxSize()
                )
            }
            2 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    WhatsAppParticipantTile(
                        name = remotes[0].name,
                        cameraOn = remotes[0].cameraOn,
                        track = remotes[0].track,
                        eglContext = eglContext,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    Spacer(Modifier.height(2.dp))
                    WhatsAppParticipantTile(
                        name = remotes[1].name,
                        cameraOn = remotes[1].cameraOn,
                        track = remotes[1].track,
                        eglContext = eglContext,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
            3 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        WhatsAppParticipantTile(
                            name = remotes[0].name,
                            cameraOn = remotes[0].cameraOn,
                            track = remotes[0].track,
                            eglContext = eglContext,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        Spacer(Modifier.width(2.dp))
                        WhatsAppParticipantTile(
                            name = remotes[1].name,
                            cameraOn = remotes[1].cameraOn,
                            track = remotes[1].track,
                            eglContext = eglContext,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    WhatsAppParticipantTile(
                        name = remotes[2].name,
                        cameraOn = remotes[2].cameraOn,
                        track = remotes[2].track,
                        eglContext = eglContext,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        WhatsAppParticipantTile(
                            name = remotes[0].name,
                            cameraOn = remotes[0].cameraOn,
                            track = remotes[0].track,
                            eglContext = eglContext,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        Spacer(Modifier.width(2.dp))
                        WhatsAppParticipantTile(
                            name = remotes[1].name,
                            cameraOn = remotes[1].cameraOn,
                            track = remotes[1].track,
                            eglContext = eglContext,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        WhatsAppParticipantTile(
                            name = remotes[2].name,
                            cameraOn = remotes[2].cameraOn,
                            track = remotes[2].track,
                            eglContext = eglContext,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        Spacer(Modifier.width(2.dp))
                        WhatsAppParticipantTile(
                            name = remotes[3].name,
                            cameraOn = remotes[3].cameraOn,
                            track = remotes[3].track,
                            eglContext = eglContext,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }

        if (localTrack != null && isCameraOn && remotes.isNotEmpty()) {
            VideoRenderer(
                track = localTrack,
                eglContext = eglContext,
                mirror = true,
                overlay = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 120.dp)
                    .width(108.dp)
                    .height(152.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
        }
    }
}

private data class WhatsAppRemote(
    val name: String,
    val cameraOn: Boolean,
    val track: VideoTrack?
)

@Composable
private fun WhatsAppParticipantTile(
    name: String,
    cameraOn: Boolean,
    track: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color(0xFF1A1A1A))) {
        if (track != null && cameraOn) {
            VideoRenderer(
                track = track,
                eglContext = eglContext,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CallerAvatar(name = name, size = 72.dp)
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
@Composable
private fun statusLabel(state: CallUiState): String = when (state.status) {
    CallStatus.OUTGOING_RINGING -> when {
        state.isGroupCall -> "Calling groupâ€¦"
        else -> "Ringingâ€¦"
    }
    CallStatus.INCOMING_RINGING -> when {
        state.isGroupCall && state.isVideoCall -> "Incoming group video call"
        state.isGroupCall -> "Incoming group call"
        state.isVideoCall -> "Incoming video call"
        else -> "Incoming call"
    }
    CallStatus.CONNECTING -> "Connectingâ€¦"
    CallStatus.CONNECTED -> formatElapsed(state.connectedAtMs)
    CallStatus.ENDED -> when (state.endReason) {
        "declined" -> "Call declined"
        "offline", "unreachable" -> "Couldn't connect"
        "busy" -> "User busy"
        "failed" -> "Call failed"
        else -> "Call ended"
    }
    CallStatus.IDLE -> ""
}

@Composable
private fun formatElapsed(connectedAtMs: Long): String {
    if (connectedAtMs <= 0) return "Connected"
    var elapsedMs by remember(connectedAtMs) { mutableLongStateOf(System.currentTimeMillis() - connectedAtMs) }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            elapsedMs = System.currentTimeMillis() - connectedAtMs
            delay(1000)
        }
    }
    val totalSec = (elapsedMs / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun IncomingActions(onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally)
    ) {
        CallActionButton(
            icon = Icons.Filled.CallEnd,
            label = "Decline",
            containerColor = MaterialTheme.colorScheme.error,
            onClick = onReject
        )
        CallActionButton(
            icon = Icons.Filled.Call,
            label = "Accept",
            containerColor = AccentGreen,
            onClick = onAccept
        )
    }
}

@Composable
private fun InCallActions(
    state: CallUiState,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    val onVideoSurface = state.isVideoCall
    val inactiveContainer = if (onVideoSurface) Color.White.copy(alpha = 0.25f)
    else MaterialTheme.colorScheme.surfaceVariant
    val inactiveTint = if (onVideoSurface) Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(
            if (state.isVideoCall) 20.dp else 32.dp,
            Alignment.CenterHorizontally
        )
    ) {
        CallActionButton(
            icon = if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = if (state.isMuted) "Unmute" else "Mute",
            containerColor = if (state.isMuted) MaterialTheme.colorScheme.primary else inactiveContainer,
            iconTint = if (state.isMuted) Color.White else inactiveTint,
            onClick = onToggleMute,
            size = 56.dp
        )
        if (state.isVideoCall) {
            CallActionButton(
                icon = if (state.isCameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = if (state.isCameraOn) "Camera" else "Cam off",
                containerColor = if (state.isCameraOn) inactiveContainer else MaterialTheme.colorScheme.primary,
                iconTint = if (state.isCameraOn) inactiveTint else Color.White,
                onClick = onToggleCamera,
                size = 56.dp
            )
            CallActionButton(
                icon = Icons.Filled.FlipCameraAndroid,
                label = "Flip",
                containerColor = inactiveContainer,
                iconTint = inactiveTint,
                onClick = onSwitchCamera,
                size = 56.dp
            )
        }
        CallActionButton(
            icon = Icons.Filled.CallEnd,
            label = "End",
            containerColor = MaterialTheme.colorScheme.error,
            onClick = onEnd
        )
        CallActionButton(
            icon = Icons.Filled.VolumeUp,
            label = "Speaker",
            containerColor = if (state.isSpeakerOn) MaterialTheme.colorScheme.primary else inactiveContainer,
            iconTint = if (state.isSpeakerOn) Color.White else inactiveTint,
            onClick = onToggleSpeaker,
            size = 56.dp
        )
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: Color,
    iconTint: Color = Color.White,
    size: Dp = 64.dp,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(size * 0.4f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}