package com.gigasan.ai.config.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

// State класс — то, что именно будет записываться в XML
class MyPluginState {
    var plugins: MutableList<MyPluginData> = mutableListOf()
}

@Service(Service.Level.PROJECT) // Или .APP, если данные общие для всех проектов
@State(
    name = "MyPluginSettings",
    storages = [Storage("MyPluginSettings.xml")]
)
class MyPluginSettingsService : PersistentStateComponent<MyPluginState> {

    private var myState = MyPluginState()

    override fun getState(): MyPluginState = myState

    override fun loadState(state: MyPluginState) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): MyPluginSettingsService =
            project.service<MyPluginSettingsService>()
    }
}

data class MyPluginData(
    var name: String = "",
    var author: String = "",
    var version: String = "",
    var description: String = "",
    var iconType: String = "",
    var text: String = "",
)