import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services)
    alias(libs.plugins.secrets.gradle)
}

// Release signing credentials, kept out of git (see keystore.properties.example
// and .gitignore). Release builds are unsigned - which Android won't install -
// if this file is missing, so any dev producing a release build needs their
// own keystore.properties pointing at their own keystore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

android {
    namespace = "com.messenger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.messenger.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API_BASE_URL and FIREBASE_SERVER_KEY are read from local.properties
        // (or secrets.defaults.properties as a fallback) and exposed on
        // BuildConfig automatically by the secrets-gradle-plugin.
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // lintVital's pinned lint-gradle version 404s against Google's Maven
        // in this environment (unrelated to app code); it's a pre-flight
        // check, not something that affects the built/signed APK.
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // Activity Compose
    implementation(libs.androidx.activity.activity.compose)

    // NOTE: the Compose BOM is deliberately not applied. Enabling it aligns
    // Compose to 1.7.6, which cannot be resolved from the Maven mirrors
    // available here, so the build fails. Versions come from libs.versions.toml
    // instead. Re-enable with implementation(platform(libs.androidx.compose.bom))
    // once 1.7.6 artifacts are reachable, and add the matching
    // androidTestImplementation(platform(...)) at the same time - applying it to
    // only one classpath makes the two disagree.
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Retrofit
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)

    // OkHttp
    implementation(libs.okhttp.core)
    implementation(libs.bundles.logging)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // WebSocket client (used directly by WebSocketManager)
    implementation(libs.java.websocket)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.paging)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Firebase (messaging only - no real project configured, so Crashlytics/
    // Performance Monitoring are omitted since they crash on an invalid API key)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Coil (Image Loading)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Swipe to Refresh
    implementation(libs.androidx.swiperefreshlayout)

    // Work Manager
    implementation(libs.androidx.work.runtime)

    // Biometric
    implementation(libs.androidx.biometric)

    // Security Crypto
    implementation(libs.androidx.security.crypto)

    // libsodium (NaCl crypto_box) for real E2EE. lazysodium pulls JNA as a jar
    // transitively; exclude it and use only the Android aar variant to avoid
    // duplicate-class conflicts.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) { artifact { type = "aar" } }

    // Timber
    implementation(libs.timber)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Lottie (Animations)
    implementation(libs.lottie.compose)

    // Shimmer
    implementation(libs.shimmer)

    // Glide
    implementation(libs.glide)

    // CardView & RecyclerView
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)

    // AppCompat & ConstraintLayout
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // Testing
    testImplementation(libs.junit.v4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // No BOM here either - see the note in the Compose block above. Applying it
    // to only the test classpath makes it demand versions the app doesn't use.
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.mockk)
}

secrets {
    defaultPropertiesFileName = "secrets.defaults.properties"
}
