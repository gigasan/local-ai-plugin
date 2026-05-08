package com.gigasan.ai.actions

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.ui.TaskCompositorDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger

class TaskCompositorAction : AnAction("TaskCompositorDialog", "Open TaskCompositorDialog", AllIcons.Actions.AddList) {
    private val logger = Logger.getInstance("TaskCompositorAction")
    private val settings = PluginSettingsService()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val lang = e.getData(CommonDataKeys.LANGUAGE)

        logger.info("lang=$lang")

        //val dir = project.baseDir?:return

        val dialog = TaskCompositorDialog(
            project,
            //dir,
            //onFileSelected = ::OnFileSelected,
            //onFileSelected = { file, data -> OnFileSelected(file, data) }
        )

        //dialog.setCode(selectedText)
        //dialog.setTask("Find errors in this code and suggest improvements.")

        dialog.showAndGet()
    }

//    fun OnFileSelected(myFile: VirtualFile, data: String): Unit {
//        logger.info("onFileSelected: $myFile")
//    }

    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val editor = e.getData(CommonDataKeys.EDITOR)
        val lang = e.getData(CommonDataKeys.LANGUAGE)
        val dynamicText = "TaskCompositorAction $editor $lang"
        e.presentation.setText(dynamicText)
        //editor?.document?.setText(lang.toString())
        //e.presentation.isEnabledAndVisible = editor?.selectionModel?.selectedText?.isNotBlank() == true

        val prov = DefaultChatConfigProvider(project)
        val model = prov.buildEndpointSetting().selectedModelName
        e.presentation.isEnabled = model.isNotBlank()
        e.presentation.isEnabledAndVisible = settings.state.enableTaskCompositor
    }


    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }


}