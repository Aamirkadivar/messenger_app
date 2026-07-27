package com.messenger.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalTime

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings"
)

/**
 * Reads and writes user settings.
 *
 * Every mutation is applied the moment it happens - there is no "Save" step -
 * so each setter is a single [DataStore.edit] that both persists the value and
 * pushes it through [settings] to whoever is observing.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFICATION_SOUND = stringPreferencesKey("notification_sound")
        val VIBRATE = booleanPreferencesKey("notification_vibrate")
        val IN_APP_SOUNDS = booleanPreferencesKey("in_app_sounds")
        val PREVIEW_PRIVACY = stringPreferencesKey("preview_privacy")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start_minute")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end_minute")

        /** Set of NotificationChannelKind ids that are switched OFF. */
        val CHANNELS_OFF = stringSetPreferencesKey("notification_channels_off")

        val AUTO_DELETE_WINDOW = stringPreferencesKey("auto_delete_window")
        val DATA_SAVER = booleanPreferencesKey("data_saver")

        /** One key per network, holding the set of enabled MediaKind ids. */
        fun autoDownload(network: NetworkKind) =
            stringSetPreferencesKey("auto_download_${network.id}")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e ->
            // A corrupt or unreadable store must not take the screen down;
            // fall back to defaults so Settings still opens.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> prefs.toAppSettings() }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()

        val channelsOff = this[Keys.CHANNELS_OFF].orEmpty()
        val perChannel = NotificationChannelKind.entries.associateWith { kind ->
            kind.id !in channelsOff
        }

        val autoDownload = NetworkKind.entries.associateWith { network ->
            val stored = this[Keys.autoDownload(network)]
            if (stored == null) {
                // Never configured - keep the shipped default for this network.
                defaults.storage.autoDownload[network].orEmpty()
            } else {
                stored.mapNotNull { id -> MediaKind.entries.firstOrNull { it.id == id } }.toSet()
            }
        }

        return AppSettings(
            notifications = NotificationSettings(
                enabled = this[Keys.NOTIFICATIONS_ENABLED] ?: defaults.notifications.enabled,
                perChannel = perChannel,
                soundId = NotificationSound.fromId(this[Keys.NOTIFICATION_SOUND]).id,
                vibrate = this[Keys.VIBRATE] ?: defaults.notifications.vibrate,
                inAppSounds = this[Keys.IN_APP_SOUNDS] ?: defaults.notifications.inAppSounds,
                previewPrivacy = PreviewPrivacy.fromId(this[Keys.PREVIEW_PRIVACY]),
                quietHoursEnabled = this[Keys.QUIET_HOURS_ENABLED]
                    ?: defaults.notifications.quietHoursEnabled,
                quietHoursStart = this[Keys.QUIET_HOURS_START]?.toLocalTime()
                    ?: defaults.notifications.quietHoursStart,
                quietHoursEnd = this[Keys.QUIET_HOURS_END]?.toLocalTime()
                    ?: defaults.notifications.quietHoursEnd
            ),
            storage = StorageSettings(
                autoDownload = autoDownload,
                autoDeleteWindow = AutoDeleteWindow.fromId(this[Keys.AUTO_DELETE_WINDOW]),
                dataSaver = this[Keys.DATA_SAVER] ?: defaults.storage.dataSaver
            )
        )
    }

    // ==================== Notifications ====================

    suspend fun setNotificationsEnabled(enabled: Boolean) = edit {
        it[Keys.NOTIFICATIONS_ENABLED] = enabled
    }

    suspend fun setChannelEnabled(kind: NotificationChannelKind, enabled: Boolean) = edit { prefs ->
        val off = prefs[Keys.CHANNELS_OFF].orEmpty().toMutableSet()
        if (enabled) off.remove(kind.id) else off.add(kind.id)
        prefs[Keys.CHANNELS_OFF] = off
    }

    suspend fun setNotificationSound(sound: NotificationSound) = edit {
        it[Keys.NOTIFICATION_SOUND] = sound.id
    }

    suspend fun setVibrate(enabled: Boolean) = edit { it[Keys.VIBRATE] = enabled }

    suspend fun setInAppSounds(enabled: Boolean) = edit { it[Keys.IN_APP_SOUNDS] = enabled }

    suspend fun setPreviewPrivacy(privacy: PreviewPrivacy) = edit {
        it[Keys.PREVIEW_PRIVACY] = privacy.id
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) = edit {
        it[Keys.QUIET_HOURS_ENABLED] = enabled
    }

    suspend fun setQuietHours(start: LocalTime, end: LocalTime) = edit {
        it[Keys.QUIET_HOURS_START] = start.toMinuteOfDay()
        it[Keys.QUIET_HOURS_END] = end.toMinuteOfDay()
    }

    // ==================== Storage & data ====================

    suspend fun setAutoDownload(network: NetworkKind, media: MediaKind, enabled: Boolean) =
        edit { prefs ->
            val key = Keys.autoDownload(network)
            val current = prefs[key]
                ?: StorageSettings().autoDownload[network].orEmpty().map { it.id }.toSet()
            val updated = current.toMutableSet()
            if (enabled) updated.add(media.id) else updated.remove(media.id)
            prefs[key] = updated
        }

    suspend fun setAutoDeleteWindow(window: AutoDeleteWindow) = edit {
        it[Keys.AUTO_DELETE_WINDOW] = window.id
    }

    suspend fun setDataSaver(enabled: Boolean) = edit { it[Keys.DATA_SAVER] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}

private fun Int.toLocalTime(): LocalTime =
    LocalTime.of((this / 60).coerceIn(0, 23), (this % 60).coerceIn(0, 59))

private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
