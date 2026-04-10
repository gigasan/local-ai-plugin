package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val LOG = Logger.getInstance("AIRequest")

data class AIRequest(
    val model: String,
    val system: String? = null,
    val input: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val metadata: Map<String, String> = emptyMap()
)

class AIRequestBuilder {

    private var model: String = "gpt-4"
    private var system: String? = null
    private var input: String = ""

    private var temperature: Double? = null
    private var maxTokens: Int? = null

    private val metadata = mutableMapOf<String, String>()

    fun model(value: String) = apply { model = value }

    fun system(value: String) = apply { system = value }

    fun input(value: String) = apply { input = value }

    fun temperature(value: Double) = apply { temperature = value }

    fun maxTokens(value: Int) = apply { maxTokens = value }

    fun meta(key: String, value: String) = apply {
        metadata[key] = value
    }

    fun build(): AIRequest {
        return AIRequest(
            model = model,
            system = system,
            input = input,
            temperature = temperature,
            maxTokens = maxTokens,
            metadata = metadata
        )
    }


    fun AIRequest.toJson(): String {
        return kotlinx.serialization.json.buildJsonObject {

            put("model", model)
            put("input", input)

            system?.let { put("system", it) }

            temperature?.let { put("temperature", it) }
            maxTokens?.let { put("max_tokens", it) }

            putJsonArray("tools") {
                // преобразуем tools
            }

            metadata.forEach { (k, v) ->
                put("meta_$k", v)
            }
        }.toString()
    }



}

fun AIRequest.toHttpRequest(url: String, apiKey: String): Request {
    val json = buildJsonObject {

        put("model", model)

        val fullInput = buildString {
            system?.let { append("System: $it\n\n") }

            input?.let { append(it) }
        }

        put("input", fullInput)

        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
    }.toString()

    return Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $apiKey")
        .post(json.toRequestBody("application/json".toMediaType()))
        .build()
}

data class ChatMessage(
    val role: String,
    val content: String
)

class ChatRequestBuilder {

    private var model: String = "gpt-4"
    private var system: String? = null

    private val messages = mutableListOf<ChatMessage>()

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

    fun temperature(value: Double) = apply { temperature = value }

    fun maxTokens(value: Int) = apply { maxTokens = value }

    fun meta(key: String, value: String) = apply {
        metadata[key] = value
    }

    // -----------------------
    // BUILD → AIRequest
    // -----------------------

    fun build(task: TaskData? = null): AIRequest {

        val input = buildString {

            system?.let {
                append("System: $it\n\n")
            }

            // 🔥 memory injection
            if (memoryEnabled && task != null) {
                append(MemoryContextBuilder.build(task, memoryLimit))
                append("\n\n")
            }

            messages.forEach { msg ->
                append("${msg.role.uppercase()}: ${msg.content}\n")
            }
        }

        return AIRequestBuilder()
            .model(model)
            .input(input)
            .temperature(temperature ?: 0.7)
            .maxTokens(maxTokens ?: 1000)
            .apply {
                metadata.forEach { (k, v) ->
                    meta(k, v)
                }
            }
            // tools → позже в JSON
            .build()
    }
}

/*
* val request = ChatRequestBuilder()
    .system("You are an IntelliJ assistant")
    .user(task.content)
    .user("Context: ${task.job}")
    .model("gpt-5.4")
    .temperature(0.7)
    .meta("taskId", task.id)
    .build()
* */


/*

val request = ChatRequestBuilder()
    .system("You are IntelliJ assistant")
    .memory(limit = 5) // 🔥 вот оно
    .user(task.content)
    .model("gpt-5.4")
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