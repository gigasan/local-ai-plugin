package com.gigasan.ai.runtime.parser



import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.*


@Serializable
data class ToolCall(
    val name: String,
    //val arguments: Map<String, Any>
    //val arguments: JsonObject
    val arguments: Map<String, JsonElement>
)


@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiError
)

@Serializable
data class OpenAiError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)

// OpenAI (responses API)
@Serializable
data class OpenAiResponse(
    val id: String? = null,
    @SerialName("object")
    val objectType: String? = null,
    val created_at: Long? = null,
    val completed_at: Long? = null,
    val status: String? = null,
    val incomplete_details: IncompleteDetails? = null,
    val model: String? = null,
    val previous_response_id: String? = null,
    val instructions: String? = null,
    val output: List<OutputItem>? = null,
    val usage: Usage? = null,
    val error: String? = null,
    val tools: List<ToolCall>? = null,
    val tool_choice: String? = null,
    val truncation: String? = null,
    val parallel_tool_calls: Boolean? = null,
    val text: TextFormat? = null, // non-useful field
    val top_p: Float? = null,
    val presence_penalty: Float? = null,
    val frequency_penalty: Float? = null,
    val top_logprobs: Long? = null,
    val temperature: Float? = null,
    val reasoning: Reasoning? = null,
    val max_output_tokens: Int? = null,
    val max_tool_calls: Int? = null,
    val store: Boolean? = null,
    val background: Boolean? = null,
    val service_tier: String? = null,
    val metadata: Metadata? = null,
    val safety_identifier: String? = null,
    val prompt_cache_key: String? = null,


)

//@Serializable
//sealed class ApiResult {
//    data class Success(val data: OpenAiResponse) : ApiResult()
//    data class Error(val error: OpenAiError) : ApiResult()
//}

@Serializable
data class Metadata(
    val value: String? = null,
)

@Serializable
data class Reasoning(
    val summary: String? = null,
    val effort: String? = null,
)


@Serializable
data class TextFormat(
    val format: FormatType? = null
)

@Serializable
data class FormatType(
    val type: String? = null
)


@Serializable
data class IncompleteDetails(
    val reason: String? = null
)

@Serializable
data class OutputItem(
    val id: String? = null,
    val content: List<ContentItem>? = null,
    val type: String? = null,
    val role: String? = null,
    val status: String? = null,
)

@Serializable
data class ContentItem(
    val text: String? = null,
    val type: String? = null,
    val annotations: ArrayList<String>? = null,
    val logprobs: ArrayList<String>? = null,
)


class OpenAiParser(val project: Project) {
    private val logger = Logger.getInstance("OpenAiParser")
    val isInternal = com.intellij.openapi.application.ApplicationManager.getApplication().isInternal
    val json = Json {
        ignoreUnknownKeys = !isInternal
        coerceInputValues = !isInternal
        isLenient = !isInternal
    }

    fun parseResponses(raw: String): ResponseResult {

        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject

        logUnknownKeys(
            obj,
            knownKeys = setOf("output", "usage", "model", "id", "created_at"),
            context = "OpenAIResponses"
        )

        val parsed = json.decodeFromJsonElement<OpenAiResponse>(element)
        //logger.warn("USAGE RAW = ${parsed.usage}")

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

        return ResponseResult.Success(
            text = text,
            usage = Usage(
                inputTokens = parsed.usage?.inputTokens,
                outputTokens = parsed.usage?.outputTokens,
                totalTokens = parsed.usage?.totalTokens,
                reasoningTokens = parsed.usage?.output_tokens_details?.reasoning_tokens,
            ),
            model = parsed.model,
            response_id = parsed.id,
            reasoning = reasoning,
            raw = raw
        )

    }

    fun parseChatCompletion(raw: String): ResponseResult {
        val logger = Logger.getInstance("ChatCompletionParser")
        @Serializable
        data class AIMessageContent(
            val content: String? = null
        )
        @Serializable
        data class Choice(
            val message: AIMessageContent? = null
        )
        // OpenAI (chat.completions старый формат)
        @Serializable
        data class ChatCompletionResponse(
            val choices: List<Choice>? = null,
            val usage: Usage? = null,
            val model: String? = null
        )

        val parsed = json.decodeFromString<ChatCompletionResponse>(raw)

        val text = parsed.choices
            ?.firstOrNull()
            ?.message
            ?.content
            ?: ""

        return ResponseResult.Success(
            text = text,
            usage = parsed.usage,
            model = parsed.model,
            raw = raw
        )
    }

}


/*

    return ResponseResult(
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


/* OpenAI
2026-04-11 16:46:07,415 [ 180534]   WARN - ChatPanel - toolResult = ResponseResult(text=, usage=Usage(inputTokens=4, outputTokens=286, totalTokens=290, reasoning_tokens=null, tokens_per_second=null, time_to_first_token_seconds=null), model=deepseek/deepseek-r1-0528-qwen3-8b, reasoning=null, toolCalls=[], raw={
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

