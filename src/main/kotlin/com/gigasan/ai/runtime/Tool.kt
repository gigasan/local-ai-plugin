package com.gigasan.ai.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val LOG = Logger.getInstance("Tool")

data class Tool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>
)

object ToolRegistry {

    //private val tools = mutableMapOf<String, (Map<String, Any>) -> Any>()
    private val tools = mutableMapOf<String, (Map<String, Any>) -> String>()

    //fun register(name: String, handler: (Map<String, Any>) -> Any) {
    //    tools[name] = handler
    //}
    fun register(name: String, handler: (Map<String, Any>) -> String) {
        tools[name] = handler
    }

    //fun execute(name: String, args: Map<String, Any>): Any? {
    //    return tools[name]?.invoke(args)
    //}
    fun execute(name: String, args: Map<String, Any>): String {
        return tools[name]?.invoke(args) ?: "Tool '$name' not found"
    }


    fun has(name: String): Boolean = tools.containsKey(name)
}

//ToolRegistry.register("createTask") { args ->
//    val title = args["title"] as? String ?: "Untitled"
//    val priority = args["priority"] as? String ?: "normal"
//
//    println("Creating task: $title ($priority)")
//
//    "Task created"
//}


class ToolOrchestrator(private val client: Client) {

    fun run(ctx: ChatContext): Result {

        val first = client.send(ctx)

        if (first.toolCalls.isEmpty()) {
            return first
        }

        val toolMessages = first.toolCalls.map { tool ->
            val result = ToolRegistry.execute(tool.name, tool.arguments)

            ChatMessage(
                role = "tool",
                content = result,
                toolName = tool.name
            )
        }

        val secondCtx = ctx.copy(messages = ctx.messages + toolMessages)

        return client.send(secondCtx)
    }
}


// tool chaining : User → AI → tool1 → AI → tool2 → AI → answer

// {
//  "action": "get_file_content",
//  "path": "src/render/renderer.rs"
//}


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
