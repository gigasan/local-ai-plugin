package com.gigasan.ai.config

import com.gigasan.ai.config.BackendEngine.LM_STUDIO

enum class BackendApi(val id: Int, val displayName: String) {

    // default, always enabled
    LM_STUDIO_API   (0, "LM Studio API"),
    OLLAMA_API      (1, "Ollama API"),
    OPEN_AI_API     (2, "OpenAI API"),
    CLAUDE_API      (3, "Anthropic API"),
    OPEN_ROUTER_API (4, "Open Router API");



    // HOST
//    val defaultHost: String
//        get() = when (this) {
//            LM_STUDIO -> "http://127.0.0.1:1234"
//            OLLAMA    -> "http://127.0.0.1:11434"
//            OPEN_AI   -> "https://api.openai.com"
//            CLAUDE    -> "https://api.anthropic.com"
//        }
//
//    // Web link
//    val defaultLink: String
//        get() = when (this) {
//            LM_STUDIO -> "https://lmstudio.com/"
//            OLLAMA    -> "https://ollama.com/"
//            OPEN_AI   -> "https://openai.com"
//            CLAUDE    -> "https://claude.com/"
//        }

    override fun toString(): String = displayName   // ← самое важное для ComboBox

    companion object {
        fun fromId(id: Int) = BackendApi.entries.find { it.id == id } ?: LM_STUDIO_API
        //fun default(): BackendEngine = LM_STUDIO
    }
}