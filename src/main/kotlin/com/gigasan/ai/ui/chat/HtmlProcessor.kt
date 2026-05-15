package com.gigasan.ai.ui.chat

import com.intellij.openapi.diagnostic.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object HtmlProcessor {

    private val logger = Logger.getInstance(this::class.java.name)

    fun getFormatedTime(id: String): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val instant = Instant.ofEpochMilli(id.toLong())
        val dateTime = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dateTime.format(formatter)
    }

    fun transformCodeBlocks(html: String): String {
        // Регулярное выражение находит блоки точно в формате, который вы указали.
        // Поддерживает пробелы/переносы строк между тегами <pre> и <code>,
        // но сохраняет ВЕСЬ внутренний код (включая отступы и переносы) без изменений.
        val regex = Regex("""<pre class="language-(\w+)"\s*>\s*<code class="language-\1"\s*>([\s\S]*?)</code>\s*</pre>""")

        return regex.replace(html) { matchResult ->
            val language = matchResult.groupValues[1]   // например, "python"
            val codeContent = matchResult.groupValues[2] // весь код внутри <code>...</code>
            """
            <div class="code-block closed">
             <div class="code-header">
             <span>$language</span>
             <button onclick="toggleCode(this)">expand</button>
             <button onclick="copyCode(this)">copy</button>
             </div>

             <pre><code class="language-$language">$codeContent</code></pre>
            </div>
            """.trimIndent()
        }
    }

    // Пример использования (полная программа):
    // Читает весь HTML из stdin и выводит преобразованный результат в stdout.
    // Можно запустить как обычный Kotlin-файл.
    fun main() {
        val html = System.`in`.bufferedReader().use { it.readText() }
        val result = transformCodeBlocks(html)
        println(result)
    }

    fun fixTableFormatting(input: String): String {
        // Добавляем перенос строки перед первой чертой таблицы,
        // если это похоже на начало заголовка
        return input.replace(Regex("""(\w)\s*\|"""), "$1\n|")
    }

    private fun cleanMarkdownHtml(rawHtml: String): String {
        var html = rawHtml

        // === Очистка через Jsoup — очень эффективно ===
        val doc: Document = Jsoup.parseBodyFragment(html)

        // Удаляем всё, что связано с IntelliJ highlighter и copy buttons
        doc.select("div.code-fence-highlighter-copy-button, .code-fence-highlighter-copy-button-icon, .tooltiptext").remove()
        doc.select("pre").forEach { pre ->
            pre.select("div").remove()           // удаляем обёртки внутри pre
        }

        // Удаляем все md-src-pos и data-* атрибуты
        doc.select("*").forEach { element ->
            element.removeAttr("md-src-pos")
            element.removeAttr("data-src-pos")
            element.removeAttr("data-fence-content")
            if (element.attr("style").isBlank()) {
                element.removeAttr("style")
            }
        }

        // Делаем чистый HTML (без лишних обёрток)
        html = doc.body().html()
        return html
    }

    // Простая функция определения языка
    fun detectLanguage(code: String): String {

        val text = code.lowercase().trim()

        return when {

            // JSON
            text.startsWith("{") &&
                    text.endsWith("}") &&
                    text.contains(":")
                -> "json"

            // Kotlin
            (
                    "fun " in text &&
                            ("val " in text || "var " in text)
                    ) ||
                    "println(" in text
                -> "kotlin"

            // Java
            "public static void main" in text ||
                    "system.out.println" in text
                -> "java"

            // Python
            Regex("""def\s+\w+\(""")
                .containsMatchIn(text)
                    ||
                    "print(" in text &&
                    ":" in text
                -> "python"

            // JavaScript
            "console.log" in text ||
                    "function " in text ||
                    "document.queryselector" in text
                -> "javascript"

            // Bash
            text.startsWith("#!/bin/bash") ||
                    text.startsWith("#!/bin/sh") ||
                    "sudo " in text ||
                    "apt install" in text
                -> "bash"

            // Go
            "package main" in text &&
                    "func main()" in text
                -> "go"

            // Rust
            "fn main()" in text ||
                    "println!" in text
                -> "rust"

            // C
            "#include <stdio.h>" in text
                -> "c"

            // C++
            "#include <iostream>" in text ||
                    "std::cout" in text
                -> "cpp"

            // Pascal
            "begin" in text &&
                    "end." in text
                -> "pascal"

            // Lisp
            text.startsWith("(") &&
                    ("defun" in text || "lambda" in text)
                -> "lisp"

            else -> "plaintext"
        }
    }


    //    details pre {
    //        white-space: pre-wrap;      /* перенос строк */
    //        word-break: break-word;     /* перенос длинных слов */
    //        overflow-wrap: anywhere;    /* страховка */
    //
    //        max-width: 100%;            /* не вылазит за контейнер */
    //        overflow-x: auto;           /* если всё же длинно */
    //
    //        line-height: 1.4;           /* нормальный интервал */
    //        font-family: monospace;
    //
    //        padding: 8px;
    //        background: #f6f8fa;        /* опционально */
    //        border-radius: 6px;
    //    } 🗨️ 🧠

    fun insertReasoning(reasoning: String): String {
        return """
            <details style="margin-top:8px;">
            <summary>Размышления 🗨️</summary>
            <div class="reasoning">${reasoning}</div>
            </details>
        """.trimIndent()
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

    val prismAlias = mapOf(
        // TypeScript
        "ts" to "typescript",
        "typescript" to "typescript",

        // JavaScript
        "js" to "javascript",
        "javascript" to "javascript",

        // HTML
        "html" to "markup",

        // Bash
        "sh" to "bash",
        "shell" to "bash",

        // C#
        "c#" to "csharp",
        "csharp" to "csharp",
        "cs" to "csharp"
    )

    fun normalizeLanguage(lang: String?): String {
        if (lang.isNullOrBlank()) return "plaintext"
        return prismAlias[lang.lowercase()] ?: lang.lowercase()
    }


    fun wrapCode(code: String?, language: String? = null): String {
        if (code.isNullOrBlank()) return ""

        val detected = language ?: detectLanguage(code)

        val prismLang = prismAlias[detected] ?: detected

        //logger.info("wrapCode: Detected language: $prismLang")
        val fence = if (code.contains("```")) "````" else "```"

        /*
            val lang = language
                ?.takeIf { it.isNotBlank() }
                ?.let { it }
                ?: ""
        */

        return "$fence$prismLang\n$code\n$fence"
    }

    fun wrapData(block: String, begin: String = "===DATA===", end: String = "===DATA END==="): String {
        if (block.isEmpty()) return ""
        return "\n$begin\n\n$block\n\n$end\n"
    }



}