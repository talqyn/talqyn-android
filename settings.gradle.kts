pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "talqyn-android"

// Networking, authorization, and models. No UI.
include(":talqyn-sdk")
// The logic of the consultant screen with no view attached: the conversation,
// answer rendering, chat history, copy. For a storefront that draws its own
// screen and must not link somebody else's.
include(":talqyn-consultant-core")
// The ready-made consultant screen in Jetpack Compose over the two above.
include(":talqyn-ui")
// A host app over a canned transport: the screen without credentials, for a
// look on an emulator. Not published.
include(":sample")
