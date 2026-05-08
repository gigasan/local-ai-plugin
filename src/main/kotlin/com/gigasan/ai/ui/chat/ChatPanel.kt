package com.gigasan.ai.ui.chat

import com.gigasan.ai.actions.AnalyzeAction
import com.gigasan.ai.actions.AskAction
import com.gigasan.ai.actions.AutoSearchToggleAction
import com.gigasan.ai.actions.CleanChatAction
import com.gigasan.ai.actions.RefactorAction
import com.gigasan.ai.actions.SendFileAction
import com.gigasan.ai.actions.TaskCompositorAction
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.core.wrapCode
import com.gigasan.ai.runtime.ClientStream
import com.gigasan.ai.runtime.AIMetrics
import com.gigasan.ai.runtime.BackendAdapter
import com.gigasan.ai.runtime.ChatRequestBuilder
import com.gigasan.ai.runtime.HttpClientProvider
import com.gigasan.ai.runtime.LocalAIService
import com.gigasan.ai.runtime.MemorySystem
import com.gigasan.ai.runtime.StateMachine
import com.gigasan.ai.runtime.StateManager
import com.gigasan.ai.runtime.StreamEvent
import com.gigasan.ai.runtime.parser.ResponseResult
import com.gigasan.ai.runtime.parser.withDuration
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
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
import com.intellij.util.ui.JBUI
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.AttributeProvider
import com.vladsch.flexmark.html.AttributeProviderFactory
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.html.renderer.AttributablePart
import com.vladsch.flexmark.html.renderer.LinkResolverContext
import com.vladsch.flexmark.parser.Parser
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.text.format
import java.awt.FlowLayout
import java.awt.event.ComponentEvent
import java.awt.event.ComponentAdapter
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction

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
    private val provider get() = project.service<PluginConfigProvider>()
    private var updateTimer: Timer? = null
    private var jbCefBrowser = JBCefBrowser() // или через builder, если нужно
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

    private lateinit var layeredPane: JLayeredPane
    private lateinit var searchPanel: JPanel   // ← теперь поле
    private var searchField: JTextField? = null

//    private var animationStep = 0
    //private val statusBarWidth = 30 // Желаемая ширина строки в символах
    //private var lastUpdateMs = 0L
    private var marqueeOffset = 0L
    private var lastTextLength = 0
    private var lastAnimationMs = 0L
    private var isAutoSearchEnabled = false

    //private val chatBlocks = mutableListOf<ChatBlock>()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel()
    private val logger = Logger.getInstance("ChatPanel")

    companion object {
        fun buildTaskFromString(instruction: String, request: String): TaskData {
            val task = TaskData(
                id = System.currentTimeMillis().toString(),
                title = "✏️",
                //description = "chat message",
                //content = text,
                zoneType = "Chat",
                //request = request,
                answer = "",
                status = TaskStatus.CREATED,
                reasoning = "",
                footer = "",
                instruction = instruction,
                question = request,
            )
            return task
        }

        fun buildTaskFromInstructionQuestionTask(instruction: String, question: String): TaskData {
            val task = TaskData(
                id = System.currentTimeMillis().toString(),
                title = "✏️",
                zoneType = "Compositor",
                status = TaskStatus.CREATED,
                footer = "",
                instruction = instruction,
                question = question,
                reasoning = "",
                answer = "",

            )
            return task
        }


        var instance: ChatPanel? = null

        val CHAT_BROWSER_KEY = DataKey.create<JBCefBrowser>("ChatCefBrowser")
    }

    private fun createChatToolbar(): JComponent {
        val actionManager = ActionManager.getInstance()

        // 1. Создаем основную группу экшенов
        val mainGroup = DefaultActionGroup()

        mainGroup.add(Separator.getInstance()) // Визуальный разделитель

        // 3. Условная загрузка экшенов из настроек
        val settings = PluginSettingsService.instance // Предполагаю, что это синглтон/сервис

        if (settings.state.enableDebugFeature) {
            mainGroup.add(AskAction())
        }
        if (settings.state.enableFileTransfer) {
            mainGroup.add(SendFileAction())
        }
        if (settings.state.enableTaskCompositor) {
            mainGroup.add(TaskCompositorAction())
        }
        if (settings.state.enableRefactoring) {
            mainGroup.add(RefactorAction())
        }
        if (settings.state.enableCodeAnalysis) {
            mainGroup.add(AnalyzeAction())
        }
        if (settings.state.enableCleanChat) {
            mainGroup.add(CleanChatAction())
        }
        if (settings.state.enableAutoSearch) {
            mainGroup.add(AutoSearchToggleAction { enabled ->
            isAutoSearchEnabled = enabled
            })
        }

        // Добавляем DevTools, если включено
        if (settings.state.enableDevToolsAction) {
            val devToolsAction = object : AnAction("DevTools", "Open DevTools", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    // Используем поиск по компонентам, если DataKey не сработает
                    val browser = e.getData(CHAT_BROWSER_KEY)
                    browser?.cefBrowser?.openDevTools()
                }
            }
            mainGroup.add(devToolsAction)
        }

        // 4. Добавляем стандартные экшены в конец
