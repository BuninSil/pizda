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

        // We only ship the arm64 cloudflared binary (as a native lib). Almost every
        // modern Android phone is arm64-v8a. Add armeabi-v7a here only if you also
        // drop a 32-bit cloudflared into jniLibs/armeabi-v7a/.
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

    packaging {
        jniLibs {
            // CRITICAL: the cloudflared "lib" must be extracted to disk so we can exec it.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// --- Embed cloudflared ----------------------------------------------------
// The cloudflared binary is COMMITTED at src/main/jniLibs/arm64-v8a/libcloudflared.so.
// It is a custom GOOS=android + CGO build (see scripts/build-cloudflared-android.sh)
// that resolves DNS through Android's system resolver. The upstream linux/arm64
// binary does NOT work on Android (no /etc/resolv.conf -> DNS lookups hit
// [::1]:53 and fail), so we do NOT download it. This task just verifies the
// committed binary is present.
val cloudflaredFile = file("src/main/jniLibs/arm64-v8a/libcloudflared.so")

val verifyCloudflared by tasks.registering {
    description = "Verify the committed Android cloudflared binary is present."
    onlyIf { !project.hasProperty("skipCloudflared") }
    doLast {
        if (!cloudflaredFile.exists() || cloudflaredFile.length() < 1_000_000) {
            throw GradleException(
                "Missing Android cloudflared binary: $cloudflaredFile\n" +
                "Rebuild it with scripts/build-cloudflared-android.sh (needs Go + Android NDK), " +
                "or pass -PskipCloudflared to build without the tunnel (Termux fallback)."
            )
        }
        logger.lifecycle("cloudflared (android) present: ${cloudflaredFile.length()} bytes")
    }
}

tasks.named("preBuild") { dependsOn(verifyCloudflared) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // Tiny embedded HTTP server for the MCP Streamable-HTTP endpoint on localhost.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Offline on-device OCR fallback (rus+eng) for screens with few accessibility
    // texts. Tesseract 4/5 (LSTM) via Tesseract4Android, matching tessdata_fast
    // models in assets. Published on JitPack. Package: com.googlecode.tesseract.android.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.7.0")
    // org.json ships with the Android platform, so no dependency needed for JSON.
}
