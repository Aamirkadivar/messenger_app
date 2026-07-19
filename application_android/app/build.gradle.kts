plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.firebase.perf)
    alias(libs.plugins.secrets.gradle)
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

        // Secrets
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${secrets.get("API_BASE_URL").get()}\""
        )
        buildConfigField(
            "String",
            "FIREBASE_SERVER_KEY",
            "\"${secrets.get("FIREBASE_SERVER_KEY").get()}\""
        )
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            firebaseCrashlytics {
                nativeSymbolUploadEnabled = true
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

    // Activity Compose
    implementation(libs.androidx.activity.activity.compose)

    // Compose BOM
    platform(libs.androidx.compose.bom)
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

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

    // Ktor Client (WebSocket)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.paging)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.bundles.firebase)

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

    // Encryption (Sodium)
    implementation(libs.sodium.android)

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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.mockk)
}

secrets {
    defaultPropertiesFileName = "secrets.defaults.properties"
}
