package com.gigasan.ai.config

import com.gigasan.ai.config.BackendEngine.*
import com.gigasan.ai.config.BackendApi.*
import com.gigasan.ai.config.storage.PluginSettingsService


enum class BackendEndpoint(val engine: BackendEngine, val api: BackendApi) {

    LM_STUDIO_ENDPOINT(LM_STUDIO, LM_STUDIO_API),
    LM_STUDIO_OPENAI_ENDPOINT(LM_STUDIO, OPEN_AI_API),
    LM_STUDIO_ANTHROPIC_ENDPOINT(LM_STUDIO, CLAUDE_API),

    OLLAMA_ENDPOINT(OLLAMA, OLLAMA_API),
    OLLAMA_OPENAI_ENDPOINT(OLLAMA, OPEN_AI_API),
    OLLAMA_ANTHROPIC_ENDPOINT(OLLAMA, CLAUDE_API),

    OPEN_AI_ENDPOINT(OPEN_AI, OPEN_AI_API),
    CLAUDE_ENDPOINT(CLAUDE, CLAUDE_API);


//    var settings = EndpointSettings()
//        get() {}

    val displayName: String
    get() = when (this) {
        LM_STUDIO_ENDPOINT -> "LM STUDIO API Endpoint"
        LM_STUDIO_OPENAI_ENDPOINT -> "LM STUDIO OpenAI-compatible API Endpoint"
        LM_STUDIO_ANTHROPIC_ENDPOINT -> "LM STUDIO Anthropic-compatible API Endpoint"
        OLLAMA_ENDPOINT -> "OLLAMA API Endpoint"
        OLLAMA_OPENAI_ENDPOINT -> "OLLAMA OpenAI-compatible API Endpoint"
        OLLAMA_ANTHROPIC_ENDPOINT -> "OLLAMA Anthropic-compatible API Endpoint"
        OPEN_AI_ENDPOINT -> "OPEN AI API Endpoint"
        CLAUDE_ENDPOINT -> "ANTHROPIC API Endpoint"
    }
    override fun toString(): String = displayName   // ← самое важное для ComboBox

    val defaultResponses: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("/api/v1/chat")
            LM_STUDIO_OPENAI_ENDPOINT -> listOf("/v1/responses")
            LM_STUDIO_ANTHROPIC_ENDPOINT -> listOf("v1/messages")
            OLLAMA_ENDPOINT -> listOf("/api/chat")
            OLLAMA_OPENAI_ENDPOINT -> listOf("/v1/responses") // Only the non-stateful flavor is supported (i.e., there is no previous_response_id or conversation support).
            OLLAMA_ANTHROPIC_ENDPOINT -> listOf("/v1/messages")
            OPEN_AI_ENDPOINT -> listOf("/v1/responses")
            CLAUDE_ENDPOINT -> listOf("/v1/messages")
        }


    // EMBEDDINGS (POST) Creates vector embeddings representing the input text
    val defaultEmbedding: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("/api/v0/embeddings")
            LM_STUDIO_OPENAI_ENDPOINT -> listOf("/v1/embeddings")
            OLLAMA_ENDPOINT -> listOf("/api/embed")
            OLLAMA_OPENAI_ENDPOINT -> listOf("/v1/embeddings")
            OPEN_AI_ENDPOINT -> listOf("/v1/embeddings")
            else -> listOf("")
        }


    val defaultTokenizer: List<String>
        get() = when (this) {
            CLAUDE_ENDPOINT -> listOf("/v1/messages/count_tokens")
            else -> listOf("")
        }


    // MODEL LIST (GET)
    val defaultModelList: MutableList<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> mutableListOf("/api/v1/models")
            LM_STUDIO_OPENAI_ENDPOINT -> mutableListOf("/v1/models")
            LM_STUDIO_ANTHROPIC_ENDPOINT -> mutableListOf("/v1/models")
            OLLAMA_ENDPOINT -> mutableListOf("/api/tags") // https://docs.ollama.com/api/tags
            OLLAMA_OPENAI_ENDPOINT -> mutableListOf("/v1/models")
            OLLAMA_ANTHROPIC_ENDPOINT -> mutableListOf("/v1/models")
            OPEN_AI_ENDPOINT -> mutableListOf("/v1/models")
            CLAUDE_ENDPOINT -> mutableListOf("/v1/models")
        }

    // MODEL INFO (GET)
    val defaultModelInfo: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("/api/v0/models/{model}")
            OLLAMA_ENDPOINT -> listOf("/api/show")
            CLAUDE_ENDPOINT -> listOf("/v1/models/{model_id}")
            else -> listOf("")
        }

    // PROCESS LIST ()
    val defaultProcessList: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("")
            OLLAMA_ENDPOINT -> listOf("/api/ps")
            CLAUDE_ENDPOINT -> listOf("/v1/sessions")
            else -> listOf("")
        }

    // API INFO (GET)
    val defaultApiInfo: List<String>
        get() = when (this) {
            OLLAMA_ENDPOINT -> listOf("/api/version")
            else -> listOf("")
        }


    // ================= NON ACTUAL ===================

    // PROMPT (POST) Generates a response for the provided prompt
    val defaultPrompt: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("")
            OLLAMA_ENDPOINT -> listOf("/api/generate") // https://docs.ollama.com/api/generate
            //OPEN_AI_COMPATIBLE -> listOf("")
            //ANTHROPIC_COMPATIBLE -> listOf("")
            else -> listOf("")
        }

    // CHAT (POST) Generate the next chat message in a conversation between a user and an assistant.
    val defaultChat: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("/api/v1/chat")
            OLLAMA_ENDPOINT -> listOf("/api/chat") // https://docs.ollama.com/api/chat
            //OPEN_AI_COMPATIBLE -> listOf("/v1/responses")
            //ANTHROPIC_COMPATIBLE -> listOf("/v1/messages")
            else -> listOf("")
        }

    //  Text Completions (prompt -> completion)
    val defaultCompletion: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("/api/v0/completions")
            OLLAMA_ENDPOINT -> listOf("")
            //OPEN_AI_COMPATIBLE -> listOf("/v1/completions")
            //ANTHROPIC_COMPATIBLE -> listOf("")
            else -> listOf("")
        }

    // Chat Completions (messages -> assistant response)
    val defaultChatCompletion: List<String>
        get() = when (this) {
            LM_STUDIO_ENDPOINT -> listOf("/api/v0/chat/completions")
            OLLAMA_ENDPOINT -> listOf("")
            //OPEN_AI_COMPATIBLE -> listOf("/v1/chat/completions")
            //ANTHROPIC_COMPATIBLE -> listOf("")
            else -> listOf("")
        }

    companion object {
        fun getFor(backend: BackendEngine, api: BackendApi) = values().filter { it.engine == backend && it.api == api }
        fun fromId(engineId: Int, apiId: Int) = BackendEndpoint.entries.find { it.engine.id == engineId && it.api.id == apiId }
    }
}

// Extension property or method inside BackendEndpoint.kt:
fun BackendEndpoint.isAllowedIn(globalState: PluginSettingsService): Boolean =
    globalState.getState().allowedBackendEndpoints.contains(this)