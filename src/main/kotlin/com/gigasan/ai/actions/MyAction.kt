package com.gigasan.ai.actions

import com.gigasan.ai.ui.chat.ChatPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class MyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText ?: return

        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Local AI Chat")
        toolWindow?.show()

        //ChatPanel.instance?.addUserMessage(selection)
        ChatPanel.instance?.sendExternalMessage(selection)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.selectedText?.isNotBlank() == true
    }

}