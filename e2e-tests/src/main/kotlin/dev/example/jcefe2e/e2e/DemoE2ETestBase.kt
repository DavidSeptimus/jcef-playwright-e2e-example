package dev.example.jcefe2e.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.config.ConfigurationStorage
import com.intellij.ide.starter.config.splitMode
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.driver.remoteDev.RemDevDriverRunner
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.DriverRunner
import com.intellij.ide.starter.driver.engine.LocalDriverRunner
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.fail
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes

// testIdeUi (root build) auto-wires this to the built plugin distribution.
private const val PLUGIN_PATH_PROPERTY = "path.to.build.plugin"

// Fixed CDP port JCEF exposes (Registry key ide.browser.jcef.debug.port). Playwright attaches here
// to drive real gestures in the tool window's page. Fixed is fine: the e2e task runs one IDE at a
// time. The port goes on the process that OWNS the JCEF browser - and only that one, because in
// split mode two IDE processes would otherwise race to bind it. Our tool window lives in a frontend
// content module, so that's the JetBrains Client in split, the single process in monolith.
internal const val JCEF_CDP_PORT = 9222

// One-shot guard for the DriverRunner DI binding (see init below): each test class's init
// re-extends `di`, and Kodein throws OverridingException on a duplicate binding.
private val driverRunnerBound = AtomicBoolean(false)

// The bundled fixture project; the Gradle task passes its absolute path via e2e.fixture.dir.
private val fixturePath: Path = Path(System.getProperty("e2e.fixture.dir") ?: "e2e-tests/testData/demo-fixture")

// IDE under test: IntelliJ IDEA Ultimate, matching the build's intellijIdea(platformVersion).
private val ideVersion: String = System.getProperty("e2e.ide.version") ?: "2025.3.3"

/**
 * Base for the Demo plugin's e2e tests (IDE Starter + Driver). The same test classes run in both
 * architectures:
 *
 *   ./gradlew e2eTest            # monolith (single IDE process)
 *   ./gradlew e2eTestSplitMode   # split: backend host + JetBrains Client (remote development)
 *
 * Provides:
 *  - a [CIServer] override so any exception/freeze inside the IDE-under-test FAILS the test;
 *  - an explicit [DriverRunner] binding so the split task GENUINELY splits (see init);
 *  - [newDemoContext] which installs the freshly built plugin on every IDE process and exposes
 *    JCEF's CDP port on the process that owns the browser;
 *  - [launchCleanIde] / [runInCleanIde] launch helpers + a [fixtureProject] temp copy.
 */
abstract class DemoE2ETestBase {
    init {
        // Route IDE-side exceptions to a JUnit failure (Starter would otherwise no-op them).
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(testName: String, message: String, details: String, linkToLogs: String?) {
                        // In real split runs the bundled Kubernetes plugin's frontend half fires an
                        // unresolved-RemoteApi error before the disable (below) fully bites.
                        // Platform/bundled-plugin noise, unrelated to this plugin.
                        if (message.contains("com.intellij.kubernetes") || details.contains("com.intellij.kubernetes")) {
                            println("Ignoring known bundled-Kubernetes split-mode noise: $message")
                            return
                        }
                        fail<Unit>("$testName failed inside the IDE under test: $message\n$details")
                    }
                }
            }
            // Make e2eTestSplitMode actually SPLIT. In Starter 253, runIdeWithDriver resolves
            // DriverRunner via instanceOrNull and falls back to LocalDriverRunner - without this
            // binding the JetBrains Client sandbox is provisioned but never launched, and the
            // "split" run is a single monolith process that can pass for months unnoticed. Newer
            // Starter versions ship this exact binding; mirror it until the dependency catches up.
            if (driverRunnerBound.compareAndSet(false, true)) {
                bindProvider<DriverRunner> {
                    if (ConfigurationStorage.splitMode()) RemDevDriverRunner() else LocalDriverRunner()
                }
            }
        }
    }

    /**
     * Builds a Starter context with the built plugin installed. In split mode the context is an
     * [IDERemDevTestContext] whose JetBrains Client (frontend) is a SEPARATE context - a single
     * PluginConfigurator call only reaches the backend - so the same setup is applied to both.
     * The CDP port goes on the frontend context only: the tool window is a frontend module, so the
     * JCEF browser runs in the client there (in monolith, on the single context).
     */
    private fun newDemoContext(testName: String, project: ProjectInfoSpec): IDETestContext =
        Starter.newContext(testName, TestCase(IdeProductProvider.IU, project).withVersion(ideVersion)).apply {
            applyDemoTestSetup()
            val frontend = (this as? IDERemDevTestContext)?.frontendIDEContext
            frontend?.applyDemoTestSetup()
            (frontend ?: this).applyVMOptionsPatch {
                // Expose JCEF's Chromium DevTools Protocol so Playwright can attach
                // (PlaywrightPreviewSupport) - on the browser-owning process only.
                addSystemProperty("ide.browser.jcef.debug.port", JCEF_CDP_PORT)
            }
        }

    /** Installs the built plugin and applies the standard test setup to one IDE context. */
    private fun IDETestContext.applyDemoTestSetup() {
        val pluginPath = System.getProperty(PLUGIN_PATH_PROPERTY)
            ?: error("System property '$PLUGIN_PATH_PROPERTY' is not set. Run via the Gradle 'e2eTest'/'e2eTestSplitMode' task.")
        PluginConfigurator(this).installPluginFromPath(Path(pluginPath))
        // The bundled Kubernetes plugin's Service View can throw on startup (unrelated platform
        // bug); the CIServer override would count it as a failure, so disable it on every context.
        PluginConfigurator(this).disablePlugins("com.intellij.kubernetes")
        applyVMOptionsPatch {
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
