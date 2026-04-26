package com.gigasan.ai.ui.chat

import com.gigasan.ai.actions.AnalyzeAction
import com.gigasan.ai.actions.AskAction
import com.gigasan.ai.actions.SendFileAction
import com.gigasan.ai.actions.RefactorAction
import com.gigasan.ai.config.PluginSettings
import com.gigasan.ai.config.PluginSettingsConfigurable
import com.gigasan.ai.config.ProjectSpecificSettings
import com.gigasan.ai.config.SettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import javax.swing.SwingUtilities
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project) // это твоя панель с чатом
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(chatPanel, "", false)
        toolWindow.contentManager.addContent(content)

        // Изначальный заголовок
        updateToolWindowTitle(toolWindow)

        // Подписка на изменение модели
        val connection = project.messageBus.connect()
        connection.subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
            override fun settingsChanged() {
                SwingUtilities.invokeLater {
                    updateToolWindowTitle(toolWindow)
                }
            }
        })

        val settingsAction = object: AnAction("Settings", "Settings", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    e.project,
                    PluginSettingsConfigurable::class.java
                )
            }
        }

        val settings = PluginSettings()
        val actionList = buildList {
            if (settings.state.enableSettingsAction) {
                add(settingsAction)
            }
        }

        toolWindow.setTitleActions(actionList)
    }

    fun updateToolWindowTitle(toolWindow: ToolWindow) {
        val name = ProjectSpecificSettings.getInstance(toolWindow.project).state.selectedModelName
        toolWindow.title = if (name.isNotBlank()) "$name" else ""
    }
}