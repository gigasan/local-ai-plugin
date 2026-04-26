package com.gigasan.ai.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import java.io.File
import java.time.LocalDateTime


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


