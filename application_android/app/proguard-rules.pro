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
    public **;
}
-dontwarn kotlin.**

# Kotlinx Serialization
-keepattributes *SerialName*
-keepattributes *Serialization*
-keep class * @kotlinx.serialization.*
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

# Timber
-keep class timber.log.Timber

# Coroutines
-keepclassmembers,allowshrinking,allowobfuscation * {
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