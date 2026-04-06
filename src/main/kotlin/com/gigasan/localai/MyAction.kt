package com.gigasan.localai

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.intellij.markdown.html.URI
import java.net.HttpURLConnection
import java.net.URL

class MyAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText?.trim() ?: "Hello from RustRover!"

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Local AI → LM Studio", false) {
                override fun run(indicator: ProgressIndicator) {
                    val response = callLocalAI(selectedText)
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(project, response, "Local AI Response")
                    }
                }
            }
        )
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.selectedText?.isNotBlank() == true
    }

    private fun callLocalAI(prompt: String): String {
        return try {
            val url = URI.create("http://192.168.0.104:1234/v1/chat/completions").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            // 🔹 Таймауты
            conn.connectTimeout = 5 * 60_000   // 1 минута на соединение
            conn.readTimeout = 5 * 60_000     // 5 минут на чтение ответа

            // JSON для запроса
            val jsonBody = """
            {
                "model": "qwen/qwen3.5-9b",
                "messages": [{"role": "user", "content": "$prompt"}],
                "stream": false
            }
            """.trimIndent()


            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                return "❌ Ошибка LM Studio (${conn.responseCode}):\n$error"
            }

            val jsonResponse = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val responseText = jsonResponse
                .substringAfter("\"response\":\"", "")
                .substringBeforeLast("\"", jsonResponse)

            responseText.ifBlank { "LM Studio вернул пустой ответ" }
        } catch (e: Exception) {
            "❌ Не удалось подключиться к LM Studio\n\n${e.message ?: e::class.simpleName}"
        }
    }



//    private fun callLocalAI(prompt: String): String {
//        return try {
//            val url = URL("http://192.168.0.104:1234/v1/chat/completions")
//            val conn = url.openConnection() as HttpURLConnection
//            conn.requestMethod = "POST"
//            conn.doOutput = true
//            conn.setRequestProperty("Content-Type", "application/json")
//
//            val body = """
//                {
//                    "model": "qwen/qwen3.5-9b",
//                    "messages": [{"role": "user", "content": "$prompt"}]
//                }
//            """.trimIndent()
//
//            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
//
//            if (conn.responseCode != 200) {
//                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
//                return "❌ Ошибка LM Studio (${conn.responseCode}):\n$error"
//            }
//
//            val jsonResponse = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
//            val responseText = jsonResponse
//                .substringAfter("\"response\":\"", "")
//                .substringBeforeLast("\"", jsonResponse)
//
//            responseText.ifBlank { "LM Studio вернул пустой ответ" }
//        } catch (e: Exception) {
//            "❌ Не удалось подключиться к LM Studio\n\n${e.message ?: e::class.simpleName}"
//        }
//    }
}