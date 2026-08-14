package com.messenger.app.ui.viewmodel

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messenger.app.BuildConfig
import android.net.Uri
import com.messenger.app.data.model.TotpConfirmRequest
import com.messenger.app.data.model.TotpDisableRequest
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.repository.AvatarRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.E2EEVaultRepository
import com.messenger.app.data.settings.AppSettings
import com.messenger.app.data.settings.AutoDeleteWindow
import com.messenger.app.data.settings.MediaKind
import com.messenger.app.data.settings.NetworkKind
import com.messenger.app.data.settings.NotificationChannelKind
import com.messenger.app.data.settings.NotificationSound
import com.messenger.app.data.settings.PreviewPrivacy
import com.messenger.app.data.settings.SettingsRepository
import com.messenger.app.data.storage.StorageAnalyzer
import com.messenger.app.data.storage.StorageScanState
import com.messenger.app.data.storage.formatBytes
import com.messenger.app.security.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject

/** A conversation the user has muted. */
data class MutedChatUi(val chatId: String, val name: String)

/** Someone this account has blocked (Settings → Privacy). */
data class BlockedUserUi(
    val id: String,
    val name: String,
    val username: String = "",
    val avatarUrl: String? = null
)

/** Registered E2EE client device (Settings → Privacy → Devices). */
data class E2EEDeviceUi(
    val deviceId: String,
    val name: String,
    val platform: String,
    val revoked: Boolean = false
)

/** The signed-in user, shown in the Settings profile row. */
data class ProfileUi(
    val name: String = "",
    val email: String = "",
    val avatarUrl: String? = null
)

