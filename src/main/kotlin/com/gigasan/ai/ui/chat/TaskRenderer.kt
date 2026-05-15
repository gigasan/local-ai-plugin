package com.gigasan.ai.ui.chat

import com.gigasan.ai.runtime.AIMetrics
import com.gigasan.ai.ui.chat.ChatPanel.MarkdownRenderer
import com.gigasan.ai.ui.chat.HtmlProcessor.normalizeLanguage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import javax.swing.UIManager
import java.awt.Color
import java.awt.dnd.*
import javax.swing.*

// Вынос UI-логики (renderChatBlocks, buildTaskHtml) в отдельный слой
// Цель: разделить рендеринг и данные
// А renderChatBlocks(tasks) вызывать извне, как отдельный метод, не встроенный внутрь UI-элемента.

private val logger = Logger.getInstance("TaskRenderer")

class TaskRenderer(
    //private val themeManager: ThemeManager // для получения цветов, стилей и т.д.
) {
    fun render(tasks: List<TaskData>): String {

        logger.info("Rendering ${tasks.size} blocks")

        val html = tasks.joinToString("\n\n") { buildHtmlForTask(it) }
        //logger.info("html=$html")

        // ==== PRISM =====

        // scan plain text for language
        val regex = Regex("```(\\w+)")
        val allText = tasks.joinToString("\n") { task ->
            listOf(
                task.question,
                "",
                task.answer
            ).joinToString("\n")
        }

        val rawLanguages = regex.findAll(allText)
            .map { it.groupValues[1] }
            .toSet()

        val prismLanguages = rawLanguages
            .map { normalizeLanguage(it) }
            .toSet()

        //logger.info("languages=$languages")

        return wrapInLayout(html, prismLanguages)
    }

    private fun buildHtmlForTask(task: TaskData): String {

        val description = AIMetrics.buildDescription(
            request = task.question,
            content = "",
            status = task.status,
            maxLen = 100
        )

        val dataTaskId = "data-task-id='${task.id}'"

        val htmlText = buildString {
            append("<details>")
            append("<summary class='chat-header' ${dataTaskId}>${task.title} ${description}</summary>\n")
            append("<div class='chat-body'>")
            append("<div class='chat'>")
            if (task.question.isNotBlank()) {
                append("<div class='bubble-user'>")
                append("<button class='collapse-btn' onclick='toggleBubble(this)'>collapse</button>")
                append(MarkdownRenderer.toHtml(task.question))
                append("</div>")
            }
            if (task.answer.isNotBlank() || task.reasoning.isNotBlank()) {
                append("<div class='bubble-assistant'>")
                append("<button class='collapse-btn' onclick='toggleBubble(this)'>collapse</button>")
                if (task.reasoning.isNotBlank()) {
                    append("<p>")
                    append(HtmlProcessor.insertReasoning(task.reasoning.trim()))
                    append("</p>")
                }
                //append("</div>")
                if (task.answer.isNotBlank()) {
                    append(MarkdownRenderer.toHtml(task.answer))
                }
                append("</div>")
            }
            append("</div>")
            append("</div>")
            if (task.id.isNotBlank()) {
                append("<div class='footer'>")
                append(HtmlProcessor.getFormatedTime(task.id))
                append("\n\n${task.footer}")
                append("</div>")
            }
            append("</details>\n")
        }

        val bubbledHtmlText = HtmlProcessor.transformCodeBlocks(htmlText)
        return bubbledHtmlText
    }

    private fun wrapInLayout(html: String, languages: Set<String>): String {

        // LANGUAGES <PRISM>
        // https://cdn.jsdelivr.net/npm/prismjs/components/
        val deps = mapOf(
            "c" to listOf("clike"),
            "cpp" to listOf("c"),
            "java" to listOf("clike"),
            "kotlin" to listOf("java"),
            "csharp" to listOf("clike")
        )

        fun expandLanguages(lang: String, deps: Map<String, List<String>>, result: MutableSet<String>) {
            for (dep in deps[lang].orEmpty()) {
                expandLanguages(dep, deps, result)
            }
            result.add(lang)
        }

        val result = linkedSetOf<String>() // ВАЖНО: сохраняет порядок
        for (lang in languages.sorted()) {
            expandLanguages(lang, deps, result)
        }

        val prismCss        = loadResource("css/prism-tomorrow.min.css")
        val prismCore       = loadResource("css/prism.min.js")

        val supportedLanguages = setOf(
            "clike", "python", "java", "kotlin", "bash", "go",
            "rust", "c", "cpp", "csharp", "pascal", "lisp", "json",
            "javascript", "typescript", "sql", "markup"
        )

        val loadedList = linkedSetOf<String>()

        for (lang in supportedLanguages) {
            if (result.contains(lang)) {
                logger.info("loading $lang language")
                loadedList.add(
                    loadResource("css/prism-languages/prism-$lang.min.js")
                )
            }
        }

        val prismLangScripts = loadedList.joinToString("\n") { js ->
            "<script>$js</script>"
        }

        // Цвета темы
        val panelBg = UIManager.getColor("Panel.background") ?: JBColor.WHITE
        val textColor = UIManager.getColor("Label.foreground") ?: JBColor.BLACK
        val codeBg = EditorColorsManager.getInstance().globalScheme.defaultBackground

        val bubbleBg = if (isDarkTheme()) {
            panelBg.adjustBrightness(0.2f)
        } else {
            panelBg.adjustBrightness(0.8f)
        }

        val bubbleText = if (isDarkTheme()) {
            textColor.adjustBrightness(0.2f)
        } else {
            textColor.adjustBrightness(0.8f)
        }

        val styledHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <style>
                /* 1. ОБЩИЕ НАСТРОЙКИ (Layout) */
                body { 
                    background-color: #191a1c; 
                    color: #d1d3d9; 
                    margin: 0; 
                    padding: 12px 16px; 
                    font-family: system-ui, -apple-system, sans-serif;
                    line-height: 1.6;
                    font-size: 13px;
                }
            
                .chat {
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                }
            
                /* 2. ПУЗЫРИ СООБЩЕНИЙ */
                .bubble-user, .bubble-assistant {
                    padding: 10px 14px;
                    background: #141516; /* Тёмный фон для обоих */
                    color: #a7a9ae;
                    max-width: 85%;
                }
            
                .bubble-user {
                    align-self: flex-start;
                    border-radius: 12px 12px 12px 4px;
                }
            
                /* Контейнер пузыря теперь должен быть относительным для позиционирования кнопки */
                .bubble-user {
                    position: relative;
                    transition: max-height 0.3s ease-out;
                    overflow: hidden;
                }
    
                /* Класс для свернутого состояния */
                .bubble-user.collapsed {
                    max-height: 40px; /* Высота одной строки + отступы */
                    cursor: pointer;
                }

                /* Кнопка сворачивания */
                .collapse-btn {
                    position: absolute;
                    top: 4px;
                    right: 8px;
                    background: #2d2d2d;
                    color: #a7a9ae;
                    border: 1px solid #444;
                    border-radius: 4px;
                    font-size: 10px;
                    cursor: pointer;
                    opacity: 0.5;
                    z-index: 20;
                }
    
                .collapse-btn:hover {
                    opacity: 1;
                    background: #3d3d3d;
                }
    
                /* Чтобы текст в свернутом виде не обрывался некрасиво */
                .bubble-user.collapsed::after {
                    content: "";
                    position: absolute;
                    bottom: 0;
                    left: 0;
                    width: 100%;
                    height: 20px;
                    background: linear-gradient(transparent, #141516);
                }

                .bubble-assistant .collapse-btn {
                    left: 8px;
                    right: auto;
                }
            
                .bubble-assistant {
                    align-self: flex-end;
                    border-radius: 12px 12px 4px 12px;
                    max-width: 90%;
                }
                .bubble-assistant > :not(.collapse-btn) {
                    margin-top: 10px;
                }
                .bubble-assistant .code-block {
                    margin-top: 20px;
                }
                .bubble-user > :not(.collapse-btn) {
                    margin-top: 10px;
                }        
                /* Контейнер пузыря теперь должен быть относительным для позиционирования кнопки */
                .bubble-assistant {
                    position: relative;
                    transition: max-height 0.3s ease-out;
                    overflow: hidden;
                }
    
                /* Класс для свернутого состояния */
                .bubble-assistant.collapsed {
                    max-height: 40px; /* Высота одной строки + отступы */
                    cursor: pointer;
                }
                
                /* Чтобы текст в свернутом виде не обрывался некрасиво */
                .bubble-assistant.collapsed::after {
                    content: "";
                    position: absolute;
                    bottom: 0;
                    left: 0;
                    width: 100%;
                    height: 20px;
                    background: linear-gradient(transparent, #141516);
                }
            
            
                /* 3. БЛОКИ КОДА (Интеграция с Prism.js) */
                
                .code-block pre {
                    display: block; /* или whatever по умолчанию */
                }
                
                .code-block.closed pre {
                    display: none;
                }
                
                /* Обертка для кнопок (Copy/Collapse) */
                .code-header {
                    display: flex;
                    justify-content: flex-end;    /* Кнопки прижаты к правому краю */
                    align-items: center;
                    background: #2b2d30;          /* Цвет заголовка чуть светлее основного фона */
                    padding: 4px 8px;
                    border-radius: 6px 6px 0 0;
                    gap: 4px;
                }
                
                /* Название языка слева (если оно у тебя в <span>) */
                .code-header span {
                    margin-right: auto;           /* Отталкивает кнопки вправо */
                    color: #6a6a6a;
                    font-size: 11px;
                    font-family: monospace;
                    text-transform: lowercase;
                }
                
                .code-header button {
                    background: transparent;      /* Убираем массивный фон */
                    color: #7a7a7a;               /* Делаем текст темнее/серее */
                    border: 1px solid #444;       /* Тонкая темная рамка */
                    border-radius: 3px;
                    font-size: 10px;              /* Уменьшаем шрифт */
                    padding: 2px 6px;             /* Минимальные отступы */
                    cursor: pointer;
                    transition: all 0.2s ease;    /* Плавное изменение при наведении */
                    margin-left: 4px;             /* Расстояние между кнопками */
                }

                .code-header button:hover {
                    color: #afb1b3;               /* При наведении текст становится светлее */
                    background: #36393e;          /* И чуть подсвечивается фон */
                    border-color: #555;
                }
                
                pre {
                    /* 1. Вместо переноса строк возвращаем скролл */
                    white-space: pre !important; 
                    overflow-x: auto !important; 
                    
                    /* 2. Ограничиваем ширину, чтобы блок не распирал чат */
                    max-width: 100%;
                    display: block;
                
                    /* 3. Оформление */
                    background-color: #1e1f22;
                    padding: 12px;
                    border-radius: 6px;
                    margin: 8px 0;
                }
                
                /* Настройка полосы прокрутки (scrollbar), чтобы она была тонкой и аккуратной */
                pre::-webkit-scrollbar {
                    height: 4px; /* Горизонтальный скролл будет тонким */
                }
                
                pre::-webkit-scrollbar-thumb {
                    background: #3e4043;
                    border-radius: 4px;
                }
                
                pre::-webkit-scrollbar-track {
                    background: transparent;
                }
                
                code {
                    word-wrap: break-word;
                }
            
                /* Настройка самого блока кода */
                pre[class*="language-"] {
                    margin: 0 0 12px 0 !important; /* Убираем внешние отступы Prism */
                    padding: 12px !important;
                    border-radius: 0 0 6px 6px; /* Скругляем только низ, если есть хедер */
                    background: #1e1f22 !important; /* Цвет как в IntelliJ */
                    font-size: 13px !important;
                    line-height: 1.5;
                    white-space: pre-wrap !important; /* Если Prism все-таки подцепился, убеждаемся, что он тоже переносит */
                }
            
                /* Inline код (внутри текста) */
                :not(pre) > code {
                    background-color: rgba(255, 255, 255, 0.1) !important;
                    padding: 2px 5px !important;
                    border-radius: 4px;
                    color: #e2c08d !important; /* Выделяем цветом как в IDE */
                    font-family: ui-monospace, 'Cascadia Mono', monospace;
                    font-size: 0.95em;
                }
            
                /* 4. ЭЛЕМЕНТЫ ИНТЕРФЕЙСА (Details, Footer) */
                details {
                    margin: 12px 0;
                    border-left: 2px solid #3e4043;
                    padding-left: 12px;
                }
            
                summary { /* заголовок чата со статусом */
                    cursor: pointer;
                    padding: 6px 10px;
                    background-color: rgba(255, 255, 255, 0.03);
                    border-radius: 10px;
                    font-weight: 400;
                    color: #d1d3d9;
                
                    /* Новые свойства для фиксации в одну строку */
                    white-space: nowrap;      /* Запрещает перенос строки */
                    overflow: hidden;         /* Скрывает текст, выходящий за пределы */
                    text-overflow: ellipsis;  /* Добавляет троеточие в конце (...) */
                    display: list-item;       /* Важно сохранить для работы стрелочки раскрытия */
                }
                
                .chat-header {
                    padding: 10px;
                    background: #1f2023;
                    border-bottom: 1px solid #2b2d30;
                }
                
                .chat-body {
                    padding-top: 10px;
                }
                
                .footer {
                    padding-top: 8px;
                    font-size: 11px;
                    font-family: monospace;
                    font-weight: 800;
                    color: #FFEB3B;
                }
                
                /* Основные стили таблиц */
                table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 16px 0;
                    font-family: var(--jb-font-family, sans-serif);
                    font-size: var(--jb-font-size, 13px);
                    color: var(--jb-foreground, #afb1b3);
                }
                
                /* Границы и отступы */
                th, td {
                    border: 1px solid #45494a; /* Цвет из стандартной темы Darcula */
                    padding: 8px 12px;
                    text-align: left;
                    line-height: 1.4;
                }
                
                /* Заголовки */
                th {
                    background-color: rgba(0, 0, 0, 0.2); /* Чуть темнее для контраста */
                    font-weight: bold;
                    color: var(--jb-label-foreground, #bbbbbb);
                }
                
                /* Зебра (опционально, для длинных таблиц) */
                tr:nth-child(even) {
                    background-color: rgba(255, 255, 255, 0.03);
                }
                
                /* Утилиты для текста */
                p { 
                    margin: 8px 0; 
                    line-height: 1.5;
                }
                
                /* принудительный отступ после блока кода для перекрытия Prism */
                .code-block {
                    display: block;
                    margin-bottom: 12px !important; /* Отступ после всего блока с кодом */
                    clear: both; 
                }

                /* На всякий случай обнуляем отступы у Prism, чтобы они не суммировались */
                .code-block pre[class*="language-"] {
                    margin-bottom: 0 !important; 
                }
            </style>
            <!-- Prism.js — лёгкая и красивая подсветка кода -->
            <style> $prismCss </style>
            <script> $prismCore </script>
            $prismLangScripts
            <!-- Добавь другие языки при необходимости: javascript, xml, json, bash и т.д. -->

        </head>
        <body>
            $html
            <script>
                document.addEventListener('DOMContentLoaded', () => {
                    if (window.Prism) {
                        Prism.highlightAll();
                    }
                });
            </script>
            <script>
                function toggleBubble(btn) {
                    const bubble = btn.parentElement;
                    const isCollapsed = bubble.classList.toggle('collapsed');
                    btn.innerText = isCollapsed ? 'expand' : 'collapse';
                    
                    // Опционально: если мы разворачиваем, скроллим к началу пузыря
                    if (!isCollapsed) {
                        bubble.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    }
                }
            </script>
            <script>
                function toggleCode(button) {
                    const container = button.closest('.code-block');
                    const isOpened = !container.classList.contains('closed');
                
                    if (isOpened) {
                        // закрыть
                        container.classList.add('closed');
                        button.innerText = 'expand';
                    } else {
                        // открыть
                        container.classList.remove('closed');
                        button.innerText = 'collapse';
                    }
                }
            </script>
            <script>            
                function copyCode(btn) {
                    const code = btn.parentElement.nextElementSibling.innerText;
                    navigator.clipboard.writeText(code).then(() => {
                        btn.innerText = "copied!";
                        setTimeout(() => btn.innerText = "copy", 1000);
                    });
                }
            </script>
        </body>
        </html>
        """.trimIndent()
        //LOG.info("styledHtml=$styledHtml")
        return styledHtml

        // Инжектим taskHandlers.js (лучше делать после loadHTML, а не сразу)
        //        try {
        //            val jsCode = this::class.java.getResource("/js/taskHandlers.js")?.readText()
        //                ?: throw kotlinx.io.files.FileNotFoundException("taskHandlers.js not found in resources")
        //
        //            // Генерируем реальный JS-код для моста
        //            val actualInject = jsQuery.inject("selection")
        //
        //            // Заменяем метку в тексте файла на рабочий код
        //            val finalJsCode = jsCode.replace("PLACEHOLDER_FOR_INJECT", actualInject)
        //
        //            // Небольшая задержка, чтобы DOM был готов
        //            SwingUtilities.invokeLater {
        //                jbCefBrowser.cefBrowser.executeJavaScript(finalJsCode, "", 0)
        //            }
        //        } catch (e: Exception) {
        //            logger.warn("Failed to load taskHandlers.js", e)
        //        }
        //       scrollToBottom()
    }

}

fun loadResource(path: String): String {
    return object {}.javaClass
        .getResourceAsStream("/$path")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Resource not found: $path")
}

fun isDarkTheme(): Boolean {
    val lookAndFeel = UIManager.getLookAndFeel().name.lowercase();
    return lookAndFeel.contains("darcula") || lookAndFeel.contains("dark");
}


/*
* Если factor > 1 цвет становится ярче
* Если factor < 1 цвет становится темнее
*/
fun Color.adjustBrightness(factor: Float): Color {
    /*
        hsb[0] -> hue
        hsb[1] -> saturation
        hsb[2] -> brightness
    */
    val hsb = FloatArray(3)
    Color.RGBtoHSB(red, green, blue, hsb)
    hsb[2] = (hsb[2] * factor).coerceIn(0f, 1f)
    return Color.getHSBColor(hsb[0], hsb[1], hsb[2])
}
