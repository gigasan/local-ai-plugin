package com.gigasan.ai.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.util.xmlb.annotations.XMap
import com.jetbrains.rd.platform.util.logger
import kotlin.properties.ObservableProperty

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.PluginSettings", storages = [Storage("PluginSettings.xml")])
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {
    private val logger = Logger.getInstance("PluginSettings")

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
        var allowedBackendEndpoints: Set<BackendEndpoint> = setOf(
            BackendEndpoint.LM_STUDIO_ENDPOINT,
            BackendEndpoint.LM_STUDIO_OPENAI_ENDPOINT,
            //BackendEndpoint.LM_STUDIO_ANTHROPIC_ENDPOINT,
            BackendEndpoint.OLLAMA_ENDPOINT,
            BackendEndpoint.OLLAMA_OPENAI_ENDPOINT,
            //BackendEndpoint.OLLAMA_ANTHROPIC_ENDPOINT,
            BackendEndpoint.OPEN_AI_ENDPOINT,
        ),
        // Хранилище настроек для каждого эндпоинта
        @XMap(
            entryTagName = "endpoint-settings",
            keyAttributeName = "backend",
            valueAttributeName = "settings"
        )
        var settingsMap: MutableMap<BackendEndpoint, EndpointSettings> = mutableMapOf(),

        )

    var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { this.myState = state }

    // Удобный метод для получения настроек
    fun getSettingsFor(endpoint: BackendEndpoint): EndpointSettings {
        return myState.settingsMap.getOrPut(endpoint) { EndpointSettings() }
    }

    // Вспомогательный граф
    private val graph: PropertyGraph = PropertyGraph("PluginSettings")

    // Создаём observable свойства по требованию
    fun getSystemProperty(endpoint: BackendEndpoint): GraphProperty<String> {
        val settings = myState.settingsMap.getOrPut(endpoint) { EndpointSettings() }
        return graph.property(settings.system)
            .also { prop ->
                prop.afterChange { newValue ->
                    settings.system = newValue
                }
            }
    }

//    var endpointSettings: Map<EndpointKey, EndpointSettings> = mutableMapOf(),
//    var modelCache: List<ModelCache> = mutableListOf(),


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
