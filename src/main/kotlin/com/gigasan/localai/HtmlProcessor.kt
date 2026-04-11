package com.gigasan.localai

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object HtmlProcessor {

    fun getFormatedTime(id: String): String {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val instant = java.time.Instant.ofEpochMilli(id.toLong())
        val dateTime = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        return dateTime.format(formatter)
    }

    fun wrapCodeBlock(htmlCode: String): String {
        // Извлекаем язык из класса (например, language-python)
        val languageRegex = """language-(\w+)""".toRegex()
        val matchResult = languageRegex.find(htmlCode)
        val language = matchResult?.groupValues?.get(1) ?: "text"

        // Извлекаем содержимое внутри <code>...</code>
        val codeContentRegex = """<code[^>]*>(.*?)</code>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val codeMatch = codeContentRegex.find(htmlCode)

        val rawCode = codeMatch?.groupValues?.get(1)?.trim() ?: ""

        // Экранируем HTML-символы в коде (чтобы &quot; и т.д. превратились обратно в " и т.п.)
        val decodedCode = rawCode
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")

        // Формируем итоговый HTML по нужному шаблону
        return """
        <div class="code-block">
            <div class="code-header">
                <span>$language</span>
                <button onclick="toggleCode(this)">collapse</button>
                <button onclick="copyCode(this)">copy</button>
            </div>
            
            <pre><code class="language-$language">$decodedCode</code></pre>
        </div>
    """.trimIndent()
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
<div class="code-block">
 <div class="code-header">
 <span>$language</span>
 <button onclick="toggleCode(this)">collapse</button>
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

    // Простая функция определения языка для class
    private fun detectLanguage(code: String): String {
        return when {
            code.contains("fn main") || code.contains("println!") -> "rust"
            code.contains("fun ") || code.contains("override fun") -> "kotlin"
            code.contains("public class") || code.contains("System.out") -> "java"
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


}