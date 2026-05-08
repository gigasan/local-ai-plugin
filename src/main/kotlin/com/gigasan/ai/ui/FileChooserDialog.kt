package com.gigasan.ai.ui

import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.config.storage.InstructionsService
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
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
import com.intellij.ui.LanguageTextField
import javax.swing.ScrollPaneConstants
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JSlider

class FileChooserDialog(
    private val project: Project,
    private val root: VirtualFile,
    private val onFileSelected: (VirtualFile, String) -> Unit
) : DialogWrapper(project) {
    private val pluginSettingsService = PluginSettingsService.instance
    private val projectSettingsService = ProjectSettingsService.getInstance(project)

    // model settings from ModelSettingsPanel.kt
    lateinit var msp: ModelSettingsPanel // Контейнер с компонентами для ModelSettingsPanel

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
    private val instructionsService = InstructionsService.instance

    init {
        title = "FileChooserDialog File Preview"
        previewContainer.add(previewEditor, "TEXT")
        previewContainer.add(JBScrollPane(imagePreviewLabel), "IMAGE")
        setupTree(root)
        setOKButtonText("Send to AI")
        logger.info("initialized")
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

    //var stepIdProp: MutableProperty<Int> = MutableProperty({instructionsService.state.stepId}, {value -> instructionsService.state.stepId = value})

    lateinit var sliderSteps: JSlider


    // ======================= ПАНЕЛЬ НАСТРОЕК С ПРОМПТАМИ =======================
    private fun createSettingsPanel(): JComponent {

        msp = ModelSettingsPanel(
            project,
            modelsComboBox = ComboBox<String>(),
            modelsList = mutableListOf<Model>(),
            isLoading = false,
            endpointSettings = pluginSettingsService.getSettingsFor(projectSettingsService.state.backendEndpoint),       // важно: передаём ссылку на существующий объект
            selectedEndpoint = projectSettingsService.state.backendEndpoint,
        )
        lateinit var modelSettingsPanel: DialogPanel
        fun refreshUIFromModel(mcc: ModelSettingsPanel) {
            modelSettingsPanel.reset()
        }

        // Передаем this и контейнер компонентов
        modelSettingsPanel = msp.createModelSettingsPanel(msp)

        // Инициализируем модели
        systemsModel = DefaultComboBoxModel<String>().apply {
            instructionsService.state.instructions.forEach { addElement(it) }
        }
        promptsModel = DefaultComboBoxModel<String>().apply {
            instructionsService.state.problems.forEach { addElement(it) }
        }

        var stepsProperty: Int = 0
        val panel = panel {
            // Регистрируем вашу внешнюю панель в жизненном цикле этой DSL панели
            onIsModified { modelSettingsPanel.isModified() }
            onApply { modelSettingsPanel.apply() }
            onReset { modelSettingsPanel.reset() }

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
                        }
                }
            }.apply {
                expanded = instructionsService.state.systemExpanded // Разворачиваем группу сразу после создания
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    instructionsService.state.systemExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0)) // Убираем лишнюю пустоту снизу



            val prevStepBtn = JButton("Prev step").apply {
                addActionListener {
                    val state = instructionsService.state
                    if (state.stepId >= 1) {
                        state.stepId --
                        sliderSteps.value = state.stepId
                        sliderSteps.maximum = state.stepIdMax - 1
                        instructionsService.settingsModified()
                        previewEditor.text = state.stepsList[state.stepId]?:""
                    }
                }
            }

            val resetStepsBtn = JButton("Reset steps").apply {
                addActionListener {
                    val state = instructionsService.state
                    state.stepsList.fill(null)
                    state.stepId = 0
                    sliderSteps.value = state.stepId
                    sliderSteps.maximum = state.stepIdMax - 1
                    instructionsService.settingsModified()
                    previewEditor.text = state.stepsList[state.stepId]?:""
                }
            }

            val nextStepBtn = JButton("Next step").apply {
                addActionListener {
                    val state = instructionsService.state

                    if (state.stepsList.size < state.stepIdMax) {
                        //state.stepsList = MutableList(state.stepIdMax)
                    }

                    if (state.stepId < state.stepIdMax - 1 && !state.stepsList[state.stepId+1].isNullOrBlank())
                    {
                        state.stepId ++
                        sliderSteps.value = state.stepId
                        sliderSteps.maximum = state.stepIdMax - 1
                        instructionsService.settingsModified()
                        previewEditor.text = state.stepsList[state.stepId]?:""
                    }
                }
            }

            val addStepDescBtn = JButton("Add Step: Description").apply {
                addActionListener {
                    val state = instructionsService.state
                    val title = "Добавить Новый Шаг"

                    if (state.stepId == state.stepIdMax) {
                        Messages.showErrorDialog("достигнут лимит ${state.stepId} steps", title)
                        return@addActionListener
                    }

                    val msg = "Описание действия:"
                    val result = Messages.showInputDialog(msg, title, Messages.getQuestionIcon())

                    if (state.stepsList.size < state.stepIdMax) {
                        //state.stepsList = arrayOfNulls(state.stepIdMax)
                    }

                    logger.info("result=$result stepId=${state.stepId} stepIdMax=${state.stepIdMax} stepList=${state.stepsList.size}")
                    if (!result.isNullOrBlank() && state.stepId < state.stepIdMax) {
                        state.stepsList[state.stepId] = result
                        state.stepId++
                        //state.stepsList = state.stepsList.copyOf()
                        instructionsService.settingsModified()
                        sliderSteps.value = state.stepId
                        sliderSteps.maximum = state.stepIdMax - 1
                        logger.info("updated stepId=${state.stepId} stepList=${state.stepsList}  value $result")
                    }
                }
            }

            val addStepDataBtn = JButton("Add Step: Data block ").apply {
                addActionListener {
                    val state = instructionsService.state
                    val title = "Добавить Новый Шаг"

                    if (state.stepId == state.stepIdMax) {
                        Messages.showErrorDialog("достигнут лимит ${state.stepId} steps", title)
                        return@addActionListener
                    }

                    val msg = "Данные:"
                    val result = Messages.showInputDialog(msg, title, Messages.getQuestionIcon())
                    if (!result.isNullOrBlank()) {

                        if (state.stepsList.size < state.stepIdMax) {
                            //state.stepsList = arrayOfNulls(state.stepIdMax)
                        }

                        state.stepsList[state.stepId] = result
                        state.stepId++
                        instructionsService.settingsModified()
                        sliderSteps.value = state.stepId
                        sliderSteps.maximum = state.stepIdMax - 1
                    }
                }
            }

            val estimateSizeBtn = JButton("Estimate the size").apply {
                addActionListener {
                    var sum = 0
                    var num = 0
                    instructionsService.state.stepsList.forEach { it ->
                        if (!it.isNullOrBlank())
                        {
                            num += 1
                            sum += it.length
                        }
                    }
                    Messages.showInfoMessage("estimated size is $sum bytes in $num steps", title)
                }
            }







            // --- INPUT НАСТРОЙКИ ---
            collapsibleGroup("Request Features") {
                row {
                    label("Steps:")
                    sliderSteps = slider(0, instructionsService.state.stepIdMax-1, 0, 1)  // min, max, minorTickSpacing, majorTickSpacing
                        .bindValue(instructionsService.state::stepId)
                        .onChanged {
                            instructionsService.state
                            previewEditor.text = instructionsService.state.stepId.toString()
                        }

                        .apply {
                            val sliderComp = component   // это JBSlider

                            sliderComp.snapToTicks = true           // ← главное!
                            sliderComp.paintTicks = true            // показывать деления
                            sliderComp.paintLabels = true           // показывать цифры (по major ticks)

                            // Дополнительно можно настроить шаг делений
                            sliderComp.minorTickSpacing = 1
                            sliderComp.majorTickSpacing = 2         // цифры будут каждые 2
                        }
                        .component
                    sliderSteps.addChangeListener {
                       // stepIdField.text = sliderSteps.value.toString()
                    }

                    //.bindValue(::yourProperty)
                    // или .bindIntValue если нужно

//                    intTextField(0..10,1)
//                        .bindIntText(stepIdProp)

                    //textField().columns(2)

                    cell(prevStepBtn)
                    cell(resetStepsBtn)
                    cell(nextStepBtn)

                    cell(addStepDescBtn)
                    cell(addStepDataBtn)
                    cell(estimateSizeBtn)


//                    panel.add(addStepDataBtn)
//                    panel.add(estimateSizeBtn)



//                    label("Temperature:")
//                    val stepsSpinner = spinner(0..10, step = 1)
                        //.bindIntValue(::stepsProperty)

//                    stepsSpinner.onChanged {
//                        val model = stepsSpinner.component.model as SpinnerNumberModel
//                        val current = stepsSpinner.component.value as Int
//
//                        if (current >= model.maximum as Int) {
//                            model.maximum = current + 10   // увеличиваем "потолок"
//                        }
//                    }
                }
            }.apply {
                expanded = instructionsService.state.inputExpanded
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    instructionsService.state.inputExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0)) // Убираем лишнюю пустоту снизу


            collapsibleGroup("General UI") {
                row {
                    checkBox("Current file")
                    checkBox("Current selection")
                    checkBox("Wrap DATA block")
                    //textField().columns(20).comment("Фильтрация файлов")
                    //button("Обновить дерево") { /* логика */ }
                    //label("Шрифт:")
                    //textField().columns(15)
                    //label("Размер шрифта:")
                    //textField().columns(5)

                }
            }.apply {
                expanded = instructionsService.state.commonExpanded
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    instructionsService.state.commonExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0)) // Убираем лишнюю пустоту снизу


            // Model Settings (Endpoint Dependent)
            row {
                cell(modelSettingsPanel).align(AlignX.FILL)
            }.customize(UnscaledGapsY(bottom = 0)) // Убираем лишнюю пустоту снизу

