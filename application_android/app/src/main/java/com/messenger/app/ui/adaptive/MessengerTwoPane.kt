package com.messenger.app.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.theme.Tokens

/**
 * Persistent list | conversation shell for Medium and Expanded widths.
 *
 * Pane widths come from [AdaptiveMetrics] so they track the actual window
 * (split-screen, foldables, desktop-sized freeform) rather than a device type.
 */
@Composable
fun MessengerTwoPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val widthClass = LocalWidthClass.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("messenger_two_pane")
    ) {
        val listWidthDp = AdaptiveMetrics.listPaneWidthDp(maxWidth.value, widthClass)
        val conversationWidthDp = (maxWidth.value - listWidthDp).coerceAtLeast(0f)
        val bubbleMax = AdaptiveMetrics.messageBubbleMaxWidthDp(
            conversationPaneWidthDp = conversationWidthDp,
            compact = false
        ).dp
        CompositionLocalProvider(LocalMessageBubbleMaxWidth provides bubbleMax) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(listWidthDp.dp)
                        .fillMaxHeight()
                        .testTag("chat_list_pane")
                ) {
                    list()
                }
                PaneDivider()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("conversation_pane")
                ) {
                    detail()
                }
            }
        }
    }
}

@Composable
private fun PaneDivider() {
    val dark = MessengerExtendedColors.isDark
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .width(Tokens.Size.hairline)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = if (dark) 0.22f else 0.18f),
                        Tokens.Palette.divider(dark),
                        accent.copy(alpha = if (dark) 0.08f else 0.06f)
                    )
                )
            )
    )
}
