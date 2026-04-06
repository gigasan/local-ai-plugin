package com.gigasan.localai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.intellij.markdown.html.URI
import java.net.HttpURLConnection
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

object LocalAIService {

// ==================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

    /**
     * Формирует URL в зависимости от chatEndpointIndex (оставляем логику как была).
     */
    private fun buildChatUrl(settings: PluginSettings): String {
        return when (settings.chatEndpointIndex) {
            0 -> settings.baseUrl.trimEnd('/') + "/api/v1/chat"
            1 -> settings.baseUrl.trimEnd('/') + "/v1/chat/completions"
            2 -> settings.baseUrl.trimEnd('/') + "/v1/responses"
            else -> settings.baseUrl.trimEnd('/') + settings.chatEndpoint.trim()
        }
    }

    /**
     * Формирует тело запроса в зависимости от endpointIndex.
     * Логика requestBody вынесена сюда, чтобы не дублировать в двух функциях.
     */
    private fun buildRequestBody(prompt: String, settings: PluginSettings): JsonObject {
        val selectedModel = settings.selectedModelKey.ifBlank { "default" }

        return JsonObject().apply {
            when (settings.chatEndpointIndex) {
                1 -> { // /v1/chat/completions (OpenAI-совместимый)
                    addProperty("model", selectedModel)
                    addProperty("stream", false)

                    val messages = JsonArray()
                    messages.add(
                        JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", prompt)
                        }
                    )
                    add("messages", messages)
                }

                2 -> { // /v1/responses
                    addProperty("model", selectedModel)
                    addProperty("input", prompt)

                    val modalities = JsonArray()
                    modalities.add("text")
                    add("modalities", modalities)
                }

                else -> { // 0 или любой другой → старый /api/v1/chat (LM Studio)
                    addProperty("model", selectedModel)
                    addProperty("input", prompt)
                }
            }
        }
    }

    /**
     * 🔥 УНИВЕРСАЛЬНЫЙ ПАРСЕР — работает со всеми известными форматами:
     * 1. /v1/chat/completions (choices)
     * 2. Старый LM Studio /api/v1/chat (output + content как строка)
     * 3. Новый /v1/responses (output + content как массив + output_text)
     */
    private fun parseResponse(jsonResponse: String): String {
        if (jsonResponse.isBlank()) return ""

        val reader = JsonReader(StringReader(jsonResponse)).apply {
            strictness = Strictness.LENIENT
        }
        val json = JsonParser.parseReader(reader).asJsonObject

        return when {
            // 1. OpenAI-совместимый чат-комплит
            json.has("choices") && json.get("choices").isJsonArray -> {
                val choices = json.getAsJsonArray("choices")
                if (choices.size() > 0) {
                    val message = choices[0].asJsonObject.getAsJsonObject("message")
                    message?.get("content")?.asString.orEmpty()
                } else ""
            }

            // 2. Формат с "output" (LM Studio + новый Responses API)
            json.has("output") && json.get("output").isJsonArray -> {
                val outputArray = json.getAsJsonArray("output")

                outputArray.asSequence().mapNotNull { elem ->
                    val obj = elem.asJsonObject
                    if (obj.get("type")?.asString != "message") return@mapNotNull null

                    val contentElement = obj.get("content")

                    when {
                        // Старый формат: content — просто строка
                        contentElement?.isJsonPrimitive == true -> contentElement.asString

                        // Новый формат: content — массив объектов {type: "output_text", text: "..."}
                        contentElement?.isJsonArray == true -> {
                            contentElement.asJsonArray.asSequence()
                                .mapNotNull { contentItem ->
                                    val item = contentItem.asJsonObject
                                    if (item.get("type")?.asString == "output_text") {
                                        item.get("text")?.asString
                                    } else null
                                }
                                .joinToString("")
                        }

                        else -> null
                    }
                }.joinToString("\n")
            }

            // 3. Фолбэк (на всякий случай)
            else -> json.get("content")?.asString
                ?: json.get("text")?.asString
                ?: ""
        }
    }

    /**
     * Общая реализация HTTP-запроса + обработка ошибок.
     * Обе публичные функции теперь просто вызывают её с разным префиксом ошибки.
     */
    private fun callGenericAI(prompt: String, errorPrefix: String): String {
        if (prompt.isBlank()) return "❌ You cannot send an empty request."

        return try {
            val settings = PluginSettings.instance
            val chatFull = buildChatUrl(settings)

            // Для отладки (как было в callLocalAI)
            println("Calling AI endpoint: $chatFull")

            val requestBody = buildRequestBody(prompt, settings)

            val url = URI.create(chatFull).toURL()
            val conn = url.openConnection() as HttpURLConnection

            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            // Таймауты
            conn.connectTimeout = 5 * 60_000
            conn.readTimeout = 60 * 60_000

            // Отправка тела
            val jsonBody = Gson().toJson(requestBody)
            conn.outputStream.use {
                it.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            // Обработка HTTP-ошибок
            if (conn.responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                return "❌ Error $errorPrefix (${conn.responseCode}):\n$error"
            }

            // Чтение ответа
            val jsonResponse = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()

            // 🔥 Парсинг по структуре JSON (без флагов!)
            var responseText = parseResponse(jsonResponse)

            // легкое форматирование
            responseText = formatResponse(responseText)

            responseText.ifBlank { "$errorPrefix returned an empty response" }
        } catch (e: Exception) {
            "❌ Failed to connect to $errorPrefix\n\n${e.message ?: e::class.simpleName}"
        }
    }

// ==================== РЕФАКТОРИНГ ДВУХ ФУНКЦИЙ ====================

    fun callLocalAI(prompt: String): String =
        callGenericAI(prompt, "Local AI")

    fun callRenoteAI(prompt: String): String =
        callGenericAI(prompt, "Remote AI")














    fun extractContent(json: String): String {
        val jsonObject = JsonParser.parseString(json).asJsonObject

        val content = jsonObject
            .getAsJsonArray("choices")[0]
            .asJsonObject
            .getAsJsonObject("message")
            .get("content")
            .asString

        return content.trim()
    }



    fun formatResponse(text: String): String {
        val formattedContent = text.trim().replace("\\n".toRegex(), "\n")
        //println(formattedContent)
        return formattedContent
    }


    // Форматируем блок кода или xml
    private fun formatCodeBlocks(text: String): String {
        // Находим ```...``` или <xml>...</xml>
        var formatted = text

        // Выделяем ```code```
        formatted = formatted.replace("```(.*?)```".toRegex(RegexOption.DOT_MATCHES_ALL)) { match ->
            val code = match.groupValues[1]
            "\n===== CODE BLOCK =====\n$code\n=====================\n"
        }

        // Выделяем xml блоки
        formatted = formatted.replace("(<\\?.*?\\?>|<[^>]+>.*?</[^>]+>)".toRegex(RegexOption.DOT_MATCHES_ALL)) { match ->
            val code = match.value
            "\n===== XML BLOCK =====\n$code\n=====================\n"
        }

        return formatted
    }


}