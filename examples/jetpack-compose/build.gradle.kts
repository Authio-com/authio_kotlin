plugins {
    id("com.android.application") version "8.6.1"
    kotlin("android") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
}

android {
    namespace = "com.authio.example.compose"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.authio.example.compose"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Wired into BuildConfig.AUTHIO_PUBLISHABLE_KEY / AUTHIO_API_URL.
        // Override locally with `~/.gradle/gradle.properties`:
        //   authio.publishableKey=pk_test_yours
        //   authio.apiUrl=https://api.authio.com
        val publishableKey = providers.gradleProperty("authio.publishableKey")
            .orElse("pk_test_replace_me")
            .get()
        val apiUrl = providers.gradleProperty("authio.apiUrl")
            .orElse("https://authioauth-core-production.up.railway.app")
            .get()
        buildConfigField("String", "AUTHIO_PUBLISHABLE_KEY", "\"$publishableKey\"")
        buildConfigField("String", "AUTHIO_API_URL", "\"$apiUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("debug") {
            isDefault = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":authio-android"))

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
