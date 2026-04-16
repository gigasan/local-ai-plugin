package com.gigasan.localai

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class RefactorAction: AnAction("Refactor", "Send/Find code block from project to refactor", com.intellij.icons.AllIcons.Actions.ShowCode)  {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText?.trim() ?: ""

        // 👇 Здесь показываем диалог
        val dialog = RefactorDialog(project)
        dialog.setCode(selectedText)
        dialog.setTask(e.presentation.text)
        if (dialog.showAndGet()) {
            val finalTask = dialog.getTask()
            val codeToRefactor = dialog.getModifiedCode()
            // Отправляем в TaskManager
            ChatPanel.instance?.sendExternalMessage(selectedText)
        }
    }

    override fun update(e: AnActionEvent) {
        val model = PluginSettings.instance.selectedModelName
        val dynamicText = "Refactor with Local AI ($model)"
        e.presentation.setText(dynamicText)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
