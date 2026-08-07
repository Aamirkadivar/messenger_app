package com.messenger.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Which kind of message a long-press records. */
enum class CaptureMode { VOICE, VIDEO }

/**
 * The composer's capture button.
 *
 * Tap toggles between voice and video, long-press records, and while
 * recording the finger's travel drives cancel (left) and lock (up). Holding
 * all three in one gesture detector is deliberate: they are phases of a single
 * press, and splitting them across separate handlers is how you end up with a
 * tap that both toggles the mode *and* starts a recording.
 *
 * Haptics fire on each state change rather than continuously - a buzz on
 * arming cancel, on arming lock, and on the press itself - which is what makes
 * the gesture legible without looking at the screen.
 */
@Composable
fun CaptureButton(
    mode: CaptureMode,
    isRecording: Boolean,
    hasText: Boolean,
    /** Hands-free: the finger is gone but the mic is still open. */
    isLocked: Boolean = false,
    onToggleMode: () -> Unit,
    onSend: () -> Unit,
    onStartRecording: () -> Unit,
    onDrag: (x: Float, y: Float) -> Unit,
    onArmCancel: (Boolean) -> Unit,
    onArmLock: (Boolean) -> Unit,
    onRelease: (cancelled: Boolean, locked: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var armedCancel by remember { mutableStateOf(false) }
    var armedLock by remember { mutableStateOf(false) }

    // The detector is keyed on Unit and reads everything through these.
    // Keying pointerInput on changing state - the obvious thing to write, and
    // what this used to do - disposes the detector the moment that state
    // changes, and disposal fires onDragCancel. Locking flips isLocked, so a
    // successful lock cancelled its own gesture and delivered a second release
    // with lock=false, stopping and sending the take it had just locked.
    val latestHasText by rememberUpdatedState(hasText)
    val latestLocked by rememberUpdatedState(isLocked)
    val latestToggleMode by rememberUpdatedState(onToggleMode)
    val latestSend by rememberUpdatedState(onSend)
    val latestStart by rememberUpdatedState(onStartRecording)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestArmCancel by rememberUpdatedState(onArmCancel)
    val latestArmLock by rememberUpdatedState(onArmLock)
    val latestRelease by rememberUpdatedState(onRelease)

    // Grows while recording so the button reads as "live".
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.35f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "captureScale"
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (isRecording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            .pointerInput(Unit) {
                // Exactly one release per press. Both callbacks can fire for
                // the same gesture, and the second always carries the
                // already-reset flags, which reads as a plain release and
                // undoes whatever the first one just did.
                var released = true
                fun deliverRelease() {
                    if (released) return
                    released = true
                    latestRelease(armedCancel, armedLock)
                    armedCancel = false
                    armedLock = false
                }

                coroutineScope {
                    // Tap: send when there is text, otherwise flip the mode.
                    launch {
                        detectTapGestures(
                            onTap = {
                                if (latestHasText || latestLocked) {
                                    // Locked means a take is already running
                                    // hands-free, so the only sensible tap is
                                    // "finish and send it".
                                    latestSend()
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestToggleMode()
                                }
                            }
                        )
                    }
                    // Press-and-hold: record, with travel driving cancel/lock.
                    launch {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (latestHasText || latestLocked) return@detectDragGesturesAfterLongPress
                                armedCancel = false
                                armedLock = false
                                released = false
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestStart()
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val x = change.position.x - size.width / 2f
                                val y = change.position.y - size.height / 2f
                                latestDrag(x, y)

                                val cancel = x < -CANCEL_DISTANCE_PX
                                if (cancel != armedCancel) {
                                    armedCancel = cancel
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestArmCancel(cancel)
                                }
                                val lock = y < -LOCK_DISTANCE_PX
                                if (lock != armedLock) {
                                    armedLock = lock
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestArmLock(lock)
                                }
                            },
                            onDragEnd = { deliverRelease() },
                            onDragCancel = { deliverRelease() }
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Crossfade + scale between mic and camera, which is what makes the
        // mode switch feel like one control changing rather than two swapping.
        AnimatedContent(
            targetState = Triple(hasText || isLocked, mode, isRecording),
            transitionSpec = {
                (fadeIn(spring()) + scaleIn(spring(), initialScale = 0.6f)) togetherWith
                    (fadeOut(spring()) + scaleOut(spring(), targetScale = 0.6f))
            },
            label = "captureIcon"
        ) { (text, m, _) ->
            Icon(
                imageVector = when {
                    text -> Icons.AutoMirrored.Filled.Send
                    m == CaptureMode.VIDEO -> Icons.Filled.Videocam
                    else -> Icons.Filled.Mic
                },
                contentDescription = when {
                    text -> "Send"
                    m == CaptureMode.VIDEO -> "Hold to record a video message. Tap to switch to voice."
                    else -> "Hold to record a voice message. Tap to switch to video."
                },
                tint = Color.White
            )
        }
    }
}
