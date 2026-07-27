package com.messenger.app.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.messenger.app.data.settings.NotificationChannelKind
import com.messenger.app.data.settings.NotificationSettings
import com.messenger.app.data.settings.NotificationSound
import com.messenger.app.ui.components.HairlineDivider
import com.messenger.app.ui.components.SectionHeader
import com.messenger.app.ui.components.SettingsRow
import com.messenger.app.ui.components.ToggleRow
import com.messenger.app.ui.components.ValueRow
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.MutedChatUi
import java.time.format.DateTimeFormatter

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun NotificationsSection(
    settings: NotificationSettings,
    mutedChats: List<MutedChatUi>,
    onMasterChange: (Boolean) -> Unit,
    onChannelChange: (NotificationChannelKind, Boolean) -> Unit,
    onSoundClick: () -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onInAppSoundsChange: (Boolean) -> Unit,
    onPreviewClick: () -> Unit,
    onQuietHoursChange: (Boolean) -> Unit,
    onQuietStartClick: () -> Unit,
    onQuietEndClick: () -> Unit,
    onUnmute: (MutedChatUi) -> Unit
) {
    val on = settings.enabled

    SectionHeader(title = "Notifications", overline = "Section one")

    ToggleRow(
        title = "Allow notifications",
        subtitle = "Turn off to silence everything below",
        icon = Icons.Outlined.NotificationsNone,
        checked = settings.enabled,
        onCheckedChange = onMasterChange
    )

    HairlineDivider()

    // Everything below the master switch stays visible but inert when it is
    // off - hiding it would leave the user unable to see what they've turned
    // off, and re-showing it on toggle would jump the layout.
    NotificationChannelKind.entries.forEach { kind ->
        ToggleRow(
            title = kind.label,
            subtitle = kind.description,
            checked = settings.perChannel[kind] ?: true,
            enabled = on,
            onCheckedChange = { onChannelChange(kind, it) }
        )
    }

    HairlineDivider()

    ValueRow(
        title = "Notification sound",
        value = NotificationSound.fromId(settings.soundId).label,
        icon = Icons.Outlined.VolumeUp,
        enabled = on,
        onClick = onSoundClick
    )

    ToggleRow(
        title = "Vibrate",
        icon = Icons.Outlined.Vibration,
        checked = settings.vibrate,
        enabled = on,
        onCheckedChange = onVibrateChange
    )

    ToggleRow(
        title = "In-app sounds",
        subtitle = "Play a sound while the app is open",
        checked = settings.inAppSounds,
        enabled = on,
        onCheckedChange = onInAppSoundsChange
    )

    HairlineDivider()

    ValueRow(
        title = "Preview",
        subtitle = "What appears on the lock screen",
        value = settings.previewPrivacy.label,
        icon = Icons.Outlined.VisibilityOff,
        enabled = on,
        onClick = onPreviewClick
    )

    HairlineDivider()

    ToggleRow(
        title = "Quiet hours",
        subtitle = "Mute notifications overnight",
        icon = Icons.Outlined.Bedtime,
        checked = settings.quietHoursEnabled,
        enabled = on,
        onCheckedChange = onQuietHoursChange
    )

    AnimatedVisibility(
        visible = settings.quietHoursEnabled && on,
        enter = fadeIn(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)) +
            expandVertically(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)),
        exit = fadeOut(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn)) +
            shrinkVertically(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn))
    ) {
        Column {
            ValueRow(
                title = "From",
                value = settings.quietHoursStart.format(TimeFormat),
                enabled = on,
                onClick = onQuietStartClick
            )
            ValueRow(
                title = "Until",
                value = settings.quietHoursEnd.format(TimeFormat),
                enabled = on,
                onClick = onQuietEndClick
            )
        }
    }

    HairlineDivider()

    MutedChatsGroup(mutedChats = mutedChats, enabled = on, onUnmute = onUnmute)
}

@Composable
private fun MutedChatsGroup(
    mutedChats: List<MutedChatUi>,
    enabled: Boolean,
    onUnmute: (MutedChatUi) -> Unit
) {
    Spacer(Modifier.height(Tokens.Space.lg))
    Text(
        text = "MUTED CHATS",
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))

    if (mutedChats.isEmpty()) {
        Text(
            text = "No muted conversations",
            style = Tokens.Type.rowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Tokens.Space.screenGutter,
                    vertical = Tokens.Space.sm
                )
        )
        Spacer(Modifier.height(Tokens.Space.sm))
    } else {
        mutedChats.forEach { chat ->
            SettingsRow(
                title = chat.name,
                icon = Icons.Outlined.NotificationsOff,
                enabled = enabled,
                onClick = { onUnmute(chat) },
                trailing = {
                    Text(
                        text = "Unmute",
                        style = Tokens.Type.rowValue,
                        color = Tokens.Palette.accent(isDarkTheme())
                    )
                }
            )
        }
    }
}
