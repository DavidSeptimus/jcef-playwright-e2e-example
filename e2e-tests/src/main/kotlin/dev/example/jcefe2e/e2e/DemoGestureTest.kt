package dev.example.jcefe2e.e2e

import com.intellij.driver.client.Driver
import com.intellij.ide.starter.driver.engine.BackgroundRun
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Drives the SAME button click as [DemoPipeTest], but with a REAL gesture via Playwright attached to
 * JCEF over CDP - so it also covers the JS layer the pipe path skips: the actual DOM click handler.
 * Asserts both the page-visible result (#message text, pushed back by Kotlin) and that the Kotlin
 * side observed the click (the round-trip really happened).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoGestureTest : DemoE2ETestBase() {
    private lateinit var run: BackgroundRun
    private lateinit var pw: PreviewPlaywright
    private val driver: Driver get() = run.driver

    @BeforeAll
    fun launch() {
        run = launchCleanIde("demoGesture")
        driver.activateDemoToolWindow()
        driver.demoBridge().awaitRendered()
        pw = attachPreviewPlaywright()
    }

    @AfterAll
    fun close() {
        if (::pw.isInitialized) pw.close()
        if (::run.isInitialized) run.useDriverAndCloseIde { }
    }

    @Test
    fun realClickRoundTripsAndShowsMessage() {
        pw.clickButton()

        // The page shows the message Kotlin pushed back (Kotlin -> JS); poll until it appears.
        var message = ""
        repeat(40) {
            message = pw.messageText()
            if (message.isNotEmpty()) return@repeat
            Thread.sleep(250)
        }
        assertEquals("Button clicked 1 time(s)", message) { "the page should display Kotlin's message" }

        // ...and the Kotlin side really handled the gesture's pipe message.
        driver.demoBridge().awaitClickCount(1)
        assertTrue(driver.demoBridge().clickCountForTest() >= 1) { "Kotlin should have observed the real click" }
    }
}
