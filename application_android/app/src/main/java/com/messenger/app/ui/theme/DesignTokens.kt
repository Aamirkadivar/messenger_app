package com.messenger.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the app's "modern luxury" surfaces.
 *
 * Every component builds off these - no raw hex, sp, or duration literals in
 * component code. The palette deliberately extends the existing [MessengerTheme]
 * colors (champagne gold on near-black / warm ivory) rather than forking a
 * second visual language.
 *
 * The three signals that carry the aesthetic:
 *  - Space, not ornament: tall rows, large section padding.
 *  - Tonal elevation, not drop shadows: surfaces step through neutral tones.
 *  - Hairline inset dividers, used only after space has failed to separate.
 */
object Tokens {

    // ==================== Spacing (4dp rhythm) ====================

    object Space {
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 16.dp
        val lg: Dp = 24.dp
        val xl: Dp = 32.dp
        val xxl: Dp = 48.dp

        /** Horizontal inset for all screen content. */
        val screenGutter: Dp = 24.dp

        /** Space above a section header - the main "luxury" breathing room. */
        val sectionTop: Dp = 40.dp

        /** Dividers stop short of the gutter so they read as hairlines, not rules. */
        val dividerInset: Dp = 24.dp
    }

    // ==================== Sizing ====================

    object Size {
        /** Standard settings row. Comfortably above the 48dp Material touch minimum. */
        val rowHeight: Dp = 56.dp

        /** Row with a supporting subtitle line. */
        val rowHeightTall: Dp = 64.dp

        /** Single icon size token - avoids the 20/24/28 drift that reads as sloppy. */
        val icon: Dp = 22.dp

        /** Minimum touch target, applied via sizeIn to visually smaller controls. */
        val touchTarget: Dp = 48.dp

        val hairline: Dp = 1.dp

        val toggleWidth: Dp = 46.dp
        val toggleHeight: Dp = 28.dp
        val toggleThumb: Dp = 22.dp

        /** Storage segmented bar. Thin - a data display, not a progress bar. */
        val storageBarHeight: Dp = 8.dp
    }

    object Radius {
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val pill: Dp = 999.dp
    }

    // ==================== Type scale (exactly 4 sizes) ====================
    //
    // 20 / 16 / 13 / 11. A refined serif carries section headers; a neutral
    // sans carries everything the user actually reads or acts on.

    private val Display = FontFamily.Serif
    private val Body = FontFamily.SansSerif

    object Type {
        /** Section headers. Serif, the one place the display face appears. */
        val sectionHeader = TextStyle(
            fontFamily = Display,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.15.sp
        )

        /** Screen title in the app bar. */
        val screenTitle = TextStyle(
            fontFamily = Display,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.15.sp
        )

        /** Primary row label. */
        val rowTitle = TextStyle(
            fontFamily = Body,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )

        /** Supporting text under a row label, and body copy in dialogs. */
        val rowSubtitle = TextStyle(
            fontFamily = Body,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        /** Values on the trailing edge of a row. */
        val rowValue = TextStyle(
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        /**
         * Small-caps group label. Generous tracking is what makes this read as
         * deliberate rather than merely small.
         */
        val overline = TextStyle(
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 1.4.sp
        )

        /** Numeric byte counts. Tabular so digits don't reflow as values update. */
        val metric = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }

    // ==================== Motion ====================
    //
    // Slow and confident. Enter decelerates in; exit is ~65% of enter so
    // dismissal still feels responsive (Material motion guidance).

    object Motion {
        const val FAST_MS = 250
        const val STANDARD_MS = 300
        const val SLOW_MS = 350
        const val EXIT_MS = 200

        /** Decelerate - things arriving settle into place. */
        val easeOut: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

        /** Accelerate - things leaving pick up speed. */
        val easeIn: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

        val standard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    }

    // ==================== Elevation by tone ====================
    //
    // Surfaces separate by tonal step, never by drop shadow.

    object Elevation {
        /** Base screen background. */
        @Composable
        @ReadOnlyComposable
        fun level0(dark: Boolean): Color = if (dark) DarkBackground else LightBackground

        /** Raised: expanded row bodies, inline panels. */
        @Composable
        @ReadOnlyComposable
        fun level1(dark: Boolean): Color = if (dark) DarkSurface else LightSurface

        /** Highest: dialogs, sheets, selected segments. */
        @Composable
        @ReadOnlyComposable
        fun level2(dark: Boolean): Color =
            if (dark) DarkSurfaceVariant else LightSurfaceVariant
    }

    // ==================== Semantic colors ====================

    object Palette {
        /** Hairline dividers - present in both themes, never invisible in one. */
        @Composable
        @ReadOnlyComposable
        fun divider(dark: Boolean): Color =
            if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

        /** The single accent. */
        @Composable
        @ReadOnlyComposable
        fun accent(dark: Boolean): Color = if (dark) AccentGoldDark else AccentGoldLight

        /** Track behind an "off" toggle. */
        @Composable
        @ReadOnlyComposable
        fun toggleTrackOff(dark: Boolean): Color =
            if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.16f)

        /**
         * Muted tonal steps for the storage breakdown. Same hue family as the
         * accent, stepping down in luminance - a considered data display rather
         * than a categorical rainbow.
         */
        @Composable
        @ReadOnlyComposable
        fun storageSegments(dark: Boolean): List<Color> = if (dark) {
            listOf(
                Color(0xFFC9A961), // photos    - full accent
                Color(0xFFA6863F), // videos
                Color(0xFF806426), // voice
                Color(0xFF5C4A20), // documents
                Color(0xFF3A3222)  // cache
            )
        } else {
            listOf(
                Color(0xFFA6803A),
                Color(0xFFBF9E5E),
                Color(0xFFD4BC8C),
                Color(0xFFE3D3B4),
                Color(0xFFEDE4D2)
            )
        }

        /** Free space remainder on the storage bar. */
        @Composable
        @ReadOnlyComposable
        fun storageTrack(dark: Boolean): Color =
            if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f)
    }

    /** Opacity for disabled content (Material: 0.38). */
    const val DISABLED_ALPHA = 0.38f
}

/**
 * Whether the active theme is the dark one.
 *
 * Derived from background luminance rather than [ThemeState] so it stays
 * correct under a dynamic-color scheme or a `MaterialTheme` override in a
 * preview, where the global toggle would lie.
 */
@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean {
    val bg = androidx.compose.material3.MaterialTheme.colorScheme.background
    return (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) < 0.5f
}

/**
 * Animation spec honouring the system "remove animations" accessibility setting.
 *
 * Android exposes this as a global animator duration scale of 0; Compose has no
 * built-in equivalent of `prefers-reduced-motion`, so components read it here
 * and collapse to an instant transition rather than ignoring the preference.
 */
@Composable
fun <T> luxuryTween(
    durationMs: Int = Tokens.Motion.STANDARD_MS,
    easing: Easing = Tokens.Motion.easeOut
): FiniteAnimationSpec<T> {
    val scale = animatorDurationScale()
    return tween(durationMillis = (durationMs * scale).toInt(), easing = easing)
}

/** 1.0 normally; 0.0 when the user has turned system animations off. */
@Composable
fun animatorDurationScale(): Float {
    val context = LocalContext.current
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
    }.getOrDefault(1.0f)
}

/** True when the user has asked the system to remove animations. */
@Composable
fun reducedMotionEnabled(): Boolean = animatorDurationScale() == 0f
