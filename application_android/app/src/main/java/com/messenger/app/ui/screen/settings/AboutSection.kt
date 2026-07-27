package com.messenger.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.messenger.app.ui.components.HairlineDivider
import com.messenger.app.ui.components.SectionHeader
import com.messenger.app.ui.components.SettingsRow
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.AboutInfo

@Composable
fun AboutSection(
    about: AboutInfo,
    onWhatsNew: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
    onSupport: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onCheckForUpdates: () -> Unit
) {
    SectionHeader(title = "About", overline = "Section three")

    AppIdentity(about)

    HairlineDivider()

    SettingsRow(
        title = "What's new",
        subtitle = "Changes in this release",
        icon = Icons.Outlined.NewReleases,
        onClick = onWhatsNew
    )

    SettingsRow(
        title = "Check for updates",
        icon = Icons.Outlined.SystemUpdateAlt,
        onClick = onCheckForUpdates
    )

    HairlineDivider()

    SettingsRow(
        title = "Terms of Service",
        icon = Icons.Outlined.Description,
        onClick = onTerms
    )

    SettingsRow(
        title = "Privacy Policy",
        icon = Icons.Outlined.PrivacyTip,
        onClick = onPrivacy
    )

    SettingsRow(
        title = "Open-source licenses",
        icon = Icons.Outlined.Gavel,
        onClick = onLicenses
    )

    HairlineDivider()

    SettingsRow(
        title = "Contact support",
        subtitle = "Report a problem",
        icon = Icons.Outlined.SupportAgent,
        onClick = onSupport
    )

    SettingsRow(
        title = "Copy diagnostics",
        subtitle = "Version, OS, device and locale",
        icon = Icons.Outlined.ContentCopy,
        onClick = onCopyDiagnostics
    )

    Spacer(Modifier.height(Tokens.Space.xxl))
}

@Composable
private fun AppIdentity(about: AboutInfo) {
    val dark = isDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Tokens.Space.screenGutter,
                vertical = Tokens.Space.lg
            )
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Tokens.Radius.md))
                .background(Tokens.Palette.accent(dark)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(Modifier.height(Tokens.Space.md))

        Text(
            text = about.appName,
            style = Tokens.Type.sectionHeader,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(Tokens.Space.xs))
        Text(
            text = "Version ${about.versionName} (build ${about.versionCode})",
            style = Tokens.Type.rowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
