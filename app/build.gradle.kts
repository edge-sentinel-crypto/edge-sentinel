import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release signing credentials are loaded from an untracked keystore.properties
// file or environment variables. Nothing is hardcoded. If no credentials are
// present the release signing config is left unconfigured and release builds
// fall back to unsigned output rather than embedding a burned key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }
val releaseStoreFilePath = signingValue("storeFile", "EDGE_SENTINEL_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "EDGE_SENTINEL_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "EDGE_SENTINEL_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "EDGE_SENTINEL_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFilePath != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null &&
    file(releaseStoreFilePath!!).exists()

android {
    namespace = "com.bp22intel.edgesentinel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bp22intel.edgesentinel"
        minSdk = 26
        targetSdk = 35
        versionCode = 98
        versionName = "2.0.98"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // NDK: build for ARM and x86 (emulator) targets
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // Native DIAG helper build
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    // CMake configuration for the native DIAG helper library
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Only attach a release signing config when credentials are supplied
            // out-of-tree. Otherwise the release build stays unsigned and must be
            // signed manually with a rotated key before any distribution.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("Edge Sentinel: no release signing credentials found; release build will be UNSIGNED. Provide keystore.properties or EDGE_SENTINEL_* env vars.")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt - Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room - Local Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    ksp(libs.room.compiler)

    // WorkManager - Background Processing
    implementation(libs.workmanager.runtime.ktx)
    implementation(libs.workmanager.hilt)
    ksp(libs.hilt.compiler)

    // DataStore - Preferences
    implementation(libs.datastore.preferences)

    // Splash Screen
    implementation(libs.splashscreen)

    // Glance - App Widgets
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // OpenStreetMap (osmdroid) - Threat Map view
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}
