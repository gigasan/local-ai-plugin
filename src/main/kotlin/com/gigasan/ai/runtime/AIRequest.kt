package com.gigasan.ai.runtime

import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.PluginSettings
import com.gigasan.ai.ui.chat.TaskData
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import okhttp3.Request
import kotlin.collections.plusAssign

private val logger = Logger.getInstance("AIRequest")

data class ChatMessage(
    val role: String,
    val content: String,
    val toolName: String? = null
)

private val provider = DefaultChatConfigProvider(PluginSettings.instance)

data class ChatContext(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = provider.buildMaxTokenLimit(),
    val metadata: Map<String, String> = emptyMap(),
    val contextLen: Int = provider.buildMaxTokenLimit(),
    val stream: Boolean = false,
)

class ChatRequestBuilder {

    private var model: String = "gpt-4"
    private var system: String? = null

    private val messages = mutableListOf<ChatMessage>()

    private var stream: Boolean = false

    private var memoryEnabled: Boolean = false
    private var memoryLimit: Int = 5

    private val tools = mutableListOf<AITool>()

    fun tool(name: String, description: String, params: Map<String, String>) = apply {
        tools += AITool(name, description, params)
    }

    private var temperature: Double? = null
    private var maxTokens: Int? = null

    private val metadata = mutableMapOf<String, String>()

    // -----------------------
    // CHAT API STYLE
    // -----------------------

    fun system(text: String) = apply {
        system = text
    }

    fun user(text: String) = apply {
        messages += ChatMessage("user", text)
    }

    fun stream(value: Boolean) = apply {
        stream = value
    }

    fun assistant(text: String) = apply {
        messages += ChatMessage("assistant", text)
    }

    fun model(value: String) = apply {
        model = value
    }

    // 🔥 включение памяти
    fun memory(limit: Int = 5) = apply {
        memoryEnabled = true
        memoryLimit = limit
    }

    fun temperature(value: Double) = apply {
        temperature = value
    }

    fun maxTokens(value: Int) = apply {
        maxTokens = value
    }

    fun meta(key: String, value: String) = apply {
        metadata[key] = value
    }



    fun build(task: TaskData? = null): ChatContext {

        val provider = DefaultChatConfigProvider(PluginSettings.instance)

        return ChatContext(
            model = model,
            messages = messages,
            temperature = temperature ?: 0.7,
            maxTokens = maxTokens ?: provider.buildMaxTokenLimit(),
            metadata = metadata,
            system = "Ты агент, говорящий только на русском языке",
            contextLen = provider.buildMaxTokenLimit(),
            stream = stream,
        )
    }
}





object LmStudioAdapter {

    /*
    model : string
    input : string | array<object>
                    Input text : string
                    Input object : object
                        Text Input (optional) : object
                            type : "message"
                            content : string
                        Image Input (optional) : object
                            type : "image"
                            data_url : string
    system_prompt (optional) : string
    integrations (optional) : array<string | object>
    stream (optional) : boolean
    temperature (optional) : number
    top_p (optional) : number
    top_k (optional) : integer
    min_p (optional) : number
    repeat_penalty (optional) : number
    max_output_tokens (optional) : integer
    reasoning (optional) : "off" | "low" | "medium" | "high" | "on"
    context_length (optional) : integer
    store (optional) : boolean
    previous_response_id (optional) : string
    */


    fun toRequest(ctx: ChatContext, url: String, apiKey: String): Request {

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
        return json.toHttpRequest(url, apiKey)
    }

}

object ResponsesAdapter {

    fun toRequest(ctx: ChatContext, url: String, apiKey: String): Request {

        val json = buildJsonObject {
            put("model", ctx.model)

            putJsonArray("input") {
                ctx.messages.forEach {
                    add(buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    })
                }
            }
            put("temperature", ctx.temperature)
            put("max_tokens", ctx.maxTokens)
        }
        logger.warn("ResponsesAdapter FINAL JSON = $json") // 🔥 ОБЯЗАТЕЛЬНО
        return json.toHttpRequest(url, apiKey)
    }
}


object ChatCompletionsAdapter {

    fun toRequest(ctx: ChatContext, url: String, apiKey: String): Request {

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
        return json.toHttpRequest(url, apiKey)
    }
}


class BackendAdapter {

    fun toRequest(ctx: ChatContext): Request {

        val provider = DefaultChatConfigProvider(PluginSettings.instance)

        val url = provider.buildChatUrl()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackend()

        return when (backend) {

            AIBackendType.LmStudioLegacy ->
                LmStudioAdapter.toRequest(ctx, url, apiKey)

            AIBackendType.Responses ->
                ResponsesAdapter.toRequest(ctx, url, apiKey)

            AIBackendType.ChatCompletions ->
                ChatCompletionsAdapter.toRequest(ctx, url, apiKey)
        }
    }
}


/* using

val request = ChatRequestBuilder()
    .system("You are IntelliJ assistant")
    .memory(limit = 5)
    .user(task.content)
    .user("Context: ${task.job}")
    .model("gpt-5.4")
    .temperature(0.7)
    .meta("taskId", task.id)
    .build(task)

val updatedTask = processTask(task)

MemorySystem.add(updatedTask)

*/


// core
object MemorySystem {

    private val tasks = mutableListOf<TaskData>()

    fun add(task: TaskData) {
        tasks += task
    }

    fun all(): List<TaskData> = tasks

    fun last(n: Int = 10): List<TaskData> {
        return tasks.takeLast(n)
    }

    fun clear() {
        tasks.clear()
    }
}

// 👉 превращает память в текст для AI
object MemoryContextBuilder {

    fun build(task: TaskData, limit: Int = 5): String {
        val history = MemorySystem
            .last(limit)
            .filter { it.answer.isNotBlank() }

        if (history.isEmpty()) return ""

        return buildString {
            append("Previous context:\n")

            history.forEach { t ->
                append("- User: ${t.content}\n")
                append("  Assistant: ${t.answer}\n")
            }

            append("\nCurrent task:\n")
            append(task.content)
        }
    }
}