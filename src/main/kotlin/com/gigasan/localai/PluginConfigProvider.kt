package com.gigasan.localai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

interface PluginConfigProvider {
    fun buildChatUrl(): String
    fun buildApiKey(): String
    fun buildMaxTokenLimit(): Int
    fun buildBackend(): AIBackendType
    fun buildChatModel(): String
    fun buildRequestBody(prompt: String): JsonObject

}

class DefaultChatConfigProvider(
    private val settings: PluginSettings
) : PluginConfigProvider {

    /**
     * Формирует URL в зависимости от baseUrl и chatEndpointIndex.
     */
    override fun buildChatUrl(): String {
        return when (settings.chatEndpointIndex) {
            0 -> settings.baseUrl.trimEnd('/') + "/api/v1/chat"
            1 -> settings.baseUrl.trimEnd('/') + "/v1/responses"
            2 -> settings.baseUrl.trimEnd('/') + "/v1/chat/completions"
            else -> settings.baseUrl.trimEnd('/') + settings.chatEndpoint.trim()
        }
    }

    override fun buildApiKey(): String {
        return settings.apiKey.orEmpty()
    }

    override fun buildMaxTokenLimit(): Int {
        return settings.maxTokenLimit.or(8192)
    }

    override fun buildChatModel(): String {
        return settings.selectedModelKey.orEmpty()
    }

    override fun buildBackend(): AIBackendType {
        return when (settings.backendIndex) {
            0 -> AIBackendType.LmStudioLegacy
            1 -> AIBackendType.Responses
            else -> AIBackendType.ChatCompletions
        }
    }


    /**
     * Формирует тело запроса в зависимости от endpointIndex.
     * Логика requestBody вынесена сюда, чтобы не дублировать в двух функциях.
     */
    override fun buildRequestBody(prompt: String): JsonObject {
        val selectedModel = settings.selectedModelKey.ifBlank { "default" }

        return JsonObject().apply {
            when (settings.chatEndpointIndex) {
                0 -> { //api/v1/chat (LM Studio)
                    addProperty("model", selectedModel)
                    addProperty("input", prompt)
                }

                1 -> { // /v1/responses
                    addProperty("model", selectedModel)
                    addProperty("input", prompt)

                    val modalities = JsonArray()
                    modalities.add("text")
                    add("modalities", modalities)
                }

                else -> { // 2 -> /v1/chat/completions (OpenAI-совместимый)
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

            }
        }
    }

}
