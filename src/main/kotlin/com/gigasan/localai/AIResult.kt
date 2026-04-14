package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*


private val logger = Logger.getInstance("AIParser")

@Serializable
data class OutputTokensDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null,
)

@Serializable
data class Usage(
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    val reasoning_tokens: Int? = null,
    val tokens_per_second: Float? = null,
    val time_to_first_token_seconds: Float? = null,
    val output_tokens_details: OutputTokensDetails? = null,
)

// Common type
@Serializable
data class AIResult(
    val text: String,
    val usage: Usage? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val toolCalls: List<AIToolCall> = emptyList(),
    val raw: String,
    val durationMs: Long? = null,
    val response_id: String? = null,
)

@Serializable
data class AIToolCall(
    val name: String,
    //val arguments: Map<String, Any>
    //val arguments: JsonObject
    val arguments: Map<String, JsonElement>
)

// OpenAI (responses API)
@Serializable
data class OpenAIResponse(
    val output: List<OutputItem>? = null,
    val usage: Usage? = null,
    val model: String? = null,
    val id: String? = null,
    val previous_response_id: String? = null,
)

@Serializable
data class OutputItem(
    val content: List<ContentItem>? = null,
    val type: String? = null
)

@Serializable
data class ContentItem(
    val text: String? = null,
    val type: String? = null,
)

@Serializable
data class LMOutputItem(
    val type: String? = null,
    val content: String? = null
)

@Serializable
data class LMStats(
    val input_tokens: Int? = null,
    val total_output_tokens: Int? = null,
    val reasoning_output_tokens: Int? = null,
    val tokens_per_second: Float? = null,
    val time_to_first_token_seconds: Float? = null,
)

