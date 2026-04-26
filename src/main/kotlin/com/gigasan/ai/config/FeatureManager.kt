package com.gigasan.ai.config

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.APP)
class FeatureManager(private val project: Project) {

    //private val settings get() = ProjectSpecificSettings.getInstance(project).state
    private val global = PluginSettings.instance.state
    private val local = ProjectSpecificSettings.getInstance(project).state

    fun isChatEnabled() = global.enableChat
    fun isAnalysisEnabled() = global.enableCodeAnalysis

    /**
     * Вызывается после изменения настроек, чтобы применить изменения в UI
     */
    fun applyFeatureChanges() {
        val toolWindowManager = ToolWindowManager.getInstance(project)

        // Управляем видимостью ToolWindow чата
        toolWindowManager.getToolWindow("AI Chat")?.let {
            it.isAvailable = global.enableChat
        }

        // Уведомляем систему, что экшены (кнопки в меню) нужно перерисовать
        com.intellij.openapi.actionSystem.impl.ActionToolbarImpl.updateAllToolbarsImmediately()
    }

    companion object {
        //fun getInstance(project: Project): FeatureManager = project.service()
        val instance: FeatureManager get() = service()
    }
}