package com.gigasan.ai.ui

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import org.eclipse.jgit.ignore.IgnoreNode
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.ui.components.JBLabel
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import java.awt.CardLayout
import java.awt.Image
import java.awt.image.BufferedImage
import javax.swing.SwingConstants
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBCheckBox
import javax.swing.ScrollPaneConstants
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.components.Service
import com.intellij.ui.dsl.builder.AlignX
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.JSeparator
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.labelTable
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.showValueHint
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.gridLayout.Gaps
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList


// ========== Настройки промптов ==========
@State(name = "com.gigasan.ai.ui.PromptSettings", storages = [Storage("PromptSettings.xml")])
@Service(Service.Level.APP)
class PromptSettings : PersistentStateComponent<PromptSettings.State> {
    data class State(
        var systems: MutableList<String> = mutableListOf(),
        var prompts: MutableList<String> = mutableListOf(),
        var systemExpanded: Boolean = false,
        var inputExpanded: Boolean = false,
        var commonExpanded: Boolean = false,

    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) {
        myState = state
    }
    companion object {
        val instance: PromptSettings get() = service()
    }
}

class FileChooserDialog(
    private val project: Project,
    private val root: VirtualFile,
    private val onFileSelected: (VirtualFile, String) -> Unit
) : DialogWrapper(project) {

    private val treeRoot = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    // 1. Меняем тип на LanguageTextField, не lateinit – создаём сразу
    private val previewEditor: LanguageTextField = createEditor(project)
    private val imagePreviewLabel = JBLabel().apply { horizontalAlignment = SwingConstants.CENTER }
    private val cardLayout = CardLayout()
    private val previewContainer = JPanel(cardLayout)

    private val logger = Logger.getInstance("FileChooserDialog")
    private var selectedFile: VirtualFile? = null

    // Комбобокс и его модель
    private lateinit var systemsCombo: JComboBox<String>
    private lateinit var systemsModel: DefaultComboBoxModel<String>

    private lateinit var promptsCombo: JComboBox<String>
    private lateinit var promptsModel: DefaultComboBoxModel<String>
    private val promptSettings = PromptSettings.instance

    init {
        title = "FileChooserDialog File Preview"
        previewContainer.add(previewEditor, "TEXT")
        previewContainer.add(JBScrollPane(imagePreviewLabel), "IMAGE")
        setupTree(root)
        init()
    }

    /*

    instructions — поведение
    input — задача
    ===DATA=== - сами данные
    ===END=== - конец данных

    {
      "messages": [
        {
          "role": "system",
          "content": "Ты анализируешь данные"
        },
        {
          "role": "user",
          "content": "Проанализируй:\n\n===DATA===\n{...}\n===END==="
        }
      ]
    }


    {
      "instructions": "Ты data-аналитик. Работай только с блоком DATA.",
      "input": "Задача: найди аномалии\n\n===DATA===\n{...огромный JSON...}\n===END DATA==="
    }

    {
      "model": "gpt-4.1",
      "instructions": "Ты анализируешь данные и даёшь краткий вывод.",
      "input": [
        {
          "role": "user",
          "content": [
            {
              "type": "text",
              "text": "Проанализируй следующий JSON:"
            },
            {
              "type": "text",
              "text": "{ ... БОЛЬШОЙ JSON ... }"
            }
          ]
        }
      ]
    }

    */


    // ======================= ПАНЕЛЬ НАСТРОЕК С ПРОМПТАМИ =======================
    private fun createSettingsPanel(): JComponent {
        // Инициализируем модели
        systemsModel = DefaultComboBoxModel<String>().apply {
            promptSettings.state.systems.forEach { addElement(it) }
        }
        promptsModel = DefaultComboBoxModel<String>().apply {
            promptSettings.state.prompts.forEach { addElement(it) }
        }

        return panel {
            collapsibleGroup("Request Settings") {

                row("Система:") {
                    comboBox(systemsModel)
                        .align(AlignX.FILL) // РАСТЯГИВАЕМ НА ВСЮ ШИРИНУ
                        .resizableColumn()
                        .applyToComponent {
                            isEditable = false
                            setRenderer(object : DefaultListCellRenderer() {
                                override fun getListCellRendererComponent(
                                    list: JList<*>,
                                    value: Any?,
                                    index: Int,
                                    isSelected: Boolean,
                                    cellHasFocus: Boolean
                                ): Component {
                                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent

                                    val text = value?.toString()

                                    // index >= 0 означает, что мы сейчас рисуем элементы ВНУТРИ выпадающего списка
                                    // index == -1 означает, что мы рисуем выбранный элемент в "шапке" комбобокса
                                    if (index >= 0 && text != null) {
                                        c.toolTipText = text // Устанавливаем полный текст как подсказку
                                    } else {
                                        c.toolTipText = null // Убираем подсказку для уже выбранного элемента
                                    }

                                    return c
                                }
                            })
                        }

                    // Создаем группу кнопок Система
                    button("") { handleAdd(systemsModel, "Введите текст новой системы:", "Добавление системы") { saveSystems() } }
                        .customize(UnscaledGaps(left = 8)) // Уплотняем расстояние между кнопками
                        .applyToComponent {
                            icon = AllIcons.General.Add
                            text = null
                            margin = JBUI.emptyInsets()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            preferredSize = Dimension(26, 26)
                            minimumSize = Dimension(26, 26)
                            putClientProperty("JButton.buttonType", "square")
                        }
                    button("") { handleEdit(systemsModel, "Измените систему:", "Редактирование") { saveSystems() } }
                        .customize(UnscaledGaps(left = 8))
                        .applyToComponent {
                            icon = AllIcons.Actions.Edit
                            text = null
                            margin = JBUI.emptyInsets()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            preferredSize = Dimension(26, 26)
                            minimumSize = Dimension(26, 26)
                            putClientProperty("JButton.buttonType", "square")
                            preferredSize = Dimension(26, 26)
                        }
                    button("") { handleDelete(systemsModel, "Удалить систему?") { saveSystems() } }
                        .customize(UnscaledGaps(left = 8))
                        .applyToComponent {
                            icon = AllIcons.General.Remove
                            text = null
                            margin = JBUI.emptyInsets()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            preferredSize = Dimension(26, 26)
                            minimumSize = Dimension(26, 26)
                            putClientProperty("JButton.buttonType", "square")
                            preferredSize = Dimension(26, 26)
                        }
                }

                row("Промпт:") {
                    comboBox(promptsModel)
                        .align(AlignX.FILL) // РАСТЯГИВАЕМ
                        .resizableColumn()
                        .applyToComponent {
                            isEditable = false
                            setRenderer(object : DefaultListCellRenderer() {
                                override fun getListCellRendererComponent(
                                    list: JList<*>,
                                    value: Any?,
                                    index: Int,
                                    isSelected: Boolean,
                                    cellHasFocus: Boolean
                                ): Component {
                                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent

                                    val text = value?.toString()

                                    // index >= 0 означает, что мы сейчас рисуем элементы ВНУТРИ выпадающего списка
                                    // index == -1 означает, что мы рисуем выбранный элемент в "шапке" комбобокса
                                    if (index >= 0 && text != null) {
                                        c.toolTipText = text // Устанавливаем полный текст как подсказку
                                    } else {
                                        c.toolTipText = null // Убираем подсказку для уже выбранного элемента
                                    }

                                    return c
                                }
                            })
                        }

                    button("") { handleAdd(promptsModel, "Введите текст нового промпта:", "Добавление промпта") { savePrompts() } }
                        .customize(UnscaledGaps(left = 8)) // Уплотняем расстояние между кнопками
                        .applyToComponent {
                            icon = AllIcons.General.Add
                            text = null
                            margin = JBUI.emptyInsets()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            preferredSize = Dimension(26, 26)
                            minimumSize = Dimension(26, 26)
                            putClientProperty("JButton.buttonType", "square")
                        }
                    button("") { handleEdit(promptsModel, "Измените промпт:", "Редактирование") { savePrompts() } }
                        .customize(UnscaledGaps(left = 8))
                        .applyToComponent {
                            icon = AllIcons.Actions.Edit
                            text = null
                            margin = JBUI.emptyInsets()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            preferredSize = Dimension(26, 26)
                            minimumSize = Dimension(26, 26)
                            putClientProperty("JButton.buttonType", "square")
                            preferredSize = Dimension(26, 26)
                        }
                    button("") { handleDelete(promptsModel, "Удалить промпт?") { savePrompts() } }
                        .customize(UnscaledGaps(left = 8))
                        .applyToComponent {
                            icon = AllIcons.General.Remove
                            text = null
                            margin = JBUI.emptyInsets()
                            horizontalAlignment = SwingConstants.CENTER
                            verticalAlignment = SwingConstants.CENTER
                            preferredSize = Dimension(26, 26)
                            minimumSize = Dimension(26, 26)
                            putClientProperty("JButton.buttonType", "square")
                            preferredSize = Dimension(26, 26)
                        }
                }
                row {
                    checkBox("Wrap DATA block")
                }
            }.apply {
                expanded = promptSettings.state.systemExpanded // Разворачиваем группу сразу после создания
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    promptSettings.state.systemExpanded = isExpanded
                }

            }.customize(UnscaledGapsY(bottom = 0)) // Убираем лишнюю пустоту снизу

            // --- INPUT НАСТРОЙКИ ---
            collapsibleGroup("Request Features") {
                row {
                    checkBox("Stream")
                    checkBox("Thinking").onChanged {

                    }
                    //spinner(0..100)
                    //intTextField(0..100)
                    label("Temperature:")
                    slider(0, 10, 1, 0)
                    //textField().columns(2)

                }
            }.apply {
                expanded = promptSettings.state.inputExpanded
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    promptSettings.state.inputExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 20))


            collapsibleGroup("General UI") {
                row {
                    checkBox("Current file")
                    checkBox("Current selection")
                    //textField().columns(20).comment("Фильтрация файлов")
                    //button("Обновить дерево") { /* логика */ }
                    //label("Шрифт:")
                    //textField().columns(15)
                    //label("Размер шрифта:")
                    //textField().columns(5)

                }
            }.apply {
                expanded = promptSettings.state.commonExpanded
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    promptSettings.state.commonExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 20))




        }
    }

    // Вспомогательные функции для сокращения кода кнопок
    private fun handleAdd(model: DefaultComboBoxModel<String>, msg: String, title: String, onSave: () -> Unit) {
        val result = Messages.showInputDialog(msg, title, Messages.getQuestionIcon())
        if (!result.isNullOrBlank()) {
            model.addElement(result.trim())
            onSave()
        }
    }

    private fun handleEdit(model: DefaultComboBoxModel<String>, msg: String, title: String, onSave: () -> Unit) {
        val selected = model.selectedItem as? String ?: return
        val edited = Messages.showInputDialog(msg, title, Messages.getQuestionIcon(), selected, null)
        if (!edited.isNullOrBlank() && edited.trim() != selected) {
            val index = model.getIndexOf(selected)
            model.removeElement(selected)
            model.insertElementAt(edited.trim(), index)
            model.selectedItem = edited.trim()
            onSave()
        }
    }

    private fun handleDelete(model: DefaultComboBoxModel<String>, msg: String, onSave: () -> Unit) {
        val selected = model.selectedItem as? String ?: return
        if (Messages.showYesNoDialog("$msg «$selected»?", "Подтверждение", Messages.getQuestionIcon()) == Messages.YES) {
            model.removeElement(selected)
            onSave()
        }
    }

    // Сохраняем текущий список в настройки
    private fun saveSystems() {
        promptSettings.state.systems.clear()
        for (i in 0 until systemsModel.size) {
            promptSettings.state.systems.add(systemsModel.getElementAt(i))
        }
    }

    // Сохраняем текущий список в настройки
    private fun savePrompts() {
        promptSettings.state.prompts.clear()
        for (i in 0 until promptsModel.size) {
            promptSettings.state.prompts.add(promptsModel.getElementAt(i))
        }
    }

    // ======================= ИНТЕГРАЦИЯ В ЦЕНТРАЛЬНУЮ ПАНЕЛЬ =======================
    override fun createCenterPanel(): JComponent {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = JBScrollPane(tree)
            rightComponent = previewContainer
            resizeWeight = 0.4
        }

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(createSettingsPanel(), BorderLayout.NORTH)   // ← панель сверху
        mainPanel.add(split, BorderLayout.CENTER)                  // ← дерево + превью
        mainPanel.add(createButtonsPanel(), BorderLayout.SOUTH)    // ← кнопки внизу

        return mainPanel
    }

    private fun createEditor(project: Project): LanguageTextField {
        // Создаём с произвольным языком (позже подсветка изменится через setFileType)
        val languageTextField = LanguageTextField(Language.ANY, project, "")

        languageTextField.apply {
            isViewer = false          // только чтение
            setOneLineMode(false)    // многострочный режим

            addSettingsProvider { editor ->
                editor.settings.isUseSoftWraps = true
                editor.settings.isLineNumbersShown = true
                editor.settings.isFoldingOutlineShown = true
                editor.settings.isWhitespacesShown = false
                editor.settings.isCaretRowShown = true

                val scheme = EditorColorsManager.getInstance().globalScheme
                editor.colorsScheme = scheme
                editor.colorsScheme.editorFontName = "JetBrains Mono"
                editor.colorsScheme.editorFontSize = 14
                editor.scrollPane.apply {
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBar.unitIncrement = 50
                    verticalScrollBar.blockIncrement = 420
                    horizontalScrollBar.unitIncrement = 28
                }
            }

        }

        return languageTextField
    }



    override fun getInitialSize(): Dimension? {
        val ideWindow = WindowManager.getInstance().getFrame(project)
        return if (ideWindow != null) {
            Dimension((ideWindow.width * 0.85).toInt(), (ideWindow.height * 0.75).toInt())
        } else {
            super.getInitialSize()
        }
    }

    private fun setupTree(root: VirtualFile) {
        val rootNode = DefaultMutableTreeNode(root)
        buildTree(root, rootNode)
        tree.model = DefaultTreeModel(rootNode)
        tree.isRootVisible = false
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = value as? DefaultMutableTreeNode
                val file = node?.userObject as? VirtualFile
                append(file?.name ?: value.toString())
            }
        }
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val file = node?.userObject as? VirtualFile ?: return@addTreeSelectionListener
            if (!file.isDirectory) {
                selectedFile = file
                previewFile(file)
            }
        }
    }

    private fun buildTree(file: VirtualFile, node: DefaultMutableTreeNode) {
        if (!file.isDirectory) return
        file.children.forEach { child ->
            if (!shouldShowInTree(child)) return@forEach
            val childNode = DefaultMutableTreeNode(child)
            node.add(childNode)
            if (child.isDirectory) {
                buildTree(child, childNode)
            }
        }
    }

    private fun previewFile(file: VirtualFile) {
        val extension = file.extension?.lowercase() ?: ""
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
        val svgExtensions = setOf("svg")

        imagePreviewLabel.text = ""

        when {
            extension in imageExtensions -> {
                val bytes = file.contentsToByteArray()
                val icon = ImageIcon(bytes)

                if (icon.iconWidth > 500) {
                    val scaledImage = icon.image.getScaledInstance(500, -1, Image.SCALE_SMOOTH)
                    imagePreviewLabel.icon = ImageIcon(scaledImage)
                } else {
                    imagePreviewLabel.icon = icon
                }

                cardLayout.show(previewContainer, "IMAGE")
            }

            extension in svgExtensions -> {
                imagePreviewLabel.icon = null
                displaySvgViaBatik(file)
                cardLayout.show(previewContainer, "IMAGE")
            }

            else -> {
                val content = try {
                    val fileType = FileTypeManager.getInstance().getFileTypeByFile(file)
                    val fileTypeByName = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
                    logger.info("fileType=$fileType fileTypeByName=$fileTypeByName")
                    // setFileType доступен у LanguageTextField (наследует EditorTextField)
                    //previewEditor.fileType = fileType
                    previewEditor.setFileType(fileType)
                    VfsUtilCore.loadText(file)
                } catch (e: Exception) {
                    "<binary or unreadable file>"
                }
                previewEditor.text = content
                previewEditor.setCaretPosition(0)
                previewEditor.repaint()
                cardLayout.show(previewContainer, "TEXT")
            }
        }

        previewContainer.revalidate()
        previewContainer.repaint()
    }

    private fun displaySvgViaBatik(file: VirtualFile) {
        val inputStream = file.inputStream
        val resultImage = object : ImageTranscoder() {
            var image: BufferedImage? = null
            override fun createImage(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            override fun writeImage(img: BufferedImage, output: TranscoderOutput?) { image = img }
        }

        try {
            val input = TranscoderInput(inputStream)
            resultImage.addTranscodingHint(ImageTranscoder.KEY_WIDTH, 500f)
            resultImage.transcode(input, null)

            imagePreviewLabel.icon = ImageIcon(resultImage.image)
            imagePreviewLabel.text = ""
        } catch (e: Exception) {
            imagePreviewLabel.text = "Failed to render SVG"
        }
    }

    // Пример использования выбранного промпта в кнопках
    private fun createButtonsPanel(): JComponent {
        val panel = JPanel()

        val openBtn = JButton("Open").apply {
            addActionListener {
                selectedFile?.let { file ->
                    val prompt = promptsCombo.selectedItem as? String ?: ""
                    onFileSelected(file, "open") // можно передать prompt отдельно
                    //close(OK_EXIT_CODE)
                }
            }
        }


        val explainBtn = JButton("Explain").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "explain")
                }
            }
        }

        val refactorBtn = JButton("Refactor").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "refactor")
                }
            }
        }

        val testsBtn = JButton("Generate tests").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "tests")
                }
            }
        }

        panel.add(openBtn)
        panel.add(explainBtn)
        panel.add(refactorBtn)
        panel.add(testsBtn)

        return panel
    }


    // filters

    fun isIgnoredByGitignore(ignore: IgnoreNode, root: File, file: File): Boolean {
        val relativePath = file.relativeTo(root).path.replace("\\", "/")
        val result = ignore.isIgnored(relativePath, file.isDirectory)
        return result == IgnoreNode.MatchResult.IGNORED
    }

    private fun isIgnored(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        if (file.name.startsWith(".")) return true
        return name in setOf(
            ".git", ".idea", ".gradle", "node_modules", "build", "dist", "out"
        ) || file.path.contains("/.git/")
    }

    fun loadGitIgnore(rootPath: String): IgnoreNode {
        val ignore = IgnoreNode()
        val file = File(rootPath, ".gitignore")
        if (file.exists()) {
            file.inputStream().use {
                ignore.parse(it)
            }
        }
        return ignore
    }

    private fun shouldShowInTree(file: VirtualFile): Boolean {
        if (isIgnored(file)) return false
        //if (file.isDirectory) return true // директории фильтруем только по содержимому (см. buildTree)

        val rootFile = File(root.path)
        val gitIgnore = loadGitIgnore(rootFile.path)
        return !isIgnoredByGitignore(gitIgnore, rootFile, File(file.path))
    }

    fun shouldSendToAI(file: VirtualFile): Boolean {
        val rootFile = File(root.path)
        val gitIgnore = loadGitIgnore(rootFile.path)
        if (isIgnoredByGitignore(gitIgnore, rootFile, File(file.path))) {
            return false
        }
        if (file.isDirectory) return false
        return true
    }


}