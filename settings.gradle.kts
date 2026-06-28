pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        // JetBrains IntelliJ Platform — required so Gradle can resolve
        // `org.jetbrains.intellij.platform` 2.2.1 and recognise the
        // `intellijPlatform { ... }` extension type at settings time.
        // Without this line the plugin loads, but the extension isn't
        // registered → "Unresolved reference 'intellijPlatform'".
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        intellijPlatform {
            defaultRepositories()
        }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()

        // IntelliJ Platform artifact repositories (IDE distributions, bundled
        // plugins, etc.). Same cache-redirector URL as above.
        intellijPlatform {
            defaultRepositories()
        }
    }
}

rootProject.name = "hermes-agent-jetbrains"
