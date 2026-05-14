// Root build file. Per-module configuration lives in each subproject's
// build.gradle.kts; this file only carries cross-cutting bits like the
// shared group/version + a convenience `clean` task.

plugins {
    // Apply the Kotlin JVM plugin at the root for consistent versioning;
    // subprojects that need it apply their own block.
}

allprojects {
    group = "com.authio"
    version = "0.1.0-alpha.0"
}

tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { delete(it.layout.buildDirectory) }
}
