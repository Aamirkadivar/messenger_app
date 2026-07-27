package com.messenger.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.data.settings.MediaKind
import com.messenger.app.data.settings.NetworkKind
import com.messenger.app.data.settings.NotificationSound
import com.messenger.app.data.settings.PreviewPrivacy
import com.messenger.app.data.storage.ConversationStorage
import com.messenger.app.data.storage.formatBytes
import com.messenger.app.ui.components.ConfirmDialog
import com.messenger.app.ui.components.LuxDialog
import com.messenger.app.ui.components.OptionList
import com.messenger.app.ui.screen.settings.AboutLinks
import com.messenger.app.ui.screen.settings.AboutSection
import com.messenger.app.ui.screen.settings.Changelog
import com.messenger.app.ui.screen.settings.NotificationsSection
import com.messenger.app.ui.screen.settings.OpenSourceLicenses
import com.messenger.app.ui.screen.settings.StorageSection
import com.messenger.app.ui.theme.Tokens
import com.messenger.app.ui.theme.isDarkTheme
import com.messenger.app.ui.viewmodel.SettingsViewModel
import java.time.LocalTime

/** Which modal is currently open. */
private sealed interface ActiveDialog {
    data object SoundPicker : ActiveDialog
    data object PreviewPrivacyPicker : ActiveDialog
    data object QuietStart : ActiveDialog
    data object QuietEnd : ActiveDialog
    data object WhatsNew : ActiveDialog
    data object Licenses : ActiveDialog
    data object ConfirmClearCache : ActiveDialog
    data class ConfirmClearChat(val conversation: ConversationStorage) : ActiveDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storageState by viewModel.storageState.collectAsStateWithLifecycle()
    val mutedChats by viewModel.mutedChats.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var activeDialog by remember { mutableStateOf<ActiveDialog?>(null) }

