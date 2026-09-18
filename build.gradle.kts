plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The coordinates an app names the modules by — `com.talqyn:talqyn-ui` — whether it takes them
// from a repository or from this directory through `includeBuild("../talqyn-android")`.
subprojects {
    group = "com.talqyn"
    version = "1.0.0"

    // What Maven Central requires of every artifact and is the same for all three modules; the
    // name and the description stay in each module's own `pom {}`.
    plugins.withId("maven-publish") {
        apply(plugin = "signing")
        val publishing = extensions.getByType<PublishingExtension>()

        // Central requires a javadoc jar next to every AAR and accepts an empty one. A real one is
        // not built: the Dokka inside AGP cannot read sealed classes from a sibling module
        // (`PermittedSubclasses requires ASM9`), and Android Studio shows KDoc from the sources jar.
        val javadocJar = tasks.register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")
        }

        publishing.publications.withType<MavenPublication>().configureEach {
            artifact(javadocJar)
            pom {
                url.set("https://github.com/talqyn/talqyn-android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("talqyn")
                        name.set("Talqyn")
                        url.set("https://talqyn.com")
                    }
                }
                scm {
                    url.set("https://github.com/talqyn/talqyn-android")
                    connection.set("scm:git:https://github.com/talqyn/talqyn-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/talqyn/talqyn-android.git")
                }
            }
        }

        // Signed only where a key is configured: a local `publishToMavenLocal` needs no key, and
        // Central refuses an unsigned bundle anyway. The release workflow hands the armored key in
        // as `ORG_GRADLE_PROJECT_signingKey`, since a CI runner has no keyring; a developer's machine
        // names a key of its own keyring in `~/.gradle/gradle.properties`, so the key never leaves gpg.
        val signingKey = providers.gradleProperty("signingKey")
        val gpgKeyName = providers.gradleProperty("signing.gnupg.keyName")
        if (signingKey.isPresent || gpgKeyName.isPresent) {
            extensions.configure<SigningExtension> {
                if (signingKey.isPresent) {
                    useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingKeyPassword").get())
                } else {
                    useGpgCmd()
                }
                sign(publishing.publications)
            }
        }
    }
}
