# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    <methods>;
}
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp
-dontwarn okio.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Kotlin
-keepattributes *Annotation*
-keepclassmembers enum * {
    public static **[] $VALUES;
    public static *** values();
    public static *** valueOf(java.lang.String);
}
-dontwarn kotlin.**

# Kotlinx Serialization
-keepattributes *SerialName*
-keepattributes *Serialization*
-dontwarn kotlinx.serialization.**

# Room
-keep class * implements androidx.room.RoomDatabase

# Signal Protocol
-keep class org.signal.** { *; }
-dontwarn org.signal.**

# Firebase
-keepattributes *Firebase*
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }

# ProGuard configuration for Sodium
-keep class com.scottyab.** { *; }
-dontwarn com.scottyab.**

# JNA - its native code (Native.initIDs) looks up field/method IDs on these
# classes by exact name via JNI, so R8 renaming/removing them crashes with
# "Can't obtain peer field ID for class com.sun.jna.Pointer" at runtime.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# lazysodium (libsodium JNA bindings used for E2EE) - binds native functions
# reflectively via JNA proxies, so its interfaces/structures must survive
# unobfuscated too.
-keep class com.goterl.lazysodium.** { *; }
-keepclassmembers class com.goterl.lazysodium.** { *; }
-dontwarn com.goterl.lazysodium.**

# Timber
-keep class timber.log.Timber

# Coroutines
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @kotlinx.coroutines.** *;
}
-dontwarn kotlinx.coroutines.internal.**

# R8/ProGuard for Compose
-keep class * extends androidx.compose.ui.component.**
-keep class * implements androidx.compose.**
-keepclassmembers class * {
    @org.jetbrains.annotations.* *;
}

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes
-keepclassmembers class ** {
    @androidx.room.Entity <fields>;
}
-keep @kotlinx.serialization.Serializable class **
-keep class **$$Serializer { *; }

# Keep build config
-keep class com.messenger.app.BuildConfig { *; }

# Minify optimization
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 7
-allowaccessmodification
-verbose

# Keep enum values
-keepclassmembers enum * {
    *;
}

# Keep Parcelable
-keep class ** implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Biometric
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# Keep WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**