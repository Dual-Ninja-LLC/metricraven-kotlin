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
    }
}

rootProject.name = "metricraven-kotlin"

// Pure Kotlin/JVM: the collect protocol, validation, offline queue and HTTP. Tested on the plain JVM.
include(":metricraven-core")
// The Android layer: device info, SharedPreferences, foreground/background and network flushes.
include(":metricraven-android")
