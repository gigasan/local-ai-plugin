package com.gigasan.ai.runtime.parser

import com.gigasan.ai.config.BackendApi
import com.gigasan.ai.core.JsonFileLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class ErrorCategory {
    AUTH,
    QUOTA,
    NOT_FOUND,
    RATE_LIMIT,
    INVALID_REQUEST,
    SERVER,
    UNKNOWN
}

inline fun ResponseResult.onSuccess(block: (ResponseResult.Success) -> Unit): ResponseResult {
    if (this is ResponseResult.Success) block(this)
    return this
}

inline fun ResponseResult.onError(block: (ResponseResult.Error) -> Unit): ResponseResult {
    if (this is ResponseResult.Error) block(this)
    return this
}

fun ResponseResult.withDuration(duration: Long): ResponseResult =
    when (this) {
        is ResponseResult.Success -> copy(durationMs = duration)
        is ResponseResult.Error -> copy(durationMs = duration)
    }

@Serializable
data class Usage(
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val tokens_per_second: Float? = null,
    val prompt_tokens_per_second: Float? = null,
    val time_to_first_token_seconds: Float? = null,
    val input_tokens_details: InputTokensDetails? = null,
    val output_tokens_details: OutputTokensDetails? = null,
)

@Serializable
data class InputTokensDetails(
    val cached_tokens: Int? = null,
)

@Serializable
data class OutputTokensDetails(
    val reasoning_tokens: Int? = null,
)


// Common type
sealed class ResponseResult {
    abstract val raw: String
    abstract val durationMs: Long?

    data class Success(
        val text: String,
        val usage: Usage? = null,
        val model: String? = null,
        val reasoning: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val response_id: String? = null,
        override val raw: String,
        override val durationMs: Long? = null,
    ) : ResponseResult()

    data class Error(
        val message: String,
        val category: ErrorCategory,
        val type: String? = null,
        val code: String? = null,
        val param: String? = null,
        override val raw: String,
        override val durationMs: Long? = null,
    ) : ResponseResult()

}

// ERRORS
@Serializable
data class ApiErrorResponse(
    val error: ApiError
)

@Serializable
data class ApiError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)

fun ApiError.toError(raw: String): ResponseResult {
    val category = when (type) {
        "insufficient_quota" -> ErrorCategory.QUOTA
        "not_found_error" -> ErrorCategory.NOT_FOUND
        "invalid_request_error" -> ErrorCategory.INVALID_REQUEST
        "invalid_request" -> ErrorCategory.INVALID_REQUEST
        "authentication_error" -> ErrorCategory.AUTH
        "rate_limit_error" -> ErrorCategory.RATE_LIMIT
        "server_error" -> ErrorCategory.SERVER
        else -> ErrorCategory.UNKNOWN
    }
    val errorParam = param?:""
    val errorCode = code?:""
    return ResponseResult.Error (
        message = "$message $errorParam $errorCode",
        category = category,
        type = type,
        code = code,
        param = param,
        raw = raw,
    )
}

fun fallback(raw: String): ResponseResult {
    return ResponseResult.Error(
        message = "Unknown response format",
        category = ErrorCategory.UNKNOWN,
        raw = raw
    )
}

private val logger = Logger.getInstance("ResponseParser")

public fun logUnknownKeys(
    json: JsonObject,
    knownKeys: Set<String>,
    context: String = "AIResponse"
) {
    val unknown = json.keys - knownKeys

    if (unknown.isNotEmpty()) {
        //println("⚠️ [$context] Unknown keys: $unknown")
        logger.warn("Unknown keys: $unknown")
    }
}


sealed class ResponseType {
    data object OpenAIResponses : ResponseType()
    data object OpenAIChat : ResponseType()
    data object Ollama : ResponseType()
    data object LmStudio : ResponseType()
    data object Error : ResponseType()
    data object Unknown : ResponseType()
}

fun classify(obj: JsonObject): ResponseType {
    obj["error"]?.let {
        if (it !is JsonNull) return ResponseType.Error
    }

    return when {
        obj.containsKey("output") && obj.containsKey("stats") ->
            ResponseType.LmStudio

        obj.containsKey("output") ->
            ResponseType.OpenAIResponses

        obj.containsKey("choices") ->
            ResponseType.OpenAIChat

        obj.containsKey("response") || obj.containsKey("message") ->
            ResponseType.Ollama

        else -> ResponseType.Unknown
    }
}

class ResponseParser(val project: Project,  backendApi: BackendApi): JsonFileLogger {

    val lmStudioParser by lazy { LmStudioParser(project) }
    val ollamaParser by lazy { OllamaParser(project) }
    val openAiParser by lazy { OpenAiParser(project) }

    val isInternal = com.intellij.openapi.application.ApplicationManager.getApplication().isInternal
    val json = Json {
        ignoreUnknownKeys = !isInternal
        coerceInputValues = !isInternal
        isLenient = !isInternal
    }

    fun parse(raw: String): ResponseResult {
        //logger.info("parse raw=${raw}")
        logger.info("parse raw.length=${raw.length}")
        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject
        val responseType = classify(obj)
        saveJson(project, "response_${responseType}_raw", raw)

        val result = when (responseType) {
            ResponseType.Error -> {
                logger.info("ResponseType.Error")
                try {
                    val errorResponse = json.decodeFromString<ApiErrorResponse>(raw)
                    val apiError = errorResponse.error
                    apiError.toError(raw)
                } catch (e: Exception) {
                    logger.warn("Error parse of JSON Error: ${e.message}", e)

                    // Фоллбэк на случай, если структура объекта слегка поплыла
                    ResponseResult.Error(
                        message = "Can't parse error object. Raw answer: $raw",
                        category = ErrorCategory.SERVER,
                        type = "parsing_error",
                        code = "unknown",
                        param = null,
                        raw = raw
                    )
                }
            }

            ResponseType.LmStudio -> {
                logger.info("ResponseType.LmStudio")
                lmStudioParser.parse(raw)
            }

            ResponseType.OpenAIResponses -> {
                logger.info("ResponseType.OpenAIResponses")
                openAiParser.parseResponses(raw)
            }

            ResponseType.OpenAIChat -> {
                logger.info("ResponseType.OpenAIChat")
                openAiParser.parseChatCompletion(raw)
            }

            ResponseType.Ollama -> {
                logger.info("ResponseType.Ollama")
                ollamaParser.parse(raw)
            }

            ResponseType.Unknown -> {
                logger.info("ResponseType.Unknown")
                fallback(raw)
            }
        }
        return result
    }
}
