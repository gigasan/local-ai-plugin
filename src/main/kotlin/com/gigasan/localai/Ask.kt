package com.gigasan.localai

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class Ask : AnAction("Ask", "Ask local AI", com.intellij.icons.AllIcons.General.Balloon) {

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