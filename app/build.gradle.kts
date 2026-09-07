plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * The version comes from the release tag, so the tag, the APK and the published
 * release can never disagree. Locally (no RELEASE_TAG) it builds as a dev version.
 */
val appVersionName: String =
    (System.getenv("RELEASE_TAG")?.takeIf { it.isNotBlank() } ?: "v0.1.0-dev")
        .removePrefix("v")

/**
 * Android decides what counts as an update purely from versionCode, and it must
 * increase every release or the new APK will not install over the old one. Deriving
 * it from the version number keeps it monotonic without anyone remembering to bump it.
 *
 * Minor and patch are assumed to stay below 100, which 1.2.3 -> 10203 relies on.
 */
val appVersionCode: Int = run {
    val parts = appVersionName.substringBefore('-').split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    (major * 10_000) + (minor * 100) + patch
}

android {
    namespace = "xyz.photocleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.photocleaner"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // No test-orchestrator / no analytics dependencies are pulled in on purpose.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Keep the debug build as close to release as possible so the WebView
            // session behaves identically during testing.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
