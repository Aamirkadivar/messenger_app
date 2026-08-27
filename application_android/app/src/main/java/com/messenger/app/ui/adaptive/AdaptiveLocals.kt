package com.messenger.app.ui.adaptive

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalWidthClass = staticCompositionLocalOf { MessengerWidthClass.Compact }

val LocalTwoPane = staticCompositionLocalOf { false }

/**
 * Max width of a message bubble. Compact defaults to the historical 280dp;
 * the two-pane scaffold overrides this from the conversation pane's width.
 */
val LocalMessageBubbleMaxWidth = compositionLocalOf { AdaptiveMetrics.CompactBubbleMaxDp.dp }
