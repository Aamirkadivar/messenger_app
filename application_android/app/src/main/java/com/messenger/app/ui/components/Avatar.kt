package com.messenger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.messenger.app.BuildConfig

private val avatarPalette = listOf(
    Color(0xFFC9A961), Color(0xFFA6803A), Color(0xFF7A5C22),
    Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFE07A5F),
    Color(0xFF00A896), Color(0xFF795548), Color(0xFFA39A8A), Color(0xFF5C4A20)
)

/** Stable per-name colour, so the same person keeps the same fallback colour. */
fun avatarColorFor(name: String): Color {
    if (name.isEmpty()) return avatarPalette[0]
    return avatarPalette[(name.hashCode().and(Int.MAX_VALUE)) % avatarPalette.size]
}

/**
 * Turns a stored avatar path into a loadable URL.
 *
 * The server stores a root-relative path ("/uploads/avatars/<uuid>.jpg") rather
 * than an absolute URL, because its host changes between networks and clients
 * cache these values. Resolve it against the API host at display time.
 */
fun resolveAvatarUrl(path: String?): String? {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
    // BuildConfig.API_BASE_URL looks like "http://host:3000/api/v1/"; strip the
    // API prefix to get the server origin that /uploads sits under.
    val origin = BuildConfig.API_BASE_URL.substringBefore("/api/").trimEnd('/')
    return origin + if (raw.startsWith("/")) raw else "/$raw"
}

/**
 * Circular avatar: shows the uploaded picture when there is one, and falls back
 * to a coloured initial otherwise.
 *
 * The fallback also covers loading and error states, so a slow or broken image
 * never leaves a blank hole in a list.
 */
@Composable
fun Avatar(
    name: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = (size.value / 2.4f).sp
) {
    val resolved = resolveAvatarUrl(avatarUrl)
    val shape = CircleShape

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(avatarColorFor(name), shape)
            // Decorative: every caller shows the name next to it, so announcing
            // the image too would just repeat.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        if (resolved == null) {
            InitialFallback(name, fontSize)
        } else {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                loading = { InitialFallback(name, fontSize) },
                error = { InitialFallback(name, fontSize) }
            )
        }
    }
}

/** Letter only - the enclosing Box already paints the coloured circle. */
@Composable
private fun InitialFallback(name: String, fontSize: TextUnit) {
    Text(
        text = name.take(1).uppercase(),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        style = MaterialTheme.typography.titleMedium
    )
}
