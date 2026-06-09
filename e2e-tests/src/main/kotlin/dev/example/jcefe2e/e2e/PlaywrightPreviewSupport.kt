package dev.example.jcefe2e.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright

/**
 * A live Playwright attachment to JCEF's embedded Chromium (CEF) over the DevTools Protocol, plus the
 * resolved tool-window [Page]. Playwright drives REAL, trusted input via CDP, which the page handles
 * exactly like a genuine user gesture - so this exercises the JS click handler + the Kotlin pipe it
 * fires, end to end. CDP input dispatches into the renderer, so it works for the off-screen-rendered
 * tool-window browser too.
 */
internal class PreviewPlaywright(
    val playwright: Playwright,
    private val browser: Browser,
    val page: Page,
) : AutoCloseable {
    override fun close() {
        runCatching { browser.close() } // detaches CDP; leaves the IDE's CEF running
        runCatching { playwright.close() }
    }

    /** Real click on the page's button. */
    fun clickButton() = page.locator("#click-btn").click()

    /** The current text of the #message element (what Kotlin pushed back after handling the click). */
    fun messageText(): String = page.locator("#message").textContent().trim()
}

/**
 * Attaches Playwright to the Demo tool-window page over the CDP endpoint exposed by
 * `-Dide.browser.jcef.debug.port=$JCEF_CDP_PORT`, then resolves the page hosting `#click-btn`.
 * Retries both the connect (JCEF DevTools starts asynchronously) and the page discovery. Skips
 * Playwright's own browser download - connectOverCDP attaches to the running CEF.
 */
internal fun attachPreviewPlaywright(): PreviewPlaywright {
    val playwright = Playwright.create(
        Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")),
    )
    try {
        var browser: Browser? = null
        var lastError: Exception? = null
        repeat(30) {
            if (browser == null) {
                try {
                    browser = playwright.chromium().connectOverCDP("http://localhost:$JCEF_CDP_PORT")
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(1000)
                }
            }
        }
        val connected = browser ?: error("could not connect to JCEF CDP at localhost:$JCEF_CDP_PORT: ${lastError?.message}")
        repeat(30) {
            val page = connected.contexts().flatMap { it.pages() }.firstOrNull { p ->
                runCatching { p.querySelector("#click-btn") != null }.getOrDefault(false)
            }
            if (page != null) return PreviewPlaywright(playwright, connected, page)
            Thread.sleep(1000)
        }
        runCatching { connected.close() }
        error("No JCEF page with #click-btn found over CDP at localhost:$JCEF_CDP_PORT")
    } catch (e: Exception) {
        playwright.close()
        throw e
    }
}
