import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

/**
 * Writes the version the SDK is built under into its sources, as `TALQYN_SDK_VERSION`.
 *
 * `Talqyn.VERSION` travels in every request's `X-Talqyn-SDK` header and is what support
 * narrows a report down with. Typed by hand next to the Gradle version, it would drift on
 * the first release somebody forgets to bump both.
 */
abstract class GenerateTalqynVersion : DefaultTask() {
    @get:Input
    abstract val sdkVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDirectory.file("com/talqyn/sdk/TalqynVersion.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.talqyn.sdk
            |
            |/** The version this build of the SDK carries. Generated from the Gradle project version. */
            |internal const val TALQYN_SDK_VERSION: String = "${sdkVersion.get()}"
            |
            """.trimMargin(),
        )
    }
}

val generateTalqynVersion = tasks.register<GenerateTalqynVersion>("generateTalqynVersion") {
    sdkVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("generated/source/talqynVersion"))
}

android {
    namespace = "com.talqyn.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Published as an AAR: an app on another version of the Android Gradle plugin cannot take the
// module from source through `includeBuild`, but it can take the artifact.
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = project.name
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Talqyn SDK")
                description.set("Talqyn public API for Android: device token, search, filters, consultant, chat history, events.")
            }
        }
    }
    repositories {
        // `./gradlew publishAllPublicationsToBuildRepository` — a Maven repository in the build folder.
        maven {
            name = "build"
            url = uri(rootProject.layout.buildDirectory.dir("maven"))
        }
    }
}

kotlin {
    // Every public declaration states its visibility and type: this is a
    // contract, and what it exposes is decided on purpose, not by default.
    explicitApi()
    // Built with Kotlin 2.2, written as 2.1 against the 2.1 standard library: a compiler reads
    // metadata at most one version newer than itself, and an app is not asked to move its
    // Kotlin forward just to link the SDK.
    coreLibrariesVersion = "2.1.21"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(JvmTarget.JVM_17)
        // The SDK promises to build without warnings; the compiler holds it
        // to that, not the memory of whoever last read the build log.
        allWarningsAsErrors.set(true)
    }
}

// Registered through the variant API, which wires the generator into every task that reads
// the sources — the Kotlin compiler, the sources jar, lint — rather than into the few named here.
androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateTalqynVersion, GenerateTalqynVersion::outputDirectory)
    }
}

tasks.withType<Test>().configureEach {
    // The tests hold the generated constant to the version the build declares.
    systemProperty("talqyn.version", project.version.toString())
}

// No dependencies beyond coroutines, deliberately: the SDK is embedded in
// somebody else's app, and every third-party library here is a version
// conflict for the integrator. HTTP is `HttpURLConnection`, JSON is the SDK's
// own reader and writer, signatures are `javax.crypto`.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
