plugins {
    id("com.android.library") version "8.6.1"
    kotlin("android") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.authio.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // SDK core lives in :authio-core so it stays unit-testable on plain
    // JVM and so a future Kotlin-Multiplatform port has a clean seam.
    api(project(":authio-core"))

    // Coroutines are part of the public API surface (suspend functions).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The marquee feature: passkey ceremonies via the modern Credential
    // Manager API. The Play Services backport is what actually ships
    // the FIDO2 implementation on devices without GMS-native passkey
    // support; both are required.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    // Custom Tabs for OAuth.
    implementation("androidx.browser:browser:1.8.0")

    // EncryptedSharedPreferences for the session store.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Activity + Fragment KTX gives us the suspend-friendly ActivityResult
    // contracts that Custom Tabs uses for the deep-link callback path.
    implementation("androidx.activity:activity-ktx:1.9.2")

    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
