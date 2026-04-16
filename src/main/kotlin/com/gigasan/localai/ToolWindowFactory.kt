package com.gigasan.localai

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import javax.swing.SwingUtilities
import com.intellij.icons.AllIcons

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project) // это твоя панель с чатом
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(chatPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // Изначальный заголовок
        updateToolWindowTitle(toolWindow)

        // Подписка на изменение модели
        PluginSettings.instance.addChangeListener {
            SwingUtilities.invokeLater {
                updateToolWindowTitle(toolWindow)
            }
        }

        toolWindow.setTitleActions(
            listOf(
                AskAction(),
                RefactorAction(),
                AnalyzeAction(),
                object : AnAction("Settings", "Settings", AllIcons.General.Settings) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(
                            e.project,
                            PluginSettingsConfigurable::class.java
                        )
                    }
                },
                object : AnAction("DevTools", "Open cefBrowser DevTools", AllIcons.General.Web) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val browser = e.getData(ChatPanel.CHAT_BROWSER_KEY)
                        browser?.cefBrowser?.openDevTools()
                    }
                },
            )
        )
    }

    fun updateToolWindowTitle(toolWindow: ToolWindow) {
        val name = PluginSettings.instance.selectedModelName
        toolWindow.title = if (name.isNotBlank()) "$name" else ""
    }
}