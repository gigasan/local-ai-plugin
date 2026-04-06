package com.gigasan.localai

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class Ask : AnAction("Open chat") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Chat")
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText
        toolWindow?.show()
        toolWindow?.activate {
            if (!selectedText.isNullOrBlank()) {
                ChatPanel.instance?.sendExternalMessage(selectedText)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val model = PluginSettings.instance.selectedModelName
        val dynamicText = "Ask Local AI ($model)"
        e.getPresentation().setText(dynamicText)
    }

}