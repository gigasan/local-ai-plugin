package com.gigasan.ai.runtime.parser

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

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
    val model_load_time_seconds: Float? = null,
)

@Serializable
data class LMStudioResponse(
    val model_instance_id: String? = null,
    val output: List<LMOutputItem>? = null,
    val stats: LMStats? = null,
    val response_id: String? = null,
)

class LmStudioParser(val project: Project) {
    private val logger = Logger.getInstance("LmStudioParser")
    private val json = Json

    fun parse(raw: String): ResponseResult {

        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject

        logUnknownKeys(
            obj,
            knownKeys = setOf("output", "stats", "model_instance_id", "response_id"),
            context = "LMStudioResponses"
        )

        val parsed = json.decodeFromJsonElement<LMStudioResponse>(element)
        //logger.warn("USAGE RAW = ${parsed}")

        val reasoning = parsed.output
            ?.filter { it.type == "reasoning" }
            ?.joinToString("\n") { it.content ?: "" }

        val text = parsed.output
            ?.firstOrNull { it.type == "message" }
            ?.content
            ?: ""

        return ResponseResult.Success(
            text = text,
            usage = Usage(
                inputTokens = parsed.stats?.input_tokens,
                outputTokens = parsed.stats?.total_output_tokens,
                reasoningTokens = parsed.stats?.reasoning_output_tokens,
                time_to_first_token_seconds = parsed.stats?.time_to_first_token_seconds,
                tokens_per_second = parsed.stats?.tokens_per_second,
            ),
            model = parsed.model_instance_id,
            response_id = parsed.response_id,
            reasoning = reasoning,
            raw = raw
        )
    }
}



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
