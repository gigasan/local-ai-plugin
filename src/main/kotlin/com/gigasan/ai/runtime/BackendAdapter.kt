package com.gigasan.ai.runtime

import com.gigasan.ai.config.BackendApi
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.core.JsonFileLogger
import com.gigasan.ai.runtime.parser.ResponseParser
import com.gigasan.ai.runtime.parser.ResponseResult
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request

private val logger = Logger.getInstance("BackendAdapter")

data class ExecutionContext(
    val request: Request,
    val parser: (String) -> ResponseResult, // Для обычного режима
    val streamProcessorFactory: (StateManager, StateMachine) -> StreamResponseProcessor // Для стриминга
)

class BackendAdapter(private val project: Project): JsonFileLogger {
    private val provider = project.service<PluginConfigProvider>()

    val sseParser = SSEParser(project)
    val streamParser = StreamParser(project)
    val responseParser = ResponseParser(project)

    fun getContext(ctx: ChatContext, stateManager: StateManager, stateMachine: StateMachine): ExecutionContext {
        val baseUrl = provider.buildBaseUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()

        // Определяем, какой адаптер использовать
        val (request, parser) = when (val backend = provider.buildBackendEndpoint().api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" ->
                LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to responseParser::parse

            BackendApi.OLLAMA_API to "/api/chat" ->
                OllamaAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to responseParser::parse

            BackendApi.OPEN_AI_API to "/v1/chat/completions" ->
                ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to responseParser::parse

            BackendApi.OPEN_AI_API to "/v1/responses" ->
                ResponsesAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to responseParser::parse

            // Здесь можно добавить специфичный парсер, если OpenAI формат отличается
            else ->
                ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to responseParser::parse
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
        val baseUrl = provider.buildBaseUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackendEndpoint()

        logger.warn("BackendAdapter backend=$backend endpoint=$endpoint")

        return when (backend.api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" -> LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey)
            BackendApi.OLLAMA_API to "/api/chat" -> LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey)
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
        val baseUrl = provider.buildBaseUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackendEndpoint()

        // Создаем процессор по умолчанию (OpenAI style)
        val defaultProcessor = DefaultStreamProcessor(project, stateManager, stateMachine, sseParser, streamParser)

        return when (backend.api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" ->
                LmStudioAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            BackendApi.OLLAMA_API to "/api/chat" ->
                OllamaAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            BackendApi.OPEN_AI_API to "/v1/chat/completions" ->
                ChatCompletionsAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            BackendApi.OPEN_AI_API to "/v1/responses" ->
                ResponsesAdapter.toRequest(project, ctx, baseUrl + endpoint, apiKey) to defaultProcessor

            else -> DefaultAdapter.toRequest(project, ctx, baseUrl, apiKey) to defaultProcessor
        }
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
        val provider = project.service<PluginConfigProvider>()
        if (provider.buildDebugLogs() == true) {
            logger.info("DefaultAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        }
        saveJson(project, "request_DefaultAdapter", json.toString())
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
            ctx.stream.let { put("stream", it) }
            ctx.temperature.let { put("temperature", it) }
            ctx.maxTokens.let { put("max_tokens", it) }
        }
        val provider = project.service<PluginConfigProvider>()
        if (provider.buildDebugLogs() == true) {
            logger.info("ChatCompletionsAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        }
        saveJson(project, "request_ChatCompletionsAdapter", json.toString())
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
            //put("max_tokens", ctx.maxTokens)
            put("stream", ctx.stream)
        }
        val provider = project.service<PluginConfigProvider>()
        if (provider.buildDebugLogs() == true) {
            logger.info("ResponsesAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        }
        // Сохраняем запрос
        saveJson(project, "request_ResponsesAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
    }
}

object OllamaAdapter: JsonFileLogger {

    fun toRequest(project: Project, ctx: ChatContext, url: String, apiKey: String): Request {
        val json = buildJsonObject {
            put("model", ctx.model)

            putJsonArray("messages") {

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

                // messages.images string [Base64-encoded image content] Optional list of inline images for multimodal models

                // messages.tool_calls object[]
                // -> messages.tool_calls.function object
                // --> messages.tool_calls.function.name string required
                // --> messages.tool_calls.function.description string
                // --> messages.tool_calls.function.arguments object

            }

            putJsonObject("option") {
                put("temperature", ctx.temperature)
                put("num_predict", ctx.maxTokens)
                put("logprobs", true)
                put("top_logprobs", 0)

            }
            // tools object[]
            // format enum:string json

            put("stream", ctx.stream) // default true
            put("think", ctx.reasoning)
            put("keep_alive", "${ctx.keep_alive}m")


        }
        val provider = project.service<PluginConfigProvider>()
        if (provider.buildDebugLogs() == true) {
            logger.info("OllamaAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        }
        saveJson(project, "request_OllamaAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
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
            // depends on model capability reasoning otherwise error
            if (ctx.reasoning != null) {
                put("reasoning", ctx.reasoning)
            }

        }
        val provider = project.service<PluginConfigProvider>()
        if (provider.buildDebugLogs() == true) {
            logger.info("LmStudioAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        }
        saveJson(project, "request_LmStudioAdapter", json.toString())
        return json.toHttpRequest(url, apiKey)
    }
}
