package com.messenger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.luxuryTween
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

@Composable
fun MessageActionBar(
    visible: Boolean,
    hazeState: HazeState,
    actions: List<MessageAction>,
    modifier: Modifier = Modifier
) {
    val dark = MessengerExtendedColors.isDark
    // Pill, matching the connecting chip - a fixed 22dp radius on a bar this
    // tall reads as a rounded panel rather than a floating control.
    val shape = RoundedCornerShape(percent = 50)
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val enterSpec = luxuryTween<Float>(Tokens.Motion.SLOW_MS, Tokens.Motion.easeOut)
    val exitSpec = luxuryTween<Float>(Tokens.Motion.EXIT_MS, Tokens.Motion.easeIn)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = enterSpec) +
            slideInVertically(
                animationSpec = tween(
                    durationMillis = Tokens.Motion.SLOW_MS,
                    easing = Tokens.Motion.easeOut
                )
            ) { it / 2 },
        exit = fadeOut(animationSpec = exitSpec) +
            slideOutVertically(
                animationSpec = tween(
                    durationMillis = Tokens.Motion.EXIT_MS,
                    easing = Tokens.Motion.easeIn
                )
            ) { it / 3 },
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // A small side inset only - enough that the pill is not glued to
            // the screen edges. Hugging the actions made it far too small.
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (dark) 16.dp else 10.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.12f),
                        spotColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.16f)
                    )
                    // clip BEFORE hazeEffect. The other way round the blur is
                    // drawn to the node's square bounds and spills past the
                    // rounded outline at the corners.
                    .clip(shape)
                    // Tints kept light so the chat genuinely shows through the
                    // blur. The heavier white wash that was here read as a
                    // frosted panel sitting on top rather than glass.
                    .hazeEffect(state = hazeState) {
                        blurEnabled = true
                        blurRadius = 28.dp
                        backgroundColor = bg
                        tints = listOf(
                            HazeTint(Color.White.copy(alpha = if (dark) 0.05f else 0.22f)),
                            HazeTint(accent.copy(alpha = if (dark) 0.07f else 0.05f))
                        )
                        // Only used where the platform cannot blur; still
                        // translucent rather than the near-opaque 0xE6 before.
                        fallbackTint = HazeTint(
                            if (dark) Color(0xB312100E) else Color(0xB3F7F1E6)
                        )
                        noiseFactor = 0.06f
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { action ->
                    MessageActionItem(
                        action = action,
                        itemModifier = Modifier.weight(1f, fill = true)
                    )
                }
            }
        }
    }
}

data class MessageAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

@Composable
private fun MessageActionItem(
    action: MessageAction,
    itemModifier: Modifier = Modifier
) {
    val tint = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
        action.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = itemModifier
            .widthIn(max = 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .semantics {
                role = Role.Button
                contentDescription = action.label
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = action.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}