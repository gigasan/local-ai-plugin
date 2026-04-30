package com.gigasan.ai.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.*

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.ProjectSettings", storages = [Storage("ProjectSettings.xml")])
@Service(Service.Level.PROJECT)
class ProjectSettings(private val project: Project) : PersistentStateComponent<ProjectSettings.State> {
    //private val project: com.intellij.openapi.project.Project // Передайте его в конструктор, если нужно

    data class State (
        // connection
        var backendEndpoint: BackendEndpoint = BackendEndpoint.LM_STUDIO_ENDPOINT,

        // panel states
        var connectionExpanded: Boolean = true,
        var modelSelectionExpanded: Boolean = true,
        var chatExpanded: Boolean = true,
        var promptsExpanded: Boolean = true,

        // chat system prompt
        var chatSystemPrompt: String = "",

        // Карта для хранения любых доп. параметров: "temperature" -> "0.7", "top_p" -> "0.9"
        var extraParameters: MutableMap<String, String> = mutableMapOf(),
        var customParams: MutableMap<String, String> = mutableMapOf(),

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
        fun getInstance(project: Project): ProjectSettings = project.service()
    }
}