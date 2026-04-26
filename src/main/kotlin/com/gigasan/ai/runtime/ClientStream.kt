package com.gigasan.ai.runtime


import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.string.printToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import org.jetbrains.kotlin.tools.projectWizard.core.toResult
import com.fasterxml.jackson.annotation.JsonAlias
import com.gigasan.ai.core.JsonFileLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = Logger.getInstance("ClientStream")

class ClientStream(
    private val adapter: BackendAdapter,
    private val http: OkHttpClient,
    private val stateManager: StateManager,
    private val stateMachine: StateMachine,
): JsonFileLogger {
    fun execute(project: Project, ctx: ChatContext, indicator: ProgressIndicator, onEvent: (StreamEvent) -> Unit): Result {
        // Получаем контекст со всеми инструментами под конкретный бэкенд
        val context = adapter.getContext(ctx, stateManager, stateMachine)
        val call = http.newCall(context.request)
        val builder = ResultBuilder(context.parser)
        val rawFullResponse = StringBuilder()
        stateManager.onEvent(ModelEvent.Start)

        try {
            val response = call.execute()

            // Если сервер вернул ошибку сразу (не 200)
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                saveJson(project, "error_response", errorBody)
                return context.parser(errorBody) // Используем штатный парсер для ошибки
            }

            response.body!!.source().use { source ->

                // Создаем процессор через фабрику из контекста
                val processor = context.streamProcessorFactory(stateManager, stateMachine)

                val isStream = response.header("Content-Type")?.contains("text/event-stream") == true
                if (isStream) {
                    while (true) {
                        indicator.checkCanceled()
                        val line = source.readUtf8Line() ?: break

                        if (line.isNotBlank()) {
                            rawFullResponse.append(line).append("\n")
                        }

                        // ПРОЦЕССОР ДОЛЖЕН:
                        // 1. Убрать "data: "
                        // 2. Склеить куски JSON
                        // 3. Выдать готовый Event
                        // Колбэк внутри процессора наполняет наш builder.
                        processor.handleLine(line) { event ->
                            builder.append(event)
                            onEvent(event)
                        }
                    }
                } else {
                    // Логика для обычного JSON: читаем всё сразу
                    val fullJson = source.readUtf8()
                    rawFullResponse.append(fullJson)

                    val result = context.parser(fullJson)
                    saveJson(project, "raw_json_result", result.raw)
                    builder.setRaw(result.raw)
                }
            }

        // СОХРАНЯЕМ ДЕБАГ-ЛОГ (SSE протокол)
        saveJson(project, "raw_network_stream", rawFullResponse.toString())

//            // БИЛДЕР ТЕПЕРЬ СТРОИТ ЧИСТЫЙ ОТВЕТ
//            val finalResult = builder.build()
//
//            // Если хочешь сохранить еще и ЧИСТЫЙ JSON итогового ответа:
//            saveJson(project, "final_clean_response", finalResult.toRawJson())
//
//            val rawData = rawFullResponse.toString()
//            saveJson(project, "raw_stream_response", rawData)
//
//            builder.setRaw(rawData)

            return builder.build()

        } catch (e: ProcessCanceledException) {
            // Это штатная ситуация: пользователь нажал "отмена" или IDE чистит память.
            logger.info("Task cancelled by IntelliJ")
            // Обязательно пробрасываем дальше, чтобы IDEA знала, что мы завершились
            throw e
        } catch (e: Exception) {
            // Если произошла ошибка, сохраняем то, что успели накопить (для отладки обрывов)
            if (rawFullResponse.isNotEmpty()) {
                saveJson(project, "error_partial_response", rawFullResponse.toString())
            }
            logger.error("Stream execution failed", e)
            throw e
        }
    }


}



@Serializable
data class ChatEndPayload(
    // @JsonNames позволяет искать поле под разными именами
    @JsonNames("result", "response")
    val data: FinalResultData? = null
)

@Serializable
data class FinalResultData(
    val output: List<FinalOutputItem>? = null
)

