package com.messenger.app.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Animated typing indicator with bouncing dots
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    dotCount: Int = 3,
    dotSize: Float = 8f,
    color: Color = Color.Gray.copy(alpha = 0.6f)
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until dotCount) {
            BouncingDot(
                index = i,
                size = dotSize.dp,
                color = color
            )
        }
    }
}

/**
 * Individual bouncing dot animation
 */
@Composable
private fun BouncingDot(
    index: Int,
    size: androidx.compose.ui.unit.Dp,
    color: Color
) {
    val transition = updateTransition(targetState = index, label = "bouncing_dot")
    
    val offsetY by transition.animateFloat(
        label = "offsetY"
    ) { state ->
        animateFloat(
            animationSpec = RepeatableSpec(
                iterations = Int.MAX_VALUE,
                durationMillis = 600,
                delayMillis = 200 * state
            ),
            transitionSpec = {
                spring(
                    dampingRatio = 0.8f,
                    stiffness = 200f
                )
            }
        ) { value ->
            if (value < 0.5f) 0f else -8f
        }
    }

    val alpha by transition.animateFloat(
        label = "alpha"
    ) { state ->
        animateFloat(
            animationSpec = RepeatableSpec(
                iterations = Int.MAX_VALUE,
                durationMillis = 1200,
                delayMillis = 300 * state
            )
        ) { value ->
            if (value < 0.5f) 0.4f else 1f
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .offset(y = offsetY)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}