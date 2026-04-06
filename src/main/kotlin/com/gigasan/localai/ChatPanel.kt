package com.gigasan.localai

import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.Disposable
import org.intellij.plugins.markdown.ui.preview.jcef.MarkdownJCEFHtmlPanel
import com.intellij.openapi.project.Project
import javax.swing.*
import java.awt.*
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import javax.swing.text.JTextComponent
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.border.LineBorder
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.fileEditor.FileEditorManager

// Блок Markdown
data class MarkdownBlock(
    val id: String,
    var content: String,
    var description: String,
)

class ChatPanel(val project: Project) : JPanel(BorderLayout()), Disposable {
    private var updateTimer: javax.swing.Timer? = null

    private val markdownFile = LightVirtualFile("chat.md", "")   // dummy-файл для панели
    private var markdownPanel = MarkdownJCEFHtmlPanel(project, markdownFile)
    private val markdownBlocks = mutableListOf<MarkdownBlock>()

    private lateinit var codeZone: JTextField
    private lateinit var fileZone: JTextField
    private lateinit var textZone: JTextField
    private val inputField = JTextField()
    private val sendButton = JButton("Send")
    private val taskManagerPanel = TaskManagerPanel()

    private val LOG = Logger.getInstance("LocalAIChatPlugin")

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
        taskContainer.preferredSize = Dimension(0, 0)
        taskContainer.maximumSize = Dimension(Int.MAX_VALUE, 150)

        southPanel.add(taskContainer)

