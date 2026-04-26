package com.gigasan.ai.runtime

import com.gigasan.ai.config.BackendApi
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.core.JsonFileLogger
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request


private val logger = Logger.getInstance("BackendAdapter")


data class ExecutionContext(
    val request: Request,
    val parser: (String) -> Result, // Для обычного режима
    val streamProcessorFactory: (StateManager, StateMachine) -> StreamResponseProcessor // Для стриминга
)

class BackendAdapter(private val project: Project): JsonFileLogger {
    private val provider = project.service<PluginConfigProvider>()

    val sseParser = SSEParser(project)
    val streamParser = StreamParser(project)

    fun getContext(ctx: ChatContext, stateManager: StateManager, stateMachine: StateMachine): ExecutionContext {
        val baseUrl = provider.buildUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()

        // Определяем, какой адаптер использовать
        val (request, parser) = when (val backend = provider.buildBackend().api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" ->
                LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to AIResponseParser::parse

            BackendApi.OPEN_AI_API to "/v1/chat/completions" ->
                ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to AIResponseParser::parse

            BackendApi.OPEN_AI_API to "/v1/responses" ->
                ResponsesAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to AIResponseParser::parse

            // Здесь можно добавить специфичный парсер, если OpenAI формат отличается
            else ->
                ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to AIResponseParser::parse
        }

        return ExecutionContext(
            request = request,
            parser = parser,
            streamProcessorFactory = { mgr, machine ->
                // Возвращаем процессор. Если для LM Studio нужен другой — меняем логику здесь.
                DefaultStreamProcessor(project, mgr, machine, sseParser, streamParser)
            }
        )
    }

    fun toRequest(ctx: ChatContext): Request {
        val baseUrl = provider.buildUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackend()

        logger.warn("BackendAdapter backend=$backend endpoint=$endpoint")

        return when (backend.api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" -> LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey)
            BackendApi.OPEN_AI_API to "/v1/chat/completions" -> ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey)
            BackendApi.OPEN_AI_API to "/v1/responses" -> ResponsesAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey)
            else -> DefaultAdapter.toRequest(project, ctx, baseUrl, apiKey)
        }
    }

    fun toRequestWithProcessor(
        ctx: ChatContext,
        stateManager: StateManager,
        stateMachine: StateMachine
    ): Pair<Request, StreamResponseProcessor> {
        val baseUrl = provider.buildUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackend()

        // Создаем процессор по умолчанию (OpenAI style)
        val defaultProcessor = DefaultStreamProcessor(project, stateManager, stateMachine, sseParser, streamParser)

        return when (backend.api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" ->
                LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            BackendApi.OPEN_AI_API to "/v1/chat/completions" ->
                ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            BackendApi.OPEN_AI_API to "/v1/responses" ->
                ResponsesAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            else -> DefaultAdapter.toRequest(project, ctx, baseUrl, apiKey) to defaultProcessor
        }
    }
}




object LmStudioAdapter: JsonFileLogger {

    fun toRequest(project: Project, ctx: ChatContext, url: String, apiKey: String): Request {
        val json = buildJsonObject {
            put("model", ctx.model)
            put("system_prompt", ctx.system)
            if (ctx.messages.size == 1) {
                put("input", ctx.messages[0].content)
            } else if (ctx.messages.size > 1) {
                putJsonArray("input") {
                    ctx.messages.forEach { msg ->
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put("content", msg.content)
                            }
                        )
                    }
                }
            }
            put("temperature", ctx.temperature)
            put("max_output_tokens", ctx.maxTokens)
            put("context_length", ctx.contextLen)
            put("stream", ctx.stream)
        }
        logger.warn("LmStudioAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        saveJson(project, "request_LmStudioAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
    }

}

object ResponsesAdapter: JsonFileLogger {

    fun toRequest(project: Project, ctx: ChatContext, url: String, apiKey: String): Request {
        val json = buildJsonObject {
            put("model", ctx.model)
            putJsonArray("input") {
                // 👉 system message первым
                ctx.system.let { systemText ->
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemText)
                    })
                }
                ctx.messages.forEach {
                    add(buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    })
                }
            }
            put("temperature", ctx.temperature)
            put("max_tokens", ctx.maxTokens)
            put("stream", ctx.stream)
        }
        logger.warn("ResponsesAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        // Сохраняем запрос
        saveJson(project, "request_ResponsesAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
    }
}


object ChatCompletionsAdapter: JsonFileLogger {

    fun toRequest(project: Project, ctx: ChatContext, url: String, apiKey: String): Request {
        val json = buildJsonObject {
            put("model", ctx.model)

            putJsonArray("messages") {
                ctx.messages.forEach {
                    add(buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    })
                }
            }
            ctx.temperature.let { put("temperature", it) }
            ctx.maxTokens.let { put("max_tokens", it) }
        }
        logger.warn("ChatCompletionsAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        saveJson(project, "request_ChatCompletionsAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
    }
}


object DefaultAdapter: JsonFileLogger {

    fun toRequest(project: Project, ctx: ChatContext, url: String, apiKey: String): Request {
        val json = buildJsonObject {
            put("model", ctx.model)
            putJsonArray("messages") {
                ctx.messages.forEach {
                    add(buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    })
                }
            }
            ctx.temperature.let { put("temperature", it) }
            ctx.maxTokens.let { put("max_tokens", it) }
        }
        logger.warn("DefaultAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        saveJson(project, "request_DefaultAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
    }
}

