plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Versioning -----------------------------------------------------------
// CI passes VERSION_CODE (github.run_number) so every build on main is higher
// than the last; locally it falls back to 1 / 0.1-dev.
val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val appVersionName = System.getenv("VERSION_NAME") ?: "0.1-dev"

// Repo used for in-app auto-update checks (override with -PupdateRepo=owner/name).
val updateRepo = (project.findProperty("updateRepo") as String?) ?: "BuninSil/pizda"

android {
    namespace = "com.phonemcp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonemcp"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        // Relay config baked in at build time. The phone opens an outbound WSS to
        // this Worker and authenticates with RELAY_SECRET (the same value you set
        // on the Worker via `wrangler secret put UPDATE_SECRET`). Never commit the
        // secret: CI passes it as the RELAY_SECRET env var (a GitHub Secret);
        // local builds fall back to empty and you set the Worker URL in the app.
        val relayWorker = listOf(
            project.findProperty("relayWorker") as String?,
            System.getenv("RELAY_WORKER"),
        ).firstOrNull { !it.isNullOrBlank() } ?: "https://raspy-dew-5339.vladislav20050705.workers.dev"
        val relaySecret = System.getenv("RELAY_SECRET") ?: ""
        buildConfigField("String", "RELAY_WORKER", "\"$relayWorker\"")
        buildConfigField("String", "RELAY_SECRET", "\"$relaySecret\"")

        // tesseract4android ships native libs for several ABIs; keep only arm64
        // (every modern phone) to shrink the APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // --- Signing ----------------------------------------------------------
    // One stable identity signs EVERY build (debug and release, local and CI), so
    // each new APK installs over the previous one without uninstalling.
    //  - If GitHub Secrets provide a keystore (KEYSTORE_FILE + passwords), use it.
    //  - Otherwise use the shared keystore committed at keystore/debug-shared.jks
    //    (well-known password "android"). It's an intentionally public debug key,
    //    fine for a personal tool; swap in a real release key later via Secrets.
    signingConfigs {
        create("shared") {
            val envKs = System.getenv("KEYSTORE_FILE")
            if (envKs != null && file(envKs).exists()) {
                storeFile = file(envKs)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/debug-shared.jks")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // OkHttp WebSocket: the phone's outbound WSS connection to the relay Worker
    // (plain 443, so providers/VPNs that kill cloudflared's :7844 don't matter).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Offline on-device OCR fallback (rus+eng) for screens with few accessibility
    // texts. Tesseract 4/5 (LSTM) via Tesseract4Android, matching tessdata_fast
    // models in assets. Published on JitPack. Package: com.googlecode.tesseract.android.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.7.0")
    // org.json ships with the Android platform, so no dependency needed for JSON.
}