    // ArrayList rather than Set so the expansion state survives rotation -
    // rememberSaveable can only persist Bundle-compatible types.
    var expandedChats by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var expandedNetworks by rememberSaveable { mutableStateOf(ArrayList<String>()) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    /** Opens a URL, reporting rather than failing silently when it can't. */
    fun openUrl(url: String, unavailableMessage: String) {
        if (url.isBlank()) {
            viewModel.showMessage(unavailableMessage)
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            viewModel.showMessage("No app available to open this link")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = Tokens.Type.screenTitle,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            NotificationsSection(
                settings = settings.notifications,
                mutedChats = mutedChats,
                onMasterChange = viewModel::setNotificationsEnabled,
                onChannelChange = viewModel::setChannelEnabled,
                onSoundClick = { activeDialog = ActiveDialog.SoundPicker },
                onVibrateChange = viewModel::setVibrate,
                onInAppSoundsChange = viewModel::setInAppSounds,
                onPreviewClick = { activeDialog = ActiveDialog.PreviewPrivacyPicker },
                onQuietHoursChange = viewModel::setQuietHoursEnabled,
                onQuietStartClick = { activeDialog = ActiveDialog.QuietStart },
                onQuietEndClick = { activeDialog = ActiveDialog.QuietEnd },
                onUnmute = { viewModel.unmuteChat(it.chatId) }
            )

            StorageSection(
                scanState = storageState,
                settings = settings.storage,
                expandedChatIds = expandedChats.toSet(),
                expandedNetworks = expandedNetworks.mapNotNull { id ->
                    NetworkKind.entries.firstOrNull { it.id == id }
                }.toSet(),
                onToggleChatExpanded = { id ->
                    expandedChats = ArrayList(expandedChats).apply {
                        if (!remove(id)) add(id)
                    }
                },
                onToggleNetworkExpanded = { network ->
                    expandedNetworks = ArrayList(expandedNetworks).apply {
                        if (!remove(network.id)) add(network.id)
                    }
                },
                onClearChatMedia = { activeDialog = ActiveDialog.ConfirmClearChat(it) },
                onClearCacheClick = { activeDialog = ActiveDialog.ConfirmClearCache },
                onAutoDownloadChange = viewModel::setAutoDownload,
                onAutoDeleteChange = viewModel::setAutoDeleteWindow,
                onDataSaverChange = viewModel::setDataSaver
            )

            AboutSection(
                about = viewModel.aboutInfo,
                onWhatsNew = { activeDialog = ActiveDialog.WhatsNew },
                onTerms = {
                    openUrl(AboutLinks.TERMS_OF_SERVICE, "Terms of Service aren't published yet")
                },
                onPrivacy = {
                    openUrl(AboutLinks.PRIVACY_POLICY, "Privacy Policy isn't published yet")
                },
                onLicenses = { activeDialog = ActiveDialog.Licenses },
                onSupport = {
                    if (AboutLinks.SUPPORT_EMAIL.isBlank()) {
                        viewModel.showMessage("No support address is configured yet")
                    } else {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:${AboutLinks.SUPPORT_EMAIL}")
                            putExtra(Intent.EXTRA_SUBJECT, "Messenger support request")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "\n\n---\n${viewModel.aboutInfo.toDiagnosticsText()}"
                            )
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            viewModel.showMessage("No email app available")
                        }
                    }
                },
                onCopyDiagnostics = viewModel::copyDiagnostics,
                onCheckForUpdates = {
                    // Standard Android behaviour: hand off to the installing
                    // store. There is no bespoke update service to query.
                    val pkg = context.packageName
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                        )
                    } catch (e: ActivityNotFoundException) {
                        viewModel.showMessage(
                            "This build was installed manually - update it the same way"
                        )
                    }
                }
            )
        }
    }

    // ==================== Dialogs ====================

    when (val dialog = activeDialog) {
        null -> Unit

        ActiveDialog.SoundPicker -> LuxDialog(
            title = "Notification sound",
            onDismiss = { activeDialog = null }
        ) {
            OptionList(
                options = NotificationSound.entries,
                selected = NotificationSound.fromId(settings.notifications.soundId),
                onSelect = {
                    viewModel.setSound(it)
                    activeDialog = null
                },
                label = { it.label }
            )
        }

        ActiveDialog.PreviewPrivacyPicker -> LuxDialog(
            title = "Preview",
            onDismiss = { activeDialog = null }
        ) {
            OptionList(
                options = PreviewPrivacy.entries,
                selected = settings.notifications.previewPrivacy,
                onSelect = {
                    viewModel.setPreviewPrivacy(it)
                    activeDialog = null
                },
                label = { it.label },
                description = { it.description }
            )
        }

        ActiveDialog.QuietStart -> QuietHourPicker(
            title = "Quiet hours start",
            initial = settings.notifications.quietHoursStart,
            onConfirm = { time ->
                viewModel.setQuietHours(time, settings.notifications.quietHoursEnd)
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )

        ActiveDialog.QuietEnd -> QuietHourPicker(
            title = "Quiet hours end",
            initial = settings.notifications.quietHoursEnd,
            onConfirm = { time ->
                viewModel.setQuietHours(settings.notifications.quietHoursStart, time)
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )

        ActiveDialog.WhatsNew -> LuxDialog(
            title = "What's new",
            onDismiss = { activeDialog = null }
        ) {
            Column(modifier = Modifier.padding(horizontal = Tokens.Space.screenGutter)) {
                Changelog.forEach { entry ->
                    Text(
                        text = "Version ${entry.version}",
                        style = Tokens.Type.overline,
                        color = Tokens.Palette.accent(isDarkTheme())
                    )
                    Spacer(Modifier.height(Tokens.Space.sm))
                    entry.changes.forEach { change ->
                        Text(
                            text = "·  $change",
                            style = Tokens.Type.rowSubtitle,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(bottom = Tokens.Space.xs)
                        )
                    }
                }
            }
        }

        ActiveDialog.Licenses -> LuxDialog(
            title = "Open-source licenses",
            onDismiss = { activeDialog = null }
        ) {
            Column(modifier = Modifier.padding(horizontal = Tokens.Space.screenGutter)) {
                OpenSourceLicenses.forEach { license ->
                    Text(
                        text = license.library,
                        style = Tokens.Type.rowTitle,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = license.license,
                        style = Tokens.Type.rowSubtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Tokens.Space.sm)
                    )
                }
            }
        }

        ActiveDialog.ConfirmClearCache -> {
            val cacheBytes = (storageState as? com.messenger.app.data.storage.StorageScanState.Ready)
                ?.report?.cacheBytes ?: 0L
            ConfirmDialog(
                title = "Clear cache?",
                message = "Temporary files will be removed. Your messages and " +
                    "media are not affected.",
                amountAtRisk = "${formatBytes(cacheBytes)} will be freed",
                confirmLabel = "Clear cache",
                destructive = false,
                onConfirm = {
                    viewModel.clearCache()
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }

        is ActiveDialog.ConfirmClearChat -> ConfirmDialog(
            title = "Clear media?",
            message = "All photos, videos, voice messages and documents in " +
                "${dialog.conversation.chatName} will be deleted from this device. " +
                "The messages themselves are kept.",
            amountAtRisk = "${formatBytes(dialog.conversation.bytes)} will be deleted",
            confirmLabel = "Clear media",
            destructive = true,
            onConfirm = {
                viewModel.clearConversationMedia(
                    dialog.conversation.chatId,
                    dialog.conversation.chatName
                )
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHourPicker(
    title: String,
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false
    )
    val dark = isDarkTheme()

    LuxDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = "Set",
        onConfirm = { onConfirm(LocalTime.of(state.hour, state.minute)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Space.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Tokens.Palette.storageTrack(dark),
                    selectorColor = Tokens.Palette.accent(dark),
                    containerColor = Tokens.Elevation.level2(dark),
                    periodSelectorSelectedContainerColor =
                        Tokens.Palette.accent(dark).copy(alpha = 0.20f),
                    periodSelectorSelectedContentColor = Tokens.Palette.accent(dark),
                    timeSelectorSelectedContainerColor =
                        Tokens.Palette.accent(dark).copy(alpha = 0.20f),
                    timeSelectorSelectedContentColor = Tokens.Palette.accent(dark)
                )
            )
        }
    }
}
