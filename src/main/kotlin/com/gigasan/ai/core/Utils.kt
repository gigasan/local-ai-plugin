package com.gigasan.ai.core

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.util.ui.UIUtil
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import java.awt.Color
import java.awt.datatransfer.StringSelection

object TokenCalculator {
    private val registry = Encodings.newDefaultEncodingRegistry()
    private val encoding = registry.getEncoding(EncodingType.O200K_BASE)

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

private fun isDarkColor(color: Color): Boolean {
    // perceptual luminance
    val luminance = (
            0.2126 * color.red +
                    0.7152 * color.green +
                    0.0722 * color.blue
            ) / 255

    return luminance < 0.5
}

fun isDarkTheme(): Boolean {
    val bg = UIUtil.getPanelBackground()
    return isDarkColor(bg)
}

fun copyToClipboard(text: String) {
    if (text.isNotEmpty()) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}
