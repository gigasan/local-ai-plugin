package com.gigasan.ai.config.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT) // Или .APP, если данные общие для всех проектов
@State(name = "TaskSequenceService", storages = [Storage("TaskSequenceService.xml")])
class TaskSequenceService : PersistentStateComponent<TaskSequence> {

    private var myState = TaskSequence()

    override fun getState(): TaskSequence = myState

    override fun loadState(state: TaskSequence) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): TaskSequenceService =
            project.service<TaskSequenceService>()
    }
}

// State класс — то, что именно будет записываться в XML
//@Tag("TaskSequence")
data class TaskSequence(
    var items: MutableList<WorkItem> = mutableListOf()
)

data class WorkItem(
    var name: String = "",
    var author: String = "",
    var version: String = "",
    var description: String = "",
    var iconType: String = "",
    var text: String = "",
)