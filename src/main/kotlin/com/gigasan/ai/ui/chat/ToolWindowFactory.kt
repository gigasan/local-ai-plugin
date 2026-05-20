package com.gigasan.ai.ui.chat

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.SettingsChangeListener
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

        val settingsAction = object : AnAction("Settings", "Settings", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: return
                val settingsUtil = ShowSettingsUtil.getInstance()
                val configurableClass = com.gigasan.ai.config.PluginSettingsConfigurable::class.java

                ApplicationManager.getApplication().invokeLater {
//                    if (com.intellij.util.PlatformUtils.isPyCharm()) {
//                        // Специфичный хак для PyCharm: открываем изолированное окно
//                        // Создаем инстанс вручную для editConfigurable
//                        val configurable = com.gigasan.ai.config.PluginSettingsConfigurable(project)
//                        settingsUtil.editConfigurable(project, configurable)
//                    } else {
                        // Для всех остальных IDE (IntelliJ IDEA, WebStorm и т.д.)
                        // используем стандартное общее окно настроек
                        settingsUtil.showSettingsDialog(project, configurableClass)
//                    }
                }
            }
        }

        val settings = PluginSettingsService.instance
        val actionList = buildList {
            if (settings.state.enableSettingsAction) {
                add(settingsAction)
            }
        }

        toolWindow.setTitleActions(actionList)
    }

    fun updateToolWindowTitle(toolWindow: ToolWindow) {
        val prov = DefaultChatConfigProvider(toolWindow.project)
        val model = prov.buildEndpointSetting().selectedModelName
        toolWindow.title = model.ifBlank { "" }
    }
}