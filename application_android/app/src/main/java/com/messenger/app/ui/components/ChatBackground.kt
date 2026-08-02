package com.messenger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.theme.MessengerExtendedColors

/**
 * A subtle diamond-lattice wallpaper behind the message list, matching the
 * Windows app's luxury chat background: small diamond outlines with a tiny
 * center dot at each tile, drawn at low opacity in the theme's accent color.
 */
@Composable
fun ChatBackground(
    modifier: Modifier = Modifier,
    tileSize: Dp = 48.dp,
    /** 0f lets an [AmbientGlow] layered behind this show through instead of a flat fill. */
    baseOpacity: Float = 1f
) {
    val baseColor = MaterialTheme.colorScheme.background.copy(alpha = baseOpacity)
    val patternColor = MaterialTheme.colorScheme.primary
    // The same alpha reads much fainter against a pale background than a
    // near-black one, so light mode needs a bit more to land at the same
    // visual weight.
    val patternOpacity = if (MessengerExtendedColors.isDark) 0.05f else 0.09f
    val tint = patternColor.copy(alpha = patternOpacity)

    Canvas(modifier = modifier.fillMaxSize().background(baseColor)) {
        val tile = tileSize.toPx()
        val r = tile * 0.28f
        val cols = (size.width / tile).toInt() + 2
        val rows = (size.height / tile).toInt() + 2
        val strokeWidth = 1.dp.toPx()
        val dotRadius = 1.2.dp.toPx()

        for (row in 0..rows) {
            for (col in 0..cols) {
                val cx = col * tile
                val cy = row * tile

                val diamond = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r, cy)
                    lineTo(cx, cy + r)
                    lineTo(cx - r, cy)
                    close()
                }
                drawPath(diamond, color = tint, style = Stroke(width = strokeWidth))
                drawCircle(color = tint, radius = dotRadius, center = Offset(cx, cy))
            }
        }
    }
}
