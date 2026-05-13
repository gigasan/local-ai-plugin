package com.gigasan.ai.core

import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.runtime.AIMetrics
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleListCellRenderer
import java.awt.Component
import java.io.File
import java.time.LocalDateTime
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

object TokenCalculator {
    private val registry = Encodings.newDefaultEncodingRegistry()
    private val encoding = registry.getEncoding(EncodingType.CL100K_BASE)

    fun countTokens(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return encoding.countTokens(text)
    }
}

fun countWords(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    // Удаляем лишние пробелы по краям и делим по любым пробельным символам
    return text.trim().split("\\s+".toRegex()).size
}

fun estimateTokens(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    // Грубая оценка: символы / 3.5 для смешанного текста (RU/EN)
    return (text.length / 3.5).toInt().coerceAtLeast(1)
}

fun createTooltipRenderer() = object : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val fullText = value?.toString() ?: ""

        // В самом списке (в выпадающем меню) показываем только первую строку,
        // чтобы комбобокс не раздувался.
        val previewText = fullText.lineSequence().firstOrNull() ?: ""

        val c = super.getListCellRendererComponent(list, previewText, index, isSelected, cellHasFocus) as JComponent

        if (value != null) {
            // Экранируем HTML спецсимволы, чтобы текст не интерпретировался как теги
            val escapedText = fullText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            // Оборачиваем в html и pre для сохранения форматирования
            // Тег <pre> идеально сохраняет табы и переносы
            c.toolTipText = "<html><pre>$escapedText</pre></html>"
        }

        return c
    }
}

fun createModelListRenderer(modelsList: List<Model>) = object : SimpleListCellRenderer<String>() {
    override fun customize(
        list: JList<out String>,
        value: String?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        text = value ?: ""

        // Отрисовка только для элементов в выпадающем списке (index >= 0)
        if (index >= 0 && value != null) {
            val modelInfo = modelsList.find { it.displayName == value }

            if (modelInfo != null) {
                val sizeHuman = StringUtil.formatFileSize(modelInfo.size)
                val ctxHuman = AIMetrics.formatSize(modelInfo.maxContext)

                val reasoningStatus = if (modelInfo.reasoningOptions.isNullOrEmpty() || modelInfo.defaultReasoning == null) {
                    "<i color='gray'>not supported</i>"
                } else {
                    "${modelInfo.reasoningOptions.joinToString(", ", "[", "]")}: ${modelInfo.defaultReasoning}"
                }

                toolTipText = """
                <html>
                    <div style='margin: 5px;'>
                    <b>Model parameters:</b><br/>
                    • Name: ${modelInfo.displayName} <br/>
                    • Max context: $ctxHuman <br/>
                    • Quant: ${modelInfo.quant} <br/>
                    • Params: ${modelInfo.params} <br/>
                    • Size: $sizeHuman <br/>
                    <hr/>
                    • Reasoning: $reasoningStatus <br/>
                    • Tools: ${modelInfo.tools} <br/>
                    • Format: ${modelInfo.format} <br/>
                    • Arc: ${modelInfo.arc} <br/>
                    • Source: ${modelInfo.source} <br/>
                    • Key: ${modelInfo.key} <br/>
                    </div>
                </html>
                """.trimIndent()
            }
        } else {
            toolTipText = null
        }
    }
}

fun projectHasKotlinSource(project: Project): Boolean {
    var found = false

    ProjectRootManager.getInstance(project)
        .fileIndex
        .iterateContent { file ->
            if (file.extension == "kt") {
                found = true
                false // остановить обход
            } else {
                true // продолжить
            }
        }

    return found
}

fun TextRange.toIntRange(): IntRange = IntRange(this.startOffset, this.endOffset)

fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

fun Long.toHumanReadableSize(): String {
    if (this < 1024) return "$this B"
    val exp = (Math.log(this.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", this / Math.pow(1024.0, exp.toDouble()), pre)
}

fun wrapCodeBlock(text: String, code: String, language: String?=null): String {
    val lang = language?:"text"
    val result = """
$text
```$lang
$code
```
""".trimIndent()
    //logger.info("codeBlock: $result")
    return result
}

fun wrapCode(code: String?, language: String? = null): String {
    if (code.isNullOrBlank()) return ""

    val lang = language ?: "text"
    val fence = if (code.contains("```")) "````" else "```"

    return "$fence$lang\n$code\n$fence"
}

fun wrapData(block: String, begin: String = "===DATA===", end: String = "===DATA END==="): String {
    if (block.isEmpty()) return ""
    return "\n$begin\n\n$block\n\n$end\n"
}
