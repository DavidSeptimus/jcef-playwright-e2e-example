package dev.example.jcefe2e.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes

// testIdeUi (root build) auto-wires this to the built plugin distribution.
private const val PLUGIN_PATH_PROPERTY = "path.to.build.plugin"

// Fixed CDP port JCEF exposes (Registry key ide.browser.jcef.debug.port). Playwright attaches here
// to drive real gestures in the preview. Fixed is fine: the e2e task runs one IDE at a time.
internal const val JCEF_CDP_PORT = 9222

// The bundled fixture project; the Gradle task passes its absolute path via e2e.fixture.dir.
private val fixturePath: Path = Path(System.getProperty("e2e.fixture.dir") ?: "e2e-tests/testData/demo-fixture")

// IDE under test: IntelliJ IDEA Ultimate, matching the build's intellijIdea(platformVersion).
private val ideVersion: String = System.getProperty("e2e.ide.version") ?: "2025.3.3"

/**
 * Base for the Demo plugin's e2e tests (IDE Starter + Driver), MONOLITH only.
 *
 * Provides:
 *  - a [CIServer] override so any exception/freeze inside the IDE-under-test FAILS the test;
 *  - [newDemoContext] which installs the freshly built plugin and exposes JCEF's CDP port;
 *  - [launchCleanIde] / [runInCleanIde] launch helpers + a [fixtureProject] temp copy.
 *
 * Run via: ./gradlew e2eTest
 */
abstract class DemoE2ETestBase {
    init {
        // Route IDE-side exceptions to a JUnit failure (Starter would otherwise no-op them).
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(testName: String, message: String, details: String, linkToLogs: String?) {
                        fail<Unit>("$testName failed inside the IDE under test: $message\n$details")
                    }
                }
            }
        }
    }

    private fun newDemoContext(testName: String, project: ProjectInfoSpec): IDETestContext =
        Starter.newContext(testName, TestCase(IdeProductProvider.IU, project).withVersion(ideVersion)).apply {
            val pluginPath = System.getProperty(PLUGIN_PATH_PROPERTY)
                ?: error("System property '$PLUGIN_PATH_PROPERTY' is not set. Run via the Gradle 'e2eTest' task.")
            PluginConfigurator(this).installPluginFromPath(Path(pluginPath))
            // The bundled Kubernetes plugin's Service View can throw on startup (unrelated platform
            // bug); the CIServer override would count it as a failure, so disable it.
            PluginConfigurator(this).disablePlugins("com.intellij.kubernetes")
            applyVMOptionsPatch {
                // Expose JCEF's Chromium DevTools Protocol so Playwright can attach (PlaywrightPreviewSupport).
                addSystemProperty("ide.browser.jcef.debug.port", JCEF_CDP_PORT)
                addSystemProperty("ide.integration.test.disable.got.it.tooltips", true)
            }
        }

    /** A fresh temp copy of the bundled fixture (the IDE may write files back, so never use the committed copy). */
    protected fun fixtureProject(): LocalProjectInfo {
        val tempRoot = createTempDirectory("demo-e2e-fixture")
        Files.walk(fixturePath).use { paths ->
            paths.forEach { src ->
                val dest = tempRoot.resolve(fixturePath.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dest)
                else { Files.createDirectories(dest.parent); Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        return LocalProjectInfo(tempRoot)
    }

    /** Launch the IDE on the fixture, wait for indexing, clean the UI; returns the still-running IDE. */
    protected fun launchCleanIde(testName: String, project: ProjectInfoSpec = fixtureProject()): BackgroundRun {
        val run = newDemoContext(testName, project).runIdeWithDriver()
        with(run.driver) {
            waitForIndicators(5.minutes)
            prepareCleanIde()
        }
        return run
    }

    /** Single-shot: launch a clean IDE, run [block], close it. */
    protected fun runInCleanIde(testName: String, project: ProjectInfoSpec = fixtureProject(), block: Driver.() -> Unit) {
        launchCleanIde(testName, project).useDriverAndCloseIde { block() }
    }

    protected fun Driver.prepareCleanIde() {
        runCatching { invokeAction("HideAllWindows", now = false) }
        runCatching { invokeAction("ClearAllNotifications", now = false) }
    }
}
