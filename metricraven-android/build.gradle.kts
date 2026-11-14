plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

android {
    namespace = "com.metricraven.android"
    compileSdk = 36

    defaultConfig {
        // Android 6.0+, the floor AndroidX, Firebase and Play services use: all but a sliver of active devices.
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The on-device end-to-end test (androidTest) reads its local backend from these, e.g.
        // -Pandroid.testInstrumentationRunnerArguments.endpoint=http://10.0.2.2:5221/api/v1/collect
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            // Required by Maven Central.
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    // Apps on Kotlin 2.1 or later can use the library: stdlib and API level 2.2, whatever compiles it.
    coreLibrariesVersion = "2.2.0"
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    api(project(":metricraven-core"))

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "metricraven-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("MetricRaven for Android")
                description.set("MetricRaven analytics for native Android apps: screen views, events, identify, consent and an offline queue.")
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
