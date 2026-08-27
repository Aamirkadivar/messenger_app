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

    packaging {
        resources {
            // BouncyCastle ships an OSGi manifest in every artifact (bcmls,
            // bcprov, bcutil, bcpkix) and jspecify adds a fifth copy, which
            // collides during resource merge. None of it is needed at runtime.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // JVM unit tests run against a stub android.jar whose methods throw
            // by default. TokenManager logs through android.util.Log, which is
            // incidental to what these tests assert, so let the stubs return
            // defaults rather than failing a test on a log call.
            isReturnDefaultValues = true
        }
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

    // MigrationTestHelper loads the exported schema JSONs from the test APK's assets, so the
    // schema directory has to be on the androidTest asset path. Without this the migration test
    // fails with "Cannot find the schema file in the assets folder".
    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"))
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
    implementation(libs.haze)

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

    // WebRTC (audio calling)
    implementation(libs.webrtc)

    // CameraX (round video message capture)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // Device-pairing QR encode + camera scan
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // Media3 - ExoPlayer for round-video playback. Note: media3-transformer is
    // NOT available offline here, so compression uses MediaCodec/MediaMuxer
    // from the platform SDK instead (see VideoCompressor).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // Work Manager
    implementation(libs.androidx.work.runtime)

    // Biometric
    implementation(libs.androidx.biometric)

    // Security Crypto
    implementation(libs.androidx.security.crypto)

    // libsodium (NaCl crypto_box) for real E2EE. lazysodium pulls JNA as a jar
    // transitively; exclude it and use only the Android aar variant to avoid
    // duplicate-class conflicts.
    // BouncyCastle MLS (RFC 9420) for group messaging. Audited, maintained
    // implementation - TreeKEM is never hand-rolled here.
    implementation(libs.bcmls)
    implementation(libs.bcprov)

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
    // Test-only. Lets the API contract be exercised over a real Retrofit/OkHttp
    // stack instead of a hand-written fake, which is the only way to catch a
    // wrong HTTP verb, path, or header - a fake implements the interface and so
    // cannot disagree with the annotations.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlin.coroutines.test)
    // Room MigrationTestHelper. Test scope only - the production dependency graph is unchanged.
    androidTestImplementation(libs.androidx.room.testing)
    // Test-only. Lets an instrumented test stand in for the two collaborators the
    // reset path never touches (ChatRepository, WebSocketManager) so the REAL
    // E2EEVaultRepository.resetLocked can be executed on-device against real
    // libsodium and a real Android Keystore.
    androidTestImplementation(libs.mockk.android)
}

secrets {
    defaultPropertiesFileName = "secrets.defaults.properties"
}
