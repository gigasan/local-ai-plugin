package com.gigasan.ai.config

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.Source

fun String?.orUnknown() = this ?: "unknown"

class ModelParser(project: Project) {

    private val logger = Logger.getInstance("ModelParser")

    // DTO под LM Studio
    @Serializable
    data class LmStudioResponse(
        val models: List<LmStudioModel>
    )

    @Serializable
    data class LmStudioModel(
        val key: String,
        val display_name: String,
        val size_bytes: Long,
        val format: String? = null,
        val architecture: String? = null,
        val publisher: String? = null,
        val params_string: String? = null,
        val max_context_length: Long? = null,
        val quantization: Quantization? = null,
        val capabilities: Capabilities? = null,
        val type: String? = null, // .filter { it.type == "llm" }
        val description: String? = null,
        val loaded_instances: List<Instances>? = null,
        val variants: List<String>? = null,
        val selected_variant: String? = null,
    )

    @Serializable
    data class Instances(
        val id: String? = null,
        val config: Config? = null,
        val remaining_ttl_seconds: Int? = null,
    )

    @Serializable
    data class Config(
        val context_length: Long? = null,
        val eval_batch_size: Int? = null,
        val parallel: Int? = null,
        val flash_attention: Boolean? = null,
        val num_experts: Int? = null,
        val offload_kv_cache_to_gpu: Boolean? = null,
    )

    @Serializable
    data class Quantization(
        val name: String? = null,
        val bits_per_weight: Int? = null,
    )

    @Serializable
    data class Capabilities(
        val trained_for_tool_use: Boolean? = null,
        val vision: Boolean? = null,
        val reasoning: Reasoning? = null,
    )

    @Serializable
    data class Reasoning(
        val default: String? = null,
        val allowed_options: List<String>? = null,
    )

    fun LmStudioModel.toModel(): Model {
        return Model(
            key = key,
            displayName = display_name,
            size = size_bytes,
            format = format ?: "unknown",
            quant = quantization?.name ?: "unknown",
            params = params_string ?: "unknown",
            arc = architecture ?: "unknown",
            maxContext = max_context_length ?: 0,
            tools = capabilities?.trained_for_tool_use ?: false,
            source = Source.LM_STUDIO,
            reasoningOptions = capabilities?.reasoning?.allowed_options ?: emptyList(),
            defaultReasoning = capabilities?.reasoning?.default,
        )
    }


    // DTO под Ollama
    @Serializable
    data class OllamaResponse(
        val models: List<OllamaModel>
    )

    @Serializable
    data class OllamaModel(
        val model: String,
        val name: String,
        val modified_at: String,
        val digest: String,
        val size: Long,
        val details: Details? = null
    )

    @Serializable
    data class Details(
        val parent_model: String? = null,
        val format: String? = null,
        val family: String? = null,
        val families: List<String>? = null,
        val parameter_size: String? = null,
        val quantization_level: String? = null
    )

    fun OllamaModel.toModel(): Model {
        return Model(
            key = model,
            displayName = name,
            size = size,
            format = details?.format ?: "unknown",
            quant = details?.quantization_level ?: "unknown",
            params = details?.parameter_size ?: "unknown",
            arc = details?.family ?: "unknown",
            maxContext = 0, // нет в Ollama
            tools = false, // нет в Ollama
            source = Source.OLLAMA,
            reasoningOptions = emptyList(), // нет в Ollama
        )
    }

    // DTO под Open AI
    @Serializable
    data class OpenAiResponse(
        val data: List<OpenAiModel>,
        @SerialName("object")
        val objectType: String? = null,
    )

    @Serializable
    data class OpenAiModel(
        val id: String,
        @SerialName("object")
        val objectType: String? = null,
        val created: Long? = null,
        val owned_by: String? = null,
    )

    fun OpenAiModel.toModel(): Model {
        return Model(
            key = id,
            displayName = id,
            size = 0,           // нет в OpenAI
            format = "unknown", // нет в OpenAI
            quant = "unknown",  // нет в OpenAI
            params = "unknown", // нет в OpenAI
            arc = "unknown",    // нет в OpenAI
            maxContext = 0,     // нет в OpenAI
            tools = false,      // нет в OpenAI
            source = Source.OPEN_AI,
            reasoningOptions = emptyList(), // нет в OpenAI
        )
    }

    // DTO под Open Router
    @Serializable
    data class OpenRouterResponse(
        val data: List<OpenRouterModel>,
    )

