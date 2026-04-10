package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger

private val LOG = Logger.getInstance("AITool")

data class AITool(
    val name: String,
    val description: String,
    val parameters: Map<String, String>
)

object ToolRegistry {

    private val tools = mutableMapOf<String, (Map<String, Any>) -> Any>()

    fun register(
        name: String,
        handler: (Map<String, Any>) -> Any
    ) {
        tools[name] = handler
    }

    fun execute(name: String, args: Map<String, Any>): Any? {
        return tools[name]?.invoke(args)
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