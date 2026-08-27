package com.messenger.app.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.components.AmbientGlow
import com.messenger.app.ui.components.ChatBackground
import com.messenger.app.ui.components.GlassSurface
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.theme.Tokens

/**
 * Detail-pane placeholder when a tablet-sized window has no conversation open.
 */
@Composable
fun ConversationEmptyPane(modifier: Modifier = Modifier) {
    val dark = MessengerExtendedColors.isDark
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("conversation_empty_pane")
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center
    ) {
        AmbientGlow(
            modifier = Modifier.fillMaxSize(),
            baseColor = MaterialTheme.colorScheme.background,
            primaryGlow = MaterialTheme.colorScheme.primary,
            secondaryGlow = MaterialTheme.colorScheme.primary,
            intensity = if (dark) 0.55f else 0.35f
        )
        ChatBackground(
            modifier = Modifier.fillMaxSize(),
            baseOpacity = 0f
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = Tokens.Space.xl)
        ) {
            GlassSurface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                sheen = true
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(Tokens.Space.lg))
            Text(
                text = stringResource(R.string.select_conversation),
                style = Tokens.Type.sectionHeader,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            Text(
                text = stringResource(R.string.select_conversation_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
