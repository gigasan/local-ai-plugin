package com.gigasan.ai.runtime

import com.gigasan.ai.config.PluginConfigProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.intellij.markdown.html.URI
import java.io.StringReader
import java.net.HttpURLConnection

@Service(Service.Level.PROJECT)
class LocalAIService(private val project: Project) {
    private val provider = project.service<PluginConfigProvider>()
    private val logger = Logger.getInstance("LocalAIService")

// ==================== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ====================

    /**
     * 🔥 УНИВЕРСАЛЬНЫЙ ПАРСЕР — работает со всеми известными форматами:
     * 1. Старый LM Studio /api/v1/chat (output + content как строка)
     * 2. /v1/chat/completions (choices)
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

    // "https://api.openai.com/v1/responses"
    suspend fun createResponse(url: String, model: String, prompt: String): String {
        val client = HttpClient(CIO)

        val response: JsonObject = client.post(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer YOUR_API_KEY")
                append(HttpHeaders.ContentType, "application/json")
            }
            setBody("""{
            "model": "$model",
            "input": "$prompt"
        }""")
        }.body()

        return response.toString()
    }


    // https://api.openai.com/v1/responses
    fun createRequest(url: String, model: String, prompt: String): Request {

        val json = """
                    {
                      "model": "$model",
                      "input": "$prompt"
                    }
                    """

        val request = Request.Builder()
            .url("$url")
            .addHeader("Authorization", "Bearer YOUR_API_KEY")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        return request
    }


    /**
     * Общая реализация HTTP-запроса + обработка ошибок.
     * Обе публичные функции теперь просто вызывают её с разным префиксом ошибки.
     */
    private fun callGenericAI(prompt: String, errorPrefix: String): String {
        if (prompt.isBlank()) return "❌ You cannot send an empty request."

        return try {

            val chatFull = provider.buildUrl() + provider.buildChatEndpoint()
            val requestBody = provider.buildRequestBody(prompt)

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
            logger.info("json body: $jsonBody")
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
            logger.info("jsonResponse: $jsonResponse")

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