//        mainGroup.addSeparator()
//        val clearAction = actionManager.getAction("LocalAI.ClearChat")
//        if (clearAction != null) {
//            mainGroup.add(clearAction)
//        }

        // 5. Создаем итоговый Toolbar
        val toolbar = actionManager.createActionToolbar(
            "ChatPanelToolbar",
            mainGroup,
            true // Горизонтальный
        )



        // Привязываем события к текущей панели
        toolbar.targetComponent = this

        return toolbar.component
    }


    init {
        instance = this

        // === Инициализация JCEF ===
        if (!JBCefApp.isSupported()) {
            logger.error("JBCefApp is not supported!")
            exitProcess(0)
        }

        // Создаём браузер (лучше здесь, а не в поле)
        jbCefBrowser = JBCefBrowser()

        // === LAYERED PANE ===
        layeredPane = JLayeredPane().apply { layout = null }

        // === Основная структура панели ===
        val mainPanel = JPanel(BorderLayout())

        layeredPane.add(mainPanel, JLayeredPane.DEFAULT_LAYER)

        searchPanel = createSearchPanel(jbCefBrowser)
        layeredPane.add(searchPanel, JLayeredPane.POPUP_LAYER)

        layeredPane.setLayer(searchPanel, JLayeredPane.POPUP_LAYER)
        layeredPane.moveToFront(searchPanel)

        layeredPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = layeredPane.width
                val h = layeredPane.height
                mainPanel.setBounds(0, 0, w, h)
                updateSearchPanelPosition()
                //searchPanel.setBounds(w - 240, 10, 220, 32)
            }
        })

        // Принудительное начальное позиционирование
        SwingUtilities.invokeLater {
            updateSearchPanelPosition()
            layeredPane.revalidate()
            layeredPane.repaint()
        }

        installShortcuts(layeredPane, searchPanel)
        searchPanel.isVisible = false

        setContent(layeredPane)

        val toolbarComponent = createChatToolbar()
        setToolbar(toolbarComponent) // Устанавливаем в слот SimpleToolWindowPanel

        // Верхняя часть — браузер (чат)
        val chatPanel = JPanel(BorderLayout())
        chatPanel.add(browserWrapper, BorderLayout.CENTER)

        // Нижняя часть — задачи + поле ввода
        val southPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // --- Панель задач ---
        val taskContainer = JPanel(BorderLayout()).apply {
            val taskScroll = JBScrollPane(taskManagerPanel).apply {
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
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

        //setContent(mainPanel)

        // === доинициализация JCEF ===
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

    private fun updateSearchPanelPosition() {
        if (!::layeredPane.isInitialized || !::searchPanel.isInitialized) return

        val w = layeredPane.width
        if (w > 320) {
            // Правый верхний угол с небольшим отступом
            searchPanel.setBounds(w - 310, 10, 290, 38)
        } else {
            // fallback для очень узкого окна
            searchPanel.setBounds(10, 10, 290, 38)
        }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        // "Регистрируем" наш браузер в контексте под ключом
        sink[CHAT_BROWSER_KEY] = jbCefBrowser
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
                            zoneType = "File",
                            answer = "",
                            status = TaskStatus.ERROR,
                            reasoning = "",
                            footer = "",
                            instruction = "",
                            question = files.joinToString("\n") { it.absolutePath },
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
                                //content = text,
                                zoneType = "Editor",
                                //request = "check errors",
                                answer = "",
                                status = TaskStatus.CREATED,
                                reasoning = "",
                                footer = "",
                                instruction = "",
                                question = "",
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
                                zoneType = "External",
                                answer = "",
                                status = TaskStatus.CREATED,
                                reasoning = "",
                                footer = "",
                                instruction = "",
                                question = droppedText,
                            )
                        }
                        taskManagerPanel.addTask(task)
                        event.dropComplete(true)
                    } else {
                        event.rejectDrop()
                    }
                } catch (e: Exception) {
                    logger.warn(e)
                    event.rejectDrop()
                }
            }
        }, true)
        // Привязываем DropTarget к панели
        panel.dropTarget = dropTarget
    }


    fun createSearchPanel(browser: JBCefBrowser): JPanel {
        val panel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = true
            preferredSize = Dimension(310, 38)
            minimumSize = Dimension(260, 38)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY.adjustBrightness(0.3f), 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
            )
        }

        // Поле ввода — теперь реально растягивается
        val field = JTextField(20).apply {
            font = font.deriveFont(12f)
        }

        // Маленькие плоские кнопки
        fun createSmallButton(text: String, action: () -> Unit): JButton {
            return JButton(text).apply {
                preferredSize = Dimension(26, 26)
                margin = JBUI.emptyInsets()
                isFocusPainted = false
                isBorderPainted = false          // убираем рамку
                isContentAreaFilled = false      // убираем фон при нажатии
                font = font.deriveFont(12f)
                addActionListener { action() }
            }
        }

        val btnPrev = createSmallButton("↑") {
            browser.cefBrowser.find(field.text, false, false, true)  // назад
        }

        val btnNext = createSmallButton("↓") {
            browser.cefBrowser.find(field.text, true, false, true)   // вперёд
        }

        val btnClose = createSmallButton("\uD83D\uDDD9") {
            browser.cefBrowser.stopFinding(true)
            searchPanel.isVisible = false
        }

        // Панель с кнопками
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(btnPrev)
            add(btnNext)
            add(btnClose)
        }

        panel.add(field, BorderLayout.CENTER)
        panel.add(buttonsPanel, BorderLayout.EAST)

        // Сохраняем ссылку на поле, чтобы можно было заполнять текст из других мест
        this.searchField = field   // ← добавь private var searchField: JTextField? = null в класс

        return panel
    }

    private fun getSelectedTextFromEditor(): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val selectionModel = editor.selectionModel
        return if (selectionModel.hasSelection()) {
            selectionModel.selectedText
        } else null
    }

    fun installShortcuts(root: JComponent, searchPanel: JPanel) {
        val im = root.getInputMap(WHEN_IN_FOCUSED_WINDOW)
        val am = root.getActionMap()

        // Основные шорткаты
        im.put(KeyStroke.getKeyStroke("control F"), "find")
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "hideFind")
        im.put(KeyStroke.getKeyStroke("F3"), "findNext")
        im.put(KeyStroke.getKeyStroke("shift F3"), "findPrev")

        am.put("find", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                updateSearchPanelPosition()
                searchPanel.isVisible = true
                layeredPane.moveToFront(searchPanel)
                searchPanel.revalidate()
                searchPanel.repaint()
                layeredPane.repaint()

                // 1. Сначала пытаемся взять из редактора
                val editorSelected = getSelectedTextFromEditor()

                if (!editorSelected.isNullOrBlank()) {
                    searchField?.text = editorSelected
                    searchField?.selectAll()
                    searchField?.requestFocusInWindow()
                } else {
                    // 2. Если ничего нет в редакторе — берём из браузера (чата)
                    jbCefBrowser.cefBrowser.executeJavaScript("window.getChatSelection();", "", 0)
                    // Результат придёт асинхронно в jsQuery.addHandler
                }

                logger.info("🔍 Search panel opened with text: '${searchField?.text}'")
            }
        })

        am.put("hideFind", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                searchPanel.isVisible = false
                jbCefBrowser.cefBrowser.stopFinding(true)

                layeredPane.repaint()
            }
        })

        am.put("findNext", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                searchField?.text?.let {
                    if (it.isNotBlank()) jbCefBrowser.cefBrowser.find(it, true, false, true)
                }
            }
        })

        am.put("findPrev", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                searchField?.text?.let {
                    if (it.isNotBlank()) jbCefBrowser.cefBrowser.find(it, false, false, true)
                }
            }
        })
    }

    fun scheduleMarkdownUpdate() {
        updateTimer?.stop()
        updateTimer = Timer(300) {
            rebuildChatBlocksFromTasks()
        }
        updateTimer?.isRepeats = false
        updateTimer?.start()
    }

    // WorkItem -> ChatBlock
    fun rebuildChatBlocksFromTasks() {
        val tasks = taskManagerPanel.getAllTasks()
        renderChatBlocks(tasks)
    }

    fun buildTaskHtml(task: TaskData): String {

        val description = AIMetrics.buildDescription(
            request = task.question,
            content = "",
            status = task.status,
            maxLen = 100
        )

        var data_task_id = "data-task-id='${task.id}'"

        val htmlText = buildString {
            append("<details>")
            append("<summary class='chat-header' ${data_task_id}>${task.title} ${description}</summary>\n")
            append("<div class='chat-body'>")
            append("<div class='chat'>")
                if (task.question.isNotBlank()) {
                    append("<div class='bubble-user'>")
                    append("<button class='collapse-btn' onclick='toggleBubble(this)'>collapse</button>")
                    append(MarkdownRenderer.toHtml(task.question))
                    append("</div>")
                }

//                if (task.content.isNotBlank()) {
//                    append("<div class='bubble-user'>")
//                    append("<button class='collapse-btn' onclick='toggleBubble(this)'>collapse</button>")
//                    append(MarkdownRenderer.toHtml(task.content))
//                    append("</div>")
//                }

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


    fun sendTask(task: TaskData) {
        val url = provider.buildUrl() + provider.buildChatEndpoint()
        val model = provider.buildChatModel()

        val clientOk = HttpClientProvider.client
        val localAIService = LocalAIService(project)
        val requestOk = localAIService.createRequest(url, model, "Write a short bedtime story about a unicorn.")
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

    fun processChatTask(task: TaskData, indicator: ProgressIndicator): TaskResult {
        return try {
            //logger.info("task = $task")
            //logger.info("model = $model")
            val request = task.question.trimIndent()
            val chatContext = ChatRequestBuilder(project)
                .system(provider.buildChatSystem())
                .memory(limit = 5)
                .user(request)
                .stream(provider.buildStream())
                .model(provider.buildChatModel())
                .maxTokens(provider.buildMaxTokenLimit())
                .build(task)
            logger.info("$chatContext")
            val stateManager = StateManager()
            val stateMachine = StateMachine()
            val adapter = BackendAdapter(project)
            val http = HttpClientProvider.client
            val clientStream = ClientStream(
                adapter = adapter,
                http = http,
                stateManager = stateManager,
                stateMachine = stateMachine
            )
            //val toolClient = ToolOrchestrator(AIClientPost()) // url, api and backend from PluginSettingsService
            val startTime = System.currentTimeMillis()
            val responseResult = clientStream.execute(project, chatContext, indicator) { event ->
                onStreamEvent(event, indicator)
            }

            //val toolResult = toolClient.run(chatContext)
            //logger.warn("toolResult = $toolResult")
            val endTime = System.currentTimeMillis()
            val result = responseResult.withDuration(endTime - startTime)

            val updated = task.copy(
                title = when (result) {
                    is ResponseResult.Success -> {
                        "✔\uFE0F${task.title}"
                    }
                    is ResponseResult.Error -> {
                        "❌${task.title}"
                    }
                },
                answer = when (result) {
                    is ResponseResult.Success -> {
                        result.text
                    }
                    is ResponseResult.Error -> {
                        result.message
                    }
                },

                footer = AIMetrics.buildFooter(
                    usage = if (result is ResponseResult.Success) { result.usage} else {null},
                    durationMs = result.durationMs
                ),

                reasoning = if (result is ResponseResult.Success) { result.reasoning?.trim()?:""} else {""},

                status = when (result) {
                    is ResponseResult.Success -> {
                        TaskStatus.DONE
                    }
                    is ResponseResult.Error -> {
                        TaskStatus.ERROR
                    }
                },
            )

            TaskResult.Success(updated)
        } catch (e: Exception) {
            TaskResult.Error(
                task.copy(
                    status = TaskStatus.ERROR,
                    title = "❌${task.title}",
                    answer = wrapCode(e.message?:"unknown error"),
                ),
                e)
        }
    }

    fun runChatTaskInBackground(
        project: Project?,
        task: TaskData,
        onUpdate: (TaskData) -> Unit
    ) {
        // Включаем canBeCancelled = true (третий параметр)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Ai processing", true) {

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Подготовка запроса..."
                indicator.isIndeterminate = true

                // Передаем индикатор в процесс, чтобы внутри цикла чтения
                // делать индикатор.checkCanceled()
                when (val result = processChatTask(task, indicator)) {
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
        val task = buildTaskFromString(provider.buildInstruction(), mainText)
        inputField.text = ""

        taskManagerPanel.addTask(task)

        updateTaskStatus(task, TaskStatus.SENDING)

        runChatTaskInBackground(project, task) { updatedTask ->
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

    fun cleanAllTasks() {
        val tasks = taskManagerPanel.getAllTasks()
        try {
            for (task in tasks.reversed()) {
                taskManagerPanel.removeTask(task.id)
            }
            taskManagerPanel.onTasksChanged?.invoke()
        } catch (e: Exception) {
            
        }
    }

    fun sendInstructionQuestionTask(instruction: String, question: String) {
        val task = buildTaskFromInstructionQuestionTask(instruction, question)
        taskManagerPanel.addTask(task)
        updateTaskStatus(task, TaskStatus.SENDING)
        runChatTaskInBackground(project, task) { updatedTask ->
            updateTaskOnUI(updatedTask)
        }
    }

    fun sendExternalMessage(text: String) {
        val task = buildTaskFromString(provider.buildInstruction(), text)
        taskManagerPanel.addTask(task)
        updateTaskStatus(task, TaskStatus.SENDING)
        runChatTaskInBackground(project, task) { updatedTask ->
            updateTaskOnUI(updatedTask)
        }
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
        //logger.info("html=$html")

        // scan plain text for language
        val regex = Regex("```(\\w+)")
        val allText = tasks.joinToString("\n") { task ->
            listOf(
                task.question,
                "",
                task.answer
            ).joinToString("\n")
        }
        val languages = regex.findAll(allText)
            .map { it.groupValues[1] }
            .toSet()
        //logger.info("languages=$languages")

        // LANGUAGES <PRISM>
        val deps = mapOf(
            "c" to listOf("clike"),
            "cpp" to listOf("c"),
            "java" to listOf("clike"),
            "kotlin" to listOf("java")
        )

        fun expand(lang: String, deps: Map<String, List<String>>, result: MutableSet<String>) {
            for (dep in deps[lang].orEmpty()) {
                expand(dep, deps, result)
            }
            result.add(lang)
        }

        val result = linkedSetOf<String>() // ВАЖНО: сохраняет порядок
        for (lang in languages.sorted()) {
            expand(lang, deps, result)
        }

        val prismCss        = loadResource("css/prism-tomorrow.min.css")
        val prismCore       = loadResource("css/prism.min.js")

        val supportedLanguages = setOf(
            "clike", "python", "java", "kotlin", "bash", "go",
            "rust", "c", "cpp",  "pascal", "lisp", "json", "javascript"
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
                    font-size: 10px;
                    font-family: monospace;
                    font-weight: 600;
                    color: #604E29;
                }
            
                /* Утилиты */
                p { margin: 6px 0; }
                table { width: 100%; border-collapse: collapse; margin: 10px 0; }
                th, td { border: 1px solid #444; padding: 6px; }
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
                    // Находим блок кода в том же контейнере, где и кнопка
                    const container = button.closest('.code-block');
                    const pre = container ? container.querySelector('pre') : button.parentElement.nextElementSibling;
                    
                    if (pre.style.display === 'none') {
                        pre.style.display = 'block';
                        button.innerText = 'collapse';
                    } else {
                        pre.style.display = 'none';
                        button.innerText = 'expand';
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
        jbCefBrowser.loadHTML(styledHtml)

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

    fun loadResource(path: String): String {
        return requireNotNull(
            this::class.java.classLoader.getResourceAsStream(path)
        ).bufferedReader().use { it.readText() }
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

        // 2. Обработка сообщений от JS
        jsQuery = JBCefJSQuery.create(jbCefBrowser)   // ← самый стабильный способ сейчас

        //Best Practice
        //        jsQuery.addHandler { arg ->
        //            when {
        //                arg.startsWith("click:") -> handleTaskClick(arg.removePrefix("click:"))
        //                arg.startsWith("hover:") -> handleTaskHover(arg.removePrefix("hover:"))
        //                arg == "hover:exit" -> handleHoverExit()
        //                else -> handleTextSelection(arg) // Если ничего не подошло — значит это текст
        //            }
        //            JBCefJSQuery.Response("ok")
        //        }

        jsQuery.addHandler { queryResult: String ->
            // Проверяем, что это НЕ команда для второго обработчика
            val isCommand = queryResult.startsWith("click:") ||
                    queryResult.startsWith("hover:") ||
                    queryResult == "hover:exit"

            if (!isCommand && queryResult.isNotBlank()) {
                // Получили выделенный текст из браузера
                SwingUtilities.invokeLater {
                    searchField?.text = queryResult.trim()
                    searchField?.selectAll()
                    searchField?.requestFocusInWindow()

                    if (isAutoSearchEnabled) {
                        // Сразу запускаем поиск (опционально — можно убрать, если хочешь только заполнить поле)
                        jbCefBrowser.cefBrowser.find(queryResult.trim(), true, false, false)
                        searchPanel.isVisible = true
                    }
                }
            }
            null  // Даем пройти в handleJsQuery, если нужно
        }

        jsQuery.addHandler(::handleJsQuery)

        // Добавляем LoadHandler (чтобы инжектить при перезагрузках страницы)
        val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectTaskHandlers(cefBrowser)
                }
            }
        }
        jbCefBrowser.jbCefClient.addLoadHandler(loadHandler, cefBrowser)
    }

    private fun injectSelectionScript() {
        val script = """
        // Функция, которую будем вызывать из Java
        window.getChatSelection = function() {
            const selection = window.getSelection().toString().trim();
            if (selection.length > 0) {
                ${jsQuery.inject("selection")}   // отправляем текст в Java
            }
            return selection;
        };

        // Дополнительно: по двойному клику или Ctrl+C можно автоматически отправлять (по желанию)
        document.addEventListener('mouseup', function() {
            // Можно раскомментировать, если хочешь авто-заполнение при любом выделении
            // setTimeout(() => window.getChatSelection(), 100);
        });
    """.trimIndent()

        // Выполняем после загрузки страницы
        jbCefBrowser.cefBrowser.executeJavaScript(script, "", 0)
    }

    // Функция для инъекции bridge + загрузки твоего JS
    private fun injectTaskHandlers(cefBrowser: CefBrowser?) {
        if (!::jsQuery.isInitialized) {
            logger.warn("jsQuery ещё не инициализирован")
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

        // Заменяем метку в тексте файла на рабочий код
        val actualInject = jsQuery.inject("selection")
        val finalJsCode = taskHandlersJs.replace("PLACEHOLDER_FOR_INJECT", actualInject)

        cefBrowser?.executeJavaScript(finalJsCode, null, 0)

        val scrollFixScript = """
    (function() {
        window.addEventListener('wheel', function(e) {
            if (e.ctrlKey) return;

            // Берем коэффициент масштабирования системы (например, 1.25 или 2.0)
            const dpr = window.devicePixelRatio || 1;
            
            // Если дельта очень маленькая (особенность JCEF), 
            // мы компенсируем её, учитывая масштаб
            let multiplier = 15 * dpr; 

            if (e.deltaMode === 1) { // Lines
                multiplier *= 1.5; 
            }

            e.preventDefault();
            window.scrollBy({
                top: e.deltaY * multiplier,
                left: e.deltaX * multiplier,
                behavior: 'auto'
            });
        }, { passive: false });
    })();
""".trimIndent()

        cefBrowser?.executeJavaScript(scrollFixScript, null, 0)
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
"""
(function() {
    const el = document.scrollingElement || document.documentElement;
    if (el) {
        el.scrollTop = el.scrollHeight;
    }
})();
""".trimIndent(),
            jbCefBrowser.cefBrowser.url,
            0
        )
    }

    fun onTaskUpdated() {
        rebuildChatBlocksFromTasks()
    }

    override fun dispose() {
        // Очистка ресурсов при закрытии
    }


}