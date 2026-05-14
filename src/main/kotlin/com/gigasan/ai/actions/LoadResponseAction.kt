package com.gigasan.ai.actions

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.ui.MyIcons
import com.gigasan.ai.ui.chat.ChatPanel
import com.google.gson.reflect.TypeToken
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil

class LoadResponseAction : AnAction("LoadResponseAction", "LoadResponseAction", AllIcons.FileTypes.Json) {
    private val logger = Logger.getInstance("LoadResponseAction")
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import Response from JSON")

        val virtualFile = FileChooser.chooseFile(descriptor, e.project, null) ?: return

        try {
            val jsonString = FileUtil.loadFile(java.io.File(virtualFile.path))
            ChatPanel.instance?.renderResponseMessage(jsonString)
        } catch (e: Exception) {
            logger.error(e)
            Messages.showErrorDialog("Import failed: ${e}", "Error")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = settings.state.enableDebugFeature
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}