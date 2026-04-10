package com.gigasan.localai

import com.gigasan.localai.LocalAIService.buildChatModel
import com.gigasan.localai.LocalAIService.buildChatUrl
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import okhttp3.OkHttpClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.system.exitProcess
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.AttributeProvider
import com.vladsch.flexmark.html.AttributeProviderFactory
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.html.renderer.AttributablePart
import com.vladsch.flexmark.html.renderer.LinkResolverContext
import kotlin.text.format
import java.util.regex.Matcher
import java.util.regex.Pattern


// Блок Markdown
data class ChatBlock(
    val id: String,
    var content: String,
    var description: String,
)

class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private var updateTimer: javax.swing.Timer? = null
    private var jbCefBrowser = JBCefBrowser()   // или через builder, если нужно
    // 2. Создаём jsQuery ТОЛЬКО после того, как браузер полностью создан
    private lateinit var jsQuery: JBCefJSQuery   // используем lateinit
    // ← сюда добавь свой wrapper

    private val browserWrapper = JPanel(BorderLayout()).apply {
        // Важно: делаем так, чтобы браузер растягивался по всему доступному месту
        preferredSize = Dimension(600, 800)   // начальный разумный размер
        minimumSize = Dimension(300, 400)
        add(jbCefBrowser.component, BorderLayout.CENTER)
        // Опционально: можно добавить тонкую рамку для отладки
        border = BorderFactory.createLineBorder(Color.GRAY)
    }

    private var markdownPanel = JPanel()
    private val markdownFile = LightVirtualFile("chat.md", "")   // dummy-файл для панели
    private val chatBlocks = mutableListOf<ChatBlock>()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel()
    private val LOG = Logger.getInstance("ChatPanel")

    companion object {
        var instance: ChatPanel? = null
    }

    init {
        instance = this

        // === Основная структура панели ===
        val mainPanel = JPanel(BorderLayout())

        // Верхняя часть — браузер (чат)
        val chatPanel = JPanel(BorderLayout())
        chatPanel.add(browserWrapper, BorderLayout.CENTER)   // ← вот сюда wrapper

        // Нижняя часть — задачи + поле ввода
        val southPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // --- Панель задач ---
        val taskContainer = JPanel(BorderLayout()).apply {
            val taskScroll = JBScrollPane(taskManagerPanel)
            taskScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            taskScroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            add(taskScroll, BorderLayout.CENTER)
            minimumSize = Dimension(0, 0)
            preferredSize = Dimension(Int.MAX_VALUE, 0)
            maximumSize = Dimension(Int.MAX_VALUE, 150)
        }
        southPanel.add(taskContainer)

        // --- Панель ввода ---
        val inputPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(5, 0, 0, 0)
            add(inputField, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        southPanel.add(inputPanel)

        // Собираем всё вместе
        mainPanel.add(chatPanel, BorderLayout.CENTER)
        mainPanel.add(southPanel, BorderLayout.SOUTH)

        setContent(mainPanel)

        // === Инициализация JCEF ===
        if (!JBCefApp.isSupported()) {
            LOG.error("JBCefApp is not supported!")
            exitProcess(0)
        }

        // Создаём браузер (лучше здесь, а не в поле)
        jbCefBrowser = JBCefBrowser()

        // Убираем серую рамку (для отладки можно потом вернуть)
        browserWrapper.border = null

        // Добавляем браузер в wrapper (ещё раз на всякий случай)
        browserWrapper.removeAll()
        browserWrapper.add(jbCefBrowser.component, BorderLayout.CENTER)

        // Инициализируем JS-обработчики
        addJbCefBrowserHandler()

        // === Остальная логика ===
        sendButton.addActionListener { sendMessage() }
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) sendMessage()
            }
        })

        // --- Обновление высоты taskContainer при добавлении/удалении задач ---
        fun updateTaskContainerHeight() {
            SwingUtilities.invokeLater {
                val taskHeight = taskManagerPanel.preferredSize.height.coerceIn(0, 150)
                taskContainer.preferredSize = Dimension(0, taskHeight)
                taskContainer.revalidate()
            }
        }

        // Обновление высоты задач
        taskManagerPanel.onTasksChanged = {
            scheduleMarkdownUpdate()
            updateTaskContainerHeight()
        }

        // Подписка на смену темы
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                SwingUtilities.invokeLater { recreateMarkdownPanel() }
            })

        LOG.info("ChatPanel initialized")
    }
    // --- Drag-and-Drop ---
    private fun installDropHandler(panel: JPanel) {

        val dropTarget = DropTarget(panel, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {

            override fun dragEnter(dtde: DropTargetDragEvent) {
                // Можно добавить подсветку при наведении
            }

            override fun drop(event: DropTargetDropEvent) {
                try {
                    val comp = event.dropTargetContext.component

                    // Проверяем, что дроп не попал на кнопку или inputField
                    if (comp is JButton || comp is JTextField) {
                        event.rejectDrop()
                        return
                    }

                    val transferable = event.transferable

                    // --- Файлы ---
                    if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val data = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                        val files = data?.filterIsInstance<File>() ?: emptyList()
                        val task = TaskData(
                            id = System.currentTimeMillis().toString(),
                            title = "📂",
                            description = files.joinToString(" ") { it.name },
                            content = files.joinToString("\n") { it.absolutePath },
                            zoneType = "File",
                            job = "check errors",
                            answer = "",
                            status = "created",
                        )
                        taskManagerPanel.addTask(task)
                        event.dropComplete(true)

                        // --- Текст ---
                    } else if (event.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val droppedText = transferable.getTransferData(DataFlavor.stringFlavor) as String
                        val editorData = getEditorContext(project)
                        val task = if (editorData != null && droppedText == editorData.third) {
                            // editor context
                            val (fileName, lines, text) = editorData
                            TaskData(
                                id = System.currentTimeMillis().toString(),
                                title = "📝",
                                description = "$fileName ${lines.first}-${lines.last}",
                                content = text,
                                zoneType = "Editor",
                                job = "check errors",
                                answer = "",
                                status = "created",
                            )
                        } else {
                            // any other data
                            val previewLength = 50 // сколько первых символов показывать
                            val processed = droppedText.replace(Regex("\\s+"), " ").trim()
                            val preview = if (processed.length > previewLength) {
                                "${processed.take(previewLength)}..."
                            } else {
                                processed
                            }
                            TaskData(
                                id = System.currentTimeMillis().toString(),
                                title = "📄",
                                description = preview,
                                content = droppedText,
                                zoneType = "External",
                                job = "check errors",
                                answer = "",
                                status = "created",
                            )
                        }
                        taskManagerPanel.addTask(task)
                        event.dropComplete(true)
                    } else {
                        event.rejectDrop()
                    }
                } catch (e: Exception) {
                    LOG.error(e)
                    event.rejectDrop()
                }
            }
        }, true)
        // Привязываем DropTarget к панели
        panel.dropTarget = dropTarget
    }

    fun scheduleMarkdownUpdate() {
        updateTimer?.stop()
        updateTimer = javax.swing.Timer(300) {
            rebuildMarkdownFromTasks()
        }
        updateTimer?.isRepeats = false
        updateTimer?.start()
    }

    fun wrapBubble(str: String): String {
        // <pre><code class="language-kotlin">
        // into
        // <div class="code-block">
        //  <div class="code-header">
        //    <span>kotlin</span>
        //    <button onclick="toggleCode(this)">collapse</button>
        //    <button onclick="copyCode(this)">copy</button>
        //  </div>
        //  <pre><code>...</code></pre>
        //</div>
        return "$str"
    }

    fun getFormatedTime(id: String): String {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val instant = java.time.Instant.ofEpochMilli(id.toLong())
        val dateTime = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        return dateTime.format(formatter)
    }



