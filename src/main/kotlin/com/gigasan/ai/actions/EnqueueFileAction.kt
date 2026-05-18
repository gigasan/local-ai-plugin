package com.gigasan.ai.actions

import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.TaskSequenceService
import com.gigasan.ai.config.storage.WorkItem
import com.gigasan.ai.ui.TaskCompositorDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore

class EnqueueFileAction : AnAction("Add File to Task Compositor", "Read file content and add to AI task", AllIcons.Actions.AddFile) {
    private val logger = Logger.getInstance("EnqueueFileAction")
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        // actionPerformed ВСЕГДА выполняется на EDT, здесь всё отлично и безопасно!
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (virtualFile.isDirectory || virtualFile.fileType.isBinary) {
            Messages.showErrorDialog(project, "This is a binary file or directory. Cannot read as text.", "Invalid File")
            return
        }

        try {
            val text = VfsUtilCore.loadText(virtualFile).trimIndent()
            val savedPlugins = TaskSequenceService.getInstance(project).state.items
            val newData = WorkItem(virtualFile.name, "ProjectView", "${savedPlugins.size}", "", "File", text)
            savedPlugins.add(newData)

            val dialog = TaskCompositorDialog(project, true)
            dialog.showAndGet()
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Error reading file: ${ex.message}", "Loading Error")
        }
    }

    override fun update(e: AnActionEvent) {
        // Теперь этот блок безопасно крутится на BGT (в фоне)
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val isAvailable = project != null &&
                virtualFile != null &&
                !virtualFile.isDirectory &&
                !virtualFile.fileType.isBinary &&
                settings.state.enableTaskCompositor

        e.presentation.isEnabledAndVisible = isAvailable

        if (isAvailable) {
            e.presentation.text = "Add '${virtualFile.name}' to Task"
        }
    }

    // ⚡ КРИТИЧЕСКИЙ ФИКС: Переключаем логику обновления UI в бэкграунд-поток
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}