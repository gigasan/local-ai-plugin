package com.gigasan.ai.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.annotations.XMap

// 1. Указываем имя файла, в котором будут лежать настройки (в папке конфигов IDE)
@State(name = "com.gigasan.localai.config.ModelCacheService", storages = [Storage("ModelCacheService.xml")])
@Service(Service.Level.APP)
class ModelCacheService : PersistentStateComponent<ModelCacheService.State> {

    private val logger = Logger.getInstance("ModelCacheService")

    data class State(
        var ttlSec: Long = 5 * 60,
        // Хранилище списков моделей для каждого эндпоинта
        @XMap(
            entryTagName = "endpoint-models",
            keyAttributeName = "backend",
            valueAttributeName = "models"
        )
        var modelCache: MutableMap<BackendEndpoint, ModelCache> = mutableMapOf(),
    )

    var myState = State() // Доступ к полям будет через settings.state.baseUrl
    override fun getState(): ModelCacheService.State = myState
    override fun loadState(state: ModelCacheService.State) { this.myState = state }

    // Удобный метод для получения списка моделей
    fun getSettingsFor(endpoint: BackendEndpoint): ModelCache {
        return myState.modelCache.getOrPut(endpoint) { ModelCache() }
    }

    fun save(endpoint: BackendEndpoint, cache: ModelCache) {
        myState.modelCache[endpoint] = cache
    }

    fun isValid(cache: ModelCache, ttlMs: Long = myState.ttlSec * 1000L): Boolean {
        return System.currentTimeMillis() - cache.timestamp < ttlMs
    }


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
        val instance: ModelCacheService get() = service()
    }
}