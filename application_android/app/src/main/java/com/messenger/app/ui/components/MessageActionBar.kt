package com.messenger.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.reducedMotionEnabled
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Floating action pill shown while messages are selected.
 *
 * Opaque surface (chat does not show through) with a light haze wash for
 * depth. Reveal still animates opacity/scale/lift from 0 → 1.
 */
@Composable
fun MessageActionBar(
    visible: Boolean,
    hazeState: HazeState,
    actions: List<MessageAction>,
    modifier: Modifier = Modifier
) {
    val dark = MessengerExtendedColors.isDark
    val shape = RoundedCornerShape(percent = 50)
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val reduceMotion = reducedMotionEnabled()

    val progress = remember { Animatable(0f) }

    LaunchedEffect(visible, reduceMotion) {
        if (reduceMotion) {
            progress.snapTo(if (visible) 1f else 0f)
            return@LaunchedEffect
        }
        if (visible) {
            progress.snapTo(0f)
            // Soft settle in — spring for scale/lift feel, but opacity is the
            // same progress so transparency still reads 0-to-100.
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = Tokens.Motion.EXIT_MS,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    val p = progress.value
    if (p < 0.01f && !visible) return

    val liftPx = with(density) { lerp(28f, 0f, p).dp.toPx() }
    val scale = lerp(0.90f, 1f, FastOutSlowInEasing.transform(p))
    // Blur grows with opacity so the glass "resolves" as it appears.
    // Deep blur. Well past "softened" - at this radius nothing behind the bar
    // is legible, only its colour bleeds through.
    //
    // Only real on Android 12+, where haze uses RenderEffect. Below that it
    // cannot blur at all and falls back to the flat fallbackTint, which is why
    // that colour is kept close to what this resolves to.
    val blurDp = lerp(6f, 40f, p).dp
    val tintAlpha = p
    val rimAlpha = lerp(0f, if (dark) 0.32f else 0.22f, p)
    val shadowAlpha = lerp(0f, if (dark) 0.55f else 0.18f, p)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .graphicsLayer {
                alpha = p
                scaleX = scale
                scaleY = scale
                translationY = liftPx
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = lerp(0f, if (dark) 20f else 14f, p).dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha * 0.85f),
                    spotColor = Color.Black.copy(alpha = shadowAlpha)
                )
                // clip BEFORE hazeEffect so blur stays inside the pill.
                .clip(shape)
                // Solid fill first so the bar reads opaque even if haze
                // under-draws or blur is unavailable below API 31.
                .background(
                    color = bg.copy(alpha = if (dark) 0.97f else 0.98f),
                    shape = shape
                )
                .hazeEffect(state = hazeState) {
                    blurEnabled = true
                    blurRadius = blurDp
                    backgroundColor = bg
                    // Near-fully opaque: chat behind must not read through.
                    // A thin white + accent wash keeps a little material depth
                    // without translucency.
                    tints = listOf(
                        HazeTint(
                            bg.copy(
                                alpha = (if (dark) 0.97f else 0.96f) * tintAlpha
                            )
                        ),
                        HazeTint(
                            Color.White.copy(
                                alpha = (if (dark) 0.04f else 0.10f) * tintAlpha
                            )
                        ),
                        HazeTint(
                            accent.copy(
                                alpha = (if (dark) 0.05f else 0.04f) * tintAlpha
                            )
                        )
                    )
                    fallbackTint = HazeTint(
                        if (dark) Color(0xF81A1714) else Color(0xF8FBF7EF)
                    )
                    noiseFactor = 0.08f * tintAlpha
                }
                // Soft top sheen only — not a see-through gradient.
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = (if (dark) 0.06f else 0.18f) * p),
                            Color.Transparent
                        )
                    ),
                    shape = shape
                )
                .border(
                    width = 0.75.dp,
                    color = accent.copy(alpha = rimAlpha),
                    shape = shape
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEachIndexed { index, action ->
                // Stagger icons slightly so the bar feels assembled, not flat.
                val itemDelay = index * 0.06f
                val itemP = ((p - itemDelay) / (1f - itemDelay)).coerceIn(0f, 1f)
                MessageActionItem(
                    action = action,
                    reveal = itemP,
                    itemModifier = Modifier.weight(1f, fill = true)
                )
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
    reveal: Float,
    itemModifier: Modifier = Modifier
) {
    val tint = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
        action.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val itemScale = lerp(0.82f, 1f, reveal)
    Column(
        modifier = itemModifier
            .widthIn(max = 88.dp)
            .graphicsLayer {
                alpha = reveal
                scaleX = itemScale
                scaleY = itemScale
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = action.enabled && reveal > 0.85f, onClick = action.onClick)
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
