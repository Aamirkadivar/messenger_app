package com.messenger.app.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.messenger.app.data.settings.AutoDeleteWindow
import com.messenger.app.data.settings.MediaKind
import com.messenger.app.data.settings.NetworkKind
import com.messenger.app.data.settings.StorageSettings
import com.messenger.app.data.storage.ConversationStorage
import com.messenger.app.data.storage.StorageReport
import com.messenger.app.data.storage.StorageScanState
import com.messenger.app.data.storage.formatBytes
import com.messenger.app.ui.components.HairlineDivider
import com.messenger.app.ui.components.SectionHeader
import com.messenger.app.ui.components.SegmentedControl
import com.messenger.app.ui.components.SettingsRow
import com.messenger.app.ui.components.StorageBar
import com.messenger.app.ui.components.StorageLegendRow
import com.messenger.app.ui.components.StorageSegment
import com.messenger.app.ui.components.ToggleRow
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.theme.luxuryTween

@Composable
fun StorageSection(
    scanState: StorageScanState,
    settings: StorageSettings,
    expandedChatIds: Set<String>,
    expandedNetworks: Set<NetworkKind>,
    onToggleChatExpanded: (String) -> Unit,
    onToggleNetworkExpanded: (NetworkKind) -> Unit,
    onClearChatMedia: (ConversationStorage) -> Unit,
    onClearCacheClick: () -> Unit,
    onAutoDownloadChange: (NetworkKind, MediaKind, Boolean) -> Unit,
    onAutoDeleteChange: (AutoDeleteWindow) -> Unit,
    onDataSaverChange: (Boolean) -> Unit
) {
    SectionHeader(title = "Storage and data", overline = "Section two")

    StorageSummary(scanState)

    HairlineDivider()

    if (scanState is StorageScanState.Ready && scanState.report.perConversation.isNotEmpty()) {
        ConversationBreakdown(
            conversations = scanState.report.perConversation,
            expandedChatIds = expandedChatIds,
            onToggleExpanded = onToggleChatExpanded,
            onClearChatMedia = onClearChatMedia
        )
        HairlineDivider()
    }

    val cacheBytes = (scanState as? StorageScanState.Ready)?.report?.cacheBytes ?: 0L
    SettingsRow(
        title = "Clear cache",
        subtitle = "Frees temporary files. Messages are never deleted.",
        icon = Icons.Outlined.CleaningServices,
        enabled = cacheBytes > 0,
        onClick = onClearCacheClick,
        trailing = {
            Text(
                text = formatBytes(cacheBytes),
                style = Tokens.Type.metric,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )

    HairlineDivider()

    AutoDownloadGroup(
        settings = settings,
        expandedNetworks = expandedNetworks,
        onToggleExpanded = onToggleNetworkExpanded,
        onChange = onAutoDownloadChange
    )

    HairlineDivider()

    Spacer(Modifier.height(Tokens.Space.lg))
    Text(
        text = "AUTO-DELETE MEDIA",
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))
    Text(
        text = "Older media is removed automatically. Messages stay.",
        style = Tokens.Type.rowSubtitle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.md))
    SegmentedControl(
        options = AutoDeleteWindow.entries,
        selected = settings.autoDeleteWindow,
        onSelect = onAutoDeleteChange,
        label = { window ->
            when (window) {
                AutoDeleteWindow.OFF -> "Never"
                AutoDeleteWindow.DAYS_7 -> "7d"
                AutoDeleteWindow.DAYS_30 -> "30d"
                AutoDeleteWindow.DAYS_90 -> "90d"
            }
        },
        modifier = Modifier.padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.lg))

    HairlineDivider()

    ToggleRow(
        title = "Data saver",
        subtitle = "Lower-quality uploads, no media autoplay",
        icon = Icons.Outlined.DataSaverOn,
        checked = settings.dataSaver,
        onCheckedChange = onDataSaverChange
    )
}

// ==================== Summary ====================

@Composable
private fun StorageSummary(scanState: StorageScanState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    ) {
        when (scanState) {
            is StorageScanState.Idle, is StorageScanState.Scanning -> ScanningState()
            is StorageScanState.Failed -> Text(
                text = scanState.message,
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = Tokens.Space.md)
            )
            is StorageScanState.Ready -> ReadyState(scanState.report)
        }
    }
}

