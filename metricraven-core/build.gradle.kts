import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    signing
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    // Required by Maven Central; Kotlin-only sources make it a near-empty jar.
    withJavadocJar()
}

kotlin {
    explicitApi()
    // Apps on Kotlin 2.1 or later can use the library: stdlib and API level 2.2, whatever compiles it.
    coreLibrariesVersion = "2.2.0"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        // Compiled against the Java 11 API, so nothing newer slips in. The code also sticks to APIs
        // Android 6 has (no java.time).
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// The payload fixtures shared with the Swift SDK and the backend's collectSchema test. A standalone
// copy of this package (a public mirror) keeps a copy in src/test/resources/contract-payloads.json.
val sharedContract = rootProject.file("../sdk-swift/Tests/MetricRavenTests/contract-payloads.json")
val localContract = file("src/test/resources/contract-payloads.json")

tasks.test {
    useJUnit()
    systemProperty("metricraven.contractPayloads", (if (sharedContract.exists()) sharedContract else localContract).absolutePath)
    systemProperty("metricraven.androidPayloads", rootProject.file("android-payloads.json").absolutePath)
    // The end-to-end test against a local backend runs only when these are set (see README).
    listOf("METRICRAVEN_E2E_ENDPOINT", "METRICRAVEN_E2E_SITE_ID", "METRICRAVEN_E2E_SECRET_KEY", "METRICRAVEN_E2E_APP_ID", "METRICRAVEN_E2E_OFFLINE_ENDPOINT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    // Rerun when the shared fixtures or the E2E settings change.
    inputs.files(listOf(sharedContract, localContract, rootProject.file("android-payloads.json")).filter { it.exists() })
    inputs.property("e2e", System.getenv("METRICRAVEN_E2E_ENDPOINT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "metricraven-core"
            from(components["java"])
            pom {
                name.set("MetricRaven core")
                description.set("The MetricRaven collect protocol, offline queue and HTTP client for Kotlin and Java apps.")
                url.set("https://metricraven.com/docs/android-sdk")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                developers { developer { id.set("metricraven"); name.set("MetricRaven"); email.set("support@metricraven.com") } }
                scm {
                    url.set("https://github.com/Dual-Ninja-LLC/metricraven-kotlin")
                    connection.set("scm:git:https://github.com/Dual-Ninja-LLC/metricraven-kotlin.git")
                }
            }
        }
    }
}

// Maven Central bundle: `./gradlew centralBundle -Psigning.gnupg.keyName=<KEY_ID>` writes signed artifacts,
// with checksums, to build/central (root) and zips them for the Central Portal's "Publish Component" upload.
// Signing uses the local gpg agent (useGpgCmd), so the private key is never exported; it's off without a key.
publishing {
    repositories {
        maven {
            name = "centralStaging"
            url = uri(rootProject.layout.buildDirectory.dir("central"))
        }
    }
}

if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
    signing {
        useGpgCmd()
        sign(publishing.publications)
    }
}
