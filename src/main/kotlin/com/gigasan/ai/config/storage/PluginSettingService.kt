package com.gigasan.ai.config.storage

import com.gigasan.ai.config.BackendEndpoint
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XMap

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.PluginSettingsService", storages = [Storage("PluginSettingsService.xml")])
@Service(Service.Level.APP)
class PluginSettingsService : PersistentStateComponent<PluginSettingsService.State> {
    private val logger = Logger.getInstance("PluginSettingsService")

    data class State(
        // base functions
        var enableChat: Boolean = true,
        var enableSettingsAction: Boolean = true,

        var enableDebugLog: Boolean = false,

        // toolbar actions
        var enableDebugFeature: Boolean = true,
        var enableTaskCompositor: Boolean = true,
        var enableCleanChat: Boolean = true,
        var enableAutoSearch: Boolean = true,
        var enableDevToolsAction: Boolean = false,

        // deprecated
        var enableFileTransfer: Boolean = false,
        var enableRefactoring: Boolean = false,
        var enableCodeAnalysis: Boolean = false,

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
    private val graph: PropertyGraph = PropertyGraph("PluginSettingsService")

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
        val instance: PluginSettingsService get() = service()
    }
}

@Tag("Endpoint")
data class EndpointSettings(

    // connection
    var baseUrl: String = "",
    var modelListEndpointUrl: String = "",
    var chatEndpointUrl: String = "",
    var apiKey: String = "",

    // model
    var selectedModelName: String = "",
    var selectedModelKey: String = "",
    //   V
    var system: String = "",
    var maxContext: Long = 16384,
    var maxTokenLimit: Long = 16000,
    var reasoning: String = "",
    var stream: Boolean = false,
    var temperature: Float = 0.7f,
    var logprobs: Boolean = false,
    var top_logprobs: Int = 0,
    var keep_alive: Int = 60,
)