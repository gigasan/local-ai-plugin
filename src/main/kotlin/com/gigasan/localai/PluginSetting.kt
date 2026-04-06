package com.gigasan.localai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(
    name = "com.gigasan.localai.PluginSettings",
    storages = [Storage("LocalAISettings.xml")]
)
@Service(Service.Level.APP) // Для современных версий платформы
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    private val listeners = mutableListOf<() -> Unit>()

    // 2. Создаем вложенный класс для хранения данных
    // Все поля здесь должны быть var, чтобы сериализатор мог их записать
    data class State(
        var selectedModelKey: String = "",
        var selectedModelName: String = "",
        var baseUrl: String = "http://127.0.0.1:11434",  // общий базовый адрес // 11434 - ollama, 1234 - LM studio
        var chatEndpointIndex: Int = 1, // 0 = /api/v1/chat, 1 = /v1/chat/completions, 2 = /v1/responses, 3 = custom.
        var chatEndpoint: String = "",
        var modelListEndpointIndex: Int = 1, // 0 = /api/v1/models, 1 = /v1/models, 2 = custom
        var modelListEndpoint: String = "",
    )

    private var myState = State()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyChange() {
        listeners.forEach { it() }
    }

    // 3. Реализуем методы интерфейса
    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // Удобные геттеры/сеттеры для остального кода
    var selectedModelKey: String
        get() = myState.selectedModelKey
        set(value) { myState.selectedModelKey = value }

    var selectedModelName: String
        get() = myState.selectedModelName
        set(value) { myState.selectedModelName = value }

    var baseUrl: String
        get() = myState.baseUrl
        set(value) { myState.baseUrl = value }

    var chatEndpointIndex: Int
        get() = myState.chatEndpointIndex
        set(value) { myState.chatEndpointIndex = value }

    var chatEndpoint: String
        get() = myState.chatEndpoint
        set(value) { myState.chatEndpoint = value }

    var modelListEndpointIndex: Int
        get() = myState.modelListEndpointIndex
        set(value) { myState.modelListEndpointIndex = value }

    var modelListEndpoint: String
        get() = myState.modelListEndpoint
        set(value) { myState.modelListEndpoint = value }

    companion object {
        val instance: PluginSettings
            get() = ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
