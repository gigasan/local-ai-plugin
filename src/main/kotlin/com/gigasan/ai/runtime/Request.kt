package com.gigasan.ai.runtime

import com.gigasan.ai.config.BackendApi
import com.gigasan.ai.config.BackendEngine
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.core.JsonFileLogger
import com.gigasan.ai.ui.chat.TaskData
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.collections.plusAssign

private val logger = Logger.getInstance("Request")

data class ChatMessage(
    val role: String,
    val content: String,
    val toolName: String? = null
)

data class ChatContext(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.666f,
    val maxTokens: Int,
    val keep_alive: Int,
    val metadata: Map<String, String> = emptyMap(),
    val contextLen: Int,
    val stream: Boolean = false,
    val think: Boolean = false,
)

class ChatRequestBuilder(private val project: Project) {
    private val provider = project.service<PluginConfigProvider>()
    private var model: String = "gpt-4"
    private var system: String = ""
    private val messages = mutableListOf<ChatMessage>()
    private var stream: Boolean = false
    private var think: Boolean = false
    private var memoryEnabled: Boolean = false
    private var memoryLimit: Int = 5
    private val tools = mutableListOf<Tool>()

    fun tool(name: String, description: String, params: Map<String, String>) = apply {
        tools += Tool(name, description, params)
    }

    private var temperature: Float? = null
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

    fun temperature(value: Float) = apply {
        temperature = value
    }

    fun maxTokens(value: Int) = apply {
        maxTokens = value
    }

    fun reasoning(reasoning: Boolean) = apply {
        think = reasoning
    }


    fun meta(key: String, value: String) = apply {
        metadata[key] = value
    }

    fun build(task: TaskData? = null): ChatContext {
        return ChatContext(
            model = model,
            messages = messages,
            temperature = temperature?: provider.buildTemperature(),
            maxTokens = maxTokens ?: provider.buildMaxTokenLimit(),
            keep_alive = provider.buildKeepAlive(),
            metadata = metadata,
            system = system, // "Ты агент, говорящий только на русском языке и очень молчаливый. Отвечаешь очень и очень кратко"
            contextLen = provider.buildMaxTokenLimit(),
            stream = stream,
        )
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

