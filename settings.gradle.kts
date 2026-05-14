@file:Suppress("UnstableApiUsage")

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

rootProject.name = "authio-kotlin"

include(":authio-core")

// The Android library + example app are conditionally included; without
// the Android SDK on the build host they fail at configuration time which
// blocks pure-JVM unit tests on the core module. Set
// `AUTHIO_INCLUDE_ANDROID=true` in your environment (or in
// gradle.properties) when building from Android Studio or CI with the
// SDK available.
val includeAndroid = providers
    .gradleProperty("authio.includeAndroid")
    .orElse(providers.environmentVariable("AUTHIO_INCLUDE_ANDROID"))
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

if (includeAndroid) {
    include(":authio-android")
    include(":examples:jetpack-compose")
}
