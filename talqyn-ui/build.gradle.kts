import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.talqyn.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // What the screen does with a touch, a layout, or TalkBack is tested on Robolectric, which
        // reads the merged manifest: the activity the tests compose into comes from `ui-test-manifest`.
        unitTests.isIncludeAndroidResources = true
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
                name.set("Talqyn UI")
                description.set("The Talqyn consultant screen for Android, in Jetpack Compose.")
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
    explicitApi()
    // Written as Kotlin 2.1 against the 2.1 standard library, like the modules under it.
    coreLibrariesVersion = "2.1.21"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// The screen's Robolectric tests compose into the activity that `ui-test-manifest` brings, and
// that artifact is debug-only so it can never reach the published AAR. On release there is no
// such activity to launch, and the same tests fail on a manifest rather than on the screen — the
// code under them is the same code. Only `release` is published; `debug` runs them all.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

// Compose is the one dependency the screen cannot do without, and it is the
// app's own already. Material 3 is used inside for the sheet, dialogs and
// progress only; every color and shape comes from `TalqynTheme`.
//
// Versions rather than the BOM: a platform in a library's published metadata raises every
// Compose artifact of the app to it, the ones this module never touches included. What an app
// inherits is what these artifacts ask for: compileSdk 35 and AGP 8.6.
dependencies {
    api(project(":talqyn-consultant-core"))

    api(libs.compose.ui)
    api(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    // `WindowInsetsControllerCompat`, for the icons of the system bars.
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    // The activity `createComposeRule` composes into. Debug only, so never part of the published library.
    debugImplementation(libs.compose.ui.test.manifest)
}
