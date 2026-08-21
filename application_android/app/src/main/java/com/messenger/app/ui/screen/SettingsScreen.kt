package com.messenger.app.ui.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.data.settings.MediaKind
import com.messenger.app.data.settings.NetworkKind
import com.messenger.app.data.settings.NotificationSound
import com.messenger.app.data.settings.PreviewPrivacy
import com.messenger.app.data.storage.ConversationStorage
import com.messenger.app.data.storage.formatBytes
import com.messenger.app.ui.components.Avatar
import com.messenger.app.ui.components.ConfirmDialog
import com.messenger.app.ui.components.LuxDialog
import com.messenger.app.ui.components.OptionList
import com.messenger.app.ui.screen.settings.AboutLinks
import com.messenger.app.ui.screen.settings.AboutSection
import com.messenger.app.ui.screen.settings.Changelog
import com.messenger.app.ui.screen.settings.NotificationsSection
import com.messenger.app.ui.screen.settings.OpenSourceLicenses
import com.messenger.app.ui.screen.settings.PrivacySection
import com.messenger.app.ui.screen.settings.DevicesSection
import com.messenger.app.ui.screen.settings.StorageSection
import com.messenger.app.ui.pairing.PairingQrScanDialog
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
    data object ChangePassword : ActiveDialog
    data object AuthenticatorSetup : ActiveDialog
    data object AuthenticatorDisable : ActiveDialog
    data object AuthenticatorBackupCodes : ActiveDialog
    data object LinkDevice : ActiveDialog
    data object ScanPairingQr : ActiveDialog
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
    val blockedUsers by viewModel.blockedUsers.collectAsStateWithLifecycle()
    val blockedUsersLoading by viewModel.blockedUsersLoading.collectAsStateWithLifecycle()
    val e2eeDevices by viewModel.e2eeDevices.collectAsStateWithLifecycle()
    val e2eeDevicesLoading by viewModel.e2eeDevicesLoading.collectAsStateWithLifecycle()
    val currentDeviceId by viewModel.currentDeviceId.collectAsStateWithLifecycle()
    val changingPassword by viewModel.changingPassword.collectAsStateWithLifecycle()
    val totpEnabled by viewModel.totpEnabled.collectAsStateWithLifecycle()
    val totpSecret by viewModel.totpSecret.collectAsStateWithLifecycle()
    val totpBusy by viewModel.totpBusy.collectAsStateWithLifecycle()
    val totpBackupCodes by viewModel.totpBackupCodes.collectAsStateWithLifecycle()
    val passwordChangeSucceeded by viewModel.passwordChangeSucceeded.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val uploadingAvatar by viewModel.uploadingAvatar.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // System photo picker - no storage permission, and only the chosen image
    // is ever shared with the app.
    val pickProfilePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::setProfilePhoto) }

    var activeDialog by remember { mutableStateOf<ActiveDialog?>(null) }

    LaunchedEffect(totpEnabled, totpBackupCodes) {
        if (totpEnabled && totpBackupCodes.isEmpty() &&
            activeDialog == ActiveDialog.AuthenticatorSetup
        ) {
            activeDialog = null
        }
        if (!totpEnabled && activeDialog == ActiveDialog.AuthenticatorDisable) {
            activeDialog = null
        }
        if (totpBackupCodes.isNotEmpty() &&
            activeDialog == ActiveDialog.AuthenticatorBackupCodes
        ) {
            activeDialog = ActiveDialog.AuthenticatorSetup
        }
    }

    LaunchedEffect(passwordChangeSucceeded) {
        if (passwordChangeSucceeded) {
            activeDialog = null
            viewModel.consumePasswordChangeSucceeded()
        }
    }

    // ArrayList rather than Set so the expansion state survives rotation -
    // rememberSaveable can only persist Bundle-compatible types.
    var expandedChats by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var expandedNetworks by rememberSaveable { mutableStateOf(ArrayList<String>()) }

    LaunchedEffect(Unit) {
        viewModel.loadBlockedUsers()
    }

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
            ProfileCard(
                name = profile.name,
                email = profile.email,
                avatarUrl = profile.avatarUrl,
                uploading = uploadingAvatar,
                onChangePhoto = {
                    pickProfilePhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )

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

            PrivacySection(
                blockedUsers = blockedUsers,
                loading = blockedUsersLoading,
                onUnblock = viewModel::unblockUser
            )

            DevicesSection(
                devices = e2eeDevices,
                loading = e2eeDevicesLoading,
                currentDeviceId = currentDeviceId,
                onChangePassword = { activeDialog = ActiveDialog.ChangePassword },
                totpEnabled = totpEnabled,
                onAuthenticator = {
                    if (totpEnabled) activeDialog = ActiveDialog.AuthenticatorDisable
                    else {
                        viewModel.startTotpSetup()
                        activeDialog = ActiveDialog.AuthenticatorSetup
                    }
                },
                onBackupCodes = { activeDialog = ActiveDialog.AuthenticatorBackupCodes },
                onLinkDevice = { activeDialog = ActiveDialog.LinkDevice },
                onRevoke = viewModel::revokeE2EEDevice
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

        ActiveDialog.ChangePassword -> ChangePasswordDialog(
            busy = changingPassword,
            onDismiss = { if (!changingPassword) activeDialog = null },
            onConfirm = { current, next -> viewModel.changePassword(current, next) }
        )

        ActiveDialog.AuthenticatorSetup -> AuthenticatorSetupDialog(
            secret = totpSecret,
            backupCodes = totpBackupCodes,
            busy = totpBusy,
            onDismiss = {
                viewModel.clearTotpSetup()
                activeDialog = null
            },
            onConfirm = { code -> viewModel.confirmTotp(code) }
        )

        ActiveDialog.AuthenticatorDisable -> AuthenticatorDisableDialog(
            busy = totpBusy,
            onDismiss = { if (!totpBusy) activeDialog = null },
            onConfirm = { password, code -> viewModel.disableTotp(password, code) }
        )

        ActiveDialog.AuthenticatorBackupCodes -> AuthenticatorDisableDialog(
            title = "New backup codes",
            confirmLabel = if (totpBusy) "Generating…" else "Replace codes",
            busy = totpBusy,
            onDismiss = { if (!totpBusy) activeDialog = null },
            onConfirm = { password, code -> viewModel.regenerateTotpBackupCodes(password, code) }
        )

        ActiveDialog.LinkDevice -> LinkDeviceDialog(
            onDismiss = { activeDialog = null },
            onScan = { activeDialog = ActiveDialog.ScanPairingQr },
            onConfirm = { code ->
                // Same prefix dispatch as the scanner, so a pasted sign-in code
                // works too.
                if (code.trim().startsWith("qr1.")) viewModel.approveQrLogin(code)
                else viewModel.approveDevicePairing(code)
                activeDialog = null
            }
        )

        ActiveDialog.ScanPairingQr -> PairingQrScanDialog(
            onDismiss = { activeDialog = ActiveDialog.LinkDevice },
            title = "Scan code",
            onCode = { code ->
                // Two different codes reach this scanner and the user should not
                // have to know which is which:
                //   qr1. = sign in on another device (grants a session)
                //   mp1. = link a device to this account's E2EE vault
                if (code.trim().startsWith("qr1.")) viewModel.approveQrLogin(code)
                else viewModel.approveDevicePairing(code)
                activeDialog = null
            }
        )

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

@Composable
private fun LinkDeviceDialog(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    LuxDialog(
        title = "Link a device",
        onDismiss = onDismiss,
        confirmLabel = "Approve",
        onConfirm = {
            if (code.isNotBlank()) onConfirm(code.trim())
        },
        dismissLabel = "Cancel"
    ) {
        Column(Modifier.padding(horizontal = Tokens.Space.lg)) {
            Text(
                text = "Scan the code shown on the other device - either to sign " +
                    "in on a computer, or to link it to your encrypted messages. " +
                    "You can also paste the code.",
                style = Tokens.Type.rowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            TextButton(onClick = onScan) {
                Text("Scan QR code")
            }
            Spacer(Modifier.height(Tokens.Space.sm))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Pairing code") },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (current: String, newPassword: String) -> Unit
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    LuxDialog(
        title = "Change password",
        onDismiss = onDismiss,
        confirmLabel = if (busy) "Updating…" else "Update",
        onConfirm = {
            if (busy) return@LuxDialog
            localError = when {
                current.isBlank() -> "Enter your current password"
                next.length < 8 -> "New password must be at least 8 characters"
                next != confirm -> "New passwords do not match"
                next == current -> "New password must differ from current password"
                else -> null
            }
            if (localError == null) onConfirm(current, next)
        },
        dismissLabel = "Cancel"
    ) {
        Column(Modifier.padding(horizontal = Tokens.Space.lg)) {
            OutlinedTextField(
                value = current,
                onValueChange = { current = it; localError = null },
                label = { Text("Current password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            OutlinedTextField(
                value = next,
                onValueChange = { next = it; localError = null },
                label = { Text("New password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; localError = null },
                label = { Text("Confirm new password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            localError?.let { err ->
                Spacer(Modifier.height(Tokens.Space.sm))
                Text(
                    text = err,
                    style = Tokens.Type.rowSubtitle,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AuthenticatorSetupDialog(
    secret: String?,
    backupCodes: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val showingCodes = backupCodes.isNotEmpty()
    LuxDialog(
        title = if (showingCodes) "Save backup codes" else "Authenticator app",
        onDismiss = onDismiss,
        confirmLabel = when {
            showingCodes -> "I've saved them"
            busy -> "Checking…"
            else -> "Enable"
        },
        onConfirm = {
            if (showingCodes) onDismiss()
            else if (!busy && code.length == 6) onConfirm(code)
        }
    ) {
        if (showingCodes) {
            Text(
                "Store these somewhere safe. Each code works once if you lose your authenticator.",
                style = Tokens.Type.rowSubtitle
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            backupCodes.forEach { c ->
                Text(c, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text(
                "Add this secret in Google Authenticator, Aegis, or Authy, then enter a code to confirm.",
                style = Tokens.Type.rowSubtitle
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            Text(
                text = secret ?: "Generating…",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(Tokens.Space.sm))
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 6) code = it.filter { ch -> ch.isDigit() } },
                label = { Text("6-digit code") },
                singleLine = true,
                enabled = !busy && !secret.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AuthenticatorDisableDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, code: String) -> Unit,
    title: String = "Disable authenticator",
    confirmLabel: String? = null
) {
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    LuxDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel ?: if (busy) "Disabling…" else "Disable",
        onConfirm = {
            if (!busy && password.isNotBlank() && code.isNotBlank()) onConfirm(password, code)
        }
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Account password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Tokens.Space.sm))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 9) code = it.filter { ch -> ch.isLetterOrDigit() || ch == '-' } },
            label = { Text("Authenticator or backup code") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Profile identity card with a tappable picture. */
@Composable
private fun ProfileCard(
    name: String,
    email: String,
    avatarUrl: String?,
    uploading: Boolean,
    onChangePhoto: () -> Unit
) {
    val dark = isDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Space.md, vertical = Tokens.Space.sm)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(Tokens.Elevation.level1(dark))
            .clickable(role = Role.Button, onClick = onChangePhoto)
            .padding(Tokens.Space.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name. Change profile picture"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Avatar(name = name.ifBlank { "?" }, avatarUrl = avatarUrl, size = 64.dp)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Tokens.Palette.accent(dark)),
                contentAlignment = Alignment.Center
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(Tokens.Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                text = name.ifBlank { "Your profile" },
                style = Tokens.Type.rowTitle,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (email.isNotBlank()) {
                Text(
                    text = email,
                    style = Tokens.Type.rowSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Tap to change your picture",
                style = Tokens.Type.rowSubtitle,
                color = Tokens.Palette.accent(dark)
            )
        }
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