@Composable
private fun ScanningState() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Space.lg)
            // Announced once when it appears; the result replaces it below.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Calculating storage use"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = Tokens.Palette.accent(isDarkTheme())
        )
        Spacer(Modifier.size(Tokens.Space.md))
        Text(
            text = "Calculating storage use…",
            style = Tokens.Type.rowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadyState(report: StorageReport) {
    val dark = isDarkTheme()
    val colors = Tokens.Palette.storageSegments(dark)

    val segments = buildList {
        MediaKind.entries.forEachIndexed { index, kind ->
            add(
                StorageSegment(
                    label = kind.label,
                    bytes = report.byKind[kind] ?: 0L,
                    color = colors[index % colors.size]
                )
            )
        }
        add(StorageSegment("Cache", report.cacheBytes, colors.last()))
    }

    val summary = if (report.totalBytes == 0L) {
        "No storage in use yet"
    } else {
        "Total ${formatBytes(report.totalBytes)}. " +
            segments.filter { it.bytes > 0 }
                .joinToString(", ") { "${it.label} ${formatBytes(it.bytes)}" }
    }

    Spacer(Modifier.height(Tokens.Space.sm))

    Text(
        text = formatBytes(report.totalBytes),
        style = Tokens.Type.sectionHeader,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "in use",
        style = Tokens.Type.rowSubtitle,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(Tokens.Space.md))

    StorageBar(
        segments = segments,
        totalBytes = report.totalBytes,
        accessibilitySummary = summary
    )

    Spacer(Modifier.height(Tokens.Space.md))

    // Values are listed here as well, so nothing depends on colour alone.
    segments.forEach { segment ->
        StorageLegendRow(segment = segment, formattedValue = formatBytes(segment.bytes))
    }

    Spacer(Modifier.height(Tokens.Space.md))
}

// ==================== Per-conversation breakdown ====================

@Composable
private fun ConversationBreakdown(
    conversations: List<ConversationStorage>,
    expandedChatIds: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onClearChatMedia: (ConversationStorage) -> Unit
) {
    Spacer(Modifier.height(Tokens.Space.lg))
    Text(
        text = "BY CONVERSATION",
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))

    conversations.forEach { conversation ->
        val expanded = conversation.chatId in expandedChatIds
        ExpandableConversationRow(
            conversation = conversation,
            expanded = expanded,
            onToggle = { onToggleExpanded(conversation.chatId) },
            onClear = { onClearChatMedia(conversation) }
        )
    }
    Spacer(Modifier.height(Tokens.Space.sm))
}

@Composable
private fun ExpandableConversationRow(
    conversation: ConversationStorage,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit
) {
    val dark = isDarkTheme()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = luxuryTween(Tokens.Motion.STANDARD_MS),
        label = "chevron"
    )

    Column {
        SettingsRow(
            title = conversation.chatName,
            subtitle = formatBytes(conversation.bytes),
            onClick = onToggle,
            modifier = Modifier.semantics {
                contentDescription =
                    "${conversation.chatName}, ${formatBytes(conversation.bytes)}, " +
                    if (expanded) "expanded" else "collapsed"
            },
            trailing = {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(Tokens.Size.icon)
                        .rotate(chevronRotation)
                )
            }
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)) +
                expandVertically(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)),
            exit = fadeOut(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn)) +
                shrinkVertically(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Tokens.Elevation.level1(dark))
                    .padding(
                        horizontal = Tokens.Space.screenGutter,
                        vertical = Tokens.Space.md
                    )
            ) {
                MediaKind.entries.forEach { kind ->
                    val bytes = conversation.byKind[kind] ?: 0L
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Tokens.Space.xs)
                            .semantics(mergeDescendants = true) { },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = kind.label,
                            style = Tokens.Type.rowSubtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatBytes(bytes),
                            style = Tokens.Type.metric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(Tokens.Space.sm))

                SettingsRow(
                    title = "Clear this chat's media",
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = onClear,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Clear media for ${conversation.chatName}, " +
                            "frees ${formatBytes(conversation.bytes)}. Messages are kept."
                    },
                    trailing = {
                        Text(
                            text = formatBytes(conversation.bytes),
                            style = Tokens.Type.metric,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}

// ==================== Auto-download ====================

@Composable
private fun AutoDownloadGroup(
    settings: StorageSettings,
    expandedNetworks: Set<NetworkKind>,
    onToggleExpanded: (NetworkKind) -> Unit,
    onChange: (NetworkKind, MediaKind, Boolean) -> Unit
) {
    Spacer(Modifier.height(Tokens.Space.lg))
    Text(
        text = "AUTO-DOWNLOAD",
        style = Tokens.Type.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.screenGutter)
    )
    Spacer(Modifier.height(Tokens.Space.sm))

    NetworkKind.entries.forEach { network ->
        val enabledKinds = settings.autoDownload[network].orEmpty()
        val expanded = network in expandedNetworks
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = luxuryTween(Tokens.Motion.STANDARD_MS),
            label = "chevron_${network.id}"
        )

        val summary = when {
            enabledKinds.isEmpty() -> "Off"
            enabledKinds.size == MediaKind.entries.size -> "All media"
            else -> enabledKinds.sortedBy { it.ordinal }.joinToString(", ") { it.label }
        }

        Column {
            SettingsRow(
                title = network.label,
                subtitle = summary,
                onClick = { onToggleExpanded(network) },
                modifier = Modifier.semantics {
                    contentDescription = "${network.label}, $summary, " +
                        if (expanded) "expanded" else "collapsed"
                },
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(Tokens.Size.icon)
                            .rotate(chevronRotation)
                    )
                }
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)) +
                    expandVertically(
                        tween(Tokens.Motion.STANDARD_MS, easing = Tokens.Motion.easeOut)
                    ),
                exit = fadeOut(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn)) +
                    shrinkVertically(tween(Tokens.Motion.EXIT_MS, easing = Tokens.Motion.easeIn))
            ) {
                Column(modifier = Modifier.background(Tokens.Elevation.level1(isDarkTheme()))) {
                    MediaKind.entries.forEach { kind ->
                        ToggleRow(
                            title = kind.label,
                            checked = kind in enabledKinds,
                            onCheckedChange = { onChange(network, kind, it) }
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Tokens.Space.sm))
}
