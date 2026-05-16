package com.gigasan.ai.actions

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.ui.TaskCompositorDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.gigasan.ai.config.storage.WorkItem
import com.gigasan.ai.config.storage.TaskSequenceService


class EnqueueAction : AnAction("Enqueue the Task", "Send selected text into AI task", AllIcons.Actions.AddToDictionary) {
    private val logger = Logger.getInstance("EnqueueAction")
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val savedPlugins = TaskSequenceService.getInstance(project).state.items
        val editor = e.getData(CommonDataKeys.EDITOR)

        // Если вы добавили новый плагин в список:
        fun addNewPlugin(data: WorkItem) {
            savedPlugins.add(data)
            // IDE сама сохранит файл при выходе или сохранении проекта
        }

        if (editor?.selectionModel?.hasSelection() == true && editor.document.textLength > 0) {

            var selText = editor.selectionModel.selectedText
            val selStart = editor.selectionModel.selectionStart
            val selEnd = editor.selectionModel.selectionEnd

            // Получаем номера строк (индексация начинается с 0)
            val startLine = editor.document.getLineNumber(selStart)
            val endLine = editor.document.getLineNumber(selEnd)

            // выбирать строки целиком
            if (ProjectSettingsService.getInstance(project).state.selectEntireLines) {
                val lineStartOffset = editor.document.getLineStartOffset(startLine)
                val lineEndOffset = editor.document.getLineEndOffset(endLine)

                selText = editor.document.getText(TextRange(lineStartOffset, lineEndOffset))
            }

            // Если нужно для пользователя (индексация с 1)
            val userFriendlyStart = startLine + 1
            val userFriendlyEnd = endLine + 1

            // filename
            val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val fileName = virtualFile?.name
            addNewPlugin(
                WorkItem(
                    "${fileName}:(${userFriendlyStart}-${userFriendlyEnd})",
                    "EnqueueAction",
                    "${savedPlugins.size}",
                    "",
                    "Text",
                    selText?.trimIndent() ?: ""
                )
            )
        }
        val dialog = TaskCompositorDialog(project, true)
        dialog.showAndGet()
    }


    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val state = ProjectSettingsService.getInstance(project).state
        val prov = DefaultChatConfigProvider(project)
        val model = prov.buildEndpointSetting().selectedModelName
        val key = prov.buildEndpointSetting().selectedModelKey
        logger.info("Enqueue action started endpoint=${state.backendEndpoint} key=$key model=$model")
        val dynamicText = if (model.isNotBlank()) {
            "Enqueue the Task ($model)"
        } else {
            "Enqueue the Task (No model selected)"
        }
        e.presentation.setText(dynamicText)
        // Можно также выключать кнопку, если модель не выбрана
        e.presentation.isEnabled = model.isNotBlank()
        e.presentation.isEnabledAndVisible = settings.state.enableTaskCompositor
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}