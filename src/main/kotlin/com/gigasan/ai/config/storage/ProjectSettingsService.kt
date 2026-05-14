package com.gigasan.ai.config.storage

import com.gigasan.ai.config.BackendEndpoint
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.ProjectSettingsService", storages = [Storage("ProjectSettingsService.xml")])
@Service(Service.Level.PROJECT)
class ProjectSettingsService(private val project: Project) : PersistentStateComponent<ProjectSettingsService.State> {
    private val logger = Logger.getInstance("ProjectSettingsService")

    data class State (
        // connection
        var backendEndpoint: BackendEndpoint = BackendEndpoint.LM_STUDIO_ENDPOINT,

        // task compositor and chat
        var chatInstruction: String = "",
        var selectEntireLines: Boolean = true,
        var useSoftWrap: Boolean = true,
        var closeAfterSent: Boolean = true,
        var fontName: String = "JetBrains Mono",
        var fontSize: Int = 13,

        // panel states
        var connectionExpanded: Boolean = true,
        var modelSelectionExpanded: Boolean = true,
        var instructionSetExpanded: Boolean = true,
        var taskCompositorExpanded: Boolean = true,
        var chatExpanded: Boolean = true,
        var advancedExpanded: Boolean = true,

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
        project.messageBus
            .syncPublisher(SettingsChangeListener.TOPIC)
            .settingsChanged()
        listeners.forEach { it() }
    }

    companion object {
        // Для проектного сервиса обязательно нужно передавать экземпляр проекта
        fun getInstance(project: Project): ProjectSettingsService = project.service()
    }
}