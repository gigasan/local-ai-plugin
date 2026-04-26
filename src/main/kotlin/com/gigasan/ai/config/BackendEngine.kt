package com.gigasan.ai.config

enum class BackendEngine(val id: Int, val displayName: String) {

    // default, always enabled
    LM_STUDIO(0, "LM Studio"), // https://lmstudio.ai/docs/developer/rest/endpoints
    OLLAMA(1,    "Ollama"),    // https://docs.ollama.com/api/introduction
    OPEN_AI(2,   "Open AI"),   // https://developers.openai.com/api/docs
    CLAUDE(3,    "Claude");    // https://platform.claude.com/docs/en/api/overview

    // HOST
    val defaultHost: List<String>
        get() = when (this) {
            LM_STUDIO -> listOf("http://127.0.0.1:1234")
            OLLAMA    -> listOf("http://127.0.0.1:11434")
            OPEN_AI   -> listOf("https://api.openai.com")
            CLAUDE    -> listOf("https://api.anthropic.com")
        }

    // Web link
    val defaultLink: String
        get() = when (this) {
            LM_STUDIO -> "https://lmstudio.com/"
            OLLAMA    -> "https://ollama.com/"
            OPEN_AI   -> "https://openai.com"
            CLAUDE    -> "https://claude.com/"
        }

    override fun toString(): String = displayName   // ← самое важное для ComboBox

    companion object {
        fun fromId(id: Int) = entries.find { it.id == id } ?: LM_STUDIO
        fun default(): BackendEngine = LM_STUDIO
    }
}