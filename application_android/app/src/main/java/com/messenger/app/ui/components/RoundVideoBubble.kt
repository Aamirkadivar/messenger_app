package com.messenger.app.ui.components

import android.view.TextureView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** How much the circle grows while it is playing. */
private const val PLAYING_SCALE = 1.18f

/** Reveal requests issued while the grow animation settles, and their spacing. */
private const val GROW_REVEAL_TICKS = 8
private const val GROW_REVEAL_INTERVAL_MS = 80L

/** Everything the bubble needs to draw itself; owned by the caller. */
data class RoundVideoUiState(
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
    /** 0..1 while the video downloads; 1 once it is cached and playable. */
    val downloadProgress: Float = 0f,
    val isDownloading: Boolean = false,
    val thumbnailPath: String? = null,
    val error: String? = null
)

/**
 * A Telegram-style round video message.
 *
 * The video is hosted in a [TextureView], not the SurfaceView that ExoPlayer's
 * own PlayerView uses. This is the whole reason the circle works: a
 * SurfaceView is a separate window layer punched through the app, so it
 * ignores clipping and would render as a square no matter what shape is
 * applied. A TextureView is an ordinary view in the hierarchy and clips like
 * anything else - at the cost of a little more GPU work, which is why only the
 * playing bubble gets one.
 *
 * The ring around the edge doubles as the scrubber: dragging around it seeks,
 * because there is nowhere to put a horizontal timeline on a circle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundVideoBubble(
    state: RoundVideoUiState,
    player: ExoPlayer?,
    size: Dp = 200.dp,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val ringWidth = 3.dp

    // Telegram grows the circle while it plays, which is what makes a playing
    // message stand out from the static ones around it. Animating the layout
    // size rather than a graphicsLayer scale is deliberate: a scaled circle
    // would keep its old 200dp bounds and overlap the messages next to it,
    // whereas this makes the row genuinely reserve the space.
    val animatedSize by animateDpAsState(
        targetValue = if (state.isPlaying) size * PLAYING_SCALE else size,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "roundSize"
    )
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    // Position updates arrive on a timer, so animating between them is what
    // turns a visibly stepping ring into a smooth one.
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 220),
        label = "roundProgress"
    )

    // A playing circle is both larger and often the newest message, so it
    // frequently ends up clipped by the composer or the app bar. Asking the
    // list to reveal it keeps it whole without hard-coding any scroll maths.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        // Repeated deliberately: the grow animation is still running, so a
        // single request would reveal the old, smaller bounds and leave the
        // new edge cut off. This tracks the circle as it expands and stops
        // once the spring has settled.
        repeat(GROW_REVEAL_TICKS) {
            bringIntoView.bringIntoView()
            delay(GROW_REVEAL_INTERVAL_MS)
        }
    }

    Box(
        modifier = modifier
            .size(animatedSize)
            .bringIntoViewRequester(bringIntoView)
            .pointerInput(state.durationMs) {
                detectTapGestures(
                    onTap = { onTogglePlay() },
                    onDoubleTap = { onExpand() }
                )
            }
            .pointerInput(state.durationMs) {
                // Seeking and scrolling both want a drag, so the direction of
                // the first movement decides who gets it:
                //
                //   mostly vertical  -> never consumed, so the chat scrolls
                //                       even when the finger starts on a video
                //   mostly sideways  -> consumed and treated as a scrub
                //
                // detectDragGestures used to claim every drag outright, which
                // made the list impossible to scroll wherever a round video sat
                // under the finger.
                // The composable's own `size: Dp` parameter shadows the pointer
                // scope's IntSize, so the bounds are read through this alias.
                val bounds = this
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var scrubbing = false
                        var decided = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y

                            if (!decided) {
                                val slop = viewConfiguration.touchSlop
                                if (abs(totalY) > slop && abs(totalY) >= abs(totalX)) {
                                    // Vertical: hand it to the list untouched.
                                    decided = true
                                } else if (abs(totalX) > slop) {
                                    val centre = Offset(
                                        bounds.size.width / 2f, bounds.size.height / 2f
                                    )
                                    val start = down.position - centre
                                    // Only near the rim, so a sideways slip
                                    // through the middle does not scrub.
                                    scrubbing = hypot(start.x, start.y) >
                                        (bounds.size.width / 2f) * 0.6f
                                    decided = true
                                }
                            }

                            if (scrubbing) {
                                change.consume()
                                val centre = Offset(
                                    bounds.size.width / 2f, bounds.size.height / 2f
                                )
                                val v = change.position - centre
                                // atan2 measured from 12 o'clock, clockwise, to
                                // match where the ring starts drawing.
                                var angle = atan2(v.x, -v.y) * 180f / PI.toFloat()
                                if (angle < 0) angle += 360f
                                onSeek((angle / 360f).coerceIn(0f, 1f))
                            } else if (decided) {
                                // Nothing more to do - leaving the change
                                // unconsumed is what lets the list take over.
                                break
                            }
                        }
                    }
                }
            }
    ) {
        // Poster frame: visible from the moment the message arrives, so the
        // bubble is never an empty circle waiting on a download.
        state.thumbnailPath?.let { path ->
            AsyncImage(
                model = java.io.File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(animatedSize)
                    .clip(CircleShape)
            )
        } ?: Box(
            Modifier
                .size(animatedSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { view -> player.setVideoTextureView(view) }
                },
                modifier = Modifier
                    .size(animatedSize)
                    .clip(CircleShape)
            )
        }

        // Download progress replaces the play affordance while fetching.
        if (state.isDownloading) {
            CircularProgressIndicator(
                progress = { state.downloadProgress },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else if (!state.isPlaying) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play video message",
                    tint = Color.White
                )
            }
        }

        // Playback ring.
        Canvas(modifier = Modifier.size(animatedSize)) {
            val stroke = with(density) { ringWidth.toPx() }
            val inset = stroke / 2f
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (animatedProgress > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        val remaining = (state.durationMs - state.positionMs).coerceAtLeast(0)
        Text(
            text = formatVoiceDuration(if (state.isPlaying) remaining else state.durationMs),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        )
    }
}
