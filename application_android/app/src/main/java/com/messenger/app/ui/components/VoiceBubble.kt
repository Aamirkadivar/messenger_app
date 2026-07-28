package com.messenger.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Formats a duration as m:ss. */
fun formatVoiceDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Voice-note player inside a message bubble: play/pause, a progress track, and
 * the running time.
 *
 * The track is a plain progress bar rather than a real waveform - drawing a
 * waveform needs amplitude data extracted from the decoded audio, which we
 * don't compute at record time. A fake one would imply information that isn't
 * there.
 */
@Composable
fun VoiceBubbleContent(
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Long,
    tint: Color,
    trackColor: Color,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Prefer the player's own duration once it is known: the recorded value is
    // wall-clock and can differ slightly from the encoded length.
    val total = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(progress, label = "voiceProgress")

    val remaining = if (positionMs > 0) {
        (total - positionMs).coerceAtLeast(0)
    } else {
        total
    }

    Row(
        modifier = modifier.width(200.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.22f))
                .clickable(role = Role.Button, onClick = onTogglePlay)
                .semantics {
                    contentDescription = if (isPlaying) {
                        "Pause voice message"
                    } else {
                        "Play voice message, ${formatVoiceDuration(total)}"
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(trackColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint)
            )
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = formatVoiceDuration(remaining.toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

/**
 * The mic button's recording state: elapsed time and a live level meter.
 */
@Composable
fun RecordingIndicator(
    elapsedMs: Long,
    amplitude: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val level by animateFloatAsState(amplitude.coerceIn(0f, 1f), label = "micLevel")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Recording, ${formatVoiceDuration(elapsedMs)}. " +
                    "Release to send."
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing red dot, sized by input level so silence is visibly different
        // from speech - useful feedback that the mic is actually picking up.
        Box(
            modifier = Modifier
                .size((8 + 8 * level).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatVoiceDuration(elapsedMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Release to send",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Cancel",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onCancel)
                .semantics { contentDescription = "Cancel recording" }
        )
    }
}
