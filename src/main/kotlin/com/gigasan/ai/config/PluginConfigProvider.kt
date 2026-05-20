package com.gigasan.ai.config

import com.gigasan.ai.config.storage.EndpointSettings
import com.gigasan.ai.config.storage.InstructionsService
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.ModelCache
import com.gigasan.ai.config.storage.ModelCacheService
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

interface PluginConfigProvider {

    fun buildChatInstruction(): String
    fun buildSelectEntireLines(): Boolean
    fun buildUseSoftWrap(): Boolean
    fun buildInstruction(): String
    fun buildDebugLogs(): Boolean?

    // endpoint
    fun buildBackendEndpoint(): BackendEndpoint
    fun buildEndpointSetting(): EndpointSettings

    fun buildBaseUrl(): String
    fun buildModelListEndpoint(): String
    fun buildChatEndpoint(): String
    fun buildChatModel(): String
    fun buildApiKey(): String
    fun buildMaxTokenLimit(): Long
    fun buildKeepAlive(): Int
    fun buildTemperature(): Float
    fun buildLogprobs(): Boolean
    fun buildTopLogprobs(): Int
    fun buildMaxContext(): Long
    fun buildStream(): Boolean
    fun buildReasoning(): String?
    fun buildRequestBody(prompt: String): JsonObject
    fun buildExtraParams(prompt: String): JsonObject
    fun buildActiveModel(): Model?
}

@Service(Service.Level.PROJECT) // Без регистрации в plugin.xml!
class DefaultChatConfigProvider(private val project: Project): PluginConfigProvider {
    private val global = PluginSettingsService.instance
    private val local = ProjectSettingsService.getInstance(project).state

    private val defaultAllowedEndpoint = BackendEndpoint.LM_STUDIO_ENDPOINT

    override fun buildBackendEndpoint(): BackendEndpoint {
        val preferred = local.backendEndpoint
        return if (preferred.isAllowedIn(global)) preferred else defaultAllowedEndpoint
    }

    override fun buildEndpointSetting(): EndpointSettings {
        val preferred = local.backendEndpoint
        return if (preferred.isAllowedIn(global)) global.getSettingsFor(preferred)
        // Fallback to default settings for allowed backend, or LM Studio's settings as safe default
        else global.getSettingsFor(defaultAllowedEndpoint)
    }

    override fun buildChatInstruction(): String {
        return local.chatInstruction
    }
    override fun buildSelectEntireLines(): Boolean {
        return local.selectEntireLines
    }
    override fun buildUseSoftWrap(): Boolean {
        return local.useSoftWrap
    }
    override fun buildDebugLogs(): Boolean {
        return global.state.enableDebugLog
    }
    override fun buildInstruction(): String {
        return InstructionsService.instance.state.selectedInstruction
    }

    override fun buildBaseUrl(): String {
        return buildEndpointSetting().baseUrl.trim()
    }
    override fun buildModelListEndpoint(): String {
        return buildEndpointSetting().modelListEndpointUrl.trim()
    }
    override fun buildChatEndpoint(): String {
        return buildEndpointSetting().chatEndpointUrl.trim()
    }
    override fun buildApiKey(): String {
        return buildEndpointSetting().apiKey.trim()
    }
    override fun buildMaxContext(): Long {
        return buildEndpointSetting().maxContext
    }
    override fun buildMaxTokenLimit(): Long {
        return buildEndpointSetting().maxTokenLimit
    }
    override fun buildReasoning(): String? {
        val modelCache: ModelCache = ModelCacheService.instance.getSettingsFor(buildBackendEndpoint())
        val model = modelCache.models.find { model ->
            model.key == buildEndpointSetting().selectedModelKey
        }
        val reasoning = buildEndpointSetting().reasoning
        if (model != null) {
            if (model.reasoningOptions.contains(reasoning)) {
                return reasoning
            }
            return model.defaultReasoning
        }
        return null
    }
    override fun buildStream(): Boolean {
        return buildEndpointSetting().stream
    }
    override fun buildTemperature(): Float {
        return buildEndpointSetting().temperature
    }
    override fun buildLogprobs(): Boolean {
        return buildEndpointSetting().logprobs
    }
    override fun buildTopLogprobs(): Int {
        return buildEndpointSetting().top_logprobs
    }
    override fun buildKeepAlive(): Int {
        return buildEndpointSetting().keep_alive
    }
    override fun buildChatModel(): String {
        return buildEndpointSetting().selectedModelKey
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
    override fun buildActiveModel(): Model? {
        val settings = buildEndpointSetting()
        val model = ModelCacheService.instance.getSettingsFor(buildBackendEndpoint()).models.find { it.key == settings.selectedModelKey }
        return model
    }
}
