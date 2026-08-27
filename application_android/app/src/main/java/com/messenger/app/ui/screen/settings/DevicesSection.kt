package com.messenger.app.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.messenger.app.ui.components.SectionHeader
import com.messenger.app.ui.components.SettingsRow
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.E2EEDeviceUi

@Composable
fun DevicesSection(
    devices: List<E2EEDeviceUi>,
    loading: Boolean,
    currentDeviceId: String?,
    onChangePassword: () -> Unit,
    totpEnabled: Boolean,
    onAuthenticator: () -> Unit,
    onBackupCodes: () -> Unit,
    onLinkDevice: () -> Unit,
    onRevoke: (E2EEDeviceUi) -> Unit,
    onResetEncryption: () -> Unit
) {
    SectionHeader(title = "Security", overline = "Account")

    SettingsRow(
        title = "Change password",
        subtitle = "Updates login and rewraps your encrypted vault",
        icon = Icons.Outlined.Lock,
        onClick = onChangePassword
    )
    SettingsRow(
        title = if (totpEnabled) "Disable authenticator" else "Authenticator app",
        subtitle = if (totpEnabled) "Require a code at sign-in (on)"
        else "Add Google Authenticator / Aegis as a second factor",
        icon = Icons.Outlined.Lock,
        onClick = onAuthenticator
    )
    if (totpEnabled) {
        SettingsRow(
            title = "Backup codes",
            subtitle = "Replace unused authenticator recovery codes",
            icon = Icons.Outlined.Lock,
            onClick = onBackupCodes
        )
    }
    SettingsRow(
        title = "Link a device",
        subtitle = "Scan or paste the pairing code from a new device",
        icon = Icons.Outlined.Link,
        onClick = onLinkDevice
    )

    // Destructive, so it sits apart from the routine rows and is styled as a
    // hazard - the same treatment the MLS group reset gets. Wording says what is
    // lost, not what the mechanism is: people do not reason about master keys.
    SettingsRow(
        title = "Reset encryption key",
        subtitle = "Replaces this account's encryption key. Encrypted history " +
            "becomes permanently unreadable and other devices are signed out.",
        icon = Icons.Outlined.Warning,
        onClick = onResetEncryption
    )

    Spacer(Modifier.height(Tokens.Space.md))

    Text(
        text = "LINKED DEVICES",
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))

    when {
        loading && devices.isEmpty() -> {
            Text(
                text = "Loading...",
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Tokens.Space.screenGutter,
                        vertical = Tokens.Space.sm
                    )
            )
        }
        devices.isEmpty() -> {
            Text(
                text = "No registered devices yet",
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Tokens.Space.screenGutter,
                        vertical = Tokens.Space.sm
                    )
            )
        }
        else -> {
            devices.forEach { device ->
                val isCurrent = currentDeviceId != null && device.deviceId == currentDeviceId
                SettingsRow(
                    title = device.name.ifBlank { device.deviceId },
                    subtitle = buildString {
                        append(device.platform.ifBlank { "unknown" })
                        if (isCurrent) append(" · this device")
                        if (device.revoked) append(" · revoked")
                    },
                    icon = Icons.Outlined.Devices,
                    onClick = {
                        if (!isCurrent && !device.revoked) onRevoke(device)
                    },
                    trailing = {
                        if (!isCurrent && !device.revoked) {
                            Text(
                                text = "Revoke",
                                style = Tokens.Type.rowValue,
                                color = Tokens.Palette.accent(isDarkTheme())
                            )
                        }
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(Tokens.Space.sm))
}