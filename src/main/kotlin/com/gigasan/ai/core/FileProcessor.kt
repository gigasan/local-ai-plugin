package com.gigasan.ai.core

import com.gigasan.ai.runtime.LocalAIService
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.math.min

object FileProcessor {

    // Максимальное количество символов на один чанк (примерно под лимит токенов модели)
    private const val CHUNK_SIZE = 4000

    /**
     * Главная функция: обрабатывает файл через Local AI
     * @param file файл, который нужно обработать
     * @param instruction что нужно сделать с содержимым
     * @param onResult callback с результатом обработки
     */
    fun processFile(
        file: File,
        instruction: String,
        onResult: (String) -> Unit
    ) {
        // 1. Читаем текст из файла
        val text = Files.readString(file.toPath(), StandardCharsets.UTF_8)

        // 2. Разбиваем текст на чанки
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = min(start + CHUNK_SIZE, text.length)
            chunks.add(text.substring(start, end))
            start = end
        }

        // 3. Асинхронная обработка всех чанков
        val results = mutableListOf<String>()
        Thread {
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    // Пример prompt: объединяем инструкцию + текущий чанк
                    val prompt = """
                        Чанк ${index + 1}/${chunks.size} из файла "${file.name}":
                        $chunk
                        
                        Инструкция: $instruction
                    """.trimIndent()

                    // Вызов Local AI
                    val response = LocalAIService.callLocalAI(prompt)

                    results.add(response)
                } catch (e: Exception) {
                    results.add("Ошибка при обработке чанка ${index + 1}: ${e.message}")
                }
            }

            // 4. Объединяем все ответы и вызываем callback в EDT
            val finalResult = results.joinToString("\n\n---\n\n")
            SwingUtilities.invokeLater {
                onResult(finalResult)
            }
        }.start()
    }
}