package dev.example.jcefe2e.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.openToolWindow

/** Driver @Remote stub + helpers for the Demo tool window (monolith + split). */

/**
 * Binds to the plugin's [DemoBridge] application-service test seam. The panel registers itself with
 * the bridge on creation, so these methods reach the live JCEF panel's @TestOnly hooks. The class
 * lives in the `frontend` content module, so the plugin id uses the content-module form
 * `"<pluginId>/<moduleName>"` (a bare plugin id only resolves classes in the main module). No
 * explicit RdTarget needed: the Driver defaults to the frontend in split mode, which is exactly
 * where this frontend-module service (and its JCEF panel) lives - and the single process in
 * monolith.
 */
@Remote("dev.example.jcefe2e.DemoBridge", plugin = "dev.example.jcefe2e/dev.example.jcefe2e.frontend")
internal interface DemoBridgeRef {
    fun hasRenderedForTest(): Boolean
    fun clickCountForTest(): Int
}

internal fun Driver.demoBridge(): DemoBridgeRef = service<DemoBridgeRef>()

/**
 * Shows the Demo tool window (ToolWindowManager.getToolWindow("Demo").show() on EDT), which makes the
 * factory create the JCEF panel. Retries because an EP-registered tool window can become available a
 * beat after indexing settles (openToolWindow throws NPE on a not-yet-registered id).
 */
internal fun Driver.activateDemoToolWindow() {
    repeat(40) {
        if (runCatching { openToolWindow("Demo") }.isSuccess) return
        Thread.sleep(500)
    }
    error("Demo tool window never became available")
}

/** Polls the render latch until the JCEF page has loaded and the pipe is installed. */
internal fun DemoBridgeRef.awaitRendered(polls: Int = 60) {
    repeat(polls) {
        if (hasRenderedForTest()) return
        Thread.sleep(500)
    }
    error("Demo tool window never rendered (hasRenderedForTest stayed false)")
}

/** Polls until the Kotlin side has observed at least [target] clicks (the pipe round-trip is async). */
internal fun DemoBridgeRef.awaitClickCount(target: Int, polls: Int = 40): Int {
    repeat(polls) {
        if (clickCountForTest() >= target) return clickCountForTest()
        Thread.sleep(250)
    }
    return clickCountForTest()
}
