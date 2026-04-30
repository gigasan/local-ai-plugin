package com.gigasan.ai.runtime.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

    fun parse(raw: String): List<ToolCall> {

        val parsed = json.decodeFromString<ToolCallsResponse>(raw)
        val result = parsed.tool_calls.map {
            ToolCall(
                name = it.name,
                arguments = it.arguments
            )
        }
        return result
    }

}
