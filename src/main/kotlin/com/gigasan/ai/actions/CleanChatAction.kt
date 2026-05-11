package com.gigasan.ai.actions

import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.ui.chat.ChatPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class CleanChatAction : AnAction("Clearing Chat", "Remove all chats", AllIcons.Actions.ClearCash) {
    private val logger = Logger.getInstance("CleanChatAction")
    private val settings = PluginSettingsService.instance

    override fun actionPerformed(e: AnActionEvent) {
        ChatPanel.instance?.cleanAllTasks()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = settings.state.enableCleanChat
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}