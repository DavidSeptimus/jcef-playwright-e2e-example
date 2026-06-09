package dev.example.jcefe2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent

/**
 * The JCEF content of the Demo tool window: a simple HTML page with a button (see
 * demo-preview/index.html). Clicking the button fires a browser-pipe message to this Kotlin class,
 * which counts the click, formats a message, and pushes it back to the page to display - a complete
 * JS -> Kotlin -> JS round-trip.
 *
 * The `*ForTest` hooks are the seam the e2e harness reads (via [DemoBridge]): a render latch (to know
 * when the page is ready) and the click count (to confirm a real gesture round-tripped to Kotlin).
 */
class DemoToolWindowPanel(parentDisposable: Disposable) {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val mapper = ObjectMapper()

    @Volatile private var rendered = false
    @Volatile private var clickCount = 0

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parentDisposable, browser)
        Disposer.register(parentDisposable, query)

        query.addHandler { request -> handlePipeMessage(request); null }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) installPipe()
            }
        }, browser.cefBrowser)
        browser.loadHTML(loadIndexHtml())

        // Register with the test bridge so the e2e Driver can reach the hooks; clear on dispose.
        DemoBridge.getInstance().panel = this
        Disposer.register(parentDisposable) {
            if (DemoBridge.getInstance().panel === this) DemoBridge.getInstance().panel = null
        }
    }

    private fun installPipe() {
        // Define the pipe: window.__DemoTools.send(payloadJson) -> the JBCefJSQuery handler below.
        exec("window.__DemoTools = { send: function(p) { ${query.inject("p")} } };")
        rendered = true
    }

    private fun handlePipeMessage(request: String) {
        val node = runCatching { mapper.readTree(request) }.getOrNull() ?: return
        when (node.get("type")?.asText()) {
            "buttonClicked" -> {
                clickCount++
                val message = "Button clicked $clickCount time(s)"
                // Push the message back to the page to display (Kotlin -> JS).
                exec("if (window.__showMessage) window.__showMessage(${toJsString(message)});")
            }
            else -> LOG.debug("Unknown pipe message: $request")
        }
    }

    private fun exec(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url ?: "", 0)
    }

    private fun loadIndexHtml(): String =
        javaClass.classLoader.getResourceAsStream("demo-preview/index.html")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    // --- @TestOnly hooks driven by the e2e harness (via DemoBridge) ----------------------------

    @TestOnly fun hasRenderedForTest(): Boolean = rendered
    @TestOnly fun clickCountForTest(): Int = clickCount

    companion object {
        private val LOG = logger<DemoToolWindowPanel>()

        fun toJsString(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    }
}
