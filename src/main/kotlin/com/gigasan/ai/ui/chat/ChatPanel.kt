package com.gigasan.ai.ui.chat

import com.gigasan.ai.actions.AnalyzeAction
import com.gigasan.ai.actions.AskAction
import com.gigasan.ai.actions.AutoSearchToggleAction
import com.gigasan.ai.actions.CleanChatAction
import com.gigasan.ai.actions.LoadResponseAction
import com.gigasan.ai.actions.RefactorAction
import com.gigasan.ai.actions.SendFileAction
import com.gigasan.ai.actions.TaskCompositorAction
import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.runtime.ClientStream
import com.gigasan.ai.runtime.AIMetrics
import com.gigasan.ai.runtime.BackendAdapter
import com.gigasan.ai.runtime.ChatRequestBuilder
import com.gigasan.ai.runtime.HttpClientProvider
import com.gigasan.ai.runtime.LocalAIService
import com.gigasan.ai.runtime.MemorySystem
import com.gigasan.ai.runtime.ResultBuilder
import com.gigasan.ai.runtime.StateMachine
import com.gigasan.ai.runtime.StateManager
import com.gigasan.ai.runtime.StreamEvent
import com.gigasan.ai.runtime.parser.ResponseResult
import com.gigasan.ai.runtime.parser.withDuration
import com.gigasan.ai.ui.chat.HtmlProcessor.wrapCode
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
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.ui.jcef.JBCefBrowserBase
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.util.data.MutableDataSet

class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val provider get() = project.service<PluginConfigProvider>()
    private var updateTimer: Timer? = null
    // БРАУЗЕР: Теперь он создастся только при обращении
    private val jbCefBrowser by lazy { JBCefBrowser() }

    // JS QUERY: Тоже ленивый
    private lateinit var jsQuery: JBCefJSQuery
    // ← сюда добавь свой wrapper
    private val browserWrapper = JPanel(BorderLayout()).apply {
        // Важно: делаем так, чтобы браузер растягивался по всему доступному месту
        preferredSize = Dimension(600, 800)   // начальный разумный размер
        minimumSize = Dimension(300, 400)
        border = BorderFactory.createLineBorder(JBColor.GRAY)
    }

    private lateinit var layeredPane: JLayeredPane
    private lateinit var searchPanel: JPanel   // ← теперь поле
    private var searchField: JTextField? = null

    private var marqueeOffset = 0L
    private var lastTextLength = 0
    private var lastAnimationMs = 0L
    private var isAutoSearchEnabled = false

    //private val chatBlocks = mutableListOf<ChatBlock>()
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel()
    private val logger = Logger.getInstance("ChatPanel")
    private val taskRenderer = TaskRenderer()

    companion object {
        fun buildTaskFromString(instruction: String, request: String): TaskData {
            val task = TaskData(
                id = System.currentTimeMillis().toString(),
                title = "♪",
                zoneType = "Chat",
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
                title = "♬",
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

        fun buildTaskFromResponse(response: String): TaskData {
            val task = TaskData(
                id = System.currentTimeMillis().toString(),
                title = "\uD83D\uDCBE",
                zoneType = "File",
                status = TaskStatus.UNKNOWN,
                footer = "",
                instruction = "",
                question = "",
                reasoning = "",
                answer = response,
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
        if (settings.state.enableDebugLog) {
            mainGroup.add(LoadResponseAction())
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
        //jbCefBrowser = JBCefBrowser()

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
            searchPanel.setBounds(w - 310, 0, 290, 38)
        } else {
            // fallback для очень узкого окна
            searchPanel.setBounds(10, 0, 290, 38)
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
        val html = taskRenderer.render(tasks)
        jbCefBrowser.loadHTML(html)
    }

    fun sendTask(task: TaskData) {
        val url = provider.buildBaseUrl() + provider.buildChatEndpoint()
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
        //if (now - lastAnimationMs > 50) {
        val baseMsg = event.indicatorText.replace("\n", " ")
        val fraction = event.indicatorFraction

        logger.info("event.indicatorFraction=${event.indicatorFraction} indicator.isIndeterminate=${indicator.isIndeterminate}")
        if (fraction == null) {
            if (!indicator.isIndeterminate) {
                indicator.fraction = 0.0
                indicator.isIndeterminate = true
            }
        } else {
            if (indicator.isIndeterminate)
            {
                indicator.isIndeterminate = false
            }
            indicator.fraction = event.indicatorFraction!!.coerceIn(0.0, 0.99)
        }
        indicator.text = createSmartMarquee(baseMsg, 25)
        lastAnimationMs = now
        indicator.checkCanceled()
        //}
    }

    fun processChatTask(task: TaskData, indicator: ProgressIndicator): TaskResult {
        return try {
            //logger.info("task = $task")
            //logger.info("model = $model")
            val request = task.question.trimIndent()
            val chatContext = ChatRequestBuilder(project)
                .system(task.instruction)
                .memory(limit = 5)
                .user(request)
                .stream(provider.buildStream())
                .model(provider.buildChatModel())
                .maxTokens(provider.buildMaxTokenLimit())
                .build(task)
            //logger.info("processChatTask $chatContext")
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
                indicator.text = "Sending request..."
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

    fun renderResponseMessage(response: String) {
        val task = buildTaskFromResponse(response)
        taskManagerPanel.addTask(task)
        updateTaskStatus(task, TaskStatus.DONE)
        val indicator = EmptyProgressIndicator()
        val adapter = BackendAdapter(project)
        val chatContext = ChatRequestBuilder(project)
            .system(task.instruction)
            .memory(limit = 5)
            .user(task.question)
            .stream(provider.buildStream())
            .model(provider.buildChatModel())
            .maxTokens(provider.buildMaxTokenLimit())
            .build(task)
        val stateManager = StateManager()
        val stateMachine = StateMachine()
        val context = adapter.getContext(chatContext, stateManager, stateMachine)
        val builder = ResultBuilder(context.parser)
        builder.setRaw(response)
        val result = builder.build()

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

        val teskResult = TaskResult.Success(updated)
        updateTaskOnUI(updated)
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


    fun onTaskUpdated() {
        rebuildChatBlocksFromTasks()
    }

    override fun dispose() {
        // Очистка ресурсов при закрытии
    }


    // ================== JS handlers ======================

    private fun addJbCefBrowserHandler() {
        val cefBrowser = jbCefBrowser.cefBrowser

        // 2. Обработка сообщений от JS
        jsQuery = JBCefJSQuery.create(jbCefBrowser as JBCefBrowserBase)

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

    // Функция для инъекции bridge + загрузки JS
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



}