    @Serializable
    data class OpenRouterModel(
        val id: String? = null,

        @SerialName("canonical_slug")
        val canonicalSlug: String? = null,

        @SerialName("hugging_face_id")
        val huggingFaceId: String? = null,

        val name: String? = null,
        val created: Long? = null,
        val description: String? = null,

        @SerialName("context_length")
        val contextLength: Int? = null,

        val architecture: Architecture? = null,
        val pricing: Pricing? = null,

        @SerialName("top_provider")
        val topProvider: TopProvider? = null,

        @SerialName("per_request_limits")
        val perRequestLimits: PerRequestLimits? = null,

        @SerialName("supported_parameters")
        val supportedParameters: List<String> = emptyList(),

        @SerialName("default_parameters")
        val defaultParameters: DefaultParameters? = null,

        @SerialName("supported_voices")
        val supportedVoices: List<String>? = null,

        @SerialName("knowledge_cutoff")
        val knowledgeCutoff: String? = null,

        @SerialName("expiration_date")
        val expirationDate: String? = null,

        val links: Links? = null,
    )

    @Serializable
    data class Architecture(
        val modality: String,

        @SerialName("input_modalities")
        val inputModalities: List<String> = emptyList(),

        @SerialName("output_modalities")
        val outputModalities: List<String> = emptyList(),

        val tokenizer: String,

        @SerialName("instruct_type")
        val instructType: String? = null
    )

    @Serializable
    data class Pricing(
        val prompt: String? = null,
        val completion: String? = null,
        val image: String? = null,
        val audio: String? = null,

        @SerialName("web_search")
        val webSearch: String? = null,

        @SerialName("internal_reasoning")
        val internalReasoning: String? = null,

        @SerialName("input_cache_read")
        val inputCacheRead: String? = null,

        @SerialName("input_cache_write")
        val inputCacheWrite: String? = null,
    )

    @Serializable
    data class TopProvider(
        @SerialName("context_length")
        val contextLength: Int? = null,

        @SerialName("max_completion_tokens")
        val maxCompletionTokens: Int? = null,

        @SerialName("is_moderated")
        val isModerated: Boolean
    )

    @Serializable
    data class PerRequestLimits(
        val value: Int,
        // Пока пусто, т.к. в ответе приходит null.
        // Можно заполнить позже, если API начнет возвращать объект.
    )

    @Serializable
    data class DefaultParameters(
        val temperature: Double? = null,

        @SerialName("top_p")
        val topP: Double? = null,

        @SerialName("top_k")
        val topK: Int? = null,

        @SerialName("frequency_penalty")
        val frequencyPenalty: Double? = null,

        @SerialName("presence_penalty")
        val presencePenalty: Double? = null,

        @SerialName("repetition_penalty")
        val repetitionPenalty: Double? = null
    )

    @Serializable
    data class Links(
        val details: String
    )

    fun OpenRouterModel.toModel(): Model {
        return Model(
            key = canonicalSlug?:"",
            displayName = name?:"",
            size = 0,           // нет в OpenRouter
            format = "unknown", // нет в OpenRouter
            quant = "unknown",  // нет в OpenRouter
            params = "unknown", // нет в OpenRouter
            arc = "unknown",    // нет в OpenRouter
            maxContext = contextLength?.toLong()?:0,
            tools = false,      // нет в OpenAI
            source = Source.OPEN_ROUTER,
            reasoningOptions = emptyList(), // нет в OpenAI
            description = description ?: "",
        )
    }

    fun parseModels(jsonString: String, apiId: Int): List<Model> {
        logger.info("BackendApi ${BackendApi.fromId(apiId).name}")
        val isInternal = com.intellij.openapi.application.ApplicationManager.getApplication().isInternal
        val json = Json {
            // Если мы в режиме разработки, хотим знать о новых полях (будет ошибка)
            // Если у пользователя — игнорируем всё лишнее
            ignoreUnknownKeys = !isInternal
            coerceInputValues = !isInternal
            isLenient = !isInternal
        }

        val list = try {
            when (BackendApi.fromId(apiId)) {
                BackendApi.LM_STUDIO_API -> {
                    val lmResponse = json.decodeFromString<LmStudioResponse>(jsonString)
                    //lmResponse.models.map { it.toModel() }
                    lmResponse.models.filter { it.type == "llm" }.map { it.toModel() }
                }
                BackendApi.OLLAMA_API -> {
                    val ollamaResponse = json.decodeFromString<OllamaResponse>(jsonString)
                    ollamaResponse.models.map { it.toModel() }
                }
                BackendApi.OPEN_AI_API -> {
                    val openAiResponse = json.decodeFromString<OpenAiResponse>(jsonString)
                    openAiResponse.data.map { it.toModel() }
                }
                BackendApi.CLAUDE_API -> {
//                    val claudeResponse = json.decodeFromString<claudeResponse>(jsonString)
//                    claudeResponse.data.map { it.toModel() }
                    emptyList()
                }
                BackendApi.OPEN_ROUTER_API -> {
                    val openRouterResponse = json.decodeFromString<OpenRouterResponse>(jsonString)
                    openRouterResponse.data.map { it.toModel() }
                }

            }
        } catch (e: Exception) {
            logger.warn("Failed parsing JSON", e)
            emptyList()
        }
        return list
    }

}