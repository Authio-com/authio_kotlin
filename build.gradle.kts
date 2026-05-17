// Root build file. Per-module configuration lives in each subproject's
// build.gradle.kts; this file only carries cross-cutting bits like the
// shared group/version + the nexus-publish + signing wiring for Maven
// Central releases.

plugins {
    // nexus-publish handles the staging-repo lifecycle Sonatype expects:
    // `publishAllPublicationsToSonatypeRepository` followed by
    // `closeAndReleaseSonatypeStagingRepository`. The version below is the
    // last-stable as of the time this was added; bump in lockstep with
    // the Gradle Plugin Portal release notes.
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

allprojects {
    group = "io.authio"
    version = "0.1.0"
}

// Sonatype OSSRH (Maven Central) staging endpoint configuration. The
// credentials come from env vars at publish time; without them the
// repository block resolves to nothing and the publish task fails fast
// rather than silently uploading to a misconfigured destination.
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(providers.environmentVariable("MAVEN_USERNAME").orElse(""))
            password.set(providers.environmentVariable("MAVEN_PASSWORD").orElse(""))
        }
    }
}

tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { delete(it.layout.buildDirectory) }
}
