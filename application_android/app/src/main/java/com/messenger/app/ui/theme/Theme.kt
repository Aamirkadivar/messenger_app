package com.messenger.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ==================== Color Palette (Telegram-inspired) ====================

// Light Theme Colors
val LightPrimary = Color(0xFF2AABEE)        // Telegram blue
val LightPrimaryVariant = Color(0xFF229ED9)
val LightSecondary = Color(0xFF7C69EF)       // Purple accent
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF5F5F5)
val LightBackground = Color(0xFFFFFFFF)
val LightError = Color(0xFFB00020)

// Message bubble colors (sent)
val LightSentBubble = Color(0xFF2AABEE)      // Blue bubble
val LightSentBubbleText = Color(0xFFFFFFFF)

// Message bubble colors (received)
val LightReceivedBubble = Color(0xFFE8ECF0)
val LightReceivedBubbleText = Color(0xFF1A1A1A)

// Chat background
val LightChatBackground = Color(0xFFF0F2F5)
val LightChatPattern = Color(0xFFE8E8E8)

// Dark Theme Colors
val DarkPrimary = Color(0xFF2AABEE)
val DarkPrimaryVariant = Color(0xFF3B8BF0)
val DarkSecondary = Color(0xFF8B78FF)
val DarkSurface = Color(0xFF1A1A2E)
val DarkSurfaceVariant = Color(0xFF16213E)
val DarkBackground = Color(0xFF0F0F1A)
val DarkError = Color(0xFFCF6679)

// Dark message bubble colors (sent)
val DarkSentBubble = Color(0xFF1E3A5F)
val DarkSentBubbleText = Color(0xFFE0E0E0)

// Dark message bubble colors (received)
val DarkReceivedBubble = Color(0xFF233156)
val DarkReceivedBubbleText = Color(0xFFE0E0E0)

// Dark chat background
val DarkChatBackground = Color(0xFF0F0F1A)

// ==================== Color Schemes ====================

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    primaryContainer = LightPrimaryVariant,
    secondary = LightSecondary,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    background = LightBackground,
    error = LightError
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    primaryContainer = DarkPrimaryVariant,
    secondary = DarkSecondary,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    background = DarkBackground,
    error = DarkError
)

// ==================== Typography ====================

val Typography = androidx.compose.material3.Typography(
    bodyLarge = androidx.compose.ui.text.font.FontFamily.SansSerif(
        androidx.compose.ui.text.font.FontWeight.Normal
    ),
    bodyMedium = androidx.compose.ui.text.font.FontFamily.SansSerif(
        androidx.compose.ui.text.font.FontWeight.Normal
    ),
    titleLarge = androidx.compose.ui.text.font.FontFamily.SansSerif(
        androidx.compose.ui.text.font.FontWeight.Medium
    ),
    labelMedium = androidx.compose.ui.text.font.FontFamily.SansSerif(
        androidx.compose.ui.text.font.FontWeight.Normal
    )
)

// ==================== Chat Bubble Shapes ====================

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val ChatBubbleShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomStart = 4.dp,
    bottomEnd = 4.dp
)

val ReceivedBubbleShape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomStart = 16.dp,
    bottomEnd = 4.dp
)

// ==================== App Theme ====================

@Composable
fun MessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// ==================== Extended Theme Utilities ====================

object MessengerThemeColors {
    val Primary get() = LightPrimary
    val PrimaryVariant get() = LightPrimaryVariant
    val Secondary get() = LightSecondary
    val Surface get() = LightSurface
    val Background get() = LightBackground
    val ChatBackground get() = LightChatBackground

    val SentBubble get() = LightSentBubble
    val SentBubbleText get() = LightSentBubbleText
    val ReceivedBubble get() = LightReceivedBubble
    val ReceivedBubbleText get() = LightReceivedBubbleText

    fun darkPrimary() = DarkPrimary
    fun darkSurface() = DarkSurface
    fun darkBackground() = DarkBackground
    fun darkSentBubble() = DarkSentBubble
    fun darkSentBubbleText() = DarkSentBubbleText
    fun darkReceivedBubble() = DarkReceivedBubble
    fun darkReceivedBubbleText() = DarkReceivedBubbleText
}