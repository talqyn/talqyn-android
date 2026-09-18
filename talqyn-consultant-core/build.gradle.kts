import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.talqyn.consultant"
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

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = project.name
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Talqyn Consultant Core")
                description.set("The logic of the Talqyn consultant screen with no view attached: conversation, ratings, answer rendering, history, copy.")
            }
        }
    }
    repositories {
        maven {
            name = "build"
            url = uri(rootProject.layout.buildDirectory.dir("maven"))
        }
    }
}

kotlin {
    explicitApi()
    // Built with Kotlin 2.2, written as 2.1 against the 2.1 standard library: a compiler reads
    // metadata at most one version newer than itself, and an app is not asked to move its
    // Kotlin forward just to link the SDK.
    coreLibrariesVersion = "2.1.21"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// No view toolkit: the conversation is observed through `StateFlow`, so a
// storefront can draw it in Compose, in Views, or in anything else.
dependencies {
    api(project(":talqyn-sdk"))
    // The conversation's default scope runs on the main thread.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
