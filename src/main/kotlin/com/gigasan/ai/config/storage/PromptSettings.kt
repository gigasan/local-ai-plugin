package com.gigasan.ai.config.storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

// ========== Настройки промптов ==========
@State(name = "com.gigasan.ai.ui.PromptSettings", storages = [Storage("PromptSettings.xml")])
@Service(Service.Level.APP)
class PromptSettings : PersistentStateComponent<PromptSettings.State> {
    data class State(
        var systems: MutableList<String> = mutableListOf(),
        var prompts: MutableList<String> = mutableListOf(),
        var selectedSystem: String = "",
        var selectedPrompt: String = "",

        var systemExpanded: Boolean = false,
        var inputExpanded: Boolean = false,
        var commonExpanded: Boolean = false,

        var stepIdMax: Int = 10,
        var stepId: Int = 0,
        var stepsList: Array<String?> = arrayOfNulls<String>(stepIdMax),
        //var stepsList: MutableList<String?> = mutableListOf()
    )
    {
        // Важно для data-классов с массивами
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            if (!stepsList.contentEquals(other.stepsList)) return false
            if (stepId != other.stepId) return false
            return true
        }

        override fun hashCode(): Int {
            var result = stepsList.contentHashCode()
            result = 31 * result + stepId
            return result
        }
    }


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
        val instance: PromptSettings get() = service()
    }
}