plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonemcp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonemcp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // We only ship the arm64 cloudflared binary (as a native lib). Almost every
        // modern Android phone is arm64-v8a. Add armeabi-v7a here only if you also
        // drop a 32-bit cloudflared into jniLibs/armeabi-v7a/.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // Tiny embedded HTTP server for the MCP Streamable-HTTP endpoint on localhost.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // org.json ships with the Android platform, so no dependency needed for JSON.
}
