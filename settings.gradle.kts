// Declaring the IntelliJ Platform plugin at the *settings* level tells Gradle
// to resolve `org.jetbrains.intellij.platform` (and recognise the
// `intellijPlatform { ... }` extension) before any project's build.gradle.kts
// is evaluated. Without this, settings.gradle.kts can't see the `intellijPlatform`
// DSL and the IDE reports `Unresolved reference 'intellijPlatform'`.
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Required so Gradle can fetch the platform plugin + IDE distributions.
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
    plugins {
        id("org.jetbrains.intellij.platform") version "2.2.1"
        id("org.jetbrains.intellij.platform.settings") version "2.2.1"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        // Runtime repositories (IDE distributions, bundled plugins, etc.).
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "hermes-agent-jetbrains"