/** Read-only build and device facts shown in About. */
data class AboutInfo(
    val appName: String = "Messenger",
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: String = BuildConfig.VERSION_CODE.toString(),
    val androidRelease: String = Build.VERSION.RELEASE ?: "unknown",
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val locale: String = Locale.getDefault().toLanguageTag()
) {
    /** Single block of plain text for the "Copy diagnostics" action. */
    fun toDiagnosticsText(): String = buildString {
        appendLine("$appName $versionName (build $versionCode)")
        appendLine("Android $androidRelease (API $sdkInt)")
        appendLine("Device: $device")
        appendLine("Locale: $locale")
    }.trimEnd()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val storageAnalyzer: StorageAnalyzer,
    private val chatRepository: ChatRepository,
    private val avatarRepository: AvatarRepository,
    private val chatApiService: ChatApiService,
    private val tokenManager: TokenManager,
    private val e2eeVaultRepository: E2EEVaultRepository
) : ViewModel() {

    /**
     * Persisted settings. Every write goes straight to DataStore and comes
     * back through this flow, so the UI never holds unsaved state and there
     * is nothing for a "Save" button to do.
     */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * Storage scan state. Owned by the application-scoped analyzer, so leaving
     * and re-entering Settings shows the in-progress or finished scan rather
     * than restarting it.
     */
    val storageState: StateFlow<StorageScanState> = storageAnalyzer.state

    /**
     * Display names for chats, read once from the cache. The conversations
     * table only stores a placeholder row per chat, so names come from the
     * cached chat-list snapshot instead.
     */
    private val chatNames: Flow<Map<String, String>> = flow {
        emit(
            runCatching {
                chatRepository.loadCachedChats().associate { dto ->
                    dto.id to (dto.otherUser?.displayName?.takeIf { it.isNotBlank() }
                        ?: dto.otherUser?.username
                        ?: dto.name.takeIf { it.isNotBlank() }
                        ?: "Unknown")
                }
            }.getOrDefault(emptyMap())
        )
    }

    /** Muted conversations, named from the cached chat list where available. */
    val mutedChats: StateFlow<List<MutedChatUi>> =
        chatRepository.getConversationsFlow()
            .combine(chatNames) { conversations, names ->
                conversations
                    .filter { it.isMuted }
                    .map { MutedChatUi(it.id, names[it.id] ?: it.name ?: "Unknown") }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val aboutInfo = AboutInfo()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** The signed-in user, for the profile row (name + picture). */
    private val _profile = MutableStateFlow(ProfileUi())
    val profile: StateFlow<ProfileUi> = _profile.asStateFlow()

    private val _uploadingAvatar = MutableStateFlow(false)
    val uploadingAvatar: StateFlow<Boolean> = _uploadingAvatar.asStateFlow()

    private fun loadProfile() {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            runCatching { chatApiService.getCurrentUser("Bearer $token") }
                .getOrNull()
                ?.takeIf { it.isSuccessful }
                ?.body()
                ?.user
                ?.let { me ->
                    _profile.value = ProfileUi(
                        name = me.displayName?.takeIf { it.isNotBlank() } ?: me.username,
                        email = me.email,
                        avatarUrl = me.avatarUrl
                    )
                }
        }
    }

    /** Uploads a new profile picture from a picked image. */
    fun setProfilePhoto(uri: Uri) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _uploadingAvatar.value = true
            avatarRepository.uploadMyAvatar(token, uri)
                .onSuccess { url ->
                    _uploadingAvatar.value = false
                    _profile.update { it.copy(avatarUrl = url) }
                    _toast.value = "Profile picture updated"
                }
                .onFailure { e ->
                    _uploadingAvatar.value = false
                    _toast.value = e.message ?: "Failed to update picture"
                }
        }
    }

    private val _blockedUsers = MutableStateFlow<List<BlockedUserUi>>(emptyList())
    val blockedUsers: StateFlow<List<BlockedUserUi>> = _blockedUsers.asStateFlow()

    private val _blockedUsersLoading = MutableStateFlow(false)
    val blockedUsersLoading: StateFlow<Boolean> = _blockedUsersLoading.asStateFlow()

    private val _e2eeDevices = MutableStateFlow<List<E2EEDeviceUi>>(emptyList())
    val e2eeDevices: StateFlow<List<E2EEDeviceUi>> = _e2eeDevices.asStateFlow()

    private val _e2eeDevicesLoading = MutableStateFlow(false)
    val e2eeDevicesLoading: StateFlow<Boolean> = _e2eeDevicesLoading.asStateFlow()

    private val _currentDeviceId = MutableStateFlow<String?>(null)
    val currentDeviceId: StateFlow<String?> = _currentDeviceId.asStateFlow()

    init {
        // Kick off the (slow) scan as soon as Settings is first constructed.
        storageAnalyzer.scan()
        loadProfile()
        loadBlockedUsers()
        loadE2EEDevices()
        loadTotpStatus()
    }

    fun consumeToast() { _toast.value = null }

    /** Surfaces a transient message in the screen's snackbar. */
    fun showMessage(message: String) { _toast.value = message }

    fun loadBlockedUsers() {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _blockedUsersLoading.value = true
            chatRepository.listBlockedUsers(token)
                .onSuccess { list ->
                    _blockedUsers.value = list.map { dto ->
                        BlockedUserUi(
                            id = dto.id,
                            name = dto.displayName.ifBlank { dto.username }.ifBlank { "Unknown" },
                            username = dto.username,
                            avatarUrl = dto.avatarUrl
                        )
                    }
                }
                .onFailure { e ->
                    _toast.value = e.message ?: "Failed to load blocked users"
                }
            _blockedUsersLoading.value = false
        }
    }

    fun loadE2EEDevices() {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _e2eeDevicesLoading.value = true
            _currentDeviceId.value = tokenManager.getOrCreateDeviceId().getOrNull()
            e2eeVaultRepository.listDevices(token)
                .onSuccess { list ->
                    _e2eeDevices.value = list.map { d ->
                        E2EEDeviceUi(
                            deviceId = d.deviceId,
                            name = d.name.ifBlank { d.deviceId },
                            platform = d.platform,
                            revoked = !d.revokedAt.isNullOrBlank()
                        )
                    }
                }
                .onFailure { e ->
                    _toast.value = e.message ?: "Failed to load devices"
                }
            _e2eeDevicesLoading.value = false
        }
    }

    fun revokeE2EEDevice(device: E2EEDeviceUi) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            e2eeVaultRepository.revokeDevice(token, device.deviceId)
                .onSuccess {
                    _e2eeDevices.update { list ->
                        list.map {
                            if (it.deviceId == device.deviceId) it.copy(revoked = true) else it
                        }
                    }
                    _toast.value = "Device revoked"
                }
                .onFailure { e ->
                    _toast.value = e.message ?: "Failed to revoke device"
                }
        }
    }

    private val _changingPassword = MutableStateFlow(false)
    val changingPassword: StateFlow<Boolean> = _changingPassword.asStateFlow()

    private val _passwordChangeSucceeded = MutableStateFlow(false)
    val passwordChangeSucceeded: StateFlow<Boolean> = _passwordChangeSucceeded.asStateFlow()

    fun consumePasswordChangeSucceeded() {
        _passwordChangeSucceeded.value = false
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _changingPassword.value = true
            _passwordChangeSucceeded.value = false
            e2eeVaultRepository.changeAccountPassword(token, currentPassword, newPassword)
                .onSuccess {
                    _passwordChangeSucceeded.value = true
                    _toast.value = "Password updated"
                }
                .onFailure { e ->
                    _toast.value = e.message ?: "Failed to change password"
                }
            _changingPassword.value = false
        }
    }

    private val _totpEnabled = MutableStateFlow(false)
    val totpEnabled: StateFlow<Boolean> = _totpEnabled.asStateFlow()
    private val _totpSecret = MutableStateFlow<String?>(null)
    val totpSecret: StateFlow<String?> = _totpSecret.asStateFlow()
    private val _totpBackupCodes = MutableStateFlow<List<String>>(emptyList())
    val totpBackupCodes: StateFlow<List<String>> = _totpBackupCodes.asStateFlow()
    private val _totpBusy = MutableStateFlow(false)
    val totpBusy: StateFlow<Boolean> = _totpBusy.asStateFlow()

    fun loadTotpStatus() {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            chatApiService.totpStatus("Bearer $token").body()?.let {
                _totpEnabled.value = it.totpEnabled
            }
        }
    }

    fun startTotpSetup() {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _totpBusy.value = true
            val r = runCatching { chatApiService.totpSetup("Bearer $token") }.getOrNull()
            val body = r?.body()
            if (r?.isSuccessful == true && body != null) {
                _totpSecret.value = body.secret
            } else {
                _toast.value = "Could not start authenticator setup"
            }
            _totpBusy.value = false
        }
    }

    fun confirmTotp(code: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _totpBusy.value = true
            val r = runCatching {
                chatApiService.totpConfirm("Bearer $token", TotpConfirmRequest(code.trim()))
            }.getOrNull()
            if (r?.isSuccessful == true && r.body()?.totpEnabled == true) {
                _totpEnabled.value = true
                _totpSecret.value = null
                _totpBackupCodes.value = r.body()?.backupCodes.orEmpty()
                _toast.value = if (_totpBackupCodes.value.isEmpty())
                    "Authenticator enabled"
                else
                    "Save these backup codes — they are shown once"
            } else {
                _toast.value = "Invalid code"
            }
            _totpBusy.value = false
        }
    }

    fun disableTotp(password: String, code: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _totpBusy.value = true
            val r = runCatching {
                chatApiService.totpDisable("Bearer $token", TotpDisableRequest(password, code.trim()))
            }.getOrNull()
            if (r?.isSuccessful == true) {
                _totpEnabled.value = false
                _toast.value = "Authenticator disabled"
            } else {
                _toast.value = "Could not disable (check password and code)"
            }
            _totpBusy.value = false
        }
    }

    fun regenerateTotpBackupCodes(password: String, code: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            _totpBusy.value = true
            val r = runCatching {
                chatApiService.totpRegenerateBackupCodes(
                    "Bearer $token",
                    TotpDisableRequest(password, code.trim())
                )
            }.getOrNull()
            if (r?.isSuccessful == true) {
                _totpBackupCodes.value = r.body()?.backupCodes.orEmpty()
                _toast.value = "Save these backup codes — they are shown once"
            } else {
                _toast.value = "Could not regenerate (check password and code)"
            }
            _totpBusy.value = false
        }
    }

    fun clearTotpSetup() {
        _totpSecret.value = null
        _totpBackupCodes.value = emptyList()
    }

    fun approveDevicePairing(pairingCode: String) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            e2eeVaultRepository.approveDevicePairing(token, pairingCode.trim())
                .onSuccess { _toast.value = "Device linked" }
                .onFailure { e -> _toast.value = e.message ?: "Failed to link device" }
        }
    }

    fun unblockUser(user: BlockedUserUi) {
        viewModelScope.launch {
            val token = tokenManager.getAccessToken().getOrNull() ?: return@launch
            chatRepository.unblockUser(token, user.id)
                .onSuccess {
                    _blockedUsers.update { list -> list.filterNot { it.id == user.id } }
                    _toast.value = "${user.name} unblocked"
                }
                .onFailure { e ->
                    _toast.value = e.message ?: "Failed to unblock"
                }
        }
    }

    // ==================== Notifications ====================

    fun setNotificationsEnabled(enabled: Boolean) = launchSetting {
        settingsRepository.setNotificationsEnabled(enabled)
    }

    fun setChannelEnabled(kind: NotificationChannelKind, enabled: Boolean) = launchSetting {
        settingsRepository.setChannelEnabled(kind, enabled)
    }

    fun setSound(sound: NotificationSound) = launchSetting {
        settingsRepository.setNotificationSound(sound)
    }

    fun setVibrate(enabled: Boolean) = launchSetting { settingsRepository.setVibrate(enabled) }

    fun setInAppSounds(enabled: Boolean) = launchSetting {
        settingsRepository.setInAppSounds(enabled)
    }

    fun setPreviewPrivacy(privacy: PreviewPrivacy) = launchSetting {
        settingsRepository.setPreviewPrivacy(privacy)
    }

    fun setQuietHoursEnabled(enabled: Boolean) = launchSetting {
        settingsRepository.setQuietHoursEnabled(enabled)
    }

    fun setQuietHours(start: LocalTime, end: LocalTime) = launchSetting {
        settingsRepository.setQuietHours(start, end)
    }

    fun unmuteChat(chatId: String) = launchSetting {
        chatRepository.setChatMuted(chatId, false)
    }

    // ==================== Storage & data ====================

    fun setAutoDownload(network: NetworkKind, media: MediaKind, enabled: Boolean) = launchSetting {
        settingsRepository.setAutoDownload(network, media, enabled)
    }

    fun setAutoDeleteWindow(window: AutoDeleteWindow) = launchSetting {
        settingsRepository.setAutoDeleteWindow(window)
    }

    fun setDataSaver(enabled: Boolean) = launchSetting { settingsRepository.setDataSaver(enabled) }

    fun rescanStorage() = storageAnalyzer.scan(force = true)

    fun clearCache() {
        viewModelScope.launch {
            val freed = storageAnalyzer.clearCache()
            _toast.value = "Freed ${formatBytes(freed)}. No messages were deleted."
        }
    }

    fun clearConversationMedia(chatId: String, chatName: String) {
        viewModelScope.launch {
            val freed = storageAnalyzer.clearConversationMedia(chatId)
            _toast.value = "Freed ${formatBytes(freed)} from $chatName. Messages kept."
        }
    }

    // ==================== About ====================

    fun copyDiagnostics() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText("Messenger diagnostics", aboutInfo.toDiagnosticsText())
        )
        // Android 13+ shows its own copy confirmation; don't double-announce.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _toast.value = "Diagnostics copied to clipboard"
        }
    }

    private inline fun launchSetting(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
