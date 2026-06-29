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
    // IntelliJ Platform — target the *Android Studio* distribution instead
    // of IntelliJ IDEA Community because AS ships JCEF + Android-specific
    // APIs out of the box. We point at the local install via localPath so
    // `./gradlew runIde` doesn't have to re-download ~1GB of AS just to
    // boot a sandbox.
    //
    // Note: localPath must be an existing IntelliJ Platform installation
    // directory (the one that contains `bin/`, `lib/`, `plugins/`). The
    // AS installer produces exactly that layout.
    intellijPlatform {
        // 使用标准版 IntelliJ 作为开发环境，它自带完整的 JCEF 支持
        intellijIdeaCommunity("2024.2.4")
        
        // 如果您确实需要连接到本地 AS 调试，请确保该 AS 的 jbr/bin 目录下有 cef_server.exe
        // local("D:/work/android/AndroidStudioPanda4")

        bundledPlugin("com.intellij.java")
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