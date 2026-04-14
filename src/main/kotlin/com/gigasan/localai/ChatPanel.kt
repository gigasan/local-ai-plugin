package com.gigasan.localai

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import okhttp3.OkHttpClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
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
import com.intellij.ui.JBColor
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.AttributeProvider
import com.vladsch.flexmark.html.AttributeProviderFactory
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.html.renderer.AttributablePart
import com.vladsch.flexmark.html.renderer.LinkResolverContext
import com.vladsch.flexmark.parser.Parser
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.text.format
import com.intellij.openapi.ui.Messages

//sealed class ChatBlock {
//    data class Text(val value: String): ChatBlock()
//    data class Reasoning(val value: String): ChatBlock()
//    data class Tool(val name: String, val output: String): ChatBlock()
//}

// Блок Markdown
//data class ChatBlock(
//    val id: String,
//    var content: String,
//    var reasoning: String,
//    var description: String,
//)

class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private var updateTimer: Timer? = null
    private var jbCefBrowser = JBCefBrowser()   // или через builder, если нужно
    // 2. Создаём jsQuery ТОЛЬКО после того, как браузер полностью создан
    private lateinit var jsQuery: JBCefJSQuery   // используем late init
    // ← сюда добавь свой wrapper
    private val browserWrapper = JPanel(BorderLayout()).apply {
        // Важно: делаем так, чтобы браузер растягивался по всему доступному месту
        preferredSize = Dimension(600, 800)   // начальный разумный размер
        minimumSize = Dimension(300, 400)
        //add(jbCefBrowser.component, BorderLayout.CENTER)
        // Опционально: можно добавить тонкую рамку для отладки
        border = BorderFactory.createLineBorder(Color.GRAY)
    }

