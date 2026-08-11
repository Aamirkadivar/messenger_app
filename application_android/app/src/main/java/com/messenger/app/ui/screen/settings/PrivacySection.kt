package com.messenger.app.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.messenger.app.ui.components.SectionHeader
import com.messenger.app.ui.components.SettingsRow
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.BlockedUserUi

/**
 * Privacy controls. Blocked people do not appear in the chat list — manage
 * them here and unblock without resurrecting a chat row until they message
 * again (same as a deleted direct chat).
 */
@Composable
fun PrivacySection(
    blockedUsers: List<BlockedUserUi>,
    loading: Boolean,
    onUnblock: (BlockedUserUi) -> Unit
) {
    SectionHeader(title = "Privacy", overline = "Blocked")

    Text(
        text = "BLOCKED USERS",
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))

    when {
        loading && blockedUsers.isEmpty() -> {
            Text(
                text = "Loading…",
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
        blockedUsers.isEmpty() -> {
            Text(
                text = "No blocked users",
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
            blockedUsers.forEach { user ->
                SettingsRow(
                    title = user.name,
                    subtitle = user.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                    icon = Icons.Outlined.Block,
                    onClick = { onUnblock(user) },
                    trailing = {
                        Text(
                            text = "Unblock",
                            style = Tokens.Type.rowValue,
                            color = Tokens.Palette.accent(isDarkTheme())
                        )
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(Tokens.Space.sm))
}
