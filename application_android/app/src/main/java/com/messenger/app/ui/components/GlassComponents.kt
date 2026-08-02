package com.messenger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.MessengerExtendedColors

/**
 * Compose counterpart to the Windows desktop app's GlassPanel.qml: a
 * translucent surface faking "glass" without a real-time backdrop blur (which
 * would need a blur-behind library on Compose too) - a low-alpha white fill,
 * a hairline border, and an optional soft top sheen. Same tuned constants as
 * the Windows component so both platforms read as one design language.
 *
 * Meant to sit on top of an [AmbientGlow] (or another colorful background) so
 * the translucency has something to show through.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    darkMode: Boolean = MessengerExtendedColors.isDark,
    shape: Shape = RoundedCornerShape(18.dp),
    sheen: Boolean = true,
    content: @Composable () -> Unit
) {
    // Light mode needs much more fill to read as a distinct surface against a
    // pale background; dark mode needs only a whisper against near-black -
    // exactly the asymmetry GlassPanel.qml uses.
    val fillAlpha = if (darkMode) 0.055f else 0.62f
    val borderColor = if (darkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = fillAlpha), shape)
            .border(1.dp, borderColor, shape)
    ) {
        if (sheen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (darkMode) 0.05f else 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        content()
    }
}

/**
 * Compose counterpart to the Windows app's AmbientGlow.qml: 2-3 static, soft
 * radial-gradient color blobs painted behind glass surfaces, so translucency
 * has something to show through instead of flat background. Deliberately not
 * animated per-frame - a decorative texture, not a live effect - matching the
 * rest of the app's "no animated backgrounds" convention (see ChatBackground).
 */
@Composable
fun AmbientGlow(
    modifier: Modifier = Modifier,
    baseColor: Color,
    primaryGlow: Color,
    secondaryGlow: Color,
    intensity: Float = 1f
) {
    Canvas(modifier = modifier.fillMaxSize().background(baseColor)) {
        val w = size.width
        val h = size.height

        fun blob(color: Color, center: Offset, radius: Float, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha * intensity), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        blob(primaryGlow, Offset(w * 0.15f, h * 0.1f), w * 0.6f, 0.16f)
        blob(secondaryGlow, Offset(w * 0.9f, h * 0.22f), w * 0.5f, 0.12f)
        blob(primaryGlow, Offset(w * 0.5f, h * 0.95f), w * 0.65f, 0.08f)
    }
}