//    private var animationStep = 0
    private val statusBarWidth = 30 // Желаемая ширина строки в символах
    private var lastUpdateMs = 0L
    private var marqueeOffset = 0L
    private var lastTextLength = 0
    private var lastAnimationMs = 0L


    //private val chatBlocks = mutableListOf<ChatBlock>()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel()
    private val logger = Logger.getInstance("ChatPanel")

    companion object {
        fun buildTaskFromString(text: String): TaskData {
            val task = TaskData(
                id = System.currentTimeMillis().toString(),
                title = "✏️",
                //description = "chat message",
                content = text,
                zoneType = "Chat",
                request = "",
                answer = "",
                status = TaskStatus.CREATED,
                reasoning = "",
                hint = "",
            )
            return task
        }

        var instance: ChatPanel? = null
    }

    init {
        instance = this

        // === Основная структура панели ===
        val mainPanel = JPanel(BorderLayout())

        // Верхняя часть — браузер (чат)
        val chatPanel = JPanel(BorderLayout())
        chatPanel.add(browserWrapper, BorderLayout.CENTER)

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
        installDropHandler(southPanel)

        // Собираем всё вместе
        mainPanel.add(chatPanel, BorderLayout.CENTER)
        mainPanel.add(southPanel, BorderLayout.SOUTH)

        setContent(mainPanel)

        // === Инициализация JCEF ===
        if (!JBCefApp.isSupported()) {
            logger.error("JBCefApp is not supported!")
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
                if (e.keyCode == KeyEvent.VK_ENTER)
                {
                    sendMessage()
                    //KotlinProjectAnalyzer.log()
                    //Messages.showInfoMessage(project, projectMap, "Project Map")
                }

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
                SwingUtilities.invokeLater { rebuildChatBlocksFromTasks() }
            })

        logger.info("ChatPanel initialized")
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
                            //description = files.joinToString(" ") { it.name },
                            content = files.joinToString("\n") { it.absolutePath },
                            zoneType = "File",
                            request = "check errors",
                            answer = "",
                            status = TaskStatus.ERROR,
                            reasoning = "",
                            hint = "",
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
                                //description = "$fileName ${lines.first}-${lines.last}",
                                content = text,
                                zoneType = "Editor",
                                request = "check errors",
                                answer = "",
                                status = TaskStatus.CREATED,
                                reasoning = "",
                                hint = "",
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
                                //description = preview,
                                content = droppedText,
                                zoneType = "External",
                                request = "check errors",
                                answer = "",
                                status = TaskStatus.CREATED,
                                reasoning = "",
                                hint = "",
                            )
                        }
                        taskManagerPanel.addTask(task)
                        event.dropComplete(true)
                    } else {
                        event.rejectDrop()
                    }
                } catch (e: Exception) {
                    logger.error(e)
                    event.rejectDrop()
                }
            }
        }, true)
        // Привязываем DropTarget к панели
        panel.dropTarget = dropTarget
    }

    fun scheduleMarkdownUpdate() {
        updateTimer?.stop()
        updateTimer = Timer(300) {
            rebuildChatBlocksFromTasks()
        }
        updateTimer?.isRepeats = false
        updateTimer?.start()
    }

    // TaskData -> ChatBlock
    fun rebuildChatBlocksFromTasks() {
        val tasks = taskManagerPanel.getAllTasks()
        renderChatBlocks(tasks)
    }

    fun buildTaskHtml(task: TaskData): String {

        val description = AIMetrics.buildDescription(
            request = task.request,
            content = task.content,
            status = task.status,
        )

        var data_task_id = "data-task-id='${task.id}'"

        val htmlText = buildString {
            append("<details>")
            append("<summary ${data_task_id}>${task.title} ${description}</summary>\n")

            append("<div class='chat'>")
                if (task.request.isNotBlank()) {
                    append("<div class='bubble-user'>")
                    append(MarkdownRenderer.toHtml(task.request))
                    append("</div>")
                }

                if (task.content.isNotBlank()) {
                    append("<div class='bubble-user'>")
                    append(MarkdownRenderer.toHtml(task.content))
                    append("</div>")
                }

                if (task.answer.isNotBlank() || task.reasoning.isNotBlank()) {
                    append("<div class='bubble-assistant'>")

                    if (task.reasoning.isNotBlank()) {
                        append("<p>")
                        append(HtmlProcessor.insertReasoning(task.reasoning.trim()))
                        append("</p>")
                    }

                    if (task.answer.isNotBlank()) {
                        append(MarkdownRenderer.toHtml(task.answer))
                    }
                    append("</div>")
                }
            append("</div>")

            if (task.id.isNotBlank()) {
                append("<div class='footer'>")
                append(HtmlProcessor.getFormatedTime(task.id))
                append("\n\n${task.hint}")
                append("</div>")
            }
            append("</details>\n")
        }

        val bubbledHtmlText = HtmlProcessor.transformCodeBlocks(htmlText)
        return bubbledHtmlText
    }


    fun sendTask(task: TaskData) {

        val provider = DefaultChatConfigProvider(PluginSettings.instance)
        val url = provider.buildChatUrl()
        val model = provider.buildChatModel()

        val clientOk = HttpClientProvider.client
        val requestOk = LocalAIService.createRequest(url, model, "Write a short bedtime story about a unicorn.")
        val responseOk = clientOk.newCall(requestOk).execute()
        println(responseOk.body?.string())

    }

    /**
     * Создает эффект бегущей строки
     */
    private fun createSmartMarquee(text: String, width: Int): String {
        if (text.isEmpty()) return " ".repeat(width)

        // 1. Сброс, если контекст полностью сменился (текст стал короче)
        if (text.length < lastTextLength) {
            marqueeOffset = 0
        }
        lastTextLength = text.length

        // 2. Если всё влезает — не крутим
        if (text.length <= width) {
            return text.padEnd(width)
        }

        // 3. Формируем кольцо (текст + разделитель)
        val longText = "$text          "
        val len = longText.length

        val builder = StringBuilder()
        for (i in 0 until width) {
            // Используем наш сохраненный offset вместо времени
            val charIndex = ((marqueeOffset + i) % len).toInt()
            builder.append(longText[charIndex])
        }

        // 4. Двигаем окно для следующего кадра
        marqueeOffset = (marqueeOffset + 1) % len

        return builder.toString()
    }

    sealed class TaskResult {
        data class Success(val task: TaskData) : TaskResult()
        data class Error(val task: TaskData, val error: Throwable) : TaskResult()
    }

    fun onStreamEvent(event: StreamEvent, indicator: ProgressIndicator) {
        val now = System.currentTimeMillis()

        // Двигаем анимацию не чаще, чем раз в 150-200 мс,
        // чтобы скорость была комфортной
        if (now - lastAnimationMs > 180) {
            val baseMsg = event.indicatorText.replace("\n", " ")
            indicator.text = createSmartMarquee(baseMsg, 25)
            lastAnimationMs = now
        }

        indicator.checkCanceled()
    }

    fun processTask(task: TaskData, indicator: ProgressIndicator): TaskResult {
        return try {
            val provider = DefaultChatConfigProvider(PluginSettings.instance)
            val model = provider.buildChatModel()
            val url = provider.buildChatUrl()
            val backend = provider.buildBackend()
            val apiKey = "sk-lm-zESagiFt:J3TSJCffvecSKvcx4Fym"
            logger.info("task = $task")
            logger.info("model = $model")
            val request = "${task.request} ${task.content}".trimIndent()
            val chatContext = ChatRequestBuilder()
                .system("You are IntelliJ assistant")
                .memory(limit = 5)
                .user(request)
                .stream(true)
                .model(model)
                .maxTokens(provider.buildMaxTokenLimit())
                .build(task)
            logger.warn("chatContext = $chatContext")
            val stateManager = StateManager()
            val stateMachine = StateMachine()
            val adapter = BackendAdapter()
            val http = HttpClientProvider.client
            val aiClientStream = AIClientStream(
                adapter = adapter,
                http = http,
                stateManager = stateManager,
                stateMachine = stateMachine
            )
            //val toolClient = ToolOrchestrator(AIClientPost()) // url, api and backend from PluginSettings
            val startTime = System.currentTimeMillis()
            val aiResult = aiClientStream.execute(chatContext, indicator) { event ->
                onStreamEvent(event, indicator)
            }

            //val toolResult = toolClient.run(chatContext)
            //logger.warn("toolResult = $toolResult")
            val endTime = System.currentTimeMillis()
            val result = aiResult.copy(durationMs = endTime - startTime)

            val updated = task.copy(
                answer = result.text,
                hint = AIMetrics.buildHint(
                    usage = result.usage,
                    durationMs = result.durationMs
                ),
//                description = AIMetrics.buildDescription(
//                    request = task.request,
//                    content = task.content,
//                    status = task.status,
//                ),
                reasoning = result.reasoning?.trim() ?:"",
                status = TaskStatus.DONE,
            )

            TaskResult.Success(updated)
        } catch (e: Exception) {
            TaskResult.Error(
                task.copy(
                    status = TaskStatus.ERROR,
                    //description = "❌Ошибка:** ${e.message}"
                ),
                e)
        }
    }

    fun runTaskInBackground(
        project: Project?,
        task: TaskData,
        onUpdate: (TaskData) -> Unit
    ) {
        // Включаем canBeCancelled = true (третий параметр)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI Processing", true) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Подготовка запроса..."
                indicator.isIndeterminate = true

                // Передаем индикатор в процесс, чтобы внутри цикла чтения
                // делать индикатор.checkCanceled()
                when (val result = processTask(task, indicator)) {
                    is TaskResult.Success -> {
                        MemorySystem.add(result.task)
                        onUpdate(result.task)
                    }
                    is TaskResult.Error -> {
                        logger.warn("Task failed", result.error)
                        onUpdate(result.task)
                    }
                }
            }

            override fun onCancel() {
                // Опционально: логируем отмену пользователем
                logger.info("Task cancelled by user")
            }

            override fun onThrowable(error: Throwable) {
                // Если сокет закрыт, это упадет сюда
                ApplicationManager.getApplication().invokeLater {
                    onUpdate(task.copy(status = TaskStatus.ERROR))
                }
            }
        })
    }

    private fun sendMessage() {
        val mainText = inputField.text.trim()
        if (mainText.isEmpty()) return
        val task = buildTaskFromString(mainText)
        inputField.text = ""

        taskManagerPanel.addTask(task)

        updateTaskStatus(task, TaskStatus.SENDING)

        runTaskInBackground(project, task) { updatedTask ->
            updateTaskOnUI(updatedTask)
        }

/*
        // del below

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
*/
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
    private fun updateTaskStatus(task: TaskData, newStatus: TaskStatus) {
        task.status = newStatus
        updateTaskOnUI(task)
    }


    fun sendExternalMessage(text: String) {
        val task = ChatPanel.buildTaskFromString(text)
        taskManagerPanel.addTask(task)



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


    object MarkdownRenderer {

        private val parser = Parser.builder().build()

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

    /*

    if (!result.reasoning.isNullOrBlank()) {
    append(
        """
        <details style="margin-top:8px;">
            <summary>🧠 Размышления</summary>
            <pre>${result.reasoning}</pre>
        </details>
        """.trimIndent()
    )
}

     */

    fun Color.darker(factor: Float = 0.9f): Color {
        return Color(
            (red * factor).toInt().coerceIn(0, 255),
            (green * factor).toInt().coerceIn(0, 255),
            (blue * factor).toInt().coerceIn(0, 255),
            alpha
        )
    }

    fun Color.lighter(factor: Float = 0.1f): Color {
        return Color(
            (red + (255 - red) * factor).toInt().coerceIn(0, 255),
            (green + (255 - green) * factor).toInt().coerceIn(0, 255),
            (blue + (255 - blue) * factor).toInt().coerceIn(0, 255),
            alpha
        )
    }

    fun Color.adjustBrightness(factor: Float): Color {
        val hsb = FloatArray(3)
        Color.RGBtoHSB(red, green, blue, hsb)

        hsb[2] = (hsb[2] * factor).coerceIn(0f, 1f)

        return Color.getHSBColor(hsb[0], hsb[1], hsb[2])
    }

    fun isDarkTheme(): Boolean {
        val lookAndFeel = UIManager.getLookAndFeel().name.lowercase();
        return lookAndFeel.contains("darcula") || lookAndFeel.contains("dark");
    }

    private fun renderChatBlocks(tasks: List<TaskData>) {
        logger.info("Rendering ${tasks.size} blocks")

        val html = tasks.joinToString("\n\n") { task ->
            buildTaskHtml(task)
        }
        logger.info("html=$html")
        //val htmlBody = chatBlocks.joinToString("\n\n") { it.content }

        //val htmlReasoningBody = chatBlocks.joinToString("\n\n") { it.reasoning }
        //logger.info("htmlReasoningBody=$htmlReasoningBody")


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
                    align-self: flex-start;
                    background: ${bubbleBg.toHex()};
                    color: ${bubbleText.toHex()};
                    padding: 10px 14px;
                    border-radius: 14px 14px 14px 4px;
                    max-width: 75%;
                }
    
                /* ASSISTANT */
                .bubble-assistant {
                    align-self: flex-end;
                    background: ${bubbleBg.toHex()};
                    color: ${bubbleText.toHex()};
                    padding: 10px 14px;
                    border-radius: 14px 14px 4px 14px;
                    max-width: 90%;
                }
            
                .reasoning {
                    color: ${textColor.toHex()};
                    white-space: pre-wrap;
                    line-height: 1.4;
                    font-family: monospace;
                    max-width: 90%;
                }
                
                .footer {
                    padding-top: 8px;
                    font-size: 10px;
                    line-height: 1.0;
                    font-family: monospace;
                    max-width: 90%;
                    color: #604E29;
                }
                
                body { 
                    background-color: ${panelBg.toHex()}; 
                    color: ${textColor.toHex()}; 
                    margin: 0; 
                    padding: 12px 16px; 
                    font-family: system-ui, sans-serif;
                    line-height: 1.6;
                    font-size: 13px;
                }
                
                /* БЛОК КОДА (pre) */
                pre {
                    background-color: ${codeBg.toHex()};
                    border-radius: 6px;
                    padding: 12px;
                    overflow-x: auto;
                    max-width: 100%;
                    font-size: 13px;
                    white-space: pre-wrap;
                    word-break: break-word;
                }
                
                /* КОД ВНУТРИ БЛОКА */
                pre code {
                    font-family: ui-monospace, 'Cascadia Mono', 'Segoe UI Mono', monospace;
                    font-size: 13px;
                    background: none;
                    padding: 0;
                }
                
                /* INLINE code (в тексте) */
                code {
                    background-color: rgba(128, 128, 128, 0.05);
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
                    font-size: 13px;
                }
                details[open] {
                    padding-bottom: 8px;
                    font-size: 13px;
                }
                
                /* background-color: rgba(128, 128, 128, 0.12); */
                summary {
                    cursor: pointer;
                    padding: 8px 12px;
                    background-color: rgba(64, 64, 64, 0.10);
                    border-radius: 6px;
                    font-weight: 500;
                    font-size: 13px;
                    color: ${textColor.toHex()}; 
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
            <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-lisp.min.js"></script>
            <!-- Добавь другие языки при необходимости: javascript, xml, json, bash и т.д. -->
        </head>
        <body>
            $html
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
            logger.warn("Failed to load taskHandlers.js", e)
        }

        scrollToBottom()
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

        textComponent.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) menu.show(e.component, e.x, e.y) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) menu.show(e.component, e.x, e.y) }
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
            logger.error("jsQuery ещё не инициализирован")
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
        rebuildChatBlocksFromTasks()
    }

    override fun dispose() {
        // Очистка ресурсов при закрытии
    }


}