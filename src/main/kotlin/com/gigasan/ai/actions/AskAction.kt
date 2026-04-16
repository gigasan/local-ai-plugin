package com.gigasan.ai.actions

import com.gigasan.ai.config.PluginSettings
import com.gigasan.ai.ui.chat.ChatPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class AskAction : AnAction("Ask", "Ask local AI", AllIcons.General.Balloon) {

    override fun actionPerformed(e: AnActionEvent) {
        ChatPanel.instance?.sendExternalMessage("Привет. Напиши простую программу на python")
    }

    override fun update(e: AnActionEvent) {
        val model = PluginSettings.instance.selectedModelName
        val dynamicText = "Ask Local AI ($model)"
        e.presentation.setText(dynamicText)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}