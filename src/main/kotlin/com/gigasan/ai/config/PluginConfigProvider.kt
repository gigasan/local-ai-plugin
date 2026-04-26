package com.gigasan.ai.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

interface PluginConfigProvider {
    fun buildChatEndpoint(): String
    fun buildUrl(): String
    //fun buildChatUrl(): String
    fun buildApiKey(): String
    fun buildMaxTokenLimit(): Int
    fun buildBackend(): BackendEndpoints
    fun buildChatModel(): String
    fun buildStream(): Boolean
    fun buildSystem(): String
    fun buildRequestBody(prompt: String): JsonObject
    fun buildExtraParams(prompt: String): JsonObject
}

@Service(Service.Level.PROJECT) // Без регистрации в plugin.xml!
class DefaultChatConfigProvider(private val project: Project): PluginConfigProvider {
    private val global = PluginSettings.instance.state
    private val local = ProjectSpecificSettings.getInstance(project).state

    /**
     * Формирует URL в зависимости от baseUrl и chatEndpointIndex.
     */
    override fun buildChatEndpoint(): String {
        return local.chatEndpointUrl.trim()
    }

    override fun buildUrl(): String {
        return local.baseUrl.trim()
    }

//    override fun buildChatUrl(): String {
//        return local.baseUrl.trim() + local.chatEndpointUrl.trim()
//    }

    override fun buildApiKey(): String {
        return local.apiKey.orEmpty()
    }

    override fun buildMaxTokenLimit(): Int {
        return local.maxTokenLimit.or(8192)
    }

    override fun buildChatModel(): String {
        return local.selectedModelKey.orEmpty()
    }

    override fun buildStream(): Boolean {
        return local.stream.or(false)
    }

    override fun buildSystem(): String {
        return local.system.orEmpty()
    }

    override fun buildBackend(): BackendEndpoints {
        // 1. Декодируем то, что сохранено в проекте
        val preferred = BackendEndpoints.fromId(local.backendEngineId, local.backendApiId)?:BackendEndpoints.LM_STUDIO_ENDPOINT

        // 2. Проверяем, разрешен ли этот тип глобально
        val isAllowed = when (preferred) {
            BackendEndpoints.LM_STUDIO_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.LM_STUDIO_OPENAI_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.LM_STUDIO_ANTHROPIC_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.OLLAMA_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.OLLAMA_OPENAI_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.OLLAMA_ANTHROPIC_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.OPEN_AI_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            BackendEndpoints.CLAUDE_ENDPOINT -> global.allowedBackendEndpoints.contains(preferred)
            else -> false
        }

        // 3. Если разрешен — отдаем, если нет — отдаем безопасный дефолт
        return if (isAllowed) preferred else BackendEndpoints.LM_STUDIO_ENDPOINT
    }


    /**
     * Формирует тело запроса в зависимости от endpointIndex.
     * Логика requestBody вынесена сюда, чтобы не дублировать в двух функциях.
     */
    override fun buildRequestBody(prompt: String): JsonObject {
        val selectedModel = local.selectedModelKey.ifBlank { "default" }

        return JsonObject().apply {
            when (local.backendEngineId) {
                BackendEngine.LM_STUDIO.id -> { //api/v1/chat (LM Studio)
                    addProperty("model", selectedModel)
                    addProperty("input", prompt)
                }

                BackendEngine.OPEN_AI.id ->
                    when (local.chatEndpointUrl) {
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
