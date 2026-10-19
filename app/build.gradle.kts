import java.io.FileOutputStream
import java.net.URL

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
    // Release is signed with a stable keystore supplied via env (GitHub Secrets),
    // so updates install over previous versions. Without it (local dev) we fall
    // back to the debug keystore so the build still works.
    signingConfigs {
        val ksPath = System.getenv("KEYSTORE_FILE")
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
// Downloads the arm64 cloudflared binary into jniLibs (as libcloudflared.so) at
// build time if it isn't already there, so the produced APK is self-contained
// and the phone never has to download anything. Override the version with
// -PcloudflaredVersion=2024.8.3 ; skip entirely with -PskipCloudflared.
val cloudflaredVersion = (project.findProperty("cloudflaredVersion") as String?) ?: "latest"
val cloudflaredFile = file("src/main/jniLibs/arm64-v8a/libcloudflared.so")

val fetchCloudflared by tasks.registering {
    description = "Download cloudflared (arm64) into jniLibs so it ships inside the APK."
    outputs.file(cloudflaredFile)
    onlyIf { !project.hasProperty("skipCloudflared") }
    doLast {
        if (cloudflaredFile.exists() && cloudflaredFile.length() > 1_000_000) {
            logger.lifecycle("cloudflared already present (${cloudflaredFile.length()} bytes), skipping download")
            return@doLast
        }
        cloudflaredFile.parentFile.mkdirs()
        val url = if (cloudflaredVersion == "latest")
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        else
            "https://github.com/cloudflare/cloudflared/releases/download/$cloudflaredVersion/cloudflared-linux-arm64"
        logger.lifecycle("Downloading cloudflared: $url")
        URL(url).openStream().use { input ->
            FileOutputStream(cloudflaredFile).use { out -> input.copyTo(out) }
        }
        logger.lifecycle("cloudflared -> $cloudflaredFile (${cloudflaredFile.length()} bytes)")
    }
}

tasks.named("preBuild") { dependsOn(fetchCloudflared) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // Tiny embedded HTTP server for the MCP Streamable-HTTP endpoint on localhost.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // org.json ships with the Android platform, so no dependency needed for JSON.
}
