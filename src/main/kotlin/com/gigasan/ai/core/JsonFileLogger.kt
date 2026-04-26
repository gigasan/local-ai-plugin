package com.gigasan.ai.core

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner.project

interface JsonFileLogger {

    // Настройки путей и формата
    private companion object {
        val timestampFormatter: DateTimeFormatter? = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        //val dir = project.basePath?:return
        private val logger = Logger.getInstance("JsonFileLogger")
    }

    fun saveJson(project: Project, prefix: String, content: String, prefixIdx: Int? = null) {
        try {
            val projectPath = project.basePath?:return
            val logsFolder = File(projectPath, "json_logs")
            if (!logsFolder.exists()) {
                val created = logsFolder.mkdirs()
                if (!created) {
                    logger.error("Could not create directory: ${logsFolder.absolutePath}")
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
            logger.error("Failed to save JSON: ${e.message}")
        }
    }
}