@Serializable
data class FinalOutputItem(
    // Используем JsonElement, потому что тут может быть и строка, и массив
    private val content: JsonElement? = null
) {
    // Выносим логику извлечения текста в отдельное свойство
    val text: String?
        get() = when (content) {
            is JsonPrimitive -> content.content // Если это строка (LM Studio)
            is JsonArray -> {
                // Если это массив объектов (OpenAI)
                content.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
            else -> null
        }
}

@Serializable
data class ResponseOutputItem(
    val type: String? = null,
    val content: List<ResponseContent>? = null
)

@Serializable
data class ResponseContent(
    val type: String? = null,
    val text: String? = null // Вот где лежит сам текст ответа
)

// Обновляем FinalResult или создаем специфичный для этого эндпоинта
@Serializable
data class FinalResult(
    val model_instance_id: String? = null,
    val response_id: String? = null,
    val output: List<ResponseOutputItem>? = null, // Изменили тип здесь
    val stats: LMStats? = null,
    val usage: Usage? = null
)

@Serializable
data class OpenAIItem(
    val id: String? = null,
    val type: String? = null,
    val status: String? = null,
    val role: String? = null,
    val content: List<OpenAIContent>? = null
)

@Serializable
data class OpenAIContent(
    val type: String? = null,
    val text: String? = null, // Тот самый текст, который нам нужен
    val annotations: List<JsonElement>? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class StreamPayload {

    @Serializable
    @SerialName("chat.start")
    data class ChatStart(val model_instance_id: String) : StreamPayload()

    @Serializable
    @SerialName("prompt_processing.progress")
    data class PromptProgress(val progress: Double) : StreamPayload()

    @Serializable
    @SerialName("model_load.progress")
    data class LoadProgress(val progress: Double) : StreamPayload()

    @Serializable
    @SerialName("reasoning.delta")
    data class Reasoning(val content: String) : StreamPayload()

    @Serializable
    @SerialName("response.content_part.added")
    data class ResponseContentPartAdded(
        @SerialName("item_id") val itemId: String? = null,
    ) : StreamPayload()

    @Serializable
    @SerialName("response.content_part.done")
    data class ResponseContentPartDone(
        @SerialName("item_id") val itemId: String? = null,
    ) : StreamPayload()

    @Serializable
    @SerialName("response.created") // Явно указываем тип для этого API
    data class ResponseCreated(val response: FinalResult) : StreamPayload()


    @Serializable
    @SerialName("response.in_progress") // Явно указываем тип для этого API
    data class ResponseInProgress(val response: FinalResult) : StreamPayload()


    @Serializable
    @SerialName("message.delta") // Или используйте JsonClassDiscriminator
    data class Message(
        val content: String? = null,
        val delta: String? = null, // Для Responses API
        val text: String? = null   // Иногда бывает и так
    ) : StreamPayload() {
        val actualContent: String? get() = content ?: delta ?: text
    }

    @Serializable
    @SerialName("response.output_text.delta") // Явно указываем тип для этого API
    data class ResponseDelta(val delta: String? = null) : StreamPayload()

    @Serializable
    @SerialName("response.output_text.done")
    data class ResponseOutputTextDone(
        @SerialName("item_id") val itemId: String? = null,
        val text: String,
    ) : StreamPayload()

    @Serializable
    @SerialName("response.output_item.added")
    data class ResponseOutputItemAdded(
        @SerialName("output_index") val outputIndex: Int? = null,
        val item: OpenAIItem? = null,
        @SerialName("sequence_number") val sequenceNumber: Int? = null
    ) : StreamPayload()

    @Serializable
    @SerialName("response.output_item.done")
    data class ResponseOutputItemDone(
        @SerialName("output_index") val outputIndex: Int? = null,
        val item: OpenAIItem? = null,
        @SerialName("sequence_number") val sequenceNumber: Int? = null
    ) : StreamPayload()

    @Serializable
    @SerialName("response.completed")
    data class ResponseCompleted(
        val response: FinalResult
    ) : StreamPayload()

    @Serializable
    @SerialName("chat.end")
    data class ChatEnd(val result: FinalResult) : StreamPayload()

    @Serializable
    @SerialName("non.stream.chat.end")
    data class NonStreamChatEnd(val result: FinalResult) : StreamPayload()

    @Serializable
    @SerialName("error")
    data class Error(val error: Error?) : StreamPayload()

    // Если придет событие без данных (например, reasoning.start)
    // или неизвестное событие
    @Serializable
    @SerialName("unknown")
    object Unknown : StreamPayload()

    val StreamPayload.eventType: EventType
        get() = when (this) {
            is ChatStart -> EventType.CHAT_START
            is LoadProgress -> EventType.MODEL_LOAD_PROGRESS
            is PromptProgress -> EventType.PROMPT_PROGRESS
            is Reasoning -> EventType.REASONING_DELTA
            is ResponseContentPartAdded -> EventType.MESSAGE_START
            is ResponseOutputItemAdded -> EventType.MESSAGE_START
            is ResponseCreated -> EventType.MESSAGE_START
            is ResponseInProgress -> EventType.MESSAGE_DELTA
            is Message -> EventType.MESSAGE_DELTA
            is ResponseDelta -> EventType.MESSAGE_DELTA
            is ResponseCompleted -> EventType.MESSAGE_END
            is ResponseOutputItemDone -> EventType.MESSAGE_END
            is ResponseContentPartDone -> EventType.MESSAGE_END
            is ResponseOutputTextDone -> EventType.MESSAGE_END
            is ChatEnd -> EventType.CHAT_END
            is NonStreamChatEnd -> EventType.NON_STREAM_CHAT_END
            is Error -> EventType.ERROR
            is Unknown -> EventType.ERROR
        }

}



data class RawSSEEvent(
    val event: String,
    val data: String?,
    val isDone: Boolean = false,
    val raw: String
)

class SSEParser(private val project: Project) : JsonFileLogger {

    fun parse(rawBlock: String): RawSSEEvent? {
        val trimmedBlock = rawBlock.trim()
        if (trimmedBlock.isEmpty()) return null

        var eventName: String? = null
        val dataBuffer = StringBuilder()
        var isDone = false

        rawBlock.lineSequence().forEach { line ->
            val cleanLine = line.trim()
            //saveJson(project, "sse_cleanLine", cleanLine)

            when {
                cleanLine.startsWith("event:") -> {
                    eventName = cleanLine.removePrefix("event:").trim()
                }

                cleanLine.startsWith("data:") -> {
                    val content = cleanLine.removePrefix("data:").trim()

                    if (content == "[DONE]") {
                        isDone = true
                    } else {
                        dataBuffer.append(content)
                    }
                }
            }
        }

        return RawSSEEvent(
            event = eventName ?: "message", // default по SSE
            data = dataBuffer.toString().takeIf { it.isNotBlank() },
            isDone = isDone,
            raw = rawBlock
        )
    }
}

data class StreamEvent(
    val type: EventType,
    val content: String? = null,
    val payload: StreamPayload = StreamPayload.Unknown, // Структура для логики
    val raw: String? = null,
    val cleanJson: String? = null,
    var indicatorText: String = "",
)

class StreamParser(private val project: Project) : JsonFileLogger {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(event: RawSSEEvent): StreamEvent? {

        val type = EventType.from(event.event) ?: EventType.ERROR

        // DONE без data
        if (event.isDone) {
            return StreamEvent(type, raw = event.raw)
        }

        val dataString = event.data ?: return null

        val payload = try {
            json.decodeFromString<StreamPayload>(dataString)
        } catch (e: Exception) {
            logger.warn("Failed parsing JSON", e)

            try {
                val cleaned = sanitizeJson(dataString)
                json.decodeFromString<StreamPayload>(cleaned)
            } catch (e2: Exception) {
                logger.warn("Failed parsing cleaned JSON", e2)
                StreamPayload.Unknown
            }
        }

        val text = when (payload) {
            is StreamPayload.Message -> payload.actualContent
            is StreamPayload.ChatEnd ->
                payload.result.output?.firstOrNull()?.content?.firstOrNull()?.text
            else -> null
        }

        return StreamEvent(
            type = type,
            content = text,
            payload = payload,
            raw = event.raw,
            cleanJson = dataString
        )
    }

    private fun sanitizeJson(input: String): String {
        return input.dropWhile { it != '{' && it != '[' }
            .dropLastWhile { it != '}' && it != ']' }
    }
}


class ResultBuilder(val parser: (String) -> Result) {
    private var finalPayload: Any? = null
    private var rawContent: String = ""

    private var text: String? = null
    // Метод для установки сырых данных перед сборкой
    fun setRaw(data: String) {
        this.rawContent = data
    }

    private val reasoning = StringBuilder()
    private val output = StringBuilder()

    var progress: Double = 0.0

    //var raw: String? = null
    var model: String? = null
    var rawResponseId: String? = null
    var stats: LMStats? = null
    var usage: Usage? = null

    fun append(event: StreamEvent) {
//        logger.info("ResultBuilder.append: event.type=${event.type}")
//        logger.info("ResultBuilder.append: event.content=${event.content}")
//        logger.info("ResultBuilder.append: event.payload=${event.payload}")
//        logger.info("ResultBuilder.append: event.raw.length=${event.raw?.length}")
//        logger.info("ResultBuilder.append: event.raw=${event.raw}")

        val data = event.payload // допустим, ты передал распарсенный JSON
        logger.info("ResultBuilder.append: data=$data")
        //logger.warn("event: $event")
        when (event.type) {

            EventType.CHAT_START -> {
                model = (event.payload as? StreamPayload.ChatStart)?.model_instance_id
                logger.info("ResultBuilder: CHAT_START $model")
                event.indicatorText = String.format("CHAT_START %s", model)
            }

            EventType.MODEL_LOAD_START -> {
                progress = 0.0
                logger.info("ResultBuilder: MODEL_LOAD_START event.content=${event.content}")
                event.indicatorText = String.format("MODEL_LOAD_PROGRESS %s %.2f%%", model, progress * 100.0)
            }

            EventType.MODEL_LOAD_PROGRESS -> {
                progress = (event.payload as? StreamPayload.LoadProgress)?.progress?: 0.0
                logger.info("ResultBuilder: MODEL_LOAD_PROGRESS $progress")
                event.indicatorText = String.format("MODEL_LOAD_PROGRESS %s %.2f%%", model, progress * 100.0)
            }

            EventType.MODEL_LOAD_END -> {
                logger.info("ResultBuilder: MODEL_LOAD_END event.content=${event.content}")
                event.indicatorText = String.format("MODEL_LOAD_PROGRESS %s %.2f%%", model, progress * 100.0)
            }

            EventType.PROMPT_START -> {
                logger.info("ResultBuilder: PROMPT_START")
                progress = 0.0
                event.indicatorText = String.format("PROMPT_PROGRESS %.2f%%", progress * 100.0)
            }

            EventType.PROMPT_PROGRESS -> {
                progress = (event.payload as? StreamPayload.PromptProgress)?.progress?: 0.0
                logger.warn("ResultBuilder: PROMPT_PROGRESS $progress")
                event.indicatorText = String.format("PROMPT_PROGRESS %.2f%%", progress * 100.0)
            }

            EventType.PROMPT_END -> {
                logger.info("ResultBuilder: PROMPT_END")
                event.indicatorText = String.format("PROMPT_PROGRESS %.2f%%", progress * 100.0)
            }

            EventType.REASONING_START -> {
                logger.info("ResultBuilder: REASONING_START")
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("REASONING_START %d tok", modelTokens)
            }

            EventType.REASONING_DELTA -> {
                reasoning.append((event.payload as? StreamPayload.Reasoning)?.content?: "")
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("REASONING_DELTA %d tok", modelTokens)
            }
            EventType.REASONING_END -> {
                logger.info("ResultBuilder: REASONING_END")
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("REASONING_END %d tok", modelTokens)
            }

            EventType.MESSAGE_START -> {
                logger.info("ResultBuilder: MESSAGE_START")
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("MESSAGE_START %d tok", modelTokens)
            }
            EventType.MESSAGE_DELTA -> {
                output.append(event.content?: "")
                //if (output.isEmpty() && text.isBlank()) return  // убираем первый перенос строки если он есть
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("MESSAGE_DELTA %d tok", modelTokens)
            }
            EventType.MESSAGE_END -> {
                logger.info("ResultBuilder: MESSAGE_END")
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("MESSAGE_END %d tok", modelTokens)
            }

            EventType.CHAT_END -> {
                val modelTokens = reasoning.length + output.length
                event.indicatorText = String.format("CHAT_END %d%% tok", modelTokens)
                this.finalPayload = data

                // Если payload это наше новое событие завершения
                val res = when(val p = event.payload) {
                    is StreamPayload.ResponseCompleted -> p.response
                    is StreamPayload.ChatEnd -> p.result
                    else -> null
                }
                if (res != null) {
                    res.usage.let { this.usage = it}
                    res.stats.let { this.stats = it }
                    this.model = res.model_instance_id
                    this.rawResponseId = res.response_id
                    this.text = res.output?.flatMap { it.content ?: emptyList() }
                        ?.mapNotNull { it.text }
                        ?.joinToString("")

                    logger.info("ResultBuilder: CHAT_END res=$res" )
                }
            }

            /*
            * data class FinalResult(
    val model_instance_id: String? = null,
    val response_id: String? = null,
    val output: List<ResponseOutputItem>? = null, // Изменили тип здесь
    val stats: LMStats? = null,
    val usage: Usage? = null
)
            * */


            EventType.NON_STREAM_CHAT_END -> {
                logger.info("ResultBuilder: NON_STREAM_CHAT_END")
                rawContent = data.printToString()
                logger.info("ResultBuilder: rawContent=$rawContent")
            }

            else -> {
                logger.warn("ResultBuilder: smthing else event.content=${event.content}")
            }
        }
    }

    fun build(): Result {
        if (rawContent.startsWith("{")) {
            return parser(rawContent) // Теперь тут гарантированно чистый JSON объекта
        }
        val payload = finalPayload

        // Пытаемся достать текст из финала (если буфер пуст или нужен полный текст)
        val finalText = when (payload) {
            is ChatEndPayload -> payload.data?.output?.firstOrNull()?.text
            // Здесь можно добавить другие типы для других провайдеров
            else -> null
        }

        // 1. Если у нас есть сырой JSON (например, от эндпоинта /v1/responses),
        // используем ваш готовый парсер
        // Если есть накопленный сырой ответ (весь поток), парсим его целиком
        if (rawContent.isNotBlank()) {
            try {
                // Ваш парсер из Result.kt сам разберется, какой это формат
                return parser(rawContent)
            } catch (e: Exception) {
                logger.warn("AIResponseParser failed to parse rawContent, using manual assembly: ${e.message}")
                logger.info("AIResponseParser rawContent.length=${rawContent.length}")
            }
        }

        // 2. Fallback: если это был обычный SSE стрим с чанками "data:",
        // собираем Result из накопленных в append() данных
        return Result(
            text = finalText?:text?:output.toString(),
            usage = Usage(
                inputTokens = stats?.input_tokens,
                outputTokens = stats?.total_output_tokens,
                reasoning_tokens = stats?.reasoning_output_tokens,
                totalTokens = stats?.total_output_tokens,
                tokens_per_second = stats?.tokens_per_second,
                time_to_first_token_seconds = stats?.time_to_first_token_seconds,
            ),
            model = model,
            reasoning = reasoning.toString(),
            toolCalls = emptyList(),
            raw = rawContent,
            durationMs = 0,
            response_id = rawResponseId
        )
    }


}
