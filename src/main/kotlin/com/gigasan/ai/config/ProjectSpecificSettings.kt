package com.gigasan.ai.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.*

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.ProjectSpecificSettings", storages = [Storage("LocalAIProjectSpecificSettings.xml")])
@Service(Service.Level.PROJECT)
class ProjectSpecificSettings(private val project: Project) : PersistentStateComponent<ProjectSpecificSettings.State> {
    //private val project: com.intellij.openapi.project.Project // Передайте его в конструктор, если нужно

    data class State(
        // connection
        var backendEngineId: Int = 0,
        var backendApiId: Int = 0,
        var baseUrl: String = "http://127.0.0.1:1234",
        var modelListEndpointUrl: String = "",
        var chatEndpointUrl: String = "",
        var apiKey: String = "",

        // model
        var selectedModelKey: String = "",
        var selectedModelName: String = "",
        var maxTokenLimit: Int = 128000,
        var extraParameters: MutableMap<String, String> = mutableMapOf(),

        // Карта для хранения любых доп. параметров: "temperature" -> "0.7", "top_p" -> "0.9"
        var customParams: MutableMap<String, String> = mutableMapOf(),
        var stream: Boolean = false,
        var system: String = "",
    )

    var myState = State() // Доступ к полям будет через settings.state.baseUrl

    override fun getState(): State = myState
    override fun loadState(state: State) { this.myState = state }

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyChange(project: Project) {
        // Отправляем уведомление только в рамках текущего проекта
        project.messageBus
            .syncPublisher(SettingsChangeListener.TOPIC)
            .settingsChanged()

        listeners.forEach { it() }
    }

    companion object {
        // Для проектного сервиса обязательно нужно передавать экземпляр проекта
        fun getInstance(project: Project): ProjectSpecificSettings = project.service()
    }
}