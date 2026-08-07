package com.messenger.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The lock capsule that floats above the record button while a voice message
 * is being held, the way Telegram's does.
 *
 * It is a progress readout, not a button: the capsule shortens and the chevron
 * fades as the finger climbs toward the lock threshold, so the gesture shows
 * how far it has left to travel instead of locking with no warning. It arms
 * (and recolours) at exactly the same threshold the gesture uses, so what the
 * user sees and what the detector decides can never disagree.
 */
@Composable
fun RecordingLockHint(
    dragY: Float,
    armed: Boolean,
    /** True once the take is locked - the capsule stays as a closed padlock. */
    locked: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Locking resets the drag, so the raw progress falls back to 0. Pinning it
    // to 1 keeps the capsule collapsed instead of springing back to its full
    // height the instant the lock succeeds.
    val progress = if (locked) 1f else (-dragY / LOCK_DISTANCE_PX).coerceIn(0f, 1f)

    // Shrinks toward a plain lock badge as the finger approaches, which is
    // what makes the capsule feel like it is being "collapsed into" the lock.
    val capsuleHeight by animateDpAsState(
        targetValue = (78 - 30 * progress).dp,
        animationSpec = tween(90),
        label = "lockCapsuleHeight"
    )
    val chevronAlpha by animateFloatAsState(
        targetValue = if (locked) 0f else (1f - progress * 1.6f).coerceIn(0f, 1f),
        animationSpec = tween(90),
        label = "lockChevronAlpha"
    )
    val lift by animateDpAsState(
        targetValue = (-6 * progress).dp,
        animationSpec = tween(90),
        label = "lockLift"
    )
    val armScale by animateFloatAsState(
        targetValue = if (armed) 1.15f else 1f,
        animationSpec = spring(),
        label = "lockArmScale"
    )

    val highlighted = armed || locked
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (highlighted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .padding(bottom = 0.dp)
            .graphicsLayer {
                translationY = lift.toPx()
                scaleX = armScale
                scaleY = armScale
            }
            .width(44.dp)
            .height(capsuleHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .semantics {
                contentDescription = when {
                    locked -> "Recording locked. Hands-free."
                    armed -> "Release to lock recording"
                    else -> "Slide up to lock recording"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Outlined.Lock,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp)
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(18.dp)
                .alpha(chevronAlpha),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
