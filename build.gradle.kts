plugins {
    kotlin("jvm") version "2.0.0"
    `maven-publish`
}

group = "com.authio"
version = "0.1.0-alpha.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    testImplementation(kotlin("test"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