//            collapsibleGroup("Select Files") {
//            }


            // Запускаем загрузку моделей сразу при создании панели
            msp.loadModelsAsync(project, projectSettingsService.state.backendEndpoint, msp)


        }

        fun refreshUi() {
            panel.reset()
        }

        return panel
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
        instructionsService.state.instructions.clear()
        for (i in 0 until systemsModel.size) {
            instructionsService.state.instructions.add(systemsModel.getElementAt(i))
        }
    }

    // Сохраняем текущий список в настройки
    private fun savePrompts() {
        instructionsService.state.problems.clear()
        for (i in 0 until promptsModel.size) {
            instructionsService.state.problems.add(promptsModel.getElementAt(i))
        }
    }

    // ======================= ИНТЕГРАЦИЯ В ЦЕНТРАЛЬНУЮ ПАНЕЛЬ =======================
    override fun createCenterPanel(): JComponent {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = JBScrollPane(tree)
            rightComponent = previewContainer
            resizeWeight = 0.4
        }
        val mainPanel = panel {
            row { cell(createSettingsPanel()).align(AlignX.FILL) }
                .customize(UnscaledGapsY(bottom = 0)) // Убираем лишнюю пустоту снизу

            // Вот та самая группа
            collapsibleGroup("mainPanel") {
                    row {cell(split).align(AlignX.FILL).align(AlignY.FILL)}
                        .customize(UnscaledGapsY(top = 0, bottom = 0))
            }.expanded = true

            row { cell(createButtonsPanel()) }
                .customize(UnscaledGapsY(top = 0, bottom = 0))
        }

        //val mainPanel = JPanel(BorderLayout())
        //mainPanel.add(createSettingsPanel(), BorderLayout.NORTH)   // ← панель сверху
        //mainPanel.add(split, BorderLayout.CENTER)                  // ← дерево + превью
        //mainPanel.add(createButtonsPanel(), BorderLayout.SOUTH)    // ← кнопки внизу

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

        val fileActBtn = JButton("action").apply {
            addActionListener {
                selectedFile?.let { file ->
                    val prompt = promptsCombo.selectedItem as? String ?: ""
                    onFileSelected(file, "open") // можно передать prompt отдельно
                    //close(OK_EXIT_CODE)
                }
            }
        }

        //panel.add(fileActBtn)

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

    override fun doOKAction() {
        super.doOKAction()
        logger.info("doOKAction performed")
        //ChatPanel.instance?.sendExternalMessage(codeTextField.text, taskField.text)
        //wrapCodeBlock(taskField.text, codeTextField.text, language)
        //)
    }

    override fun doCancelAction() {
        super.doCancelAction()
        logger.info("doCancelAction performed")
    }
}