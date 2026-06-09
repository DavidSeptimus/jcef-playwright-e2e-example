// E2E harness driven by the IntelliJ "IDE Starter + Driver" framework. Holds ONLY the harness +
// tests; it is never run via its own `test` task. The root build's `e2eTest` testIdeUi task builds
// the plugin, launches a real IDE with it installed, and runs these classes against it.
//
// Code lives in `src/main` on purpose: the module IS a test harness, so "main" is the harness, and
// the root project consumes its compiled output + runtime classpath through the consumable
// configurations below (pure dependency resolution -> configuration-cache safe).
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform.module")
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Brings in the IDE Starter framework AND the Driver SDK (com.intellij.ide.starter.* +
        // com.intellij.driver.* APIs). Routed onto `implementation` since the harness is in src/main.
        testFramework(TestFrameworkType.Starter, configurationName = "implementation")
    }

    // Pin to the versions the Starter framework itself uses.
    implementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // The JUnit Platform launcher must be explicit: the root e2eTest task sets its own classpath, so
    // Gradle's usual auto-injection of the launcher doesn't apply.
    implementation("org.junit.platform:junit-platform-launcher:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")
    implementation("org.kodein.di:kodein-di-jvm:7.20.2")

    // Playwright (Java) drives real, trusted gestures inside the JCEF preview over the Chromium
    // DevTools Protocol endpoint JCEF exposes with -Dide.browser.jcef.debug.port. connectOverCDP
    // attaches to the already-running CEF, so no Playwright browser binaries are downloaded.
    implementation("com.microsoft.playwright:playwright:1.49.0")
}

// =============================================================================
// Consumable configurations exposed to the root project's testIdeUi task.
// =============================================================================
val e2eVariantAttr: Attribute<String> = Attribute.of("dev.example.e2e.variant", String::class.java)

val e2eTestRuntimeClasspathElements by configurations.consumable("e2eTestRuntimeClasspathElements") {
    extendsFrom(configurations.runtimeElements.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(e2eVariantAttr, "runtime-classpath")
    }
}

val e2eTestClassesDirsElements by configurations.consumable("e2eTestClassesDirsElements") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.CLASSES))
        attribute(e2eVariantAttr, "classes-dirs")
    }
}

artifacts {
    sourceSets.main.get().output.classesDirs.forEach { dir ->
        add(e2eTestClassesDirsElements.name, dir) { builtBy(tasks.named("classes")) }
    }
}
