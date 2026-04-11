package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger

private val LOG = Logger.getInstance("AITool")

data class AITool(
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


class ToolOrchestrator(private val aiClient: AIClient) {

    fun run(ctx: ChatContext): AIResult {

        val first = aiClient.send(ctx)

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

        return aiClient.send(secondCtx)
    }
}


// tool chaining : User → AI → tool1 → AI → tool2 → AI → answer