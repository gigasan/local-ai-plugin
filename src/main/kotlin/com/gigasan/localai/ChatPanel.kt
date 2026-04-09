package com.gigasan.localai

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.system.exitProcess


// Блок Markdown
data class MarkdownBlock(
    val id: String,
    var content: String,
    var description: String,
)

class ChatPanel(val project: Project) : JPanel(BorderLayout()), Disposable {
    private var updateTimer: javax.swing.Timer? = null
    private var jbCefBrowser = JBCefBrowser()   // или через builder, если нужно
    // 2. Создаём jsQuery ТОЛЬКО после того, как браузер полностью создан
    private lateinit var jsQuery: JBCefJSQuery   // используем lateinit
    private val markdownFile = LightVirtualFile("chat.md", "")   // dummy-файл для панели
    //private var markdownPanel = MarkdownJCEFHtmlPanel(project, markdownFile)
    private var markdownPanel = JPanel()
    private val markdownBlocks = mutableListOf<MarkdownBlock>()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel()
    private val LOG = Logger.getInstance("ChatPanel")

    companion object {
        var instance: ChatPanel? = null
    }

    init {
        instance = this

        val southPanel = JPanel()
        southPanel.layout = BoxLayout(southPanel, BoxLayout.Y_AXIS)

        // --- Панель задач ---
        val taskContainer = JPanel(BorderLayout())
        val taskScroll = JBScrollPane(taskManagerPanel)
        taskScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        taskScroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        taskContainer.add(taskScroll, BorderLayout.CENTER)
        taskContainer.minimumSize = Dimension(0, 0)
        taskContainer.preferredSize = Dimension(Int.MAX_VALUE, 0)
        taskContainer.maximumSize = Dimension(Int.MAX_VALUE, 150)
        taskContainer.alignmentX = Component.LEFT_ALIGNMENT
        southPanel.add(taskContainer)

        // поставить drop на southPanel
        installDropHandler(southPanel)
        inputField.dropTarget = null
        sendButton.dropTarget = null

        // --- Панель ввода ---
        val inputPanel = JPanel(BorderLayout())
        inputPanel.border = BorderFactory.createEmptyBorder(5, 0, 0, 0)
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)
        //inputPanel.minimumSize = inputPanel.preferredSize
        inputPanel.maximumSize = Dimension(Int.MAX_VALUE, inputPanel.preferredSize.height)
        inputPanel.alignmentX = Component.LEFT_ALIGNMENT
        southPanel.add(inputPanel)

        // --- Обновление высоты taskContainer при добавлении/удалении задач ---
        fun updateTaskContainerHeight() {
            SwingUtilities.invokeLater {
                val taskHeight = taskManagerPanel.preferredSize.height.coerceIn(0, 150)
                taskContainer.preferredSize = Dimension(0, taskHeight)
                taskContainer.revalidate()
            }
        }
        updateTaskContainerHeight()
        taskManagerPanel.onTasksChanged = {
            scheduleMarkdownUpdate()
            updateTaskContainerHeight()
        }

        // --- Добавляем southPanel под markdownPanel ---
        if (!JBCefApp.isSupported()) {
            LOG.error("JBCefApp is not supported!")
            /* fallback */
            exitProcess(0)
        } else {
            LOG.info("JBCefApp is supported!")
            jbCefBrowser = JBCefBrowser()
            markdownPanel.add(jbCefBrowser.component)
            addJbCefBrowserHandler()
        }

        add(markdownPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)

