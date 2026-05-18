package com.gigasan.ai.ui.chat

import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.runtime.*
import com.gigasan.ai.runtime.parser.ResponseResult
import com.gigasan.ai.runtime.parser.withDuration
import com.gigasan.ai.ui.chat.HtmlProcessor.wrapCode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.time.Instant

sealed class TaskResult {
    data class Success(val task: TaskData) : TaskResult()
    data class Error(val task: TaskData, val error: Throwable) : TaskResult()
}

class TaskSender(
    private val project: Project,
    private val provider: PluginConfigProvider,
) {
    private val logger = Logger.getInstance(TaskSender::class.java.name)

    fun onStreamEvent(event: StreamEvent, indicator: ProgressIndicator) {
        val baseMsg = event.indicatorText.replace("\n", " ").trim()
        val fraction = event.indicatorFraction

        // 1. Управление состоянием детерминированности прогресс-бара
        if (fraction == null) {
            if (!indicator.isIndeterminate) {
                indicator.fraction = 0.0
                indicator.isIndeterminate = true
            }
        } else {
            if (indicator.isIndeterminate) {
                indicator.isIndeterminate = false
            }
            indicator.fraction = fraction.coerceIn(0.0, 0.99)
        }

        // 2. Форматирование текста статуса (без бегущей строки)
        // Если нужно строго удерживать ширину в 25 символов:
        indicator.text = when {
            baseMsg.length > 25 -> baseMsg.take(22) + "..." // Элегантное сокращение длинных строк
            else -> baseMsg.padEnd(25)                      // Добивка пробелами для фиксированной ширины
        }

        // Если фиксированная ширина и обрезка не важны, можно сделать просто:
        // indicator.text = baseMsg.ifEmpty { " " }

        indicator.checkCanceled()
    }

    fun processChatTask(task: TaskData, indicator: ProgressIndicator): TaskResult {
        task.model = provider.buildChatModel()
        return try {
            //logger.info("task = $task")
            //logger.info("model = $model")
            val request = task.question.trimIndent()
            val chatContext = ChatRequestBuilder(project)
                .system(task.instruction)
                .memory(limit = 5)
                .user(request)
                .stream(provider.buildStream())
                .model(provider.buildChatModel())
                .maxTokens(provider.buildMaxTokenLimit())
                .build(task)
            //logger.info("processChatTask $chatContext")
            val stateManager = StateManager()
            val stateMachine = StateMachine()
            val adapter = BackendAdapter(project)
            val http = HttpClientProvider.client
            val clientStream = ClientStream(
                adapter = adapter,
                http = http,
                stateManager = stateManager,
                stateMachine = stateMachine
            )
            //val toolClient = ToolOrchestrator(AIClientPost()) // url, api and backend from PluginSettingsService
            val startTime = System.currentTimeMillis()
            val responseResult = clientStream.execute(project, chatContext, indicator) { event ->
                onStreamEvent(event, indicator)
            }

            //val toolResult = toolClient.run(chatContext)
            //logger.warn("toolResult = $toolResult")
            val endTime = System.currentTimeMillis()
            val result = responseResult.withDuration(endTime - startTime)

            val updated = task.copy(
                model = chatContext.model,
                title = when (result) {
                    is ResponseResult.Success -> {
                        "✔\uFE0F${task.title}"
                    }
                    is ResponseResult.Error -> {
                        "❌${task.title}"
                    }
                },
                answer = when (result) {
                    is ResponseResult.Success -> {
                        result.text
                    }
                    is ResponseResult.Error -> {
                        result.message
                    }
                },

                footer = AIMetrics.buildFooter(
                    usage = if (result is ResponseResult.Success) { result.usage} else {null},
                    durationMs = result.durationMs
                ),

                reasoning = if (result is ResponseResult.Success) { result.reasoning?.trim()?:""} else {""},

                status = when (result) {
                    is ResponseResult.Success -> {
                        TaskStatus.DONE
                    }
                    is ResponseResult.Error -> {
                        TaskStatus.ERROR
                    }
                },
            )

            TaskResult.Success(updated)
        } catch (e: Exception) {
            TaskResult.Error(
                task.copy(
                    status = TaskStatus.ERROR,
                    title = "❌${task.title}",
                    answer = wrapCode(e.message?:"unknown error"),
                ),
                e)
        }
    }

    fun runChatTaskInBackground(project: Project?, task: TaskData, onUpdate: (TaskData) -> Unit) {

        // Включаем canBeCancelled = true (третий параметр)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Ai processing", true) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Sending request..."
                indicator.isIndeterminate = true

                // Передаем индикатор в процесс, чтобы внутри цикла чтения
                // делать индикатор.checkCanceled()
                when (val result = processChatTask(task, indicator)) {
                    is TaskResult.Success -> {
                        MemorySystem.add(result.task)
                        onUpdate(result.task)
                    }
                    is TaskResult.Error -> {
                        logger.warn("Task failed", result.error)
                        onUpdate(result.task)
                    }
                }
            }

            override fun onCancel() {
                // Опционально: логируем отмену пользователем
                logger.info("Task cancelled by user")
            }

            override fun onThrowable(error: Throwable) {
                // Если сокет закрыт, это упадет сюда
                ApplicationManager.getApplication().invokeLater {
                    onUpdate(task.copy(status = TaskStatus.ERROR))
                }
            }
        })
    }

    fun send(task: TaskData, onResult: (TaskData) -> Unit) {
        // Показываем задачу как отправленную в работу
        invokeLater {
            task.status = TaskStatus.SENDING
            onResult(task)
        }

        runChatTaskInBackground(project, task, onResult)
    }

    private data class MockResponse(val choices: List<MockChoice>) {
        data class MockMessage(val content: String)
        data class MockChoice(val message: MockMessage)
    }
}
