package com.messenger.app.data.settings

import java.time.LocalTime

/**
 * Persisted user settings.
 *
 * Enum constants carry an explicit stable [id] used as the on-disk value.
 * Ordinals are never persisted - reordering or inserting a constant would
 * silently reinterpret every stored preference.
 */

enum class PreviewPrivacy(val id: String, val label: String, val description: String) {
    NAME_AND_MESSAGE(
        "name_and_message",
        "Name and message",
        "Show who sent it and what they said"
    ),
    NAME_ONLY(
        "name_only",
        "Name only",
        "Show who sent it, hide the content"
    ),
    HIDDEN(
        "hidden",
        "Hidden",
        "Show only that a message arrived"
    );

    companion object {
        val DEFAULT = NAME_AND_MESSAGE
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class AutoDeleteWindow(val id: String, val label: String, val days: Int?) {
    OFF("off", "Never", null),
    DAYS_7("7d", "After 7 days", 7),
    DAYS_30("30d", "After 30 days", 30),
    DAYS_90("90d", "After 90 days", 90);

    companion object {
        val DEFAULT = OFF
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Network conditions that auto-download rules are configured against. */
enum class NetworkKind(val id: String, val label: String) {
    WIFI("wifi", "Wi-Fi"),
    MOBILE("mobile", "Mobile data"),
    ROAMING("roaming", "Roaming")
}

/** Media categories, used by both auto-download rules and the storage breakdown. */
enum class MediaKind(val id: String, val label: String) {
    PHOTOS("photos", "Photos"),
    VIDEOS("videos", "Videos"),
    VOICE("voice", "Voice messages"),
    DOCUMENTS("documents", "Documents")
}

/** Notification categories that can be toggled independently. */
enum class NotificationChannelKind(val id: String, val label: String, val description: String) {
    DIRECT_MESSAGES("direct", "Direct messages", "One-to-one conversations"),
    GROUP_MESSAGES("group", "Group messages", "All group conversations"),
    MENTIONS_REPLIES("mentions", "Mentions and replies", "When someone addresses you directly"),
    REACTIONS("reactions", "Reactions", "When someone reacts to your message"),
    CALLS("calls", "Calls", "Incoming voice and video calls")
}

data class NotificationSettings(
    /** Master switch. When false every other notification control is inert. */
    val enabled: Boolean = true,
    val perChannel: Map<NotificationChannelKind, Boolean> =
        NotificationChannelKind.entries.associateWith { true },
    val soundId: String = NotificationSound.DEFAULT.id,
    val vibrate: Boolean = true,
    val inAppSounds: Boolean = true,
    val previewPrivacy: PreviewPrivacy = PreviewPrivacy.DEFAULT,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: LocalTime = LocalTime.of(22, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(7, 0)
) {
    fun isChannelOn(kind: NotificationChannelKind): Boolean =
        enabled && (perChannel[kind] ?: true)
}

/** Selectable notification tones. Bundled names only - no external assets. */
enum class NotificationSound(val id: String, val label: String) {
    DEFAULT("default", "Default"),
    CHIME("chime", "Chime"),
    PULSE("pulse", "Pulse"),
    ASCEND("ascend", "Ascend"),
    NONE("none", "Silent");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

data class StorageSettings(
    /**
     * Which media types download automatically, per network condition.
     * Defaults mirror common expectations: everything on Wi-Fi, photos only on
     * mobile data, nothing while roaming.
     */
    val autoDownload: Map<NetworkKind, Set<MediaKind>> = mapOf(
        NetworkKind.WIFI to MediaKind.entries.toSet(),
        NetworkKind.MOBILE to setOf(MediaKind.PHOTOS),
        NetworkKind.ROAMING to emptySet()
    ),
    val autoDeleteWindow: AutoDeleteWindow = AutoDeleteWindow.DEFAULT,
    /** Lower-quality uploads and no media autoplay. */
    val dataSaver: Boolean = false
) {
    fun autoDownloads(network: NetworkKind, media: MediaKind): Boolean =
        autoDownload[network]?.contains(media) == true
}

data class AppSettings(
    val notifications: NotificationSettings = NotificationSettings(),
    val storage: StorageSettings = StorageSettings()
)
