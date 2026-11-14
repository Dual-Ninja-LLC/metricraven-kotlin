plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), with the Kotlin Gradle plugin declared below.
    id("com.android.library") version "9.5.1" apply false
    kotlin("jvm") version "2.4.21" apply false
}

allprojects {
    group = "com.metricraven"
    version = providers.gradleProperty("metricraven.version").get()
}

// Signed artifacts of both modules, zipped for the Central Portal upload (see the signing note in each module).
tasks.register<Zip>("centralBundle") {
    dependsOn(":metricraven-core:publishAllPublicationsToCentralStagingRepository", ":metricraven-android:publishAllPublicationsToCentralStagingRepository")
    from(layout.buildDirectory.dir("central")) { exclude("**/maven-metadata*") }
    archiveFileName.set("metricraven-kotlin-${version}-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("bundle"))
}
