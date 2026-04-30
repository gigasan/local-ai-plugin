package com.gigasan.ai.config

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Tag("ModelCache")
data class ModelCache(
    // Обязательно @XCollection для списков объектов
    @XCollection(style = XCollection.Style.v2)
    var models: List<Model> = listOf(),
    @Attribute("timestamp") var timestamp: Long = 0
)

enum class Source { LM_STUDIO, OLLAMA, OPEN_AI }

@Tag("Model")
data class Model(
    @Attribute("source") var source: Source = Source.LM_STUDIO,
    @Attribute("key") var key: String = "",
    @Attribute("name") var displayName: String = "",
    @Attribute("size") var size: Long = 0,
    @Attribute("format") var format: String = "",
    @Attribute("quant") var quant: String = "",
    @Attribute("params") var params: String = "",
    @Attribute("arc") var arc: String = "",
    @Attribute("maxContext") var maxContext: Int = 0,
    @Attribute("tools") var tools: Boolean = false
)


//data class Model( // LM Studio or Ollama
//    val source: Source,
//    val key: String, // key or model
//    val displayName: String, // display_name or name
//    val size: Long, // size_bytes or size
//    val format: String, // format or details/format
//    val quant: String, // quantization/name or details/quantization_level
//    val params: String, // params_string or details/parameter_size
//    val arc: String, // architecture or details/family
//    val maxContext: Int, // max_context_length or null
//    val tools: Boolean, // capabilities/trained_for_tool_use or null
//)

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
        val max_context_length: Int? = null,
        val quantization: Quantization? = null,
        val capabilities: Capabilities? = null,
        val type: String? = null, // .filter { it.type == "llm" }
        val description: String? = null,
        val loaded_instances: ArrayList<Instances>? = null,
        val variants: ArrayList<String>? = null,
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
        val allowed_options: ArrayList<String>? = null,
    )

    @Serializable
    data class AllowedOptions(
        val default: String? = null,
        val allowed_options: Int? = null,
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
            source = Source.LM_STUDIO
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
        val families: ArrayList<String>? = null,
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
            source = Source.OLLAMA
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
            source = Source.OPEN_AI
        )
    }

    fun parseModels(jsonString: String, apiId: Int): List<Model> {
        val json = Json
//            ignoreUnknownKeys = true
//            coerceInputValues = true
//            isLenient = true
//    }

        val list = try {
            when (BackendApi.fromId(apiId)) {
                BackendApi.LM_STUDIO_API -> {
                    val lmResponse = json.decodeFromString<LmStudioResponse>(jsonString)
                    lmResponse.models.map { it.toModel() }
                    //lmResponse.models.filter { it.type == "llm" }.map { it.toModel() }
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
            }
        } catch (e: Exception) {
            logger.warn("Failed parsing JSON", e)
            emptyList()
        }
        return list
    }

}