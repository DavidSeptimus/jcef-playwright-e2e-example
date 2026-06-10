package dev.example.jcefe2e

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/** Registers the Demo tool window's content: the JCEF [DemoToolWindowPanel]. */
class DemoToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DemoToolWindowPanel(toolWindow.disposable)
        val content = toolWindow.contentManager.factory.createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
