pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (Tesseract 4/5, LSTM — matches tessdata_fast models).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PhoneMcp"
include(":app")
