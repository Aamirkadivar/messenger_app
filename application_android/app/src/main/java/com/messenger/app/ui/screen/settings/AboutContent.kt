package com.messenger.app.ui.screen.settings

/**
 * Static About content.
 *
 * The URLs below are intentionally blank: this project has no published legal
 * pages or support address yet. Rows whose link is blank report that the
 * destination isn't available rather than opening a fabricated URL - fill these
 * in and the rows start working with no other change.
 */
object AboutLinks {
    const val TERMS_OF_SERVICE = ""
    const val PRIVACY_POLICY = ""
    const val SUPPORT_EMAIL = ""
}

data class ChangelogEntry(val version: String, val changes: List<String>)

/** Release notes shown by "What's new". */
val Changelog = listOf(
    ChangelogEntry(
        version = "1.0",
        changes = listOf(
            "End-to-end encryption for direct messages",
            "Offline message and chat-list caching",
            "Read receipts and live presence",
            "Refreshed light and dark appearance"
        )
    )
)

data class OssLicense(val library: String, val license: String)

/** Third-party libraries actually bundled in the app. */
val OpenSourceLicenses = listOf(
    OssLicense("Jetpack Compose", "Apache License 2.0"),
    OssLicense("AndroidX (Core, Lifecycle, Navigation, Room, DataStore)", "Apache License 2.0"),
    OssLicense("Dagger Hilt", "Apache License 2.0"),
    OssLicense("Retrofit", "Apache License 2.0"),
    OssLicense("OkHttp", "Apache License 2.0"),
    OssLicense("kotlinx.serialization", "Apache License 2.0"),
    OssLicense("kotlinx.coroutines", "Apache License 2.0"),
    OssLicense("Java-WebSocket", "MIT License"),
    OssLicense("LazySodium / libsodium", "ISC License"),
    OssLicense("JNA", "Apache License 2.0 / LGPL 2.1"),
    OssLicense("Coil", "Apache License 2.0"),
    OssLicense("Glide", "BSD, MIT, Apache License 2.0"),
    OssLicense("Lottie", "Apache License 2.0"),
    OssLicense("Timber", "Apache License 2.0"),
    OssLicense("Firebase Cloud Messaging", "Apache License 2.0")
)
