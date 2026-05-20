package com.gigasan.ai.actions

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.ui.showModelInfoDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class ModelInfoAction : AnAction("Model Info", "Get selected Model info", AllIcons.General.Information) {
    private val logger = Logger.getInstance(ModelInfoAction::class.java)
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val prov = DefaultChatConfigProvider(project)
        val model = prov.buildActiveModel() ?: return
        showModelInfoDialog(model)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val prov = DefaultChatConfigProvider(project)
        val model = prov.buildEndpointSetting().selectedModelName
        val dynamicText = if (model.isNotBlank()) {
            "Get selected Model info ($model)"
        } else {
            "Get selected Model info (No model selected)"
        }
        e.presentation.setText(dynamicText)
        // Можно также выключать кнопку, если модель не выбрана
        e.presentation.isEnabled = model.isNotBlank()
        e.presentation.isEnabledAndVisible = settings.state.enableModelInfo
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}