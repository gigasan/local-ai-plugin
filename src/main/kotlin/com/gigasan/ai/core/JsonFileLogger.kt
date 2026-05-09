package com.gigasan.ai.core

import com.gigasan.ai.config.storage.PluginSettingsService
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.intellij.openapi.project.Project

interface JsonFileLogger {

    // Настройки путей и формата
    private companion object {
        val timestampFormatter: DateTimeFormatter? = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        private val logger = Logger.getInstance("JsonFileLogger")
    }

    public fun saveJson(project: Project, prefix: String, content: String, prefixIdx: Int? = null) {
        if (!PluginSettingsService.instance.state.enableDebugLog) {
            return
        }
        try {
            val projectPath = project.basePath?:return
            val logsFolder = File(projectPath, "json_logs")
            if (!logsFolder.exists()) {
                val created = logsFolder.mkdirs()
                if (!created) {
                    logger.warn("Could not create directory: ${logsFolder.absolutePath}")
                }
            }
            val timestamp = LocalDateTime.now().format(timestampFormatter)

            val fileName = when {
                prefixIdx != null -> "${prefixIdx} ${prefix}.json"
                else -> "${prefix}_$timestamp.json"
            }

            //val fileName = "${prefix}_$timestamp.json"
            val targetFile = File(logsFolder, fileName)
            targetFile.writeText(content)

            // Для отладки используйте .absolutePath, а не просто объект
            logger.info("JSON successfully saved to: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            // Используем стандартный println или ваш логгер
            logger.warn("Failed to save JSON: ${e.message}")
        }
    }
}

// Объект, который дает доступ к методам интерфейса без создания классов
object FileLogger : JsonFileLogger
