package dev.example.jcefe2e.e2e

import org.junit.jupiter.api.Test

/**
 * Proves the harness end-to-end: build the plugin, launch a real IDE with it installed, open the
 * Demo tool window, and confirm its JCEF page renders - all while the [CIServer] override fails the
 * test on any IDE-side exception (the most valuable assertion this harness gives us).
 */
class DemoSmokeTest : DemoE2ETestBase() {
    @Test
    fun opensAndRendersTheToolWindow() = runInCleanIde("demoSmoke") {
        activateDemoToolWindow()
        demoBridge().awaitRendered()
    }
}
