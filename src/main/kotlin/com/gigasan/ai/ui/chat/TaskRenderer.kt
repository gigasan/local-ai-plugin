package com.gigasan.ai.ui.chat

import com.gigasan.ai.runtime.AIMetrics
import com.gigasan.ai.ui.chat.HtmlProcessor.normalizeLanguage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.AttributeProvider
import com.vladsch.flexmark.html.AttributeProviderFactory
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.renderer.AttributablePart
import com.vladsch.flexmark.html.renderer.LinkResolverContext
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import javax.swing.UIManager
import java.awt.Color

// Вынос UI-логики (renderChatBlocks, buildTaskHtml) в отдельный слой
// Цель: разделить рендеринг и данные
// А renderChatBlocks(tasks) вызывать извне, как отдельный метод, не встроенный внутрь UI-элемента.

private val logger = Logger.getInstance("TaskRenderer")

class TaskRenderer(
    //private val themeManager: ThemeManager // для получения цветов, стилей и т.д.
) {

    private fun generateThemeStyles(): String {
        // Проверяем, темная ли тема сейчас в IDE
        val isDark = com.intellij.util.ui.StartupUiUtil.isDarkTheme

        // 1. КОНСТАНТЫ ЦВЕТОВ ДЛЯ ТЕМНОГО И СВЕТЛОГО РЕЖИМОВ
        val panelBg      = if (isDark) "#191a1c" else "#f2f2f2" // Основной фон чата
        val textColor    = if (isDark) "#d1d3d9" else "#1f2023" // Основной текст
        val border       = if (isDark) "#3a3d42" else "#b8bcc2" // Границы и разделители
        val bubbleBg     = if (isDark) "#141516" else "#D9D9D9" // Фон пузырей сообщений
        val bubbleText   = if (isDark) "#a7a9ae" else "#333333" // Текст внутри пузырей

        val codeHeaderBg = if (isDark) "#2b2d30" else "#C5C5C5" // Заголовок блока кода (где кнопки)
        val codeHeaderCc = if (isDark) "#666666" else "#000000" // Твой цвет для темной и светлой темы
        val codeBg       = if (isDark) "#1e1f22" else "#F6FDE6" // Фон блоков кода (внутри pre)
        val inlineCodeBg = if (isDark) "rgba(255, 255, 255, 0.1)" else "rgba(0, 0, 0, 0.05)"
        val inlineCodeCc = if (isDark) "#e2c08d" else "#b86c11" // Цвет inline-кода

        val footerColor  = if (isDark) "#FFEB3B" else "#FF9800" // статистика

        // НАСТРОЙКА ЖИРНОСТИ (Кнопки и Хедеры)
        val bodyWeight   = if (isDark) "400" else "500"
        val codeWeight   = if (isDark) "400" else "500"

        // Для кнопок в светлой теме ставим честный Bold (700) или Semi-bold (600)
        val buttonWeight = if (isDark) "300" else "600"
        val btnTextHex   = if (isDark) "#666666" else "#45464a" // Цвет текста на кнопке (Светло-серый / Темно-серый)
        val btnBorderHex = if (isDark) "#3a3d42" else "#b8bcc2" // Цвет рамки кнопки
        val btnBgHoverHex= if (isDark) "#36393e" else "#e3e5e8" // Фон кнопки при наведении мыши

        // 2. КРАСИВАЯ ПАЛИТРА ДЛЯ КОДА (Синтаксис как в IntelliJ IDEA)
        val tokenKeyword  = if (isDark) "#cc7832" else "#0033b3" // if, fun, return (оранжевый / благородный синий)
        val tokenString   = if (isDark) "#6a8759" else "#067d17" // "строки текста" (приглушенный зеленый)
        val tokenComment  = if (isDark) "#808080" else "#7e8085" // // комментарии (спокойный серый)
        val tokenNumber   = if (isDark) "#6897bb" else "#1750eb" // 123, true (сине-голубой / яркий синий)
        val tokenFunction = if (isDark) "#ffc66d" else "#00627a" // имяФункции() (песочный / морская волна)
        val tokenType     = if (isDark) "#a9b7c6" else "#000000" // Типы данных String, Int
        val tokenPunct    = if (isDark) "#b4b8c0" else "#5e6166" // Скобки {}, точки, запятые


        val summaryBgHex   = if (isDark) "#1F2022" else "#e3e5e8"
        val summaryTextHex = if (isDark) "#d1d3d9" else "#1f2023"

        // 2. ФОРМИРУЕМ root-блок для CSS
        return """
        <style>
            :root {
                /* кнопки expand, copy */
                --btn-text: $btnTextHex;
                --btn-border: $btnBorderHex;
                --btn-bg-hover: $btnBgHoverHex;
                --btn-font-weight: $buttonWeight;

                --panel-bg: $panelBg;
                --text-color: $textColor;
                --border-color: $border;
                --bubble-bg: $bubbleBg;
                --bubble-text: $bubbleText;
                --code-bg: $codeBg;
                --code-header-bg: $codeHeaderBg;
                --code-header-text: $codeHeaderCc;
                --inline-code-bg: $inlineCodeBg;
                --inline-code-color: $inlineCodeCc;
                --footer-color: $footerColor;
                --summary-bg: ${summaryBgHex};
                --summary-text: ${summaryTextHex};
                
                /* жирности */
                --body-font-weight: $bodyWeight;
                --code-font-weight: $codeWeight;
                --code-header-font-weight: $codeWeight;
                
                /* Красивые переменные */
                --token-keyword: $tokenKeyword;
                --token-string: $tokenString;
                --token-comment: $tokenComment;
                --token-number: $tokenNumber;
                --token-function: $tokenFunction;
                --token-type: $tokenType;
                --token-punct: $tokenPunct;
            }
        </style>
    """.trimIndent()
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
        val isDark = com.intellij.util.ui.StartupUiUtil.isDarkTheme
        // Загружаем нужный файл в зависимости от темы IDE
        val prismCss = if (isDark) {
            this::class.java.getResource("/css/prism-tomorrow.min.css")?.readText() ?: ""
        } else {
            this::class.java.getResource("/css/prism-solarizedlight.css")?.readText() ?: ""
        }
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

        //logger.info("Theme panelBg=$panelBg textColor=$textColor codeBg=$codeBg")

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

        val themeStyle = generateThemeStyles()

        val styledHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            $themeStyle
            <style>
                /* --- Стили для кнопок в обычном интерфейсе --- */
                .header-actions {
                    display: flex;
                    align-items: center;
                    gap: 4px; /* Уменьшили расстояние между кнопками с 8px до 4px (стало плотнее) */
                    margin-right: -4px; /* Отрицательный маржин притягивает блок вплотную к правому краю, компенсируя паддинг summary */
                    padding: 0;
                }
                
                /* Общий класс для наших кнопок (экспорт и удаление) */
                .export-task-btn, .delete-task-btn {
                    background: transparent !important;
                    border: none !important;
                    color: var(--btn-text) !important;
                    font-size: 13px !important; /* Слегка уменьшили размер шрифта/иконки для компактности */
                    cursor: pointer;
                    opacity: 0.4;
                    padding: 2px 4px !important; /* Минимальные внутренние отступы, чтобы кнопки были плотными */
                    line-height: 1 !important;
                    transition: all 0.2s ease;
                }
                
                /* Наведение на строку summary проявляет обе кнопки */
                summary.chat-header:hover .export-task-btn,
                summary.chat-header:hover .delete-task-btn {
                    opacity: 0.7;
                }

                /* Дискета при наведении просто подсвечивается основным текстом */
                .export-task-btn:hover {
                    color: var(--text-color) !important;
                    opacity: 1 !important;
                    transform: scale(1.1);
                }

                /* А вот и наш потерянный красный крестик! */
                .delete-task-btn:hover {
                    color: #e06c75 !important; /* Красивый пастельно-красный (в стиле One Dark / Darcula) */
                    opacity: 1 !important;
                    transform: scale(1.15); /* Чуть больший микро-зум для акцента */
                }

                /* Стиль кнопки удаления */
                .delete-task-btn {
                    background: transparent !important;
                    border: none !important;
                    color: var(--btn-text) !important;
                    font-size: 16px !important; /* Крестик должен быть читаемым */
                    cursor: pointer;
                    padding: 0 4px !important;
                    line-height: 1;
                    opacity: 0.4;
                    transition: all 0.2s ease;
                    font-weight: bold !important;
                }
                
                
                /* 1. ОБЩИЕ НАСТРОЙКИ (Layout) */
                body { 
                    background-color: var(--panel-bg); 
                    color: var(--text-color); 
                    margin: 0; 
                    padding: 12px 16px; 
                    
                    /* Используем более плотные системные шрифты */
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                    line-height: 1.6;
                    font-size: 13px;
                
                    /* Применяем динамическую жирность */
                    font-weight: var(--body-font-weight);
                
                    /* Магия сглаживания Chromium для устранения "тонкости" на светлом фоне */
                    -webkit-font-smoothing: antialiased;
                    -moz-osx-font-smoothing: grayscale;
                    text-rendering: optimizeLegibility;
                }
            
                .chat {
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                }
            
                /* 2. ПУЗЫРИ СООБЩЕНИЙ */
                .bubble-user, .bubble-assistant {
                    padding: 10px 14px;
                    background: var(--bubble-bg); 
                    color: var(--bubble-text);
                    max-width: 85%;
                }
            
                .bubble-user {
                    align-self: flex-start;
                    border-radius: 12px 12px 12px 4px;
                    position: relative;
                    transition: max-height 0.3s ease-out;
                    overflow: hidden;
                }
        
                .bubble-user.collapsed {
                    max-height: 40px; 
                    cursor: pointer;
                }
        
                .bubble-user.collapsed::after {
                    content: "";
                    position: absolute;
                    bottom: 0;
                    left: 0;
                    width: 100%;
                    height: 20px;
                    background: linear-gradient(transparent, var(--bubble-bg));
                }
        
                .bubble-assistant .collapse-btn {
                    left: 8px;
                    right: auto;
                }
            
                .bubble-assistant {
                    align-self: flex-end;
                    border-radius: 12px 12px 4px 12px;
                    max-width: 90%;
                    position: relative;
                    transition: max-height 0.3s ease-out;
                    overflow: hidden;
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
        
                .bubble-assistant.collapsed {
                    max-height: 40px; 
                    cursor: pointer;
                }
                
                .bubble-assistant.collapsed::after {
                    content: "";
                    position: absolute;
                    bottom: 0;
                    left: 0;
                    width: 100%;
                    height: 20px;
                    background: linear-gradient(transparent, var(--bubble-bg));
                }
            
                /* 3. БЛОКИ КОДА (Интеграция с Prism.js) */
                .code-block pre {
                    display: block; 
                }
                
                .code-block.closed pre {
                    display: none;
                }
                
                .code-header {
                    display: flex;
                    justify-content: flex-end;    
                    align-items: center;
                    background: var(--code-header-bg);          
                    padding: 4px 8px;
                    border-radius: 6px 6px 0 0;
                    gap: 4px;
                }
                
                .code-header span {
                    margin-right: auto;           
                    color: var(--code-header-text);
                    font-size: 11px;
                    font-family: ui-monospace, monospace;
                    text-transform: lowercase;
                    font-weight: 600; /* Делаем заголовок полужирным (Semi-bold) */
                    letter-spacing: 0.3px; /* Немного раздвигаем буквы для читаемости */
                }
                
                /* ==========================================================================
                   ОБЩИЕ СТИЛИ ДЛЯ ВСЕХ КНОПОК (Copy, Collapse на коде и Collapse на баблах)
                   ========================================================================== */
                .code-header button, .collapse-btn {
                    background: transparent !important;
                    
                    /* Управляем цветом текста и рамки через новые переменные */
                    color: var(--btn-text) !important;
                    border: 1px solid var(--btn-border) !important;
                    
                    font-weight: var(--btn-font-weight) !important;
                    font-size: 10px;
                    font-family: system-ui, -apple-system, sans-serif;
                    letter-spacing: 0.4px;
                    padding: 2px 6px;
                    cursor: pointer;
                    transition: all 0.2s ease;
                    -webkit-font-smoothing: antialiased;
                }
                
                /* ОБЩИЙ ХОВЕР (при наведении мышки) */
                .code-header button:hover, .collapse-btn:hover {
                    background-color: var(--btn-bg-hover) !important;
                    border-color: var(--btn-text) !important;
                    opacity: 1 !important; /* Убираем прозрачность полностью */
                }
                
                /* ==========================================================================
                   УНИКАЛЬНЫЕ СТИЛИ (Специфичные настройки для каждого типа кнопок)
                   ========================================================================== */
                
                /* 1. Кнопки внутри заголовка кода */
                .code-header button {
                    border-radius: 3px;
                    margin-left: 4px;
                }
                
                /* 2. Кнопка сворачивания самого пузыря сообщения */
                .collapse-btn {
                    position: absolute;
                    top: 4px;
                    right: 8px;
                    border-radius: 4px;
                    opacity: 0.6; /* Делаем её чуть приглушенной по умолчанию, пока не навели мышь */
                    z-index: 20;
                }
                
                pre {
                    white-space: pre !important; 
                    overflow-x: auto !important; 
                    max-width: 100%;
                    display: block;
                    background-color: var(--code-bg) !important;
                    color: var(--text-color);
                    padding: 12px;
                    border-radius: 6px;
                    margin: 8px 0;
                    
                    /* Настройки шрифта кода */
                    font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
                    font-weight: var(--code-font-weight);
                    -webkit-font-smoothing: antialiased;
                }
                
                pre::-webkit-scrollbar {
                    height: 4px; 
                }
                
                pre::-webkit-scrollbar-thumb {
                    background: var(--border-color);
                    border-radius: 4px;
                }
                
                pre::-webkit-scrollbar-track {
                    background: transparent;
                }
                
                code {
                    word-wrap: break-word;
                }
            
                pre[class*="language-"] {
                    margin: 0 0 12px 0 !important; 
                    padding: 12px !important;
                    border-radius: 0 0 6px 6px; 
                    background: var(--code-bg) !important; 
                    font-size: 13px !important;
                    line-height: 1.5;
                    font-weight: var(--code-font-weight) !important;
                }
            
                /* Inline код (внутри текста) */
                :not(pre) > code {
                    background-color: var(--inline-code-bg) !important;
                    padding: 2px 5px !important;
                    border-radius: 4px;
                    color: var(--inline-code-color) !important; 
                    font-family: ui-monospace, 'Cascadia Mono', monospace;
                    font-size: 0.95em;
                }
            
                /* 4. ЭЛЕМЕНТЫ ИНТЕРФЕЙСА (Details, Footer) */
                details {
                    margin: 12px 0;
                    border-left: 2px solid var(--border-color);
                    padding-left: 12px;
                }
            
                summary { 
                    cursor: pointer;
                    padding: 6px 10px;
                    
                    background-color: var(--summary-bg) !important;
                    color: var(--summary-text) !important;
                    border-radius: 10px;
                    font-weight: var(--body-font-weight);
                    
                    /* МЕНЯЕМ ТУТ: Включаем flex, чтобы раскидать текст и крестик по краям */
                    display: flex !important;       
                    justify-content: space-between; 
                    align-items: center;            
                    padding-right: 28px !important; /* Отступ справа, чтобы не наезжать на стрелочку details */
                    position: relative;
                
                    -webkit-font-smoothing: antialiased;                         
                }
                
                summary:hover {
                    filter: brightness(1.1); 
                }
                
                /* Контейнер summary делаем flex, чтобы легко раскидать текст и крестик */
                summary.chat-header {
                    display: flex !important;
                    justify-content: space-between;
                    align-items: center;
                    padding-right: 28px !important; /* Даем место справа, чтобы стрелочка раскрытия details не наезжала */
                    position: relative;
                }

                .task-title-text {
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                    margin-right: 8px;
                }

                .chat-header {
                    padding: 10px;
                    background: var(--panel-bg);
                    border-bottom: 1px solid var(--border-color);
                }
                
                .chat-body {
                    padding-top: 10px;
                }
                
                .footer {
                    padding-top: 8px;
                    font-size: 11px;
                    font-family: monospace;
                    font-weight: 800;
                    color: var(--footer-color);
                }
                
                table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 16px 0;
                    font-size: 13px;
                    color: var(--text-color);
                }
                
                th, td {
                    border: 1px solid var(--border-color); 
                    padding: 8px 12px;
                    text-align: left;
                    line-height: 1.4;
                }
                
                th {
                    background-color: var(--code-header-bg); 
                    font-weight: bold;
                }
                
                tr:nth-child(even) {
                    background-color: var(--code-bg);
                    opacity: 0.6;
                }
                
                p { 
                    margin: 8px 0; 
                    line-height: 1.5;
                }
                
                .code-block {
                    display: block;
                    margin-bottom: 12px !important; 
                    clear: both; 
                }
        
                .code-block pre[class*="language-"] {
                    margin-bottom: 0 !important; 
                }
            </style>
            
            <!-- Prism.js — лёгкая и красивая подсветка кода -->
            <style> 
            $prismCss
            /* ПЕРЕОПРЕДЕЛЕНИЕ СТИЛЕЙ PRISM ДЛЯ ПОДДЕРЖКИ ТЕМ */
            .token.keyword, .token.boolean, .token.operator, .token.important {
                color: var(--token-keyword) !important;
                font-weight: bold !important;
            }
            .token.string, .token.char, .token.attr-value, .token.regex {
                color: var(--token-string) !important;
            }
            .token.comment, .token.prolog, .token.doctype, .token.cdata {
                color: var(--token-comment) !important;
                font-style: italic !important;
            }
            .token.number, .token.constant, .token.symbol, .token.inserted {
                color: var(--token-number) !important;
            }
            .token.function, .token.attr-name, .token.selector {
                color: var(--token-function) !important;
            }
            .token.class-name, .token.type, .token.tag {
                color: var(--token-type) !important;
            }
            .token.punctuation {
                color: var(--token-punct) !important;
            }

            /* Убираем дефолтную тень текста, которую Prism любит пихать во все скачанные темы */
            .token {
                text-shadow: none !important;
                background: transparent !important;
            }
            </style>
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
    }


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
            model = task.model,
            request = task.question,
            content = "",
            status = task.status,
            maxLen = 100
        )

        val dataTaskId = "data-task-id='${task.id}'"
        val printer = "💾"
        val close = "×"
        val htmlText = buildString {
            append("<details>")

            append("<summary class='chat-header' ${dataTaskId}>")
            append("<span class='task-title-text'>${task.title} ${description}</span>")
            append("<div class='header-actions'>") // Обернем кнопки в контейнер для удобства
            append("  <button class='export-task-btn' data-task-id='${task.id}' title='Экспорт в Markdown'>$printer</button>")
            append("  <button class='delete-task-btn' data-delete-id='${task.id}'>$close</button>")
            append("</div>")
            append("</summary>\n")

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


fun Color.toHex() = "#%02x%02x%02x".format(red, green, blue)

object MarkdownRenderer {
    val options = MutableDataSet().set(Parser.EXTENSIONS, listOf(TablesExtension.create()))
    private val parser = Parser.builder(options).build()
    private val renderer = HtmlRenderer.builder(options)
        .escapeHtml(true)
        .attributeProviderFactory(object : AttributeProviderFactory {

            override fun apply(context: LinkResolverContext): AttributeProvider {
                return AttributeProvider { node, part, attributes ->
                    if (node is FencedCodeBlock && part == AttributablePart.NODE) {
                        val lang = node.info.toString().trim()
                        if (lang.isNotEmpty()) {
                            attributes.addValue("class", "language-$lang")
                        }
                    }
                }
            }

            override fun getBeforeDependents(): Set<Class<*>>? = null

            override fun getAfterDependents(): Set<Class<*>>? = null

            override fun affectsGlobalScope(): Boolean = false

        })
        .build()

    fun toHtml(markdown: String): String {
        val document = parser.parse(markdown)
        return renderer.render(document)
    }
}