        // --- Логика (без изменений) ---
        sendButton.addActionListener { sendMessage() }
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) sendMessage()
            }
        })

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            SwingUtilities.invokeLater {
                recreateMarkdownPanel()
            }
        })

        refreshMarkdownPanel()
        addContextMenu(inputField)
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

    fun rebuildMarkdownFromTasks() {
        markdownBlocks.clear()
        val tasks = taskManagerPanel.getAllTasks()
        tasks.forEach { task ->
            //LOG.info("task-id: ${task.id}")
            val blockText = buildString {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val instant = java.time.Instant.ofEpochMilli(task.id.toLong())
                val dateTime = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                append("<details data-task-id='${task.id}' style='margin-bottom:8px;'>")
                append("<summary data-task-id='${task.id}' style='cursor:pointer;'>${task.title} ${task.description}</summary>\n")
                append("<pre><code>")
                append(task.content.replace("<", "&lt;").replace(">", "&gt;"))
                append("</code></pre>\n")
                append("<pre><code>")
                append(task.answer.replace("<", "&lt;").replace(">", "&gt;"))
                append("</code></pre>\n")
                append("<p>${task.job}</p>")
                append("<p>${dateTime.format(formatter)}</p>")
                append("</details>\n")
            }
            val markdownBlock = MarkdownBlock(
                id = task.id,
                content = blockText,
                description = task.description,
            )
            //LOG.info("blockText: $blockText")
            markdownBlocks.add(markdownBlock)
        }
        refreshMarkdownPanel()
    }

    fun sendTask(task: TaskData) {

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
        SwingUtilities.invokeLater {
            val index = taskManagerPanel.getAllTasks().indexOfFirst { it.id == task.id }
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
    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val milliseconds = durationMs % 1000
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

    private fun refreshMarkdownPanel() {
        val fullMarkdown = markdownBlocks.joinToString("\n\n") { it.content }
        val rawHtml = MarkdownUtil.generateMarkdownHtml(markdownFile, fullMarkdown, project)

        // Определяем цвета в зависимости от темы
        val laf = LafManager.getInstance().currentUIThemeLookAndFeel
        val isDarkTheme = laf?.name?.lowercase()?.contains("dark") == true
        //val codeBgColor = if (isDarkTheme) "#313335" else "#F5F5F5"

        val panelBg  = UIManager.getColor("Panel.background") ?: Color.WHITE
        val textColor = UIManager.getColor("Label.foreground") ?: Color.BLACK
        val codeBg = EditorColorsManager.getInstance().globalScheme.defaultBackground
        val styledHtml = """
                            <html>
                            <head>
                                <style>
                                    .task-hover {
                                        background-color: rgba(255,255,0,0.2);
                                    }
                                    body {
                                        background-color: ${panelBg.toHex()};
                                        color: ${textColor.toHex()};
                                        margin: 0;
                                        padding: 8px;
                                    }
                                    pre, code {
                                        background-color: ${codeBg.toHex()};
                                    }
                                </style>
                            </head>
                            <body>
                                $rawHtml
                                <script>
                                    document.addEventListener("DOMContentLoaded", () => {
                                        attachHandlers(); // твоя функция в JS
                                    });
                                </script>            
                            </body>
                            </html>
                        """.trimIndent()

        jbCefBrowser.loadHTML(styledHtml)
        //markdownPanel.set createImmediately()
        //markdownPanel.setHtml(styledHtml, 0)
        val jsCode = this::class.java.getResource("/js/taskHandlers.js")?.readText()?: throw kotlinx.io.files.FileNotFoundException(
            "taskHandlers.js not found in resources"
        )
        jbCefBrowser.cefBrowser.executeJavaScript(jsCode, "", 0)

//        )
//        markdownPanel.cefBrowser.executeJavaScript(jsCode, markdownPanel.cefBrowser.url, 0)
        //LOG.info("styledHtml: $styledHtml");
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
        refreshMarkdownPanel()
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



    override fun dispose() {
        // Очистка ресурсов при закрытии
    }

//    // --- Создаём JS Query для текущего браузера ---
//    private val jsQuery: JBCefJSQuery = run {
//        //val cefBrowser = jbCefBrowser.cefBrowser
//        JBCefJSQuery.create(jbCefBrowser)
//    }

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

        // 1. Сначала создаём jsQuery
        //val jsQuery = JBCefJSQuery.create(cefBrowser as com.intellij.ui.jcef.JBCefBrowserBase)

        val bridgeScript = """
        if (!window.taskBridge) {
            window.taskBridge = {
                send: function(msg) {
                    ${jsQuery.inject("msg")}   // ← только здесь используется inject
                }
            };
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


}