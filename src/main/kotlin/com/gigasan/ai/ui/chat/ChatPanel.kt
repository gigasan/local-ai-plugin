package com.gigasan.ai.ui.chat

import com.gigasan.ai.actions.AskAction
import com.gigasan.ai.actions.AutoSearchToggleAction
import com.gigasan.ai.actions.CleanChatAction
import com.gigasan.ai.actions.LoadResponseAction
import com.gigasan.ai.actions.TaskCompositorAction
import com.gigasan.ai.config.PluginConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.runtime.AIMetrics
import com.gigasan.ai.runtime.BackendAdapter
import com.gigasan.ai.runtime.ChatRequestBuilder
import com.gigasan.ai.runtime.ResultBuilder
import com.gigasan.ai.runtime.StateMachine
import com.gigasan.ai.runtime.StateManager
import com.gigasan.ai.runtime.parser.ResponseResult
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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.event.ActionEvent
import java.awt.FlowLayout
import java.awt.event.ComponentEvent
import java.awt.event.ComponentAdapter
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.startup.StartupManager
import com.intellij.ui.jcef.JBCefBrowserBase
import java.awt.Toolkit

class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val provider get() = project.service<PluginConfigProvider>()
    private var updateTimer: Timer? = null
    private var jbCefBrowser: JBCefBrowser? = null

    // JS QUERY: Тоже ленивый
    private lateinit var jsQuery: JBCefJSQuery
    // ← сюда добавь свой wrapper
    private val browserWrapper = JPanel(BorderLayout()).apply {
        // Важно: делаем так, чтобы браузер растягивался по всему доступному месту
        preferredSize = Dimension(600, 800)   // начальный разумный размер
        minimumSize = Dimension(300, 400)
        border = BorderFactory.createLineBorder(JBColor.GRAY)
    }

    private var layeredPane: JLayeredPane
    private var searchPanel: JPanel
    private var searchField: JTextField? = null

    private var isAutoSearchEnabled = false

    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel(project)
    private val logger = Logger.getInstance("ChatPanel")
    private val taskRenderer = TaskRenderer()
    private val taskSender =  TaskSender(project, provider)


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
                model = "",
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
                model = "",
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
                model = "",
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
        if (settings.state.enableTaskCompositor) {
            mainGroup.add(TaskCompositorAction())
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
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = e.getData(CHAT_BROWSER_KEY) != null
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

    private fun isCefReady(): Boolean = jbCefBrowser?.cefBrowser != null

    init {
        instance = this

        layeredPane = JLayeredPane().apply { layout = null }

        // === Основная структура панели ===
        val mainPanel = JPanel(BorderLayout())

        layeredPane.add(mainPanel, JLayeredPane.DEFAULT_LAYER)

        searchPanel = createSearchPanel { jbCefBrowser }
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

        mainPanel.add(chatPanel, BorderLayout.CENTER)
        mainPanel.add(southPanel, BorderLayout.SOUTH)

        // отправка сообщения чата
        sendButton.addActionListener { sendMessage() }
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER)
                {
                    sendMessage()
                }

            }
        })

        // Обновление задач
        taskManagerPanel.onTasksChanged = {
            scheduleMarkdownUpdate()
        }

        // Подписка на смену темы
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                val isDark = com.intellij.util.ui.StartupUiUtil.isDarkTheme
                val background = com.intellij.ui.ColorUtil.toHtmlColor(com.intellij.util.ui.JBUI.CurrentTheme.ToolWindow.background())
                val foreground = com.intellij.ui.ColorUtil.toHtmlColor(JBColor.foreground())
                val border = com.intellij.ui.ColorUtil.toHtmlColor(JBColor.border())
                logger.info("Theme changed! isDark=$isDark bg=$background fg=$foreground border $border")
                SwingUtilities.invokeLater { rebuildChatBlocksFromTasks() }
            })

        // Отложенная инициализация браузера
        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().invokeLater(
                {
                    initBrowser()
                },
                ModalityState.nonModal()
            )
        }

        logger.info("ChatPanel initialized")
    }

    private fun initBrowser() {
        if (!JBCefApp.isSupported()) {
            logger.error("JCEF is not supported")
            SwingUtilities.invokeLater {
                browserWrapper.removeAll()
                browserWrapper.add(
                    JLabel("JCEF is not supported on this system"),
                    BorderLayout.CENTER
                )
                browserWrapper.revalidate()
                browserWrapper.repaint()
            }
            return
        }
        val browser = JBCefBrowser()
        jbCefBrowser = browser

        browserWrapper.border = null
        browserWrapper.removeAll()
        browserWrapper.add(browser.component, BorderLayout.CENTER)

        addJbCefBrowserHandler()

        browserWrapper.revalidate()
        browserWrapper.repaint()
        logger.info("JCEF init finished")
    }

    private fun updateSearchPanelPosition() {
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
        jbCefBrowser?.let {
            sink[CHAT_BROWSER_KEY] = it
        }
    }

    private fun createSearchPanel(browserProvider: () -> JBCefBrowser?): JPanel {
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
            browserProvider()?.cefBrowser?.find(field.text, false, false, true)  // назад
        }

        val btnNext = createSmallButton("↓") {
            browserProvider()?.cefBrowser?.find(field.text, true, false, true)   // вперёд
        }

        val btnClose = createSmallButton("\uD83D\uDDD9") {
            browserProvider()?.cefBrowser?.stopFinding(true)
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

    private fun installShortcuts(root: JComponent, searchPanel: JPanel) {
        val im = root.getInputMap(WHEN_IN_FOCUSED_WINDOW)
        val am = root.getActionMap()

        // Основные шорткаты
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "find")
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

                //layeredPane.repaint()

                // 1. Сначала пытаемся взять из редактора
                val editorSelected = getSelectedTextFromEditor()

                if (!editorSelected.isNullOrBlank()) {
                    searchField?.text = editorSelected
                    searchField?.selectAll()
                    searchField?.requestFocusInWindow()
                    logger.info("🔍 Search panel opened with text: '${editorSelected}'")
                } else {
                    logger.info("🔍 Looking for selected text in browser")
                    // 2. Если ничего нет в редакторе — берём из браузера (чата)
                    val browser = jbCefBrowser
                    browser?.cefBrowser?.executeJavaScript("window.getChatSelection();", "", 0)
                    // Результат придёт асинхронно в jsQuery.addHandler
                }

            }
        })

        am.put("hideFind", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                searchPanel.isVisible = false
                jbCefBrowser?.cefBrowser?.stopFinding(true)
                layeredPane.repaint()
            }
        })

        am.put("findNext", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val browser = jbCefBrowser ?: return
                val text = searchField?.text ?: return
                if (text.isNotBlank()) {
                    browser.cefBrowser.find(text, true, false, true)
                }
            }
        })

        am.put("findPrev", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val browser = jbCefBrowser ?: return
                val text = searchField?.text ?: return
                if (text.isNotBlank()) {
                    browser.cefBrowser.find(text, false, false, true)
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

    fun rebuildChatBlocksFromTasks() {
        val tasks = taskManagerPanel.getAllTasks()
        val html = taskRenderer.render(tasks)
        jbCefBrowser?.loadHTML(html)
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

    private fun sendMessage() {
        val mainText = inputField.text.trim()
        if (mainText.isEmpty()) return
        val task = buildTaskFromString(provider.buildInstruction(), mainText)
        inputField.text = ""

        taskManagerPanel.addTask(task)

        updateTaskStatus(task, TaskStatus.SENDING)

        taskSender.runChatTaskInBackground(project, task) { updatedTask ->
            updateTaskOnUI(updatedTask)
        }
    }

    fun sendInstructionQuestionTask(instruction: String, question: String) {
        val task = buildTaskFromInstructionQuestionTask(instruction, question)
        taskManagerPanel.addTask(task)
        updateTaskStatus(task, TaskStatus.SENDING)
        taskSender.runChatTaskInBackground(project, task) { updatedTask ->
            updateTaskOnUI(updatedTask)
        }
    }

    fun sendExternalMessage(text: String) {
        val task = buildTaskFromString(provider.buildInstruction(), text)
        taskManagerPanel.addTask(task)
        updateTaskStatus(task, TaskStatus.SENDING)
        taskSender.runChatTaskInBackground(project, task) { updatedTask ->
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
            model = when (result) {
                is ResponseResult.Success -> {
                    "${result.model}"
                }
                is ResponseResult.Error -> {
                    ""
                }
            },
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

        updateTaskOnUI(updated)
    }

    override fun dispose() {
        // Очистка ресурсов при закрытии
    }

    // ================== JS handlers ======================

    private fun addJbCefBrowserHandler() {
        val cefBrowser = jbCefBrowser?.cefBrowser
        val jbCefClient = jbCefBrowser?.jbCefClient
        if (cefBrowser == null) {
            logger.warn("JCEF browser was not init")
            return
        }

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
            if (queryResult.isBlank()) return@addHandler JBCefJSQuery.Response("empty")

            // 1. Проверяем, является ли это системной командой плагина
            val isCommand = queryResult.startsWith("click:") ||
                    queryResult.startsWith("hover:") ||
                    queryResult == "hover:exit" ||
                    queryResult.startsWith("delete:") ||
                    queryResult.startsWith("export:")

            if (isCommand) {
                // Если это команда — сразу перенаправляем в handleJsQuery
                return@addHandler handleJsQuery(queryResult)
            } else {
                // 2. Если это НЕ команда — значит, пользователь выделил текст для поиска
                SwingUtilities.invokeLater {
                    searchField?.text = queryResult.trim()
                    searchField?.selectAll()
                    searchField?.requestFocusInWindow()

                    if (isAutoSearchEnabled) {
                        cefBrowser.find(queryResult.trim(), true, false, false)
                        searchPanel.isVisible = true
                    }
                }
                return@addHandler JBCefJSQuery.Response("text_selection_processed")
            }
        }

        // Добавляем LoadHandler (чтобы инжектить при перезагрузках страницы)
        val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    injectTaskHandlers(cefBrowser)
                }
            }
        }
        jbCefClient?.addLoadHandler(loadHandler, cefBrowser)
        logger.info("JCEF browser handlers was set")
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
        logger.info("JCEF TaskHandlers injected")
    }

    private fun loadTaskHandlersJs(): String {
        return this::class.java.getResource("/js/taskHandlers.js")?.readText()
            ?: error("Cannot load taskHandlers.js")
    }

    private fun handleJsQuery(arg: String): JBCefJSQuery.Response? {
        //logger.info("JS → Kotlin: $arg")
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
                arg.startsWith("delete:") -> {
                    val taskId = arg.removePrefix("delete:")
                    taskManagerPanel.removeTask(taskId)
                }
                arg.startsWith("export:") -> {
                    val taskId = arg.removePrefix("export:")

                    // 1. Находим задачу в твоем менеджере по ID
                    val task = taskManagerPanel.getTasksById(taskId)

                    if (task != null) {
                        // Вызываем в UI-потоке IntelliJ родное окно сохранения файла
                        SwingUtilities.invokeLater {
                            val statusLine = task.status.name + "(${task.model}): "
                            // Формируем красивый текст в формате Markdown
                            val markdownContent = buildString {
                                appendLine("# $statusLine")
                                appendLine("\n## Question\n")
                                appendLine(task.question)

                                if (task.reasoning.isNotBlank()) {
                                    appendLine("\n## Thinking Process\n")
                                    appendLine("> ${task.reasoning.trim().replace("\n", "\n> ")}")
                                }

                                appendLine("\n## Answer\n")
                                appendLine(task.answer)
                            }

                            // 1. По совету доки, заменяем SwingUtilities.invokeLater на интеллиджевский Application.invokeLater
                            // и явно передаем безопасный ModalityState
                            val app = com.intellij.openapi.application.ApplicationManager.getApplication()

                            app.invokeLater({
                                val descriptor = FileSaverDescriptor("Export Task to Markdown", "Save task log", "md")
                                val baseName = task.title.replace(Regex("[^a-zA-Z0-9а-яА-Я\\s]"), "").take(20).trim()
                                val defaultName = if (baseName.isNotBlank()) "${baseName}.md" else "task_${taskId}.md"

                                // Открываем диалог внутри безопасного ModalityState
                                val fileWrapper = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                                    .createSaveFileDialog(descriptor, project)
                                    .save(com.intellij.openapi.vfs.VfsUtil.getUserHomeDir(), defaultName)

                                fileWrapper?.file?.let { ioFile ->
                                    // 2. Саму запись уводим в пул потоков платформы
                                    app.executeOnPooledThread {
                                        try {
                                            ioFile.writeText(markdownContent, Charsets.UTF_8)

                                            // 3. Дока просит: "consider making them asynchronous" для VFS refresh.
                                            // Передаем true в первый параметр (asynchronous = true)
                                            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(
                                                true,  // АСИНХРОННО! Как требует инструкция
                                                false,
                                                false,
                                                ioFile
                                            )

                                            logger.info("Task successfully exported to ${ioFile.absolutePath}")
                                        } catch (e: Exception) {
                                            logger.error("Failed to save markdown file", e)
                                        }
                                    }
                                }
                            }, com.intellij.openapi.application.ModalityState.defaultModalityState()) // ← Тот самый стейт из инструкции!
                        }
                    }
                }

            }
        }
        //logger.info("handleJsQuery OK: $arg processed")
        return JBCefJSQuery.Response("success")
    }
}