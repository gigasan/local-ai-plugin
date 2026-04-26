package com.gigasan.ai.config

import com.gigasan.ai.config.BackendEngine.*

enum class BackendFeatures(val backend: BackendEngine, val displayName: String) {
    // https://lmstudio.ai/docs/developer/rest/endpoints
    STREAM(LM_STUDIO, "Streaming"), // stream: true
    STATEFUL_CHAT(LM_STUDIO, "Stateful Chats"); // "store": false


    // https://docs.ollama.com/api/introduction


    // Stateful Chats: "response_id", "previous_response_id", "store": false
    // https://lmstudio.ai/docs/developer/rest/stateful-chats

    // Stream

    val default: String
        get() = when (this) {
            STREAM -> STREAM.displayName
            STATEFUL_CHAT -> STATEFUL_CHAT.displayName
        }

    override fun toString(): String = displayName   // ← самое важное для ComboBox

    companion object {
        //fun fromId(id: Int) = BackendEngine.entries.find { it.id == id } ?: LM_STUDIO
        //fun default(): BackendEngine = LM_STUDIO
    }
}