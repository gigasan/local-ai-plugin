package com.gigasan.ai.actions

import com.gigasan.ai.ui.RefactorDialog
import com.gigasan.ai.config.PluginSettings
import com.gigasan.ai.config.ProjectSpecificSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger

class RefactorAction: AnAction("Refactor", "Send/Find code block from project to refactor", AllIcons.Actions.ShowCode)  {
    private val logger = Logger.getInstance("RefactorAction")
    private val settings = PluginSettings()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText?.trim() ?: ""

        val dialog = RefactorDialog(project)
        dialog.setCode(selectedText)
        dialog.setTask("Find errors in this code and suggest improvements.")

        dialog.showAndGet()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val state = ProjectSpecificSettings.getInstance(project).state
        val model = state.selectedModelName
        val dynamicText = if (model.isNotBlank()) {
            "Refactor with Local AI ($model)"
        } else {
            "Refactor with Local AI (No model selected)"
        }
        e.presentation.setText(dynamicText)
        // Можно также выключать кнопку, если модель не выбрана
        e.presentation.isEnabled = model.isNotBlank()
        e.presentation.isEnabledAndVisible = settings.state.enableRefactoring
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}