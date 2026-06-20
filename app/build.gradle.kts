import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Firebase is OPTIONAL / bring-your-own-project: the Google Services plugin is only
    // applied when a google-services.json is present. Without it the app builds and runs
    // fully local (no sync) — a rider who doesn't want multi-device sync just omits the
    // file. To enable sync, drop your own Firebase project's google-services.json in app/.
    alias(libs.plugins.google.services) apply false
}

if (project.file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    // Required by the Crashlytics SDK — it injects a build ID resource at build time, and
    // without it FirebaseInitProvider throws "Crashlytics build ID is missing" on EVERY launch.
    apply(plugin = "com.google.firebase.crashlytics")
}

// Release signing is loaded from a gitignored signing.properties at the repo root (see
// that file). It points at the stable release keystore — the SAME key every public APK
// is signed with, so updates install in place. If the file is absent (e.g. a contributor
// just building locally), the release build falls back to the debug keystore.
val signingProps = Properties()
rootProject.file("signing.properties").let { f ->
    if (f.exists()) f.inputStream().use { signingProps.load(it) }
}
val hasReleaseSigning = signingProps.getProperty("storeFile")?.let { File(it).exists() } == true

android {
    namespace = "com.example.northstar"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.northstar.app"
        // API 29 (Android 10) is the real floor: the dash link is built on WifiNetworkSpecifier
        // + ConnectivityManager.requestNetwork(specifier), both added in API 29, and that IS the
        // app. Below 29 the core feature can't run and the unguarded calls would crash, so we
        // don't pretend to support it. Also makes the API-26 notification paths always safe.
        minSdk = 29
        targetSdk = 36
        // Marketing version stays 1.3 (shown as 1.3.0 in-app); versionCode just increments so
        // updates install in place — it isn't user-facing.
        versionCode = 12
        versionName = "1.3.0"

        // Test-channel telemetry: ride/connection diagnostics upload to Firestore so logs can be
        // pulled remotely (no adb) while iterating on test builds. MUST be flipped to "false" for
        // any real public release — see DiagnosticsUploader. Gated additionally on a configured
        // Firebase project, so it's a no-op on bring-your-own-keys builds without google-services.json.
        buildConfigField("boolean", "DIAG_UPLOAD", "true")

        // Ship arm64-v8a only: every modern OEM phone (OnePlus/Samsung/Xiaomi/Nothing…) is
        // 64-bit ARM; the other ABIs (armeabi-v7a/x86/x86_64) are old 32-bit or emulators. This
        // cuts the universal APK from ~107 MB to well under GitHub's 100 MB file limit so the
        // test build can live in the repo for direct download.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Hobby/open-source distribution: signed by the stable release keystore (from
            // signing.properties) so every published APK shares one signature and updates
            // install in place. Falls back to the debug keystore when signing.properties is
            // absent (contributors building locally) — that build just can't update users.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
            // Public release builds never upload diagnostics, and the test-build installer
            // (gated on BuildConfig.DEBUG) is absent — release stays clean automatically.
            buildConfigField("boolean", "DIAG_UPLOAD", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Generates BuildConfig.DEBUG — used to gate sensitive logging (GPS coordinates,
        // shared-location text/URLs, the signed-in uid, raw dash packets) out of release
        // builds. See util/Dbg.kt.
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // Firebase product suite (all gated at runtime behind FirebaseGate — no google-services.json
    // ⇒ default FirebaseApp never initializes ⇒ these are dormant and the app runs fully local).
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    // NDK crash capture — JVM CrashGuard can't see native SIGSEGVs (MapLibre/MediaCodec); this
    // routes them to the Crashlytics console so a native crash is no longer completely untraced.
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.config)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.appcheck)
    implementation(libs.firebase.appcheck.playintegrity)
    // Debug-only App Check provider (loaded via reflection in FirebaseFeatures) so the
    // release classpath stays on Play Integrity only.
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.maplibre)
    implementation(libs.maplibre.annotation)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}