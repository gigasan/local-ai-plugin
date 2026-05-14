package com.gigasan.ai.actions

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.ui.MyIcons
import com.gigasan.ai.ui.TaskCompositorDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class TaskCompositorAction : AnAction("TaskCompositorDialog", "Open TaskCompositorDialog", MyIcons.Script) {
    private val logger = Logger.getInstance("TaskCompositorAction")
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = TaskCompositorDialog(project)
        dialog.showAndGet()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project

        // Условие видимости: проект открыт и плагин включен
        val isVisible = project != null && settings.state.enableTaskCompositor
        e.presentation.isVisible = isVisible

        if (!isVisible) return

        // Проверяем модель для активности кнопки
        val modelName = DefaultChatConfigProvider(project!!).buildEndpointSetting().selectedModelName
        e.presentation.isEnabled = modelName.isNotBlank()
        e.presentation.text = "Task Compositor"
        e.presentation.description = if (modelName.isBlank()) "Select a model first" else "Open compositor for $modelName"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}