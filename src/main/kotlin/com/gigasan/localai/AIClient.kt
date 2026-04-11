package com.gigasan.localai

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = Logger.getInstance("AIClient")

class AIClient() {

    fun send(ctx: ChatContext): AIResult {

        val provider = DefaultChatConfigProvider(PluginSettings.instance)
        val url = provider.buildChatUrl()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackend()
        logger.warn("url: $url, apiKey: $apiKey, backend: ${backend::class.simpleName}")
        val (request, parser) = when (backend) {
            AIBackendType.LmStudioLegacy ->
                LmStudioAdapter.toRequest(
                    ctx,
                    url,
                    apiKey
                ) to AIResponseParser::parse

            AIBackendType.Responses ->
                ResponsesAdapter.toRequest(
                    ctx,
                    url,
                    apiKey
                ) to AIResponseParser::parse

            AIBackendType.ChatCompletions ->
                ChatCompletionsAdapter.toRequest(
                    ctx,
                    url,
                    apiKey
                ) to AIResponseParser::parse

        }

        val response = HttpClientProvider.client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()

        return parser(raw)
    }

}


sealed class AIBackendType {
    object LmStudioLegacy : AIBackendType()
    object Responses : AIBackendType()
    object ChatCompletions : AIBackendType()
}

fun JsonObject.toHttpRequest(url: String, apiKey: String): Request {
    return Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $apiKey")
        .post(
            this.toString()
                .toRequestBody("application/json".toMediaType())
        )
        .build()
}


object HttpClientProvider {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // 0 = без общего лимита
            .build()
    }
}



