package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString


private val LOG = Logger.getInstance("AIParser")

// Common type
@Serializable
data class AIResult(
    val text: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val model: String? = null,
    val toolCalls: List<AIToolCall> = emptyList(),
    val raw: String,
    val durationMs: Long? = null
)

data class AIToolCall(
    val name: String,
    val arguments: Map<String, Any>
)

// OpenAI (responses API)
@Serializable
data class OpenAIResponse(
    val output: List<OutputItem>? = null,
    val usage: Usage? = null,
    val model: String? = null
)

@Serializable
data class OutputItem(
    val content: List<ContentItem>? = null
)

@Serializable
data class ContentItem(
    val text: String? = null
)

@Serializable
data class Usage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    val total_tokens: Int? = null
)


// OpenAI (chat.completions старый формат)
@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val model: String? = null
)

@Serializable
data class Choice(
    val message: AIMessageContent? = null
)

@Serializable
data class AIMessageContent(
    val content: String? = null
)

// Ollama / LocalAI
@Serializable
data class OllamaResponse(
    val response: String? = null,
    val message: OllamaMessage? = null,
    val model: String? = null
)

@Serializable
data class OllamaMessage(
    val content: String? = null
)

// Универсальный парсер (чистый и красивый)
object AIResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true // 🔥 критично
        isLenient = true
    }

    fun parse(raw: String): AIResult {
        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject

        return when {
            "output" in obj -> parseOpenAIResponses(raw)
            "choices" in obj -> parseChatCompletion(raw)
            "response" in obj || "message" in obj -> parseOllama(raw)
            else -> fallback(raw)
        }
    }

    // ------------------------

    private fun parseOpenAIResponses(raw: String): AIResult {
        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject

        logUnknownKeys(
            obj,
            knownKeys = setOf("output", "usage", "model", "id", "created"),
            context = "OpenAIResponses"
        )

        val parsed = json.decodeFromJsonElement<OpenAIResponse>(element)

        val text = parsed.output
            ?.firstOrNull()
            ?.content
            ?.firstOrNull()
            ?.text
            ?: ""

        return AIResult(
            text = text,
            inputTokens = parsed.usage?.input_tokens,
            outputTokens = parsed.usage?.output_tokens,
            totalTokens = parsed.usage?.total_tokens,
            model = parsed.model,
            raw = raw
        )
    }

    private fun parseChatCompletion(raw: String): AIResult {
        val parsed = json.decodeFromString<ChatCompletionResponse>(raw)

        val text = parsed.choices
            ?.firstOrNull()
            ?.message
            ?.content
            ?: ""

        return AIResult(
            text = text,
            inputTokens = parsed.usage?.input_tokens,
            outputTokens = parsed.usage?.output_tokens,
            totalTokens = parsed.usage?.total_tokens,
            model = parsed.model,
            raw = raw
        )
    }

    private fun parseOllama(raw: String): AIResult {
        val parsed = json.decodeFromString<OllamaResponse>(raw)

        val text = parsed.response
            ?: parsed.message?.content
            ?: ""

        return AIResult(
            text = text,
            model = parsed.model,
            raw = raw
        )
    }

    private fun fallback(raw: String): AIResult {
        return AIResult(
            text = raw,
            raw = raw
        )
    }

    fun logUnknownKeys(
        json: JsonObject,
        knownKeys: Set<String>,
        context: String = "AIResponse"
    ) {
        val unknown = json.keys - knownKeys

        if (unknown.isNotEmpty()) {
            //println("⚠️ [$context] Unknown keys: $unknown")
            LOG.warn("Unknown keys: $unknown")
        }
    }

}

@Serializable
data class ToolCallItem(
    val name: String,
    val arguments: JsonObject
)

@Serializable
data class ToolCallsResponse(
    val tool_calls: List<ToolCallItem> = emptyList()
)

//@Serializable
//data class ToolCallsWrapper(
//    val tool_calls: List<ToolCallItem> = emptyList()
//)

object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): List<AIToolCall> {

        val parsed = json.decodeFromString<ToolCallsResponse>(raw)
        val result = parsed.tool_calls.map {
            AIToolCall(
                name = it.name,
                arguments = it.arguments
            )
        }
        return result
    }

}

