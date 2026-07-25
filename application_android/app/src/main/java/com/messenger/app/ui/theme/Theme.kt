package com.messenger.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ==================== Color Palette ====================
// Matches the Windows desktop app's design language: purple accent on a dark navy surface.

val AccentPurple = Color(0xFF6C63FF)
val AccentPurpleLight = Color(0xFF8B84FF)
val AccentPurpleDark = Color(0xFF5B52E5)
val AccentGreen = Color(0xFF4CAF50)

// Light theme
val LightPrimary = AccentPurple
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = AccentGreen
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF5F5F7)
val LightBackground = Color(0xFFF5F5F7)
val LightOnBackground = Color(0xFF1A1A2E)
val LightOnSurfaceVariant = Color(0xFF6B6B7B)
val LightBorder = Color(0xFFE0E0E5)
val LightError = Color(0xFFFF6B6B)

// Dark theme
val DarkPrimary = AccentPurple
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkSecondary = AccentGreen
val DarkSurface = Color(0xFF1B1B36)
val DarkSurfaceVariant = Color(0xFF22224A)
val DarkBackground = Color(0xFF15152B)
val DarkOnBackground = Color(0xFFEDEDF2)
val DarkOnSurfaceVariant = Color(0xFF9494AC)
val DarkBorder = Color(0x14FFFFFF)
val DarkError = Color(0xFFFF6B6B)

// Message bubbles
val SentBubbleLight = AccentPurple
val SentBubbleTextLight = Color(0xFFFFFFFF)
val ReceivedBubbleLight = Color(0xFFEDEDF2)
val ReceivedBubbleTextLight = Color(0xFF1A1A2E)

val SentBubbleDark = AccentPurple
val SentBubbleTextDark = Color(0xFFFFFFFF)
val ReceivedBubbleDark = Color(0xFF26264A)
val ReceivedBubbleTextDark = Color(0xFFEDEDF2)

// Status colors
val OnlineColor = AccentGreen
val OfflineColor = Color(0xFF9E9E9E)

// ==================== Color Schemes ====================

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    secondary = LightSecondary,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    background = LightBackground,
    onBackground = LightOnBackground,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightBorder,
    error = LightError
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkBorder,
    error = DarkError
)

// ==================== Typography ====================

private val AppFontFamily = FontFamily.SansSerif

val Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    )
)

// ==================== Shapes ====================

val ChatBubbleShapeSent = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomStart = 16.dp,
    bottomEnd = 4.dp
)

val ChatBubbleShapeReceived = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomStart = 4.dp,
    bottomEnd = 16.dp
)

val CardShape = RoundedCornerShape(16.dp)
val FieldShape = RoundedCornerShape(12.dp)
val ButtonShape = RoundedCornerShape(13.dp)

// ==================== App Theme ====================

@Composable
fun MessengerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Extended colors not covered by the Material3 ColorScheme (message bubbles, presence dots).
 * Reads the current dark/light state so callers don't need to branch themselves.
 */
object MessengerExtendedColors {
    private val isDark: Boolean
        @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val sentBubble @Composable get() = if (isDark) SentBubbleDark else SentBubbleLight
    val sentBubbleText @Composable get() = if (isDark) SentBubbleTextDark else SentBubbleTextLight
    val receivedBubble @Composable get() = if (isDark) ReceivedBubbleDark else ReceivedBubbleLight
    val receivedBubbleText @Composable get() = if (isDark) ReceivedBubbleTextDark else ReceivedBubbleTextLight
    val online = OnlineColor
    val offline = OfflineColor
}
