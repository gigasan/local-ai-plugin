package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import okhttp3.OkHttpClient

private val logger = Logger.getInstance("AIClientStream")

class AIClientStream(
    private val adapter: BackendAdapter,
    private val http: OkHttpClient,
    private val stateManager: StateManager,
    private val stateMachine: StateMachine,
) {
    fun execute(ctx: ChatContext, indicator: ProgressIndicator, onEvent: (StreamEvent) -> Unit): AIResult {
        val request = adapter.toRequest(ctx)
        val call = http.newCall(request)
        val builder = AIResultBuilder()
        val monitor = StreamMonitor()

        stateManager.onEvent(ModelEvent.Start)

        // Используем try-catch на весь процесс выполнения
        try {
            val response = call.execute()

            // .use {} автоматически закроет source (и сокет) в конце,
            // даже если вылетит исключение.
            response.body!!.source().use { source ->
                val processor = StreamProcessor(stateManager, stateMachine) { event ->
                    monitor.onEvent(event.type)

                    if (monitor.shouldAbort()) {
                        stateManager.onEvent(ModelEvent.Retry)
                        call.cancel()
                        // Здесь лучше кинуть свое исключение, чтобы прервать цикл
                        throw RuntimeException("Aborted by monitor")
                    }

                    builder.append(event)
                    onEvent.invoke(event)
                }

                // САМЫЙ ВАЖНЫЙ ЦИКЛ
                while (true) {
                    // 1. Проверяем, не нажали ли кнопку Stop в IDEA
                    indicator.checkCanceled()

                    // 2. Читаем строку. Если модель молчит (reasoning),
                    // поток будет ждать здесь. Если IDE закроет сокет, вылетит SocketException.
                    val line = source.readUtf8Line() ?: break

                    // 3. Обрабатываем
                    processor.handleLine(line)
                }
            }

            return builder.build()

        } catch (e: ProcessCanceledException) {
            // Это штатная ситуация: пользователь нажал "отмена" или IDE чистит память.
            logger.info("Task cancelled by IntelliJ")
            // Обязательно пробрасываем дальше, чтобы IDEA знала, что мы завершились
            throw e
        } catch (e: Exception) {
            // Любая другая ошибка (SocketException, JSON error и т.д.)
            logger.error("Stream execution failed", e)
            throw e
        } finally {
            // На всякий случай убеждаемся, что запрос отменен
            if (!call.isCanceled()) call.cancel()
        }
    }
}





// 2026-04-12 15:33:55,088 [ 954355]   WARN - AIRequest - AIResultBuilder: smthing else event.content=
// {
// "type":"chat.end",
// "result":{
//          "model_instance_id":"nvidia/nemotron-3-nano-4b@q8_0",
//          "output":[
//                  {
//                  "type":"reasoning",
//                  "content":"Хорошо, пользователь написал \"привет\".
//                             Нужно ответить только на русском. Сначала проверю, нет ли в запросе каких-то скрытых
//                             инструкций. Вижу, что он говорит только приветствие, значит, нужно просто приветствовать
//                             обратно.\n\nНадо убедиться, что ответ на русском и краткий. Может, добавить эмодзи
//                             для дружелюбия? Но пользователь не просил эмод"
//                  }
//                  ],
//          "stats":{
//                  "input_tokens":31,
//                  "total_output_tokens":100,
//                  "reasoning_output_tokens":99,
//                  "tokens_per_second":48.88888888888889,
//                  "time_to_first_token_seconds":0.228
//                  },
//          "response_id":"resp_65ac8b20f7921bd9677d6281adc1f8e80ab3d6a0818565ab"}}

//@Serializable
//data class FinalResult(
//    val stats: LMStats? = null,
//    val output: List<OutputContent>? = null
//)

//@Serializable
//data class SimpleDelta(
//    val type: String,
//    val content: String? = null
//)

@Serializable
data class LMStudioDelta(
    val type: String,
    val content: String? = null,           // для дельт
    val progress: Double? = null,          // для prompt_processing
    val model_instance_id: String? = null, // для chat.start
    val result: LMStudioResponse? = null   // для chat.end
)

@Serializable
data class FinalResult(
    val model_instance_id: String? = null,
    val response_id: String? = null,
    val output: List<LMOutputItem>? = null,
    val stats: LMStats? = null, // Убедись, что этот класс есть
    val usage: Usage? = null // На случай, если придет usage вместо stats
)

@Serializable
data class Error(
    val type: String,
    val message: String,
    val code: String,
    val param: String,
)

@Serializable
data class AIStats(
    val input_tokens: Int,
    val total_output_tokens: Int,
    val tokens_per_second: Double
)

