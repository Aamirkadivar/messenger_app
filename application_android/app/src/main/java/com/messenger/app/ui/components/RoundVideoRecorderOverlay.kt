package com.messenger.app.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.messenger.app.data.roundvideo.RoundRecorderState

/**
 * The full-screen capture surface for a round video message.
 *
 * Layout mirrors Telegram's: a circular live preview floating above the
 * composer, a running timer, a "slide to cancel" hint that fades as the finger
 * travels left, and a lock affordance above the button. The gestures
 * themselves live on the composer button ([CaptureButton]) - this only
 * renders the state they produce, which keeps the gesture state machine in one
 * place instead of split across two composables.
 */
@Composable
fun RoundVideoRecorderOverlay(
    state: RoundRecorderState,
    dragX: Float,
    dragY: Float,
    willCancel: Boolean,
    willLock: Boolean,
    isLocked: Boolean,
    onBindPreview: (PreviewView) -> Unit,
    onFlip: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The circle shrinks and dims as the finger nears the cancel threshold, so
    // the gesture reads as "about to throw this away" before it commits.
    val cancelProgress = (-dragX / CANCEL_DISTANCE_PX).coerceIn(0f, 1f)
    val previewScale by animateFloatAsState(
        targetValue = if (willCancel) 0.82f else 1f - cancelProgress * 0.1f,
        animationSpec = tween(140),
        label = "previewScale"
    )
    val previewAlpha by animateFloatAsState(
        targetValue = if (willCancel) 0.4f else 1f,
        animationSpec = tween(140),
        label = "previewAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .graphicsLayer {
                        scaleX = previewScale
                        scaleY = previewScale
                        alpha = previewAlpha
                        // Follows the finger a little, so the circle feels
                        // attached to the gesture rather than pinned.
                        translationX = dragX * 0.25f
                        translationY = dragY * 0.25f
                    }
                    .clip(CircleShape)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            // COMPATIBLE backs the preview with a TextureView.
                            // The default PERFORMANCE mode uses a SurfaceView,
                            // which is a separate window layer and ignores the
                            // circular clip - the preview would render as a
                            // square overhanging the mask.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            onBindPreview(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (!isLocked) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = "Switch camera",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(28.dp)
                            .clickable { onFlip() }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.isPaused) Color.White.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.error
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatVoiceDuration(state.elapsedMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(
                visible = !isLocked,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = if (willLock) "Release to keep recording" else "← Slide to cancel  ·  Slide up to lock",
                    color = Color.White.copy(alpha = (1f - cancelProgress).coerceAtLeast(0.35f)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Hands-free controls, only once locked.
        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // navigationBarsPadding first, then clearance for the composer
                // underneath - without it the row sat half under the system
                // gesture bar and half on top of the message field.
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverlayAction(Icons.Outlined.Delete, "Discard video message", onCancel)
                // Pause, not a second stop: the previous middle button called
                // the same handler as Send, so two of the three controls did
                // exactly the same thing. Pausing keeps the file open and
                // resuming appends to the same take.
                OverlayAction(
                    icon = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    label = if (state.isPaused) "Resume recording" else "Pause recording",
                    onClick = onTogglePause
                )
                OverlayAction(Icons.Filled.Send, "Send video message", onStop)
            }
        }

        // Lock hint that rises with the gesture.
        if (!isLocked) {
            val lockLift = (-dragY / LOCK_DISTANCE_PX).coerceIn(0f, 1f)
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = if (willLock) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 30.dp, bottom = 110.dp)
                    .size(if (willLock) 30.dp else 24.dp)
                    .alpha(0.5f + lockLift * 0.5f)
                    .scale(1f + lockLift * 0.25f)
            )
        }
    }
}

@Composable
private fun OverlayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

/** Travel needed to arm cancel / lock, in pixels. Tuned to feel like Telegram's. */
const val CANCEL_DISTANCE_PX = 220f
const val LOCK_DISTANCE_PX = 180f
