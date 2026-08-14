package com.messenger.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.viewmodel.ChatMessageUi

/**
 * Telegram-style peek: hold an avatar in the chat list and the recent
 * conversation rises over a dimmed backdrop, without opening the chat.
 *
 * Read-only by design. A peek is a glance, not a visit - it does not mark
 * anything read, join a room, or disturb whichever chat is actually open.
 */
@Composable
fun ChatPeekDialog(
    chatName: String,
    avatarUrl: String?,
    messages: List<ChatMessageUi>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onOpenChat: () -> Unit
) {
    val dark = MessengerExtendedColors.isDark
    val listState = rememberLazyListState()

    // Scales up from the avatar's size rather than fading in flat, which is
    // what makes it read as the avatar expanding.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    // Open at the newest message, like the real thread.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tapping anywhere outside closes it. No ripple: this is a
                // dismissal surface, not a button.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .background(Color.Black.copy(alpha = 0.55f * reveal.value)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = FastOutSlowInEasing.transform(reveal.value)
                        alpha = reveal.value
                        scaleX = 0.86f + 0.14f * p
                        scaleY = 0.86f + 0.14f * p
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        RoundedCornerShape(20.dp)
                    )
                    // Swallow taps on the card itself so they do not reach the
                    // dismissal scrim behind it.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenChat)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(name = chatName, avatarUrl = avatarUrl, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = chatName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Open",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 1.dp, max = 1.dp)
                        .background(
                            Color.White.copy(alpha = if (dark) 0.10f else 0.16f)
                        )
                )

                when {
                    loading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }

                    messages.isEmpty() -> {
                        Text(
                            text = "No messages yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(28.dp)
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            // Capped so a long history cannot grow the card off
                            // the screen; it scrolls inside instead.
                            modifier = Modifier.heightIn(max = 360.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                PeekBubble(message = message, dark = dark)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A stripped-down bubble: sender side, text, and a label for anything that is
 * not text. Attachments are deliberately not fetched or decrypted for a peek.
 */
@Composable
private fun PeekBubble(message: ChatMessageUi, dark: Boolean) {
    if (message.isSystem) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
        return
    }

    val label = when {
        message.isVideoNote -> "🎥 Video message"
        message.isVoice -> "🎤 Voice message"
        message.isImageAttachment -> "🖼 Photo"
        message.isAttachment -> "📎 ${message.attachmentName.ifBlank { "File" }}"
        else -> message.content
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (message.isMine) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (message.isMine) {
                        MaterialTheme.colorScheme.primary
                    } else if (dark) {
                        Color.White.copy(alpha = 0.08f)
                    } else {
                        Color.Black.copy(alpha = 0.06f)
                    }
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
