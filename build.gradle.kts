import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog") version "2.2.1"
}

group = "com.hermes.agent"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform — targeting IntelliJ 2024.2 (which is the base of
    // Android Studio Koala/Ladybug and newer). 2025.2 also accepted because
    // we don't use APIs that moved.
    intellijPlatform {
        intellijIdeaCommunity("2024.2.6")
        // Bundled JCEF is what powers VSCode-Chat-style markdown rendering.
        // Android Studio ships JCEF too, so this works there. Listing it
        // here does two things:
        //   1. compileClasspath picks up org.cef.* / com.intellij.ui.jcef.*
        //      so HermesChatToolWindowFactory.kt can import them
        //   2. `./gradlew runIde` sandboxes include the JCEF plugin jar
        //      automatically — without this, the sandbox IDE rejects
        //      our plugin with "requires 'com.intellij.ui.jcef'".
        // End users on Android Studio don't see a difference: AS has JCEF
        // bundled, so the dependency is satisfied out of the box.
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.intellij.intelliLang")
        bundledPlugin("com.intellij.ui.jcef")
        testFramework(TestFrameworkType.Platform)
    }

    // OkHttp is the canonical HTTP/WS client in IntelliJ Platform plugins.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        // Minimum IDE version that can load the plugin. Android Studio Koala
        // (2024.1.1) and Ladybug (2024.2.1) both satisfy this.
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "253.*"
        }
    }
    publishing {
        token = System.getenv("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

changelog {
    version.set(provider { project.version.toString() })
    path.set(file("CHANGELOG.md").canonicalPath)
    headerParserRegex.set("""(\d+\.\d+\.\d+).*""".toRegex())
    itemPrefix.set("-")
    keepUnreleasedSection.set(true)
    unreleasedTerm.set("[Unreleased]")
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
}

tasks.named<JavaExec>("runIde") {
    // When launched from Android Studio (which is what users actually run),
    // we want Android Studio as the host IDE so the plugin can register
    // against its plugin model. The default runIde target uses IntelliJ
    // IDEA Community, which works for code-level testing but not for the
    // Android-specific toolwindow anchors.
    systemProperty("idea.platform.prefix", "AndroidStudio")
}