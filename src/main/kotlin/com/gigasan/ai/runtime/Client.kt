package com.gigasan.ai.runtime

import com.gigasan.ai.config.BackendApi
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.runtime.parser.ResponseParser
import com.gigasan.ai.runtime.parser.ResponseResult
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = Logger.getInstance("Client")

class Client(private val project: Project) {
    // Используем делегат lazy, чтобы провайдер инициализировался при первом обращении
    private val provider: PluginConfigProvider by lazy {
        project.service<PluginConfigProvider>()
    }

    private val responseParser by lazy  { ResponseParser(project) }

    fun send(ctx: ChatContext): ResponseResult {
        val baseUrl = provider.buildUrl()
        val endpoint = provider.buildChatEndpoint()
        val apiKey = provider.buildApiKey()
        val backend = provider.buildBackend()
        logger.warn("endpoint: $endpoint, apiKey: $apiKey, backend: ${backend::class.simpleName}")
        val (request, parser) = when (backend.api to endpoint) {
            BackendApi.LM_STUDIO_API to "/api/v1/chat" -> LmStudioAdapter.toRequest(project, ctx, baseUrl+endpoint, apiKey) to responseParser::parse
            BackendApi.OPEN_AI_API to "/v1/responses" -> ResponsesAdapter.toRequest(project, ctx, baseUrl+endpoint, apiKey) to responseParser::parse
            BackendApi.OPEN_AI_API to "/v1/chat/completions" -> ChatCompletionsAdapter.toRequest(project, ctx, baseUrl+endpoint, apiKey) to responseParser::parse
            else -> DefaultAdapter.toRequest(project, ctx, baseUrl+endpoint, apiKey) to responseParser::parse
        }

        val response = HttpClientProvider.client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()

        return parser(raw)
    }

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



