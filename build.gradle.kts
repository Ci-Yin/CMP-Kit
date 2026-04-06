import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = "com.ciyin"
version = providers.gradleProperty("plugin.version").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        androidStudio(providers.gradleProperty("plugin.platform.version"))
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("org.jetbrains.kotlin")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("plugin.sinceBuild").get()
        }
        id = providers.gradleProperty("plugin.id").get()
        name = providers.gradleProperty("plugin.name").get()
        version = providers.gradleProperty("plugin.version").get()
    }
}
