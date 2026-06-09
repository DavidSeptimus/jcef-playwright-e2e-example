import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("java")
    // Versions come from settings.gradle.kts (pluginManagement for Kotlin, the settings plugin for
    // org.jetbrains.intellij.platform); declaring versions here would clash.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.example.jcefe2e"
        name = "JCEF + Playwright E2E Example"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

// =============================================================================
// E2E (IDE Starter + Driver) — root-owned testIdeUi task.
// =============================================================================
// The IntelliJ Platform Gradle plugin rejects `testIdeUi` in `.module` projects, so the task lives
// in the root (where the plugin-under-test is built). We pull :e2e-tests's compiled classes (as
// directories, for test discovery) and its full runtime classpath through two resolvable
// configurations — pure dependency resolution, which keeps the cross-project wiring
// configuration-cache safe. A distinguishing attribute disambiguates these from :e2e-tests's own
// default runtimeElements variant.
val e2eVariantAttr: Attribute<String> = Attribute.of("dev.example.e2e.variant", String::class.java)

val e2eTestRuntimeClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(e2eVariantAttr, "runtime-classpath")
    }
    // The Kotlin Gradle plugin pulls kotlin-reflect 2.2.20, but the Starter/driver SDK calls an older
    // reflect API (KParameter.Kind.CONTEXT NoSuchFieldError at runtime). Force reflect + stdlib to the
    // version the harness expects on the e2e process classpath only.
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-reflect:2.1.20",
            "org.jetbrains.kotlin:kotlin-stdlib:2.1.20",
        )
    }
}
val e2eTestClassesDirs: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.CLASSES))
        attribute(e2eVariantAttr, "classes-dirs")
    }
}
dependencies {
    e2eTestRuntimeClasspath(project(":e2e-tests"))
    e2eTestClassesDirs(project(":e2e-tests"))
}

intellijPlatformTesting {
    testIdeUi {
        // Monolith e2e run: ./gradlew e2eTest  (this example focuses on monolith).
        // testIdeUi auto-wires `path.to.build.plugin` (the built plugin) + prepareSandbox; we point
        // discovery/classpath at :e2e-tests and hand the harness the fixture path.
        register("e2eTest") {
            val fixtureDir = layout.projectDirectory.dir("e2e-tests/testData/demo-fixture").asFile.absolutePath
            task {
                testClassesDirs = e2eTestClassesDirs
                classpath = e2eTestClassesDirs + e2eTestRuntimeClasspath
                systemProperty("e2e.fixture.dir", fixtureDir)
                useJUnitPlatform()
            }
        }
    }
}
