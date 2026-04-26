package com.gigasan.ai.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


data class GenerationState(
    val phase: Phase = Phase.IDLE,
    val modelLoading: Boolean = false,
    val promptProcessing: Boolean = false,
    val reasoning: StringBuilder = StringBuilder(),
    val message: StringBuilder = StringBuilder(),
    val toolCallActive: Boolean = false,
    val error: String? = null
)

enum class Phase {
    IDLE,
    LOADING_MODEL,
    PROCESSING_PROMPT,
    REASONING,
    GENERATING_MESSAGE,
    TOOL_CALL,
    FINISHED,
    ERROR
}

enum class ModelState {
    IDLE,
    SENDING,
    THINKING,
    GENERATING,
    CALLING_TOOL,
    WAITING_TOOL_RESULT,
    RETRYING,
    DONE,
    ERROR
}

enum class EventType(val raw: String, vararg val aliases: String) {
    CHAT_START("chat.start", "response.created"), // Начало
    CHAT_END("chat.end", "response.output_item.done", "response.done", "response.completed"),
    NON_STREAM_CHAT_END("non.stream.chat.end"),

    MODEL_LOAD_START("model_load.start"),
    MODEL_LOAD_PROGRESS("model_load.progress"),
    MODEL_LOAD_END("model_load.end"),

    PROMPT_START("prompt_processing.start"),
    PROMPT_PROGRESS("prompt_processing.progress"),
    PROMPT_END("prompt_processing.end"),

    REASONING_START("reasoning.start"),
    REASONING_DELTA("reasoning.delta"),
    REASONING_END("reasoning.end"),

    // Эти можно просто добавить, чтобы не видеть Warn в логах
    PROGRESS("response.in_progress"),

    MESSAGE_START("message.start", "response.output_item.added", "response.content_part.added"),
    MESSAGE_DELTA("message.delta", "response.output_text.delta"),
    MESSAGE_END("message.end", "response.output_text.done", "response.content_part.done"),

    TOOL_START("tool_call.start"),
    TOOL_ARGS("tool_call.arguments"),
    TOOL_SUCCESS("tool_call.success"),
    TOOL_FAILURE("tool_call.failure"),
    ERROR("error");

    companion object {
        fun from(raw: String): EventType? {
            return entries.find { it.raw == raw || it.aliases.contains(raw) }
        }
    }
}

fun extractText(data: String?): String {
    if (data == null) return ""

    return try {
        val json = Json { ignoreUnknownKeys = true }
        val element = json.parseToJsonElement(data)

        val output = element.jsonObject["output"]?.jsonArray

        output
            ?.mapNotNull { it.jsonObject["content"]?.jsonPrimitive?.content }
            ?.joinToString("") ?: ""

    } catch (e: Exception) {
        ""
    }
}

class StateMachine {

    private var state = GenerationState()

    fun onEvent(eventType: String, data: String?): GenerationState {
        val type = EventType.from(eventType) ?: return state

        state = when (type) {

            // --- CHAT ---
            EventType.CHAT_START -> {
                GenerationState(phase = Phase.IDLE)
            }

            EventType.CHAT_END -> {
                state.copy(phase = Phase.FINISHED)
            }

            // --- MODEL LOAD ---
            EventType.MODEL_LOAD_START -> {
                state.copy(
                    phase = Phase.LOADING_MODEL,
                    modelLoading = true
                )
            }

            EventType.MODEL_LOAD_PROGRESS -> {
                // можно парсить % из data
                state
            }

            EventType.MODEL_LOAD_END -> {
                state.copy(modelLoading = false)
            }

            // --- PROMPT ---
            EventType.PROMPT_START -> {
                state.copy(
                    phase = Phase.PROCESSING_PROMPT,
                    promptProcessing = true
                )
            }

            EventType.PROMPT_PROGRESS -> state

            EventType.PROGRESS -> state

            EventType.PROMPT_END -> {
                state.copy(promptProcessing = false)
            }

            // --- REASONING ---
            EventType.REASONING_START -> {
                state.copy(
                    phase = Phase.REASONING,
                    reasoning = StringBuilder()
                )
            }

            EventType.REASONING_DELTA -> {
                val delta = extractText(data)
                state.reasoning.append(delta)
                state
            }

            EventType.REASONING_END -> state

            // --- MESSAGE ---
            EventType.MESSAGE_START -> {
                state.copy(
                    phase = Phase.GENERATING_MESSAGE,
                    message = StringBuilder()
                )
            }



            EventType.MESSAGE_DELTA -> {
                val delta = extractText(data)
                state.message.append(delta)
                state
            }

            EventType.MESSAGE_END -> state

            // --- TOOL ---
            EventType.TOOL_START -> {
                state.copy(
                    phase = Phase.TOOL_CALL,
                    toolCallActive = true
                )
            }

            EventType.TOOL_ARGS -> state

            EventType.TOOL_SUCCESS,
            EventType.TOOL_FAILURE -> {
                state.copy(toolCallActive = false)
            }

            // --- ERROR ---
            EventType.ERROR -> {
                state.copy(
                    phase = Phase.ERROR,
                    error = data
                )
            }
            EventType.NON_STREAM_CHAT_END -> {
                //GenerationState(phase = Phase.IDLE)
                state
            }

        }

        return state
    }
}



sealed class ModelEvent {
    object Start : ModelEvent()
    data class Stream(val type: EventType) : ModelEvent()
    object Done : ModelEvent()
    data class Error(val message: String) : ModelEvent()
    object Retry : ModelEvent()
}



class StateManager {

    var state: ModelState = ModelState.IDLE
        private set

    private val listeners = mutableListOf<(ModelState) -> Unit>()

    fun addListener(listener: (ModelState) -> Unit) {
        listeners += listener
    }

    private fun setState(newState: ModelState) {
        if (state == newState) return

        state = newState
        listeners.forEach { it(state) }
    }

    fun onEvent(event: ModelEvent) {
        when (event) {

            ModelEvent.Start -> {
                setState(ModelState.SENDING)
            }

            is ModelEvent.Stream -> {
                when (event.type) {
                    EventType.REASONING_DELTA -> setState(ModelState.THINKING)
                    EventType.MESSAGE_DELTA -> setState(ModelState.GENERATING)
                    EventType.TOOL_START -> setState(ModelState.CALLING_TOOL)
                    else -> {}
                }
            }

            ModelEvent.Retry -> {
                setState(ModelState.RETRYING)
            }

            ModelEvent.Done -> {
                setState(ModelState.DONE)
            }

            is ModelEvent.Error -> {
                setState(ModelState.ERROR)
            }
        }
    }
}

data class StateSnapshot(
    val state: ModelState,
    val reasoningTokens: Int,
    val outputTokens: Int
)



class StreamMonitor {

    var reasoningTokens = 0
    var outputTokens = 0

    fun onEvent(type: EventType) {
        when (type) {
            EventType.REASONING_DELTA -> reasoningTokens++
            EventType.MESSAGE_DELTA -> outputTokens++
            else -> {}
        }
    }

    fun shouldAbort(reasoning: Int=Int.MAX_VALUE): Boolean {
        return reasoningTokens > reasoning && outputTokens == 0
    }
}



class ModelStateTracker {

    var state: ModelState = ModelState.IDLE
        private set

    fun onStart() {
        state = ModelState.SENDING
    }

    fun onEvent(event: StreamEvent) {
        when (event.type) {
            EventType.REASONING_DELTA -> ModelState.THINKING
            EventType.MESSAGE_DELTA -> ModelState.GENERATING
            EventType.TOOL_START -> ModelState.CALLING_TOOL
            else -> {}
        }
    }

    fun onDone() {
        state = ModelState.DONE
    }

    fun onError() {
        state = ModelState.ERROR
    }
}