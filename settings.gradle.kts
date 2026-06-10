@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
    // Pin the Kotlin plugin version once so root + every module declare it versionless.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

// rootProject.name is also the content-module jar namespace: the IntelliJ Platform Gradle plugin
// names each module jar "<rootProject.name>.<subproject>.jar", and the platform resolves a content
// module by matching the <content><module name="..."> declaration to that jar basename. So this MUST
// equal the namespace used in plugin.xml's content-module declarations (dev.example.jcefe2e.shared, ...).
rootProject.name = "dev.example.jcefe2e"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

// Plugin modules: the root module holds only plugin.xml; all functionality lives in the "frontend"
// content module (tool window + JCEF panel + bridge), the idiomatic V2 split-mode placement.
// Isolated harness for the IDE Starter + Driver e2e tests (JUnit5/Starter/Playwright deps kept off
// the main JUnit classpath). The testIdeUi task that runs it is registered in the ROOT build - the
// IntelliJ Platform Gradle plugin rejects testIdeUi in `.module` projects.
include("frontend")
include("e2e-tests")