        // --- Drop зоны ---
        val dropZonesPanel = createDropZones()
        //dropZonesPanel.minimumSize = dropZonesPanel.preferredSize
        dropZonesPanel.maximumSize = Dimension(Int.MAX_VALUE, dropZonesPanel.preferredSize.height)
        dropZonesPanel.alignmentX = Component.LEFT_ALIGNMENT
        southPanel.add(dropZonesPanel)

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
        add(markdownPanel.component, BorderLayout.CENTER)
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
    }


    // --- Создание зон Drag-and-Drop ---
    private fun createDropZones(): JPanel {
        val zonesPanel = JPanel(GridLayout(1, 3, 5, 0))

        // Зона 1: Оборачивает в код
        codeZone = createZone("Код", "Code") { data ->
            val text = data as? String ?: ""
            "```\n$text\n```"
        }

        // Зона 2: Пути к файлам
        fileZone = createZone("Файлы", "File") { data ->
            val files = data as? List<File>
            files?.joinToString("\n") ?: ""
        }

        // Зона 3: Обычный текст
        textZone = createZone("Текст", "text") { data -> data as? String ?: "" }

        zonesPanel.add(codeZone)
        zonesPanel.add(fileZone)
        zonesPanel.add(textZone)

        return zonesPanel
    }

    private fun createZone(placeholder: String, type: String, onDrop: (Any?) -> String): JTextField {
        val field = JBTextField(placeholder)
        field.horizontalAlignment = JTextField.CENTER
        field.preferredSize = Dimension(0, 45)
        field.border = LineBorder(JBColor.GRAY, 1)
        field.background = JBColor.PanelBackground
        field.putClientProperty("zoneType", type)

        addContextMenu(field)

        // Очистка плейсхолдера при фокусе
        field.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (field.text == placeholder) field.text = ""
            }
            override fun focusLost(e: FocusEvent) {
                if (field.text.isEmpty()) field.text = placeholder
            }
        })

        // Drag-and-Drop
        field.transferHandler = object : TransferHandler() {

            override fun canImport(support: TransferSupport): Boolean {
                support.dropAction = COPY
                return support.isDataFlavorSupported(DataFlavor.stringFlavor) ||
                        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                try {
                    val zoneType = field.getClientProperty("zoneType") as String

                    var newTask = TaskData(
                        id = System.currentTimeMillis().toString(),
                        title = "",
                        description = "",
                        content = "",
                        zoneType = "",
                        job = "check errors",
                    )

                    val transferable = support.transferable

                    // files only
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        val fileNames = files.map { it.name }
                        val filesWithPath = files.map { it.absolutePath }
                        newTask.title = "📂"
                        newTask.description = fileNames.joinToString(" ")
                        newTask.content = filesWithPath.joinToString("\n")
                        newTask.zoneType = "File"
                    } else if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        val droppedText = transferable.getTransferData(DataFlavor.stringFlavor) as String
                        val editorData = getEditorContext(project)
                        if (editorData != null && droppedText == editorData.third) {
                            // editor context
                            val (fileName, lines, text) = editorData
                            newTask.title = "📝"
                            newTask.description = "$fileName ${lines.first}-${lines.last}"
                            newTask.content = text
                            newTask.zoneType = "Editor"
                        } else {
                            // any other data
                            val previewLength = 50 // сколько первых символов показывать
                            val processed = droppedText.replace(Regex("\\s+"), " ").trim()
                            val preview = if (processed.length > previewLength) {
                                "${processed.take(previewLength)}..."
                            } else {
                                processed
                            }
                            newTask.title = "📄"
                            newTask.description = preview
                            newTask.content = droppedText
                            newTask.zoneType = "External"
                        }
                    }
                    if (newTask.content.isNotBlank()) {
                        taskManagerPanel.addTask(newTask)
                    }

                    // сброс плейсхолдера
                    SwingUtilities.invokeLater {
                        field.text = placeholder
                    }

                    field.requestFocus()
                    return true
                } catch (e: Exception) {
                    LOG.error(e)
                    return false
                }
            }
        }
        return field
    }

    fun onTaskUpdated() {
        rebuildMarkdownFromTasks()
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

            val blockText = buildString {
                append("<details style='margin-bottom:8px;'>\n")
                append("<summary style='cursor:pointer;'>${task.title} ${task.description}</summary>\n\n")

                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val instant = java.time.Instant.ofEpochMilli(task.id.toLong())
                val dateTime = java.time.ZonedDateTime.ofInstant(
                    instant,
                    java.time.ZoneId.systemDefault()
                )

                if (task.content.isNotBlank()) {
                    append("```text\n")
                    append(task.content)
                    append("\n```\n")
                    append(task.job)
                    append("\n\n")
                    append(dateTime.format(formatter))
                }

                append("</details>\n")
            }

            markdownBlocks.add(
                MarkdownBlock(
                    id = task.id,
                    content = blockText,
                    description = task.description,
                )
            )
        }
        refreshMarkdownPanel()
    }


    private fun sendMessage() {
        val mainText = inputField.text.trim()

//        val tasksToSend = taskManagerPanel.getTasksAt(listOf(0,2)) // отправляем задачи с индексами 0 и 2
//        tasksToSend.forEach { task ->
//            LocalAIService.callLocalAI(task.description)
//        }

        // Извлекаем данные из зон, если они не пустые и не содержат плейсхолдер
        val codeData = if (codeZone.text != "Код" && codeZone.text.isNotBlank()) codeZone.text else ""
        val fileData = if (fileZone.text != "Файлы" && fileZone.text.isNotBlank()) fileZone.text else ""
        val textData = if (textZone.text != "Текст" && textZone.text.isNotBlank()) textZone.text else ""

        val parts = listOf(mainText, codeData, fileData, textData).filter { it.isNotBlank() }
        if (parts.isEmpty()) return

        LOG.info("codeData: ${codeData.trim()}")
        LOG.info("fileData: ${fileData.trim()}")
        LOG.info("textData: ${textData.trim()}")

        // Создаём новый блок
        val block = MarkdownBlock(
            id = System.currentTimeMillis().toString(),
            content = parts.joinToString("\n\n"),
            description = "",
        )
        markdownBlocks.add(block)
        refreshMarkdownPanel()

        // Очищаем поля
        inputField.text = ""
        codeZone.text = "Код"
        fileZone.text = "Файлы"
        textZone.text = "Текст"

        // Логика вызова AI
        Thread {
            try {
                val response = LocalAIService.callLocalAI(block.content)
                SwingUtilities.invokeLater {
                    val responseBlock = MarkdownBlock(
                        id = System.currentTimeMillis().toString(),
                        content = "**AI:**\n$response",
                        description = "",
                    )
                    markdownBlocks.add(responseBlock)
                    refreshMarkdownPanel()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    val errorBlock = MarkdownBlock(
                        id = System.currentTimeMillis().toString(),
                        content = "**Ошибка:** ${e.message}",
                        description = "",
                    )
                    markdownBlocks.add(errorBlock)
                    refreshMarkdownPanel()
                }
            }
        }.start()
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
        </body>
        </html>
        """.trimIndent()

        markdownPanel.createImmediately()
        //markdownPanel.setHtml("", 0)
        markdownPanel.setHtml(styledHtml, 0)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        markdownPanel.cefBrowser.executeJavaScript(
            "window.scrollTo(0, document.body.scrollHeight);",
            markdownPanel.cefBrowser.url, 0
        )
    }

    private fun recreateMarkdownPanel() {
        remove(markdownPanel.component)

        val newPanel = MarkdownJCEFHtmlPanel(project, markdownFile)
        add(newPanel.component, BorderLayout.CENTER)

        markdownPanel = newPanel

        revalidate()
        repaint()

        refreshMarkdownPanel()
    }

    private fun addContextMenu(textComponent: JTextComponent) {
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

    fun sendTask(task: TaskData) {
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

    fun addUserMessage(text: String) {
        //chatMarkdown += "**Вы:** $text\n\n"
        //refreshMarkdownPanel()
    }


}