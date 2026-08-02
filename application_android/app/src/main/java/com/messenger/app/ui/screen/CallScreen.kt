package com.messenger.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.app.data.call.CallStatus
import com.messenger.app.data.call.CallUiState
import com.messenger.app.ui.components.avatarColorFor
import com.messenger.app.ui.theme.AccentGreen
import kotlinx.coroutines.delay

/**
 * Full-screen call overlay - shown above whatever screen is open (see
 * MainActivity) whenever [state] isn't idle, matching how a phone call
 * interrupts the current app on a real phone rather than living inside chat
 * navigation.
 */
@Composable
fun CallOverlay(
    state: CallUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismissEnded: () -> Unit
) {
    if (state.status == CallStatus.ENDED) {
        LaunchedEffect(state.callId, state.endReason) {
            delay(1500)
            onDismissEnded()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))

            CallerAvatar(name = state.peerName)

            Spacer(Modifier.height(20.dp))

            Text(
                state.peerName.ifBlank { "Unknown" },
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

            Spacer(Modifier.weight(1f))

            when (state.status) {
                CallStatus.INCOMING_RINGING -> IncomingActions(onAccept = onAccept, onReject = onReject)
                CallStatus.OUTGOING_RINGING, CallStatus.CONNECTING, CallStatus.CONNECTED ->
                    InCallActions(state = state, onEnd = onEnd, onToggleMute = onToggleMute, onToggleSpeaker = onToggleSpeaker)
                else -> {}
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun CallerAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(avatarColorFor(name.ifBlank { "?" }), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun statusLabel(state: CallUiState): String = when (state.status) {
    CallStatus.OUTGOING_RINGING -> "Ringing…"
    CallStatus.INCOMING_RINGING -> "Incoming call"
    CallStatus.CONNECTING -> "Connecting…"
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

/** Ticks once a second while a call is connected, showing mm:ss elapsed. */
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
    onToggleSpeaker: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)
    ) {
        CallActionButton(
            icon = if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = if (state.isMuted) "Unmute" else "Mute",
            containerColor = if (state.isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            iconTint = if (state.isMuted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onToggleMute,
            size = 56.dp
        )
        CallActionButton(
            icon = Icons.Filled.CallEnd,
            label = "End",
            containerColor = MaterialTheme.colorScheme.error,
            onClick = onEnd
        )
        CallActionButton(
            icon = Icons.Filled.VolumeUp,
            label = "Speaker",
            containerColor = if (state.isSpeakerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            iconTint = if (state.isSpeakerOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
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
    size: androidx.compose.ui.unit.Dp = 64.dp,
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
