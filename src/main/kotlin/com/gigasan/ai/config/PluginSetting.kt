package com.gigasan.ai.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.PluginSettings", storages = [Storage("LocalAISettings.xml")])
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        // project modules
        var enableChat: Boolean = true,

        // toolbar actions
        var enableDebugFeature: Boolean = true,
        var enableFileTransfer: Boolean = true,
        var enableRefactoring: Boolean = false,
        var enableCodeAnalysis: Boolean = false,
        var enableCleanChat: Boolean = true,
        var enableAutoSearch: Boolean = true,
        var enableSettingsAction: Boolean = true,
        var enableDevToolsAction: Boolean = false,

        // backends
        var allowedBackendEndpoints: Set<BackendEndpoints> = setOf(
            BackendEndpoints.LM_STUDIO_ENDPOINT,
            BackendEndpoints.LM_STUDIO_OPENAI_ENDPOINT,
            //BackendEndpoints.LM_STUDIO_ANTHROPIC_ENDPOINT,
            BackendEndpoints.OLLAMA_ENDPOINT,
            BackendEndpoints.OLLAMA_OPENAI_ENDPOINT,
            //BackendEndpoints.OLLAMA_ANTHROPIC_ENDPOINT,
        ),
    )

    var myState = State() // Доступ к полям будет через settings.state.baseUrl

    override fun getState(): State = myState
    override fun loadState(state: State) { this.myState = state }

    // 3. Для удобства доступа из Configurable можно добавить публичное свойство
    // но с другим именем, либо использовать методы getState()
    val allSettings: State get() = myState

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyChange() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SettingsChangeListener.TOPIC)
            .settingsChanged()
        listeners.forEach { it() }
    }

    companion object {
        val instance: PluginSettings get() = service()
    }
}