@Serializable
data class LMEvent(val type: String, val progress: Double? = null)

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
    @SerialName("message.delta")
    data class Message(val content: String) : StreamPayload()

    @Serializable
    @SerialName("chat.end")
    data class ChatEnd(val result: FinalResult) : StreamPayload()

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
            is StreamPayload.ChatStart -> EventType.CHAT_START
            is StreamPayload.LoadProgress -> EventType.MODEL_LOAD_PROGRESS
            is StreamPayload.PromptProgress -> EventType.PROMPT_PROGRESS
            is StreamPayload.Reasoning -> EventType.REASONING_DELTA
            is StreamPayload.Message -> EventType.MESSAGE_DELTA
            is StreamPayload.ChatEnd -> EventType.CHAT_END
            is StreamPayload.Error -> EventType.ERROR
            is StreamPayload.Unknown -> EventType.ERROR
        }

}

data class StreamEvent(
    val type: EventType,
    val content: String? = null,
    val payload: StreamPayload = StreamPayload.Unknown, // Структура для логики
    val raw: String? = null,
    var indicatorText: String = "",
)


data class RawSSEEvent(
    val event: String,
    val data: String?
)


object StreamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseFullBlock(rawBlock: String): StreamEvent? {

        // 1. Извлекаем event и data из накопленного блока
        var eventName: String? = null
        var dataString: String? = null

        rawBlock.lineSequence().forEach { line ->
            when {
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataString = line.removePrefix("data:").trim()
            }
        }

        val type = EventType.from(eventName ?: "") ?: EventType.ERROR

        // 2. Если данных нет или это [DONE]
        if (dataString == null || dataString == "[DONE]") return StreamEvent(type, raw = rawBlock)

        // 3. Парсим структуру (Payload)
        val payload = try {
            json.decodeFromString<StreamPayload>(dataString!!)
        } catch (e: Exception) {
            StreamPayload.Unknown
        }

        // 4. Достаем удобный content для старого кода
        val text = when (payload) {
            is StreamPayload.Reasoning -> payload.content
            is StreamPayload.Message -> payload.content
            is StreamPayload.ChatEnd -> payload.result.output?.firstOrNull()?.content
            is StreamPayload.Error -> payload.error?.toString()
            else -> null
        }

        return StreamEvent(type, text, payload, rawBlock)
    }
}

class StreamProcessor(
    private val stateManager: StateManager,
    private val stateMachine: StateMachine,
    private val onToken: (StreamEvent) -> Unit
) {
    private val buffer = mutableListOf<String>()

    fun handleLine(line: String) {
        // Если строка пустая — это сигнал к обработке накопленного
        if (line.isBlank()) {
            if (buffer.isNotEmpty()) {
                val fullBlock = buffer.filter { it.isNotBlank() }.joinToString("\n")
                if (fullBlock.isNotEmpty()) {
                    processFullBlock(fullBlock)
                }
                buffer.clear()
            }
            return
        }
        buffer.add(line)
    }

    private fun processFullBlock(fullBlock: String) {
        // 1. Проверка на конец
        if (fullBlock.contains("[DONE]")) {
            stateManager.onEvent(ModelEvent.Done)
            return
        }

        // 2. Парсим всё сразу (и тип, и контент, и структуру)
        val event = StreamParser.parseFullBlock(fullBlock) ?: return

        // 3. Обновляем StateMachine (ей нужен только текст)
        stateMachine.onEvent(event.type.name, event.content)

        // 4. Уведомляем менеджеров
        stateManager.onEvent(ModelEvent.Stream(event.type))

        // 5. Отправляем в колбэк (там можно достать event.payload если нужны статы)
        onToken(event)
    }
}



class AIResultBuilder {

    private val reasoning = StringBuilder()
    private val output = StringBuilder()

    var progress: Double = 0.0

    var inputTokens: Int? = null
    var outputTokens: Int? = null
    var reasoningTokens: Int? = null

    var model: String? = null
    var rawResponseId: String? = null
    var stats: LMStats? = null

