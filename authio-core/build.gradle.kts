plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `maven-publish`
    signing
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // kotlinx-serialization, coroutines, and okhttp are part of
    // :authio-core's *public* API surface — Client exposes OkHttpClient
    // builders, suspend functions, and @Serializable response models in
    // method signatures, so any downstream module that does
    // `api(project(":authio-core"))` (e.g. :authio-android) needs to see
    // them at compile time. `api(...)` declares them as transitively
    // exported, matching that reality. Marking them `implementation`
    // hides the symbols and breaks :authio-android:compileReleaseKotlin
    // with "Cannot access class 'okhttp3.OkHttpClient'".
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    // kotlin("test") auto-selects JUnit 4 by default; explicit useJUnit()
    // would be redundant. testLogging just makes CI output readable.
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "authio-core"
            pom {
                name.set("authio-core")
                description.set("Authio Kotlin/JVM SDK — passwordless, multi-org auth.")
                url.set("https://github.com/authio-com/authio_kotlin")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("authio")
                        name.set("Authio")
                        email.set("releases@authio.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/authio-com/authio_kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/authio-com/authio_kotlin.git")
                    url.set("https://github.com/authio-com/authio_kotlin")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/authio-com/authio_kotlin/issues")
                }
            }
        }
    }
}

// Sonatype requires signed artifacts on Maven Central. SIGNING_KEY (an
// ASCII-armored secret key block) and SIGNING_PASSWORD are env vars set
// by the publish workflow. When they aren't set (local builds, CI sanity
// checks) we skip signing rather than failing — the publish task still
// reports the signing requirement when it runs against Sonatype.
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
