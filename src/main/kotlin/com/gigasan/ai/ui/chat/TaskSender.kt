package com.gigasan.ai.ui.chat

import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.runtime.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import java.time.Instant

class TaskSender(
    private val project: Project,
    private val provider: PluginConfigProvider,
) {
    private val logger = Logger.getInstance(TaskSender::class.java.name)

    fun send(task: TaskData, onResult: (TaskData) -> Unit) {
        // Показываем задачу как отправленную в работу
        invokeLater {
            task.status = TaskStatus.SENDING
            onResult(task)
        }

        runChatTaskInBackground(task, onResult)
    }

    private fun runChatTaskInBackground(
        task: TaskData,
        onUpdate: (TaskData) -> Unit
    ) {
        ProgressManager.getInstance().run(object : com.intellij.openapi.progress.Task.Backgroundable(
            null, "AI processing", true
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = false

                val startTime = System.currentTimeMillis()
                try {
                    val url = provider.buildBaseUrl() + provider.buildChatEndpoint()
                    val model = provider.buildChatModel()

                    // Сборка запроса
                    val requestBuilder = ChatRequestBuilder(project)
                        .system(task.instruction)
                        .memory(limit = 5)
                        .user(task.question)
                        .stream(provider.buildStream())
                        .model(model)
                        .maxTokens(provider.buildMaxTokenLimit())

                    // Выполнение запроса
                    val http = HttpClientProvider.client
                    val localAIService = LocalAIService(project)
                    val request = localAIService.createRequest(url, model, "placeholder") // реальный запрос формируется позже

                    // --- Заглушка для ответа от сервера (для примера) ---
                    val responseMock = """
                        {
                          "choices": [{
                            "message": {
                              "content": "Это имитация ответа нейросети."
                            }
                          }]
                        }
                    """.trim()

                    // Имитация задержки, чтобы выглядеть правдоподобно
                    try { Thread.sleep(1000) } catch (e: Exception) {logger.warn("error",e)}

                    // Разбор ответа
                    val response = processResponse(responseMock)

                    val endTime = System.currentTimeMillis()
                    val result = TaskResult.Success(
                        task.copy(
                            status = TaskStatus.DONE,
                            title = "✔️ ${task.title}",
                            answer = response.choices[0].message.content,
                            footer = "Done in ${endTime - startTime}ms"
                        )
                    )

                    onUpdate(result.task)

                } catch (e: Exception) {
                    val errorResult = TaskResult.Error(
                        task.copy(status = TaskStatus.ERROR, title = "❌ ${task.title}", answer = e.message ?: ""),
                        e
                    )
                    logger.warn("Task failed", e)
                    onUpdate(errorResult.task)
                }
            }

            private fun processResponse(json: String): MockResponse {
                // Разбор JSON-строки с помощью библиотеки (напр, kotlinx.serialization)
                return MockResponse(
                    listOf(MockResponse.MockChoice(MockResponse.MockMessage("Это ответ на русском языке")))
                )
            }
        })
    }

//    data class TaskResult(val task: TaskData) {
////       class Success(task: TaskData) : TaskResult(task)
////        class Error(val error: Throwable) : TaskResult(TaskData(...)) // пример
//    }
    sealed class TaskResult {
        data class Success(val task: TaskData) : TaskResult()
        data class Error(val task: TaskData, val error: Throwable) : TaskResult()
    }

    private data class MockResponse(val choices: List<MockChoice>) {
        data class MockMessage(val content: String)
        data class MockChoice(val message: MockMessage)
    }
}