//    fun extractLanguageName(text: String): String? {
//        val languagePattern = Pattern.compile("language\\s*(\\w+)")
//        val matcher = languagePattern.matcher(text)
//
//        //return matcher.findMatch()?.group(1)
//    }

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


    fun rebuildMarkdownFromTasks() {
        chatBlocks.clear()
        val tasks = taskManagerPanel.getAllTasks()
        tasks.forEach { task ->
            //LOG.info("task-id: ${task.id}")
            val htmlText = buildString {
                append("<details data-task-id='${task.id}' style='margin-bottom:8px;'>")
                append("<summary data-task-id='${task.id}' style='cursor:pointer;'>${task.title} ${task.description}</summary>\n")
                append(MarkdownRenderer.toHtml(task.content))
                append(MarkdownRenderer.toHtml(task.answer))
                append(MarkdownRenderer.toHtml(task.job))
                append("<p>${getFormatedTime(task.id)}</p>")
                append("</details>\n")
            }  //.replace("<pre><code class=\"language-", "<div class='code-block'>...")
            //val bubledText =
            //val html = """<div class="code-block"><div class="code-header"><span>$lang</span>
            //<button onclick="toggleCode(this)">collapse</button><button onclick="copyCode(this)">copy</button>
            //</div><pre><code class="language-$lang">${node.contentChars}</code></pre></div>""".trimIndent()
            //attributes.addValue("data-html", html)
            val bubbledHtmlText = transformCodeBlocks(htmlText)

            LOG.warn("htmlText: $htmlText")
            LOG.warn("bubbledHtmlText: $bubbledHtmlText")
            val chatBlock = ChatBlock(
                id = task.id,
                content = bubbledHtmlText,
                description = task.description,
            )

            chatBlocks.add(chatBlock)
        }
        refreshChatPanel()
    }

    fun sendTask(task: TaskData) {

        val settings = PluginSettings.instance
        val url = buildChatUrl(settings)
        val model = buildChatModel(settings)

        val clientOk = OkHttpClient()
        val requestOk = com.gigasan.localai.LocalAIService.createRequest(url, model, "Write a short bedtime story about a unicorn.")
        val responseOk = clientOk.newCall(requestOk).execute()
        println(responseOk.body?.string())

    }

    object HttpClientProvider {
        val client = okhttp3.OkHttpClient()
    }

    sealed class TaskResult {
        data class Success(val task: TaskData) : TaskResult()
        data class Error(val task: TaskData, val error: Throwable) : TaskResult()
    }

    fun processTask(task: TaskData): TaskResult {
        return try {
            val settings = PluginSettings.instance
            val url = buildChatUrl(settings)
            val model = buildChatModel(settings)

            val aiRequest = ChatRequestBuilder()
                .system("You are IntelliJ assistant")
                .memory(limit = 5)
                .user("${task.job} ${task.content}")
                .model(model)
                .build(task)

            val apiKey = "sk-lm-zESagiFt:J3TSJCffvecSKvcx4Fym"
            val httpRequest = aiRequest.toHttpRequest(url, apiKey)
            val startTime = System.currentTimeMillis()
            val response = HttpClientProvider.client.newCall(httpRequest).execute()
            val endTime = System.currentTimeMillis()
            val raw = response.body?.string() ?: ""
            val result = AIResponseParser.parse(raw).copy(durationMs = endTime - startTime)
            if (result.toolCalls.isNotEmpty()) {
                result.toolCalls.forEach { tool ->
                    val output = ToolRegistry.execute(tool.name, tool.arguments)
                    println("Tool result: $output")
                }
            }
            val updated = task.copy(
                answer = result.text,
                description = "Ответ за ${formatDuration(result.durationMs)}",
                status = "done"
            )
            TaskResult.Success(updated)
        } catch (e: Exception) {
            TaskResult.Error(
                task.copy(
                    status = "error",
                    description = "Ошибка: ${e.message}"
                ),
                e)
        }
    }

    fun runTaskInBackground(
        project: Project?,
        task: TaskData,
        onUpdate: (TaskData) -> Unit
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI Processing", false) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Отправка запроса..."
                indicator.isIndeterminate = true

                when (val result = processTask(task)) {
                    is TaskResult.Success -> {
                        MemorySystem.add(result.task)
                        updateTaskOnUI(result.task)
                    }
                    is TaskResult.Error -> {
                        LOG.warn("Task failed", result.error)
                        updateTaskOnUI(result.task)
                    }
                }

            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    onUpdate(
                        task.copy(
                            description = "Ошибка: ${error.message}",
                            status = "error"
                        )
                    )
                }
            }
        })
    }


    private fun sendMessageOk() {
        val mainText = inputField.text.trim()
        if (mainText.isEmpty()) return

        val task = TaskData(
            id = System.currentTimeMillis().toString(),
            title = "✏️",
            description = "chat message",
            content = mainText,
            zoneType = "Chat",
            job = "",
            answer = "",
            status = "created"
        )

        taskManagerPanel.addTask(task)
        inputField.text = ""

        updateTaskStatus(task, "sending")

        runTaskInBackground(project, task) { updatedTask ->
            updateTaskOnUI(updatedTask)
        }
    }

    private fun sendMessage() {
        val mainText = inputField.text.trim()
        if (mainText.isEmpty()) return

        // Создаём новую задачу
        val task = TaskData(
            id = System.currentTimeMillis().toString(),
            title = "✏️",
            description = "chat message",
            content = mainText,
            zoneType = "Chat",
            job = "",
            answer = "",
            status = "created"
        )
        taskManagerPanel.addTask(task)
        inputField.text = ""

        // Обновляем статус на "sending"
        updateTaskStatus(task, "sending")

        // Подготовка запроса к AI
        val request = "${task.job} ${task.content}"
        Thread {
            val startTime = System.currentTimeMillis()
            try {

                val response = LocalAIService.callLocalAI(request)
                val endTime = System.currentTimeMillis()

                val durationText = formatDuration(endTime - startTime)

                // Обновляем задачу
                task.answer = response
                task.description = "Ответ получен за $durationText"
                task.status = "done"

                updateTaskOnUI(task)
            } catch (e: Exception) {
                task.description = "**Ошибка:** ${e.message}"
                task.status = "error"
                updateTaskOnUI(task)
            }
        }.start()
    }

    // Обновление задачи в taskList и вызов onTasksChanged
    private fun updateTaskOnUI(task: TaskData) {
        ApplicationManager.getApplication().invokeLater {

        val index = taskManagerPanel.getAllTasks()
            .indexOfFirst { it.id == task.id }

        if (index != -1) {
            taskManagerPanel.taskList[index] = task
        }

        taskManagerPanel.onTasksChanged?.invoke()
        }
    }

    // Обновление статуса задачи
    private fun updateTaskStatus(task: TaskData, status: String) {
        task.status = status
        updateTaskOnUI(task)
    }

    // Форматирование времени в hh:mm:ss:ms
    private fun formatDuration(durationMs: Long?): String {
        val totalSeconds = durationMs?.div(1000)
        val hours = totalSeconds?.div(3600)
        val minutes = (totalSeconds?.rem(3600))?.div(60)
        val seconds = totalSeconds?.rem(60)
        val milliseconds = durationMs?.rem(1000)
        return String.format("%02d:%02d:%02d:%03d", hours, minutes, seconds, milliseconds)
    }

    fun sendExternalMessage(text: String) {
//        chatMarkdown += "**Вы:** $text\n\n"
//        refreshMarkdownPanel()
//
//        Thread {
//            try {
//                val response = callLocalAI(text)
//                SwingUtilities.invokeLater {
//                    chatMarkdown += "**AI:**\n$response\n\n---\n\n"
//                    refreshMarkdownPanel()
//                }
//            } catch (e: Exception) {
//                SwingUtilities.invokeLater {
//                    chatMarkdown += "**Ошибка:** ${e.message}\n\n---\n\n"
//                    refreshMarkdownPanel()
//                }
//            }
//        }.start()
    }

    fun Color.toHex() = "#%02x%02x%02x".format(red, green, blue)


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

    object MarkdownRenderer {

        private val parser = com.vladsch.flexmark.parser.Parser.builder().build()

        private val renderer = HtmlRenderer.builder()
            .escapeHtml(false)
            .attributeProviderFactory(object : AttributeProviderFactory {

                override fun apply(context: LinkResolverContext): AttributeProvider {
                    return AttributeProvider { node, part, attributes ->
                        //println("node=${node};part=${part};attributes=${attributes}")
                        //if (node is FencedCodeBlock) {
                        if (node is FencedCodeBlock && part == AttributablePart.NODE) {
                            val lang = node.info.toString().trim()
                            if (lang.isNotEmpty()) {
                                attributes.addValue("class", "language-$lang")
//                                val html = """<div class="code-block"><div class="code-header"><span>$lang</span>
//                                <button onclick="toggleCode(this)">collapse</button><button onclick="copyCode(this)">copy</button>
//                                </div><pre><code class="language-$lang">${node.contentChars}</code></pre></div>""".trimIndent()
//                                attributes.addValue("data-html", html)
                            }
                        }
                    }
                }

                override fun getBeforeDependents(): Set<Class<*>>? = null

                override fun getAfterDependents(): Set<Class<*>>? = null

                override fun affectsGlobalScope(): Boolean = false

                //                            if (info.isNotEmpty()) {
                //                                attributes.addValue("class", "language-$info")
                //                            }

            })
            .build()

        fun toHtml(markdown: String): String {
            val document = parser.parse(markdown)
            return renderer.render(document)
        }
    }

    private fun refreshChatPanel() {
        LOG.info("Rendering ${chatBlocks.size} blocks")
        val htmlBody = chatBlocks.joinToString("\n\n") { it.content }
        LOG.info("htmlDetails=$htmlBody")

        // Цвета темы
        val panelBg = UIManager.getColor("Panel.background") ?: Color.WHITE
        val textColor = UIManager.getColor("Label.foreground") ?: Color.BLACK
        val codeBg = EditorColorsManager.getInstance().globalScheme.defaultBackground

        val styledHtml = """
        <html>
        <head>
            <meta charset="UTF-8">
            <style>
            
                .chat {
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                }
    
                /* USER */
                .bubble-user {
                    align-self: flex-end;
                    background: #2b6cff;
                    color: white;
                    padding: 10px 14px;
                    border-radius: 14px 14px 4px 14px;
                    max-width: 75%;
                }
    
                /* ASSISTANT */
                .bubble-assistant {
                    align-self: flex-start;
                    background: #2a2a2a;
                    color: #eaeaea;
                    padding: 10px 14px;
                    border-radius: 14px 14px 14px 4px;
                    max-width: 85%;
                }
            
                body { 
                    background-color: ${panelBg.toHex()}; 
                    color: ${textColor.toHex()}; 
                    margin: 0; 
                    padding: 12px 16px; 
                    font-family: system-ui, sans-serif;
                    line-height: 1.6;
                    font-size: 16px;
                }
                
                /* БЛОК КОДА (pre) */
                pre {
                    background-color: ${codeBg.toHex()};
                    border-radius: 6px;
                    padding: 12px;
                    overflow-x: auto;
                    max-width: 100%;
                    font-size: 14px;
                }
                
                /* КОД ВНУТРИ БЛОКА */
                pre code {
                    font-family: ui-monospace, 'Cascadia Mono', 'Segoe UI Mono', monospace;
                    font-size: 14px;
                    background: none;
                    padding: 0;
                }
                
                /* INLINE code (в тексте) */
                code {
                    background-color: rgba(128, 128, 128, 0.15);
                    padding: 2px 6px;
                    border-radius: 4px;
                    font-size: 0.9em;   /* ключевой момент */
                    font-family: ui-monospace, 'Cascadia Mono', monospace;
                }
                
                p {
                    margin: 6px 0;
                }
                
                /* Важно для списков <details> / <summary> */
                details {
                    margin: 16px 0;
                    border-left: 4px solid #888888;
                    padding-left: 14px;
                    font-size: 16px;
                }
                
                summary {
                    cursor: pointer;
                    padding: 8px 12px;
                    background-color: rgba(128, 128, 128, 0.12);
                    border-radius: 6px;
                    font-weight: 500;
                    font-size: 16px;
                }
                
                details[open] {
                    padding-bottom: 8px;
                    font-size: 16px;
                }
                
                /* Улучшение таблиц и других блоков */
                table { width: 100%; border-collapse: collapse; }
                th, td { border: 1px solid #666; padding: 8px; }
            </style>
            
            <!-- Prism.js — лёгкая и красивая подсветка кода -->
            <link href="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/prism-tomorrow.min.css" rel="stylesheet" />
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-kotlin.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-java.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-python.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-bash.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-json.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-javascript.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-rust.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-c.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-cpp.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-go.min.js"></script>
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-pascal.min.js"></script>
            <!-- Добавь другие языки при необходимости: javascript, xml, json, bash и т.д. -->
        </head>
        <body>
            $htmlBody
            <script>
                (function() {
                    console.log("[ChatPanel] init");
                
                    if (window.Prism) {
                        Prism.highlightAll();
                    }
                })();
            </script>
            <script>
                function toggleCode(btn) {
                    const pre = btn.parentElement.nextElementSibling;
                    if (pre.style.display === "none") {
                        pre.style.display = "block";
                    } else {
                        pre.style.display = "none";
                    }
                }
            </script>
            <script>s            
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
        jbCefBrowser.loadHTML(styledHtml)

        // Инжектим taskHandlers.js (лучше делать после loadHTML, а не сразу)
        try {
            val jsCode = this::class.java.getResource("/js/taskHandlers.js")?.readText()
                ?: throw kotlinx.io.files.FileNotFoundException("taskHandlers.js not found in resources")

            // Небольшая задержка, чтобы DOM был готов
            SwingUtilities.invokeLater {
                jbCefBrowser.cefBrowser.executeJavaScript(jsCode, "", 0)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load taskHandlers.js", e)
        }

        scrollToBottom()
    }
    private fun recreateMarkdownPanel() {
//
//        remove(markdownPanel)
//        //val newPanel = MarkdownJCEFHtmlPanel(project, markdownFile)
//        val newPanel = JPanel()
//        newPanel.add(jbCefBrowser.component)
//        //addCefBrowserHandler(newPanel)
//        add(newPanel, BorderLayout.CENTER)
//        markdownPanel = newPanel
//        revalidate()
//        repaint()
        refreshChatPanel()
    }

    fun addContextMenu(textComponent: JTextComponent) {
        val menu = JPopupMenu()

        val cutItem = JMenuItem("Вырезать").apply { addActionListener { textComponent.cut() } }
        val copyItem = JMenuItem("Копировать").apply { addActionListener { textComponent.copy() } }
        val pasteItem = JMenuItem("Вставить").apply { addActionListener { textComponent.paste() } }
        val deleteItem = JMenuItem("Удалить").apply { addActionListener { textComponent.replaceSelection("") } }
        val clearItem = JMenuItem("Очистить всё").apply { addActionListener { textComponent.text = "" } }

        menu.add(cutItem)
        menu.add(copyItem)
        menu.add(pasteItem)
        menu.add(deleteItem)
        menu.addSeparator()
        menu.add(clearItem)

        textComponent.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) menu.show(e.component, e.x, e.y) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) menu.show(e.component, e.x, e.y) }
        })
    }

    fun getEditorContext(project: Project): Triple<String, IntRange, String>? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null

        val document = editor.document
        val selectionModel = editor.selectionModel

        val text = selectionModel.selectedText ?: return null

        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd

        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(endOffset) + 1

        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        val fileName = file?.name ?: "Unknown"

        return Triple(fileName, startLine..endLine, text)
    }

    private fun addJbCefBrowserHandler() {
        val cefBrowser = jbCefBrowser.cefBrowser

        jsQuery = JBCefJSQuery.create(jbCefBrowser)   // ← самый стабильный способ сейчас
        // 2. Обработка сообщений от JS
        jsQuery.addHandler(::handleJsQuery)


        // Добавляем LoadHandler (чтобы инжектить при перезагрузках страницы)
        val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectTaskHandlers(cefBrowser)
                }
            }
        }
        jbCefBrowser.jbCefClient.addLoadHandler(loadHandler, jbCefBrowser.cefBrowser)

        // Первый инжект сразу
        injectTaskHandlers(cefBrowser)
    }

    // Функция для инъекции bridge + загрузки твоего JS
    private fun injectTaskHandlers(cefBrowser: CefBrowser?) {
        if (!::jsQuery.isInitialized) {
            LOG.error("jsQuery ещё не инициализирован")
            return
        }

        // Защита от повторного создания bridge
        val bridgeScript = """
        if (!window.taskBridge) {
            window.taskBridge = {
                send: function(msg) {
                    ${jsQuery.inject("msg")}   // ← только здесь используется inject
                }
            };
            console.log("%c[ChatPanel] taskBridge created", "color: #0a0");
        }
        """.trimIndent()

        // Сначала создаём bridge
        cefBrowser?.executeJavaScript(bridgeScript, null, 0)

        // Потом загружаем твой taskHandlers.js (как строку)
        val taskHandlersJs = loadTaskHandlersJs()   // см. ниже
        cefBrowser?.executeJavaScript(taskHandlersJs, null, 0)
    }

    private fun loadTaskHandlersJs(): String {
        return this::class.java.getResource("/js/taskHandlers.js")?.readText()
            ?: error("Cannot load taskHandlers.js")
    }

    private fun handleJsQuery(arg: String): JBCefJSQuery.Response? {
        println("JS → Kotlin: $arg")
        // обработка...
        SwingUtilities.invokeLater {
            when {
                arg.startsWith("click:") -> {
                    val id = arg.removePrefix("click:")
                    taskManagerPanel.selectTask(id)
                }
                arg == "hover:exit" -> taskManagerPanel.clearHover()
                arg.startsWith("hover:") -> {
                    val id = arg.removePrefix("hover:")
                    taskManagerPanel.hoverTask(id)
                }
            }
        }
        //JBCefJSQuery.Response("OK: $queryArgument обработано")
        return JBCefJSQuery.Response("success")
    }

    private fun scrollToBottom() {
        jbCefBrowser.cefBrowser.executeJavaScript(
            "window.scrollTo(0, document.body.scrollHeight);",
            jbCefBrowser.cefBrowser.url, 0
        )
    }

    fun onTaskUpdated() {
        rebuildMarkdownFromTasks()
    }

    override fun dispose() {
        // Очистка ресурсов при закрытии
    }


}