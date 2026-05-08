package com.gigasan.ai.config.storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

// ========== Настройки промптов ==========
@State(name = "com.gigasan.ai.ui.InstructionsService", storages = [Storage("InstructionsService.xml")])
@Service(Service.Level.APP)
class InstructionsService : PersistentStateComponent<InstructionsService.State> {
    data class State(
        var instructions: MutableList<String> = mutableListOf(),
        var problems: MutableList<String> = mutableListOf(),
        var selectedInstruction: String = "",
        var enabledInstruction: Boolean = true,
        var selectedProblem: String = "",
        var enabledProblem: Boolean = true,

        var systemExpanded: Boolean = false,
        var inputExpanded: Boolean = false,
        var commonExpanded: Boolean = false,

        var stepIdMax: Int = 10,
        var stepId: Int = 0,
        var stepsList: MutableList<String?> = mutableListOf()
    )

    fun reset() {
        state.stepsList.fill(null)// = arrayOfNulls<String>(state.stepIdMax)
        state.stepId = 0
    }

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) {
        myState = state
    }

    fun settingsModified() {
        // Для современных версий IntelliJ
//        val component = this as? PersistentStateComponent<*> ?: return
//        component.state
//
        ApplicationManager.getApplication().saveSettings()
//        // Или просто:
//        XmlSerializerUtil.copyBean(state, getState()!!) // грубый способ
    }

    companion object {
        val instance: InstructionsService get() = service()
    }
}