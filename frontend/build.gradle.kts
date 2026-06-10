// Frontend content module: everything user-facing lives here - the tool window, the JCEF panel,
// and the DemoBridge test seam. Its descriptor (src/main/resources/dev.example.jcefe2e.frontend.xml)
// declares <dependencies><module name="intellij.platform.frontend"/></dependencies>, which is the
// platform's loading marker for "frontend code": the module loads in a monolith IDE and in the
// JetBrains Client, so under split mode the JCEF browser runs in the CLIENT process - the idiomatic
// V2 placement for new UI surfaces (and the process whose CDP port Playwright must attach to).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

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
