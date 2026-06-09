package dev.example.jcefe2e

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import org.jetbrains.annotations.TestOnly

/**
 * A tiny application-service test seam. The e2e Driver can't easily reach a tool-window panel
 * instance through the platform object graph, so the panel registers itself here on creation and the
 * Driver `@Remote` stub binds to this service, delegating to the live panel's `*ForTest` hooks.
 *
 * Registered by the `@Service` annotation (no plugin.xml entry needed).
 */
@Service(Service.Level.APP)
class DemoBridge {
    @Volatile
    var panel: DemoToolWindowPanel? = null

    @TestOnly fun hasRenderedForTest(): Boolean = panel?.hasRenderedForTest() ?: false
    @TestOnly fun clickCountForTest(): Int = panel?.clickCountForTest() ?: -1

    companion object {
        fun getInstance(): DemoBridge = ApplicationManager.getApplication().getService(DemoBridge::class.java)
    }
}
