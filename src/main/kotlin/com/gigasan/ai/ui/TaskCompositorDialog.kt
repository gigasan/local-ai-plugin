package com.gigasan.ai.ui

import com.gigasan.ai.config.storage.WorkItem
import com.gigasan.ai.config.storage.TaskSequenceService
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.config.storage.InstructionsService
import com.gigasan.ai.config.storage.ModelCache
import com.gigasan.ai.config.storage.ModelCacheService
import com.gigasan.ai.config.storage.TaskSequence
import com.gigasan.ai.core.TokenCalculator
import com.gigasan.ai.core.countWords
import com.gigasan.ai.ui.chat.ChatPanel
import com.gigasan.ai.ui.chat.HtmlProcessor.wrapCode
import com.gigasan.ai.ui.chat.HtmlProcessor.wrapData
import com.google.gson.GsonBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import com.intellij.lang.Language
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.Font
import org.jetbrains.annotations.NotNull

class TaskCompositorDialog(
    private val project: Project,
    private val selectedLast: Boolean = false,
) : DialogWrapper(project) {
    private val logger = Logger.getInstance("TaskCompositorDialog")
    private val pluginSettingsService = PluginSettingsService.instance
    private val projectSettingsService = ProjectSettingsService.getInstance(project)
    private val projectSettings = projectSettingsService.state
    private var modelCache: ModelCache = ModelCacheService.instance.getSettingsFor(projectSettings.backendEndpoint)
    val taskSequenceService = TaskSequenceService.getInstance(project).state

    lateinit var detailsPanel: JBPanel<JBPanel<*>>
    lateinit var statsLabel: JLabel
    lateinit var globalStatsLabel: JLabel
    lateinit var includeProblemCheckBox: JBCheckBox
    lateinit var includeItemNameCheckBox: JBCheckBox
    lateinit var wrapDataCheckBox: JBCheckBox

    // список (Левая часть)
    val tasksModel = DefaultListModel<WorkItem>().apply {
        addAll(taskSequenceService.items)
    }

    val tasksList = object : JBList<WorkItem>(tasksModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index != -1) {
                val data = model.getElementAt(index)
                val desc = data.description.take(100)
                val text = data.text.take(100)
                return if (desc.isNotBlank()) {
                    "$desc..."
                } else if (text.isNotBlank()) {
                    "$text..."
                } else {
                    null
                }

            }
            return super.getToolTipText(event)
        }
    }.apply {
        cellRenderer = WorkItemRenderer(
            ProjectSettingsService.getInstance(project).state.fontName,
            ProjectSettingsService.getInstance(project).state.fontSize
        )
    }

    val isp = InstructionsSettingsPanel(
        project,
        instructionsService = InstructionsService.instance,
        cbProblem = null,
        applyImmediately = true,
    )
    var msp = ModelSettingsPanel(
        project,
        modelsComboBox = ComboBox<String>(),
        modelsList = modelCache.models.toMutableList(),
        selectedModel = null,
        isLoading = false,
        endpointSettings = pluginSettingsService.getSettingsFor(projectSettings.backendEndpoint),       // важно: передаём ссылку на существующий объект
        selectedEndpoint = projectSettings.backendEndpoint,
    )
    lateinit var topPanel: DialogPanel
    lateinit var bottomPanel: DialogPanel

    private val titleLabel = JBLabel().apply {
        border = JBUI.Borders.empty(0, 10, 10, 10)
        setCopyable(true)
    }

    // description (editable)
    private val descriptionField = LanguageTextField(
        Language.findLanguageByID("TEXT"), project, "", false)
        .apply {
            setPlaceholder("Additional (optional) description for the task block...")
            setShowPlaceholderWhenFocused(true)
            setCaretPosition(0)
            addSettingsProvider { editor ->
                //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
                editor.contentComponent.border = JBUI.Borders.emptyTop(2)
                editor.contentComponent.border = JBUI.Borders.emptyLeft(6)
                editor.settings.isLineNumbersShown = false
                editor.settings.isCaretRowShown = true
                editor.settings.isUseSoftWraps = true
                editor.colorsScheme.editorFontName = ProjectSettingsService.getInstance(project).state.fontName
                editor.colorsScheme.editorFontSize = ProjectSettingsService.getInstance(project).state.fontSize
                editor.settings.setTabSize(4)
                editor.settings.setUseTabCharacter(false)
                editor.scrollPane.apply {
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBar.unitIncrement = 50
                    verticalScrollBar.blockIncrement = 420
                    horizontalScrollBar.unitIncrement = 28
                }
            }
        }

    // text (editable)
    private val editorField = LanguageTextField(
        Language.findLanguageByID("TEXT"), project, "", false)
        .apply {
            setPlaceholder("Task code/data block...")
            setShowPlaceholderWhenFocused(true)
            setCaretPosition(0)
            addSettingsProvider { editor ->
                //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
                editor.contentComponent.border = JBUI.Borders.emptyTop(2)
                editor.contentComponent.border = JBUI.Borders.emptyLeft(6)
                editor.settings.isLineNumbersShown = true
                editor.settings.isCaretRowShown = true
                editor.settings.isUseSoftWraps = ProjectSettingsService.getInstance(project).state.useSoftWrap
                editor.colorsScheme.editorFontName = ProjectSettingsService.getInstance(project).state.fontName
                editor.colorsScheme.editorFontSize = ProjectSettingsService.getInstance(project).state.fontSize
                editor.settings.setTabSize(4)
                editor.settings.setUseTabCharacter(false)
                editor.scrollPane.apply {
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBar.unitIncrement = 50
                    verticalScrollBar.blockIncrement = 420
                    horizontalScrollBar.unitIncrement = 28
                }
            }
        }

    // Флаг, чтобы избежать зацикливания при обновлении текста из кода
    private var isUpdating = false

    init {
        title = "Task Compositor" // Заголовок окна
        setOKButtonText("Apply")
        setCancelButtonText("Cancel")
        init() // КРИТИЧЕСКИ ВАЖНО: без этого createCenterPanel не вызовется

        // Слушатели изменений текста
        descriptionField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(@NotNull event: DocumentEvent) {
                if (isUpdating) return
                val selected = tasksList.selectedValue
                if (selected != null) {
                    // 1. Обновляем поле в самом объекте данных
                    selected.description = descriptionField.text
                    // 2. Уведомляем список, что данные внутри элемента изменились,
                    // чтобы WorkItemRenderer мог перерисовать заголовок в списке
                    val index = tasksList.selectedIndex
                    if (index != -1) {
                        tasksModel.setElementAt(selected, index)
                    }
                    // 3. статистики токенов
                    statsLabel.text = getStats(descriptionField.document.text, editorField.document.text)
                    globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)

                    //updateTotalStats() // Обновляем вашу статус-строку
                    tasksList.repaint()
                }

            }
        })
        editorField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(@NotNull event: DocumentEvent) {
                if (isUpdating) return
                val selected = tasksList.selectedValue

                if (selected != null) {
                    // 1. Обновляем поле в самом объекте данных
                    selected.text = editorField.text
                    // 2. Уведомляем список, что данные внутри элемента изменились,
                    // чтобы WorkItemRenderer мог перерисовать заголовок в списке
                    val index = tasksList.selectedIndex
                    if (index != -1) {
                        tasksModel.setElementAt(selected, index)
                    }
                    // 3. статистики токенов
                    statsLabel.text = getStats(descriptionField.document.text, editorField.document.text)
                    globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
                    tasksList.repaint()
                }
            }
        })

    }

    override fun getInitialSize(): Dimension? {
        val ideWindow = WindowManager.getInstance().getFrame(project)
        return if (ideWindow != null) {
            Dimension((ideWindow.width * 0.85).toInt(), (ideWindow.height * 0.75).toInt())
        } else {
            super.getInitialSize()
        }
    }

    override fun createCenterPanel(): JComponent {
        //logger.info("createCenterPanel enter")

        val rootPanel = JBPanel<JBPanel<*>>(BorderLayout())

        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1)
            //add(JBLabel("Статус: Готов"), BorderLayout.WEST)
        }

        bottomPanel = msp.createModelSettingsPanel(msp).apply {
        //val bottomPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            //add(JBLabel("Статус: Готов"), BorderLayout.WEST)
        }

        globalStatsLabel = JLabel("Total stats = Bytes: 0 | Words: 0 | Tokens: 0").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), // Верхняя линия (Outside)
                JBUI.Borders.emptyTop(4),                             // Отступ сверху (Inside)
                JBUI.Borders.emptyLeft(4),                             // Отступ сверху (Inside)
                JBUI.Borders.emptyRight(6),                             // Отступ сверху (Inside)
                JBUI.Borders.emptyBottom(6),                             // Отступ сверху (Inside)
            )
        }

        val southCombinedPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(bottomPanel, BorderLayout.CENTER)
            //add(globalStatsLabel, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyBottom(5)
        }

        val splitter = OnePixelSplitter(false, 0.3f).apply {
            preferredSize = Dimension((1280/1.5).toInt(), (720/1.5).toInt())
        }

        tasksList.cellRenderer = WorkItemRenderer(
            ProjectSettingsService.getInstance(project).state.fontName,
            ProjectSettingsService.getInstance(project).state.fontSize)

        // контекстное меню
        val group = DefaultActionGroup()

        group.add(object : AnAction("Rename", "Rename work item", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                val index = tasksList.selectedIndex
                if (index == -1) return

                val selectedItem = tasksModel.getElementAt(index)

                // Вызываем стандартное окно ввода текста
                val newName = Messages.showInputDialog(
                    project,
                    "Enter new name for the work item:",
                    "Edit Name",
                    AllIcons.Actions.Edit,
                    selectedItem.name, // Старое имя подставится автоматически
                    null
                )

                // Если пользователь нажал OK и ввел что-то (не пустую строку)
                if (!newName.isNullOrBlank() && newName != selectedItem.name) {
                    // 1. Обновляем данные в объекте
                    selectedItem.name = newName

                    // 2. Обновляем элемент в модели, чтобы список узнал об изменениях
                    tasksModel.setElementAt(selectedItem, index)

                    // 3. Обновляем детали справа, чтобы заголовок тоже сменился
                    updateDetails(detailsPanel, selectedItem)


                }
            }

            override fun update(e: AnActionEvent) {
                // Делаем кнопку активной только если что-то выбрано
                e.presentation.isEnabled = tasksList.selectedIndex != -1
            }
        })
        group.add(object : AnAction("Remove", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val index = tasksList.selectedIndex
                if (index != -1) (tasksList.model as DefaultListModel).remove(index)
                if (index < tasksModel.size) {
                    tasksList.selectedIndex = index
                }
                tasksList.repaint()
                // 4. Пересчитываем статистику (если имя влияет на байты/токены)
                updateTotalStats()
            }
        })
        group.add(object : AnAction("Remove All", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                tasksModel.clear()
                tasksList.repaint()
                // 4. Пересчитываем статистику (если имя влияет на байты/токены)
                updateTotalStats()
            }
        })

        val popupHandler = ActionManager.getInstance()
            .createActionPopupMenu("MyListPopup", group)

        tasksList.componentPopupMenu = popupHandler.component

        // Создаем контейнер для контента (Правая часть)
        detailsPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel("Select an item from the list"), BorderLayout.CENTER)
        }.apply { alignmentX = Component.CENTER_ALIGNMENT }

        detailsPanel.add(titleLabel, BorderLayout.NORTH)

        val mainEditorComponent = editorField.component

        // вертикальный разделитель
        val innerSplitter = OnePixelSplitter(true, 0.3f).apply {
            firstComponent = descriptionField
            secondComponent = mainEditorComponent
        }

        // сплиттер в центр основной панели
        detailsPanel.add(innerSplitter, BorderLayout.CENTER)

        val btnPanel = JPanel(BorderLayout()).apply {

            statsLabel = JLabel("").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.compound(
                    //JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), // Верхняя линия (Outside)
                    JBUI.Borders.emptyLeft(4)                             // Отступ слева (Inside)
                )
            }
            statsLabel.text = getStats(descriptionField.document.text, editorField.document.text)

            // Добавляете в свою панель
            add(statsLabel, BorderLayout.WEST)

            includeProblemCheckBox = JBCheckBox("Include problem").apply {
                alignmentX = Component.RIGHT_ALIGNMENT
                toolTipText = "Include a Problem from Instruction Set"
                isSelected = taskSequenceService.includeProblem
            }
            includeProblemCheckBox.addActionListener {
                taskSequenceService.includeProblem = includeProblemCheckBox.isSelected
                globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
            }

            includeItemNameCheckBox = JBCheckBox("Include a Item's names").apply {
                alignmentX = Component.RIGHT_ALIGNMENT
                toolTipText = "Include item name if description field is empty"
                isSelected = taskSequenceService.includeItemName
            }
            includeItemNameCheckBox.addActionListener {
                taskSequenceService.includeItemName = includeItemNameCheckBox.isSelected
                globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
            }

            wrapDataCheckBox = JBCheckBox("Wrap DATA block").apply {
                alignmentX = Component.RIGHT_ALIGNMENT
                toolTipText = "Wrap every code block into DATA ... DATA END"
                isSelected = taskSequenceService.wrapDataBlock
            }
            wrapDataCheckBox.addActionListener {
                taskSequenceService.wrapDataBlock = wrapDataCheckBox.isSelected
                statsLabel.text = getStats(descriptionField.document.text, editorField.document.text)
                globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
            }
            globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)

            val rightContainer = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                // Убираем лишние отступы, чтобы панель не "раздувалась"
                isOpaque = false
                add(includeProblemCheckBox)
                add(includeItemNameCheckBox)
                add(wrapDataCheckBox)
            }

            add(rightContainer, BorderLayout.EAST)

            background = UIUtil.getPanelBackground()
        }
        detailsPanel.add(btnPanel, BorderLayout.SOUTH)


        // Логика переключения
        tasksList.addListSelectionListener {
            val selected = tasksList.selectedValue
            updateDetails(detailsPanel, selected)
        }

        // устанавливаем активным первый элемент или последний
        if (!tasksModel.isEmpty) {
            if (selectedLast) {
                tasksList.selectedIndex = tasksModel.size - 1
                tasksList.ensureIndexIsVisible(tasksList.selectedIndex)
            } else {
                tasksList.selectedIndex = 0
                tasksList.ensureIndexIsVisible(tasksList.selectedIndex)
            }
        }

        // панель инструментов
        val decorator = ToolbarDecorator.createDecorator(tasksList)
            .setToolbarPosition(ActionToolbarPosition.TOP)
            .setAddActionName("Add New WorkItem")
            .setAddAction {
                // Логика добавления нового WorkItem
                val newData = WorkItem("New WorkItem", "Author", "1.0", "", "Web", "")
                (tasksList.model as DefaultListModel).addElement(newData)
            }
            .setRemoveActionName("Remove WorkItem")
            .setRemoveAction {
                // Логика удаления
                val index = tasksList.selectedIndex
                if (index != -1) (tasksList.model as DefaultListModel).remove(index)
                if (index < tasksModel.size) {
                    tasksList.selectedIndex = index
                }
            }
            .setMoveUpActionName("Move Up")
            .setMoveUpAction {
                val index = tasksList.selectedIndex
                if (index > 0) {
                    val item = tasksModel.remove(index)
                    tasksModel.add(index - 1, item)
                    tasksList.selectedIndex = index - 1
                }
            }
            .setMoveDownActionName("Move Down")
            .setMoveDownAction {
                val index = tasksList.selectedIndex
                if (index != -1 && index < tasksModel.size - 1) {
                    val item = tasksModel.remove(index)
                    tasksModel.add(index + 1, item)
                    tasksList.selectedIndex = index + 1
                }
            }
            // Добавляем первую кастомную кнопку
            .addExtraAction(object : AnAction("Add File", "Read file", AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) {
                    // 1. Настраиваем фильтр файлов (только .txt)
                    val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select File")
                        .withDescription("The file will be uploaded to the task list")
                        //.withFileFilter { it.extension == "txt" }

                    // 2. Открываем диалог
                    val file = FileChooser.chooseFile(descriptor, e.project, null)

                    if (file != null) {
                        try {
                            // Проверяем, не считает ли IDE этот файл бинарным
                            if (file.fileType.isBinary) {
                                logger.warn("This is a binary file, reading it as text may be incorrect.")
                                Messages.showErrorDialog(e.project, "Unable to upload file", "Upload")
                                return
                            }

                            // 3. Читаем содержимое файла
                            //val content = String(file.contentsToByteArray(), file.charset).trimIndent()

                            val text = VfsUtilCore.loadText(file).trimIndent() // Более надежный способ чтения текста в IntelliJ

                            // Здесь твоя логика обработки текста, например:
                            // tasksListModel.addRow(content)

                            val newData = WorkItem("File ${file.name}", "FileChooser", "1.0", "", "File", text)
                            (tasksList.model as DefaultListModel).addElement(newData)

                            logger.info("The file has been uploaded: ${file.name}")
                        } catch (ex: Exception) {
                            Messages.showErrorDialog(e.project, "Error reading file: ${ex.message}", "Loading")
                        }
                    }
                }
            })
            .addExtraAction(object : AnAction("Export JSON", "Export all tasks to JSON", AllIcons.Actions.MenuSaveall) {
                override fun actionPerformed(e: AnActionEvent) = exportToJson()
            })
            .addExtraAction(object : AnAction("Import JSON", "Import tasks from JSON", AllIcons.Actions.MenuOpen) {
                override fun actionPerformed(e: AnActionEvent) = importFromJson()
            })


        val panelWithButtons = decorator.createPanel()

        // Создаем контейнер для левой стороны (Список + Глобальная стата)
        val leftPartContainer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(panelWithButtons, BorderLayout.CENTER) // Список задач
            add(globalStatsLabel, BorderLayout.SOUTH)   // Ваша метка вниз
        }

        splitter.firstComponent = leftPartContainer
        splitter.secondComponent = detailsPanel

        centerPanel.add(splitter, BorderLayout.CENTER)   // Сплиттер в центре (растянется)


        topPanel = isp.createInstructionsSettingsPanel(isp){updateTotalStats()}.apply {
            border = JBUI.Borders.empty(5)
            //add(JBLabel("Это верхняя панель (на всю ширину)"), BorderLayout.WEST)
            // Сюда можно добавить, например, строку поиска или фильтры
        }

        // 4. Собираем всё в rootPanel
        rootPanel.add(topPanel, BorderLayout.NORTH)    // Панель сверху
        rootPanel.add(centerPanel, BorderLayout.CENTER)    // Панель сверху
        rootPanel.add(southCombinedPanel, BorderLayout.SOUTH) // Панель снизу

        // Запускаем загрузку моделей сразу при создании панели
        msp.loadModelsAsync(project, projectSettings.backendEndpoint, msp)
        
        //logger.info("createCenterPanel leave")
        updateTotalStats()
        return rootPanel
    }


    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun exportToJson() {
        val descriptor = FileSaverDescriptor("Export Tasks to JSON", "Choose destination file").apply {
            withExtensionFilter("JSON files", "json")
        }
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileWrapper = dialog.save("tasks_sequence.json") ?: return

        try {
            // Формируем текущее состояние из модели и флагов сервиса
            val sequence = TaskSequence(
                items = tasksModel.elements().toList().toMutableList(),
                includeProblem = taskSequenceService.includeProblem,
                wrapDataBlock = taskSequenceService.wrapDataBlock
            )

            val jsonString = gson.toJson(sequence)
            FileUtil.writeToFile(fileWrapper.file, jsonString)

            Messages.showInfoMessage("Export successful", "Success")
        } catch (e: Exception) {
            Messages.showErrorDialog("Export failed: ${e.message}", "Error")
        }
    }

    private fun importFromJson() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import Tasks from JSON")

        val virtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        try {
            val jsonString = FileUtil.loadFile(java.io.File(virtualFile.path))
            val sequence = gson.fromJson(jsonString, TaskSequence::class.java)

            if (sequence != null) {
                // 1. Обновляем UI модель
                tasksModel.clear()
                sequence.items.forEach { tasksModel.addElement(it) }

                // 2. Обновляем состояние сервиса и чекбоксов
                taskSequenceService.includeProblem = sequence.includeProblem
                taskSequenceService.wrapDataBlock = sequence.wrapDataBlock

                // Если у вас есть ссылки на чекбоксы в UI, обновите их
                includeProblemCheckBox.isSelected = sequence.includeProblem
                wrapDataCheckBox.isSelected = sequence.wrapDataBlock

                updateTotalStats()
                Messages.showInfoMessage("Imported ${sequence.items.size} items", "Success")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("Import failed: ${e.message}", "Error")
        }
    }

    private fun updateTotalStats() {
        statsLabel.text = getStats(descriptionField.document.text, editorField.document.text)
        globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
    }

    private fun updateDetails(detailsPanel: JBPanel<JBPanel<*>>, selected: WorkItem?) {
        if (selected == null) {
            detailsPanel.isVisible = false
            return
        }

        detailsPanel.isVisible = true
        isUpdating = true // Блокируем слушатель, чтобы он не сработал на программную замену текста

        try {
            // Просто меняем данные в существующих компонентах
            titleLabel.text = selected.name
            titleLabel.font = Font(projectSettings.fontName, Font.BOLD, projectSettings.fontSize)
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                descriptionField.document.setText(selected.description)
                editorField.document.setText(selected.text)
                statsLabel.text = getStats(descriptionField.document.text, editorField.document.text)
                globalStatsLabel.text = getTaskStats(descriptionField.document.text, editorField.document.text, includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
            }
        } finally {
            isUpdating = false
        }
    }

    fun getInstruction(): String {
        return isp.instructionsService.state.selectedInstruction + "\n"
    }
    fun getAllCurrentItems(): List<WorkItem> {
        val items = mutableListOf<WorkItem>()
        for (i in 0 until tasksModel.size) {
            items.add(tasksModel.getElementAt(i))
        }
        return items
    }
    fun getProblem(includeProblem: Boolean, wrapDataBlock: Boolean, includeItemName: Boolean): String {
        val text = StringBuilder()

        if (includeProblem) {
            text.append(isp.instructionsService.state.selectedProblem.trimIndent()).append("\n\n")
        }

        val items = getAllCurrentItems()

        items.forEach {
            if (it.description.isNotBlank()) {
                text.append(it.description).append("\n\n")
            } else if (includeItemName) {
                text.append(it.name).append("\n\n")
            }
            if (it.text.isNotBlank()) {
                if (wrapDataBlock) {
                    text.append(wrapData(wrapCode(it.text))).append("\n")
                } else {
                    text.append(wrapCode(it.text)).append("\n")
                }
            }
        }
        return text.toString()
    }

    fun getStats(description: String, text: String): String {
        val result = StringBuilder()
        if (description.isNotBlank()) {
            result.append(description).append("\n")
        }
        if (text.isNotBlank()) {
            if (taskSequenceService.wrapDataBlock) {
                result.append(wrapData(text)).append("\n")
            } else {
                result.append(text).append("\n")
            }
        }
        val bytes = result.length
        val words = countWords(result.toString())
        val tokens = TokenCalculator.countTokens(result.toString())
        return "Item stats = Bytes: $bytes | Words: $words | Tokens: $tokens"
    }

    fun getTaskStats(desc: String, code: String, includeProblem: Boolean, wrapData: Boolean, includeItemName: Boolean): String {
        val text = getInstruction() + getProblem(includeProblem, wrapData, includeItemName)
        val bytes = text.length
        val words = countWords(text)
        val tokens = TokenCalculator.countTokens(text)
        return "Total stats = Bytes: $bytes | Words: $words | Tokens: $tokens"
    }

    override fun createLeftSideActions(): Array<Action> {
        val sendAction = object : AbstractAction("Send To AI") {
            override fun actionPerformed(e: ActionEvent) {
                val instruction = getInstruction()
                val problem = getProblem(includeProblemCheckBox.isSelected, wrapDataCheckBox.isSelected, includeItemNameCheckBox.isSelected)
                ChatPanel.instance?.sendInstructionQuestionTask(instruction, problem)

                if (projectSettings.closeAfterSent) {
                    doOKAction()
                }
            }
        }
        return arrayOf(sendAction)
    }

    override fun doOKAction() {
        val taskSequenceService = TaskSequenceService.getInstance(project)

        // Очищаем старый список и записываем новый в текущем порядке из модели
        taskSequenceService.state.items.clear()
        for (i in 0 until (tasksList.model as DefaultListModel).size) {
            taskSequenceService.state.items.add(tasksList.model.getElementAt(i))
        }

        topPanel.apply()
        bottomPanel.apply()
        projectSettingsService.notifyChange(project)
        super.doOKAction()
    }

    override fun doCancelAction() {
        topPanel.reset()
        bottomPanel.reset()
        super.doCancelAction()
    }

    override fun applyFields() {
        super.applyFields()
    }

}





