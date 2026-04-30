package com.gigasan.ai.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

interface PluginConfigProvider {
    fun buildBackend(): BackendEndpoint
    fun buildEndpointSetting(): EndpointSettings
    fun buildUrl(): String
    fun buildChatEndpoint(): String
    fun buildChatModel(): String

    fun buildApiKey(): String
    fun buildMaxTokenLimit(): Int
    fun buildKeepAlive(): Int
    fun buildTemperature(): Float
    fun buildMaxContext(): Int
    fun buildStream(): Boolean
    fun buildSystem(): String
    fun buildChatSystem(): String
    fun buildRequestBody(prompt: String): JsonObject
    fun buildExtraParams(prompt: String): JsonObject
}

@Service(Service.Level.PROJECT) // Без регистрации в plugin.xml!
class DefaultChatConfigProvider(private val project: Project): PluginConfigProvider {
    private val global = PluginSettings.instance
    private val local = ProjectSettings.getInstance(project).state

    /**
     * Формирует URL в зависимости от baseUrl и chatEndpointIndex.
     */
    override fun buildChatEndpoint(): String {
        return buildEndpointSetting().chatEndpointUrl.trim()
    }

    override fun buildUrl(): String {
        return buildEndpointSetting().baseUrl.trim()
    }

    override fun buildApiKey(): String {
        return buildEndpointSetting().apiKey.trim()
    }

    override fun buildMaxTokenLimit(): Int {
        return buildEndpointSetting().maxTokenLimit.or(4096)
    }

    override fun buildKeepAlive(): Int {
        return buildEndpointSetting().keep_alive.or(5)
    }

    override fun buildTemperature(): Float {
        return buildEndpointSetting().temperature
    }

    override fun buildMaxContext(): Int {
        return buildEndpointSetting().maxContext.or(16384)
    }

    override fun buildChatModel(): String {
        return buildEndpointSetting().selectedModelKey
    }

    override fun buildStream(): Boolean {
        return buildEndpointSetting().stream.or(false)
    }

    override fun buildSystem(): String {
        return buildEndpointSetting().system
    }

    override fun buildChatSystem(): String {
        return local.chatSystemPrompt
    }

    override fun buildBackend(): BackendEndpoint {
        // 1. Декодируем то, что сохранено в проекте
        //val preferred = BackendEndpoint.fromId(local.backendEngineId, local.backendApiId)?:BackendEndpoint.LM_STUDIO_ENDPOINT
        val preferred = local.backendEndpoint
        // 2. Проверяем, разрешен ли этот тип глобально
        val isAllowed = when (preferred) {
            BackendEndpoint.LM_STUDIO_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.LM_STUDIO_OPENAI_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.LM_STUDIO_ANTHROPIC_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OLLAMA_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OLLAMA_OPENAI_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OLLAMA_ANTHROPIC_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OPEN_AI_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.CLAUDE_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            else -> false
        }

        // 3. Если разрешен — отдаем, если нет — отдаем безопасный дефолт
        return if (isAllowed) preferred else BackendEndpoint.LM_STUDIO_ENDPOINT
    }

    override fun buildEndpointSetting(): EndpointSettings {
        // 1. Декодируем то, что сохранено в проекте
        //val preferred = BackendEndpoint.fromId(local.backendEngineId, local.backendApiId)?:BackendEndpoint.LM_STUDIO_ENDPOINT
        val preferred = local.backendEndpoint

        // 2. Проверяем, разрешен ли этот тип глобально
        val isAllowed = when (preferred) {
            BackendEndpoint.LM_STUDIO_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.LM_STUDIO_OPENAI_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.LM_STUDIO_ANTHROPIC_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OLLAMA_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OLLAMA_OPENAI_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OLLAMA_ANTHROPIC_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.OPEN_AI_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            BackendEndpoint.CLAUDE_ENDPOINT -> global.state.allowedBackendEndpoints.contains(preferred)
            else -> false
        }

        // 3. Если разрешен — отдаем, если нет — отдаем безопасный д
         return if (isAllowed) global.getSettingsFor(local.backendEndpoint) else global.getSettingsFor(BackendEndpoint.LM_STUDIO_ENDPOINT)
        //return if (isAllowed) preferred else BackendEndpoint.LM_STUDIO_ENDPOINT
    }
    /**
     * Формирует тело запроса в зависимости от endpointIndex.
     * Логика requestBody вынесена сюда, чтобы не дублировать в двух функциях.
     */
    override fun buildRequestBody(prompt: String): JsonObject {
        val selectedModel = buildEndpointSetting().selectedModelKey.ifBlank { "default" }

        return JsonObject().apply {
            when (local.backendEndpoint.engine.id) {
                BackendEngine.LM_STUDIO.id -> { //api/v1/chat (LM Studio)
                    addProperty("model", selectedModel)
                    addProperty("input", prompt)
                }

                BackendEngine.OPEN_AI.id ->
                    when (buildEndpointSetting().chatEndpointUrl) {
                        "/v1/responses" -> {
                            addProperty("model", selectedModel)
                            addProperty("input", prompt)

                            val modalities = JsonArray()
                            modalities.add("text")
                            add("modalities", modalities)
                        }
                        "/v1/chat/completions" -> {
                            addProperty("model", selectedModel)
                            addProperty("stream", false)
                            addProperty("max_tokens", buildMaxTokenLimit())
                            addProperty("return_reasoning", false)
                            addProperty("temperature", 0.0)
                            val messages = JsonArray()
                            messages.add(
                                JsonObject().apply {
                                    addProperty("role", "user")
                                    addProperty("content", prompt)
                                }
                            )
                            add("messages", messages)
                        }
                        "/v1/completions" -> {}
                        "/v1/embeddings" -> {}
                    }
            }
        }
    }

    // dummy method
    override fun buildExtraParams(prompt: String): JsonObject {
        val body = JsonObject()
        // Основные поля
        body.addProperty("prompt", prompt)

        // Добавляем все специфичные поля из карты
        local.extraParameters.forEach { (key, value) ->
            body.addProperty(key, value)
        }
        return body
    }

}