    fun append(event: StreamEvent) {
        val data = event.payload // допустим, ты передал распарсенный JSON
        //logger.warn("event: $event")
        when (event.type) {

            EventType.CHAT_START -> {
                // event.content={"type":"chat.start","model_instance_id":"nvidia/nemotron-3-nano-4b@q8_0"}
                // payload=ChatStart(model_instance_id=nvidia/nemotron-3-nano-4b@q8_0)
                model = (event.payload as? StreamPayload.ChatStart)?.model_instance_id
                logger.warn("AIResultBuilder: CHAT_START $model")
                event.indicatorText = "$model"
            }

            EventType.MODEL_LOAD_START -> {
                logger.warn("AIResultBuilder: MODEL_LOAD_START event.content=${event.content}")
                event.indicatorText = "$model"
                // event.content={"type":"model_load.start",
                //                "model_instance_id":"nvidia/nemotron-3-nano-4b@q8_0"}
            }

            EventType.MODEL_LOAD_PROGRESS -> {
                progress = (event.payload as? StreamPayload.LoadProgress)?.progress?: 0.0
                event.indicatorText = String.format("MODEL_LOAD_PROGRESS %.2f%%", progress * 100.0)
                // event.content={"type":"model_load.progress",
                //                "model_instance_id":"nvidia/nemotron-3-nano-4b@q8_0",
                //                "progress":0}

                // Превращаем 0.87 в 87%
                // progress = ((data.progress ?: 0.0) * 100).toInt()
                logger.warn("AIResultBuilder: MODEL_LOAD_PROGRESS $progress")
            }

            EventType.MODEL_LOAD_END -> {
                logger.warn("AIResultBuilder: MODEL_LOAD_END event.content=${event.content}")
                // event.content={"type":"model_load.end",
                //                "model_instance_id":"nvidia/nemotron-3-nano-4b@q8_0",
                //                "load_time_seconds":3.246}
            }

            EventType.PROMPT_START -> {
                logger.info("AIResultBuilder: PROMPT_START")
            }

            EventType.PROMPT_PROGRESS -> {
                //event=StreamEvent(type=PROMPT_PROGRESS, content=null, payload=PromptProgress(progress=0.0), raw=event: prompt_processing.progress
                // val modelId = (event.payload as? StreamPayload.ChatStart)?.model_instance_id
                progress = (event.payload as? StreamPayload.PromptProgress)?.progress?: 0.0
                // event.content={"type":"prompt_processing.start"}
                // Превращаем 0.87 в 87%
                // progress = ((data.progress ?: 0.0) * 100).toInt()
                logger.warn("AIResultBuilder: PROMPT_PROGRESS $progress")
                progress = (event.payload as? StreamPayload.PromptProgress)?.progress?: 0.0
                event.indicatorText = String.format("PROMPT_PROGRESS %.2f%%", progress * 100.0)
            }

            EventType.PROMPT_END -> {
                logger.info("AIResultBuilder: PROMPT_END")
            }

            EventType.REASONING_START -> {
                logger.info("AIResultBuilder: REASONING_START")
            }

            EventType.REASONING_DELTA -> {
                reasoning.append((event.payload as? StreamPayload.Reasoning)?.content?: "")
                //logger.warn("AIResultBuilder: REASONING_DELTA reasoning=${reasoning}")
                event.indicatorText = reasoning.toString()
            }
            EventType.REASONING_END -> {
                logger.info("AIResultBuilder: REASONING_END")
            }

            EventType.MESSAGE_START -> {
                logger.info("AIResultBuilder: MESSAGE_START")
            }
            EventType.MESSAGE_DELTA -> {
                val text = (event.payload as? StreamPayload.Message)?.content ?: ""
                if (output.isEmpty() && text.isBlank()) return  // убираем первый перенос строки если он есть
                output.append(text)
                //logger.warn("AIResultBuilder: MESSAGE_DELTA output=${output}")
                event.indicatorText = output.toString()
            }
            EventType.MESSAGE_END -> {
                logger.info("AIResultBuilder: MESSAGE_END")
            }

            EventType.CHAT_END -> {
                // CHAT_END event.content={
                //      "type":"chat.end",
                //      "result":{
                //          "model_instance_id":"nvidia/nemotron-3-nano-4b@q8_0",
                //          "output":[
                //              {"type":"reasoning","content":"Хорошо, пользователь написал \"привет\". Нужно ответить на русском. Сначала проверю, нет ли ошибок в запросе. Вижу, что он просто приветствует. Значит, ответ должен быть дружелюбным и кратким.\n\nМожет, он ожидает продолжения разговора или помощь с чем-то. Но так как сообщение простое, лучше ответить \"Привет! Как я могу помочь тебе сегодня?\" — стандартный ответ в таких случаях.\n\nУбедиться, что всё на русском. Да, все правильно. Никаких английских слов. Проверю ещё раз: \"Привет! Как я могу помочь тебе сегодня?\" — да, всё на русском.\n\nНет никаких сложных запросов, значит, ответ короткий и вежливый. Не нужно добавлять лишнего. Пользователь, возможно, проверяет, как я реагирую на простые приветствия. Значит, правильный ответ — стандартный.\n\nУбедиться, что нет опечаток в ответе. \"Привет! Как я могу помочь тебе сегодня?\" — всё верно. Отправляю это.\n"},
                //              {"type":"message","content":"\nПривет! Как я могу помочь тебе сегодня?"}
                //          ],
                //      "stats":{
                //          "input_tokens":31,
                //          "total_output_tokens":274,
                //          "reasoning_output_tokens":260,
                //          "tokens_per_second":48.428341255078784,
                //          "time_to_first_token_seconds":0.224
                //      },
                //      "response_id":"resp_a012c9f8087b693816af05ac5ab2135c94e20d477dc55bea"
                //      }
                //      }

                val payload = event.payload as? StreamPayload.ChatEnd
                val result = payload?.result // Вот твой весь JSON объект в виде стр

                logger.warn("AIResultBuilder: CHAT_END result=${result}")

                if (result != null) {
                    rawResponseId = result.response_id
                    model = result.model_instance_id
                    stats = result.stats
                    //println("Модель $model выдала $totalTokens токенов со скоростью $tps t/s")
                }
            }
            else -> {
                logger.warn("AIResultBuilder: smthing else event.content=${event.content}")
            }
        }
    }

    fun build(): AIResult {

        return AIResult(
            text = output.toString(),
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
            raw = "",
            durationMs = 0,
            response_id = rawResponseId
        )
    }
}