@Serializable
data class LMStudioResponse(
    val model_instance_id: String? = null,
    val output: List<LMOutputItem>? = null,
    val stats: LMStats? = null,
    val response_id: String? = null,
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
            "output" in obj && "stats" in obj -> parseLMStudioResponses(raw)
            "output" in obj -> parseOpenAIResponses(raw)
            "choices" in obj -> parseChatCompletion(raw)
            "response" in obj || "message" in obj -> parseOllama(raw)
            else -> fallback(raw)
        }
    }

    // ------------------------
    private fun parseLMStudioResponses(raw: String): AIResult {
        /*
        LM Studio
        2026-04-11 03:57:51  [INFO]
         [liquid/lfm2-1.2b@q8_0:2] Generated response:  {
          "model_instance_id": "liquid/lfm2-1.2b@q8_0:2",
          "output": [
            {
              "type": "message",
              "content": "Привет! Чем я могу вам помочь сегодня?"
            }
          ],
          "stats": {
            "input_tokens": 29,
            "total_output_tokens": 18,
            "reasoning_output_tokens": 0,
            "tokens_per_second": 155.6312576734856,
            "time_to_first_token_seconds": 0.067
          },
          "response_id": "resp_6eff46d679b8b387494d5c8efc4bf0f13a7b847ddd76966e"
        }
        */

        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject

        logUnknownKeys(
            obj,
            knownKeys = setOf("output", "stats", "model_instance_id", "response_id"),
            context = "LMStudioResponses"
        )

        val parsed = json.decodeFromJsonElement<LMStudioResponse>(element)
        logger.warn("USAGE RAW = ${parsed}")

        val reasoning = parsed.output
            ?.filter { it.type == "reasoning" }
            ?.joinToString("\n") { it.content ?: "" }

        val text = parsed.output
            ?.firstOrNull { it.type == "message" }
            ?.content
            ?: ""

        return AIResult(
            text = text,
            usage = Usage(
                inputTokens = parsed.stats?.input_tokens,
                outputTokens = parsed.stats?.total_output_tokens,
                reasoning_tokens = parsed.stats?.reasoning_output_tokens,
                time_to_first_token_seconds = parsed.stats?.time_to_first_token_seconds,
                tokens_per_second = parsed.stats?.tokens_per_second,
            ),
            model = parsed.model_instance_id,
            response_id = parsed.response_id,
            reasoning = reasoning,
            raw = raw
        )
    }

    private fun parseOpenAIResponses(raw: String): AIResult {
        /*

        2026-04-11 16:46:07,415 [ 180534]   WARN - ChatPanel - toolResult = AIResult(text=, usage=Usage(inputTokens=4, outputTokens=286, totalTokens=290, reasoning_tokens=null, tokens_per_second=null, time_to_first_token_seconds=null), model=deepseek/deepseek-r1-0528-qwen3-8b, reasoning=null, toolCalls=[], raw={
        "id": "resp_ff25d8688c963978c66bde115b31395cbd60265108722204",
        "object": "response",
        "created_at": 1775915152,
        "completed_at": 1775915167,
        "status": "completed",
        "incomplete_details": null,
        "model": "deepseek/deepseek-r1-0528-qwen3-8b",
        "previous_response_id": null,
        "instructions": null,
        "output": [
        {
          "id": "rs_r35c6n8qs9zwmc4odrbw",
          "type": "reasoning",
          "status": "completed",
          "summary": [],
          "content": [
            {
              "type": "reasoning_text",
              "text": "\nО, привет. Пользователь написал просто слово “привает”. Сразу вижу опечатку – должно быть, имел в виду “прият”. \n\nИнтересно, что именно он хотел сказать? Может, спрашивать о чем-то приятном или поздравлять с радостным событием. Или просто поприветствовать без конкретного содержания.\n\nСудя по краткости сообщения и опечатке, это может быть либо неформальное общение (чтобы быстро начать разговор), либо случайная ошибка при наборе текста. \n\nЛучше всего просто подтвердить получение сообщения и попросить уточнить вопрос – так дам шанс пользователю либо продолжить нормальный диалог, либо исправить ошибку в комфортном для него темпе.\n\nХорошо бы добавить немного тепла в ответ, чтобы передать доброжелательность. “Привет” с заглавной буквы и смайлик – это пойдет.\n"
            }
          ]
        },
        {
          "id": "msg_gq3ccpzbuxmb5mumwor8g",
          "type": "message",
          "role": "assistant",
          "status": "completed",
          "content": [
            {
              "type": "output_text",
              "text": "\nПривет! У меня всё отлично. 😊  \nЧем могу быть полезен? Напиши свою задачу или вопрос, и я постараюсь помочь тебе подробнее.",
              "annotations": [],
              "logprobs": []
            }
          ]
        }
        ],
        "error": null,
        "tools": [],
        "tool_choice": "auto",
        "truncation": "auto",
        "parallel_tool_calls": true,
        "text": {
        "format": {
          "type": "text"
        }
        },
        "top_p": 0.95,
        "presence_penalty": 0,
        "frequency_penalty": 1.1,
        "top_logprobs": 0,
        "temperature": 0.7,
        "reasoning": {
        "summary": null,
        "effort": null
        },
        "usage": {
        "input_tokens": 4,
        "output_tokens": 286,
        "total_tokens": 290,
        "input_tokens_details": {
          "cached_tokens": 0
        },
        "output_tokens_details": {
          "reasoning_tokens": 243
        }
        },
        "max_output_tokens": null,
        "max_tool_calls": null,
        "store": true,
        "background": false,
        "service_tier": "default",
        "metadata": {},
        "safety_identifier": null,
        "prompt_cache_key": null
        }, durationMs=null, response_id=null)

        */



        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject

        logUnknownKeys(
            obj,
            knownKeys = setOf("output", "usage", "model", "id", "created"),
            context = "OpenAIResponses"
        )

        val parsed = json.decodeFromJsonElement<OpenAIResponse>(element)
        logger.warn("USAGE RAW = ${parsed.usage}")

        val reasoning = parsed.output?.filter { it.type == "reasoning" }
            ?.flatMap { it.content ?: emptyList() }
            ?.filter { it.type == "reasoning_text" }
            ?.joinToString("") { it.text ?: "" }
            ?: ""

        val text = parsed.output?.filter { it.type == "message" }
            ?.flatMap { it.content ?: emptyList() }
            ?.filter { it.type == "output_text" }
            ?.joinToString("") { it.text ?: "" }
            ?: ""

        return AIResult(
            text = text,
            usage = Usage(
                inputTokens = parsed.usage?.inputTokens,
                outputTokens = parsed.usage?.outputTokens,
                totalTokens = parsed.usage?.totalTokens,
                reasoning_tokens = parsed.usage?.output_tokens_details?.reasoningTokens,
            ),
            model = parsed.model,
            response_id = parsed.id,
            reasoning = reasoning,
            raw = raw
        )

        /*

            return AIResult(
            text = text,
            usage = Usage(
                inputTokens = parsed.stats?.input_tokens,
                outputTokens = parsed.stats?.total_output_tokens,
                reasoning_tokens = parsed.stats?.reasoning_output_tokens,
                time_to_first_token_seconds = parsed.stats?.time_to_first_token_seconds,
                tokens_per_second = parsed.stats?.tokens_per_second,
            ),
            model = parsed.model_instance_id,
            response_id = parsed.response_id,
            reasoning = reasoning,
            raw = raw
        )

        */

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
            usage = parsed.usage,
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
            logger.warn("Unknown keys: $unknown")
        }
    }

}

@Serializable
data class ToolCallItem(
    val name: String,
    //val arguments: JsonObject
    val arguments: Map<String, JsonElement>
)

@Serializable
data class ToolCallsResponse(
    val tool_calls: List<ToolCallItem> = emptyList()
)

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

