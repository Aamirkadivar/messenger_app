package com.messenger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.ui.theme.MessengerExtendedColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * In-flow rounded connecting chip with side inset. Takes its own row in the
 * layout so it never overlays chats underneath.
 */
@Composable
fun ConnectionStatusBanner(
    state: WebSocketManager.ConnectionState,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val visible = state == WebSocketManager.ConnectionState.CONNECTING ||
        state == WebSocketManager.ConnectionState.RECONNECTING
    val label = when (state) {
        WebSocketManager.ConnectionState.RECONNECTING -> stringResource(R.string.reconnecting)
        else -> stringResource(R.string.connecting)
    }
    val accent = MaterialTheme.colorScheme.primary
    val dark = MessengerExtendedColors.isDark
    // A pill, not a 12dp rounded bar - at this height a full radius reads as a
    // button/chip rather than a stretched banner.
    val shape = RoundedCornerShape(percent = 50)
    val bg = MaterialTheme.colorScheme.background

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        // Wrap-content, with no outer padding of its own. Callers overlay it
        // (see ChatScreen/ChatListScreen) rather than putting it in the layout
        // flow: in-flow it claimed a full-width row, which both pushed the
        // content down and gave the pill a visible band around it. As an
        // overlay everything around the pill is genuinely the content behind.
        modifier = modifier
    ) {
        run {
            Row(
                modifier = Modifier
                    // clip BEFORE hazeEffect, or the blur is drawn to the
                    // node's square bounds and the pill ends up with blurred
                    // corners sticking out past its outline.
                    .clip(shape)
                    .hazeEffect(state = hazeState) {
                        blurEnabled = true
                        blurRadius = 24.dp
                        backgroundColor = bg
                        tints = listOf(
                            HazeTint(accent.copy(alpha = if (dark) 0.20f else 0.14f)),
                            HazeTint(Color.White.copy(alpha = if (dark) 0.08f else 0.28f))
                        )
                        fallbackTint = HazeTint(accent.copy(alpha = if (dark) 0.28f else 0.18f))
                        noiseFactor = 0.10f
                    }
                    .border(0.5.dp, accent.copy(alpha = 0.32f), shape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                // No weight(1f): stretching the label is what made this fill
                // the width like a banner instead of hugging its text.
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = accent
                )
            }
        }
    }
}