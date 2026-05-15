package com.gigasan.ai.runtime.parser

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Ollama
@Serializable
data class OllamaResponse(
    val model: String? = null,
    val created_at: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean? = null,
    val done_reason: String? = null,
    val total_duration: Long? = null,
    val load_duration: Long? = null,
    val prompt_eval_count: Int? = null,
    val prompt_eval_duration: Long? = null,
    val eval_count: Int? = null,
    val eval_duration: Long? = null,
    val logprobs: Logprobs? = null,
){
    /**
     * Скорость генерации токенов в секунду.
     * Возвращает 0.0, если данные отсутствуют или длительность равна 0.
     */
    val tokensPerSecond: Double
        get() {
            val count = eval_count ?: return 0.0
            val durationNs = eval_duration ?: return 0.0

            if (durationNs <= 0) return 0.0

            return count / (durationNs.toDouble() / 1e9)
        }
    val promptTokensPerSecond: Double
        // prompt_eval_count / (prompt_eval_duration / 1_000_000_000)
        get() {
            val count = prompt_eval_count ?: return 0.0
            val durationNs = prompt_eval_duration ?: return 0.0
            if (durationNs <= 0) return 0.0

            return count / (durationNs.toDouble() / 1e9)
        }
    val time_to_first_token_seconds: Double
        get() {
            val loadDuration = load_duration ?: return 0.0
            val promptDuration = prompt_eval_duration ?: return 0.0
            return (loadDuration + promptDuration) / 1e9
        }
}


@Serializable
data class OllamaMessage(
    val role: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    val tool_calls: List<ToolCalls>? = null,
    val images: List<String>? = null,
)

@Serializable
data class ToolCalls(
    val function: Function? = null,
)

@Serializable
data class Function(
    val name: String,
    val description: String? = null,
    val arguments: Arguments? = null,
)

@Serializable
data class Arguments(
    val value: String?=null,
)

@Serializable
data class Logprobs(
    val token: String? = null,
    val logprob: Float? = null,
    val bytes: List<Int>? = null,
    val top_logprobs: List<TopLogprobs>? = null,
)

@Serializable
data class TopLogprobs(
    val token: String? = null,
    val logprob: Float? = null,
    val bytes: List<Int>? = null,
)

class OllamaParser(val project: Project) {
    private val logger = Logger.getInstance("OllamaParser")
    private val json = Json

    fun parse(raw: String): ResponseResult {
        val parsed = try {
            json.decodeFromString<OllamaResponse>(raw)
        } catch (e: Exception) {
            logger.warn("JSON parse error: $e")
            return ResponseResult.Error("JSON parse error: ${e.message}", ErrorCategory.UNKNOWN, raw=raw)
        }

        // Если это финальный аккорд (done == true) или мы не используем стриминг
        val text = parsed.message?.content ?: ""
        val reasoningText = text.substringAfter("<think>", "").substringBefore("</think>", "")

        if ((parsed.done ?: false) == false) {
            logger.warn("Failed response from ${parsed.model} because ${parsed.done_reason}")
            return ResponseResult.Error(
                message = parsed.done_reason?:parsed.message?.content?:"unknown reason",
                category = ErrorCategory.UNKNOWN,
                raw = raw,
            )
        }

        val usage = Usage(
            inputTokens = parsed.prompt_eval_count,
            outputTokens = parsed.eval_count,
            reasoningTokens = 0,
            tokens_per_second = parsed.tokensPerSecond.toFloat(),
            prompt_tokens_per_second = parsed.promptTokensPerSecond.toFloat(),
            time_to_first_token_seconds = parsed.time_to_first_token_seconds.toFloat(),
        )

        return ResponseResult.Success(
            text = text,
            model = parsed.model,
            usage = usage,
            raw = raw
        )
    }
}