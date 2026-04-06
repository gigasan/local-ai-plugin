package com.gigasan.localai

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import javax.swing.SwingUtilities

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

        // Добавим шестерёнку с меню действий
        toolWindow.setTitleActions(
            listOf(
                object : AnAction("Settings") {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(
                            e.project,
                            PluginSettingsConfigurable::class.java
                        )
                    }
                }
            )
        )
    }

    fun updateToolWindowTitle(toolWindow: ToolWindow) {
        val name = PluginSettings.instance.selectedModelName
        toolWindow.title = if (name.isNotBlank()) "$name" else ""
    }
}