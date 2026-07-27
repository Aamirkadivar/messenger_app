package com.messenger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewParent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.theme.reducedMotionEnabled

/**
 * Sets the dialog scrim and keeps the hosting window from painting behind the
 * panel, which draws its own surface.
 *
 * The scrim is set explicitly at 60% so the panel stays clearly separated from
 * the content behind it. Clearing the window background is defensive: the app
 * theme sets an opaque `android:windowBackground`, and Compose only happens to
 * override it today.
 *
 * Note this is *not* what caused the white band that used to frame every
 * dialog - that came from a theme-level `android:background`, which every view
 * without its own background inherits, including Compose's DialogLayout. See
 * res/values/themes.xml.
 */
@Composable
private fun TransparentDialogWindow() {
    val view = LocalView.current
    SideEffect {
        // DialogWindowProvider is not reliably the immediate parent, so walk up
        // rather than casting the first one and silently doing nothing.
        var parent: ViewParent? = view.parent
        while (parent != null && parent !is DialogWindowProvider) {
            parent = parent.parent
        }
        (parent as? DialogWindowProvider)?.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            // 60% scrim - keeps the panel clearly separated from content behind.
            setDimAmount(0.6f)
        }
    }
}

/**
 * Confirmation for a destructive action.
 *
 * [amountAtRisk] is mandatory and rendered prominently: the user is told
 * exactly what will be lost before they can agree to lose it.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    amountAtRisk: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true
) {
    val dark = isDarkTheme()
    var visible by remember { mutableStateOf(false) }
    val reducedMotion = reducedMotionEnabled()

    // Dialog windows appear instantly; drive our own enter transition so the
    // panel scales and fades in rather than snapping into place.
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TransparentDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Space.lg),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = if (reducedMotion) fadeIn(tween(0)) else
                    fadeIn(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)) +
                        scaleIn(
                            initialScale = 0.94f,
                            animationSpec = tween(
                                Tokens.Motion.STANDARD_MS,
                                easing = Tokens.Motion.easeOut
                            )
                        ),
                exit = if (reducedMotion) fadeOut(tween(0)) else
                    fadeOut(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn)) +
                        scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(
                                Tokens.Motion.EXIT_MS,
                                easing = Tokens.Motion.easeIn
                            )
                        )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Tokens.Radius.md))
                        .background(Tokens.Elevation.level2(dark))
                        .padding(Tokens.Space.lg)
                ) {
                    Text(
                        text = title,
                        style = Tokens.Type.sectionHeader,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(Tokens.Space.sm))
                    Text(
                        text = message,
                        style = Tokens.Type.rowSubtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(Tokens.Space.md))

                    // The exact figure, given its own visual weight.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Tokens.Radius.sm))
                            .background(Tokens.Palette.storageTrack(dark))
                            .padding(
                                horizontal = Tokens.Space.md,
                                vertical = Tokens.Space.md
                            )
                    ) {
                        Text(
                            text = amountAtRisk,
                            style = Tokens.Type.metric,
                            color = if (destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Tokens.Palette.accent(dark)
                            }
                        )
                    }

                    Spacer(Modifier.height(Tokens.Space.lg))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        DialogAction(
                            label = "Cancel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onDismiss
                        )
                        Spacer(Modifier.padding(horizontal = Tokens.Space.sm))
                        DialogAction(
                            label = confirmLabel,
                            color = if (destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Tokens.Palette.accent(dark)
                            },
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}

/**
 * Generic modal shell - same panel treatment as [ConfirmDialog], with an
 * arbitrary content slot. Content scrolls so long lists (licenses, option
 * pickers) stay usable at large font sizes and in landscape.
 */
@Composable
fun LuxDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = if (confirmLabel == null) "Close" else "Cancel",
    content: @Composable () -> Unit
) {
    val dark = isDarkTheme()
    var visible by remember { mutableStateOf(false) }
    val reducedMotion = reducedMotionEnabled()
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TransparentDialogWindow()
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(Tokens.Space.lg),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = if (reducedMotion) fadeIn(tween(0)) else
                    fadeIn(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)) +
                        scaleIn(
                            initialScale = 0.94f,
                            animationSpec = tween(
                                Tokens.Motion.STANDARD_MS,
                                easing = Tokens.Motion.easeOut
                            )
                        ),
                exit = if (reducedMotion) fadeOut(tween(0)) else
                    fadeOut(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Tokens.Radius.md))
                        .background(Tokens.Elevation.level2(dark))
                        .padding(vertical = Tokens.Space.lg)
                ) {
                    Text(
                        text = title,
                        style = Tokens.Type.sectionHeader,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = Tokens.Space.lg)
                    )
                    Spacer(Modifier.height(Tokens.Space.md))

                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }

                    Spacer(Modifier.height(Tokens.Space.md))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Tokens.Space.lg),
                        horizontalArrangement = Arrangement.End
                    ) {
                        DialogAction(
                            label = dismissLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onDismiss
                        )
                        if (confirmLabel != null && onConfirm != null) {
                            Spacer(Modifier.padding(horizontal = Tokens.Space.sm))
                            DialogAction(
                                label = confirmLabel,
                                color = Tokens.Palette.accent(dark),
                                onClick = onConfirm
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(color = color),
                role = Role.Button,
                onClick = onClick
            )
            .defaultMinSize(minHeight = Tokens.Size.touchTarget)
            .padding(horizontal = Tokens.Space.md, vertical = Tokens.Space.sm)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = Tokens.Type.rowValue, color = color)
    }
}
