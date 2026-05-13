package com.gigasan.ai.actions

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.ui.MyIcons
import com.gigasan.ai.ui.chat.ChatPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class AskAction : AnAction("Ask", "Ask local AI", MyIcons.Balloon) {
    private val logger = Logger.getInstance("AskAction")
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        ChatPanel.instance?.sendExternalMessage("Привет. Напиши простую программу на python")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val prov = DefaultChatConfigProvider(project)
        val model = prov.buildEndpointSetting().selectedModelName
        val dynamicText = if (model.isNotBlank()) {
            "Ask Local AI ($model)"
        } else {
            "Ask Local AI (No model selected)"
        }
        e.presentation.setText(dynamicText)
        // Можно также выключать кнопку, если модель не выбрана
        e.presentation.isEnabled = model.isNotBlank()
        e.presentation.isEnabledAndVisible = settings.state.enableDebugFeature
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}