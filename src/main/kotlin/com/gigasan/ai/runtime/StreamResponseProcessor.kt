package com.gigasan.ai.runtime

import com.gigasan.ai.config.BackendApi
import com.gigasan.ai.core.JsonFileLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project


private val logger = Logger.getInstance("DefaultStreamProcessor")

interface StreamResponseProcessor {
    fun handleLine(line: String, onEvent: (StreamEvent) -> Unit)
}

class DefaultStreamProcessor(
    private val project: Project,
    private val stateManager: StateManager,
    private val stateMachine: StateMachine,
    private val sseParser: SSEParser,
    private val streamParser: StreamParser,
    private val api: BackendApi,
) : StreamResponseProcessor, JsonFileLogger {
    val isInternal = com.intellij.openapi.application.ApplicationManager.getApplication().isInternal
    private val buffer = mutableListOf<String>()
    private var counter = 0
    override fun handleLine(line: String, onEvent: (StreamEvent) -> Unit) {
        if (line.isBlank()) {
            if (buffer.isNotEmpty()) {
                counter +=1
                val fullBlock = buffer.filter { it.isNotBlank() }.joinToString("\n")

                buffer.clear()

                if (fullBlock.isEmpty()) return

                // 🔹 1. SSE парсинг
                val rawEvent = sseParser.parse(fullBlock)
                //logger.info("rawEvent: $rawEvent")
                if (rawEvent == null) return
                if (isInternal) {
                    saveJson(project, "$api sse_raw ${rawEvent.event} data", rawEvent.data.toString(), counter)
                }

                // 🔹 2. JSON / domain парсинг
                val event = streamParser.parse(rawEvent)
                if (event == null) {
                    if (isInternal) {
                        saveJson(project, "$api invalid_event", event.toString(), counter)
                    }
                    return
                }

                if (event.payload == StreamPayload.Unknown) {
                    if (isInternal) {
                        saveJson(project, "$api stream_event ${event.type.name} payload", event.payload.toString(), counter)
                    }
                }

                // 🔹 3. бизнес-логика
                stateMachine.onEvent(event.type.name, event.content)
                stateManager.onEvent(ModelEvent.Stream(event.type))
                event.counter = counter
                onEvent(event)
            }
            return
        }

        buffer.add(line)
    }
}