package com.gigasan.ai.ui

import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.WorkItem
import com.gigasan.ai.config.storage.TaskSequenceService
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.config.storage.InstructionsService
import com.gigasan.ai.core.wrapCode
import com.gigasan.ai.ui.chat.ChatPanel
import com.gigasan.ai.ui.chat.ChatPanel.Companion.CHAT_BROWSER_KEY
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
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JCheckBox


class TaskCompositorDialog(
    private val project: Project,
    private val selectedLast: Boolean = false,
) : DialogWrapper(project) {
    private val logger = Logger.getInstance("TaskCompositorDialog")
    private val pluginSettingsService = PluginSettingsService.instance
    private val projectSettingsService = ProjectSettingsService.getInstance(project)
    val taskSequenceService = TaskSequenceService.getInstance(project).state

    // список (Левая часть)
    val tasksModel = DefaultListModel<WorkItem>().apply {
        // Загружаем данные
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
        cellRenderer = MyTwoLineRenderer()
    }

    val rsp = RequestSettingsPanel(instructionsService = InstructionsService.instance, cbProblem = JCheckBox())
    var msp = ModelSettingsPanel(
        project,
        modelsComboBox = ComboBox<String>(),
        modelsList = mutableListOf<Model>(),
        isLoading = false,
        endpointSettings = pluginSettingsService.getSettingsFor(projectSettingsService.state.backendEndpoint),       // важно: передаём ссылку на существующий объект
        selectedEndpoint = projectSettingsService.state.backendEndpoint,
    )
    lateinit var topPanel: DialogPanel
    lateinit var bottomPanel: DialogPanel

    init {
        title = "Task Compositor" // Заголовок окна
        setOKButtonText("Apply")
        setCancelButtonText("Cancel")
        init() // КРИТИЧЕСКИ ВАЖНО: без этого createCenterPanel не вызовется
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
        logger.info("createCenterPanel enter")

        val rootPanel = JBPanel<JBPanel<*>>(BorderLayout())

        topPanel = rsp.createRequestSettingsPanel(rsp).apply {
            border = JBUI.Borders.empty(5)
            //add(JBLabel("Это верхняя панель (на всю ширину)"), BorderLayout.WEST)
            // Сюда можно добавить, например, строку поиска или фильтры
        }

        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1)
            //add(JBLabel("Статус: Готов"), BorderLayout.WEST)
        }

        bottomPanel = msp.createModelSettingsPanel(msp).apply {
        //val bottomPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            //add(JBLabel("Статус: Готов"), BorderLayout.WEST)
        }

        val splitter = OnePixelSplitter(false, 0.4f).apply {
            preferredSize = Dimension((1280/1.5).toInt(), (720/1.5).toInt())
        }

        tasksList.cellRenderer = MyTwoLineRenderer()

        // контекстное меню
        val group = DefaultActionGroup()
        group.add(object : AnAction("Удалить", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val index = tasksList.selectedIndex
                if (index != -1) (tasksList.model as DefaultListModel).remove(index)
                if (index < tasksModel.size) {
                    tasksList.selectedIndex = index
                }
            }
        })
        group.add(object : AnAction("Удалить Всё", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                tasksModel.clear()
            }
        })

        val popupHandler = ActionManager.getInstance()
            .createActionPopupMenu("MyListPopup", group)

        tasksList.componentPopupMenu = popupHandler.component

        // Создаем контейнер для контента (Правая часть)
        val detailsPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel("Выберите элемент из списка"), BorderLayout.CENTER)
        }.apply { alignmentX = Component.CENTER_ALIGNMENT }

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
                val newData = WorkItem("New WorkItem", "author", "1.0", "", "Web", "")
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
                        .withTitle("Выберите Файл")
                        .withDescription("Файл будет загружен в список задач")
                        //.withFileFilter { it.extension == "txt" }

                    // 2. Открываем диалог
                    val file = FileChooser.chooseFile(descriptor, e.project, null)

                    if (file != null) {
                        try {
                            // Проверяем, не считает ли IDE этот файл бинарным
                            if (file.fileType.isBinary) {
                                logger.warn("Это бинарный файл, чтение как текст может быть некорректным")
                                Messages.showErrorDialog(e.project, "Невозможно загрузить файл", "Загрузка")
                                return
                            }

                            // 3. Читаем содержимое файла
                            //val content = String(file.contentsToByteArray(), file.charset).trimIndent()

                            val text = VfsUtilCore.loadText(file).trimIndent() // Более надежный способ чтения текста в IntelliJ

                            // Здесь твоя логика обработки текста, например:
                            // tasksListModel.addRow(content)

                            val newData = WorkItem("File ${file.name}", "FileChooser", "1.0", "", "File", text)
                            (tasksList.model as DefaultListModel).addElement(newData)

                            logger.info("Файл загружен: ${file.name}")
                        } catch (ex: Exception) {
                            Messages.showErrorDialog(e.project, "Ошибка при чтении файла: ${ex.message}", "Загрузка")
                        }
                    }
                }
            })

        val panelWithButtons = decorator.createPanel()

        splitter.firstComponent = panelWithButtons
        splitter.secondComponent = detailsPanel

        centerPanel.add(splitter, BorderLayout.CENTER)   // Сплиттер в центре (растянется)

        // 4. Собираем всё в rootPanel
        rootPanel.add(topPanel, BorderLayout.NORTH)    // Панель сверху
        rootPanel.add(centerPanel, BorderLayout.CENTER)    // Панель сверху
        rootPanel.add(bottomPanel, BorderLayout.SOUTH) // Панель снизу

        // Запускаем загрузку моделей сразу при создании панели
        msp.loadModelsAsync(project, projectSettingsService.state.backendEndpoint, msp)
        
        logger.info("createCenterPanel leave")
        return rootPanel
    }

    private fun updateDetails(detailsPanel: JBPanel<JBPanel<*>>, selected: WorkItem?) {
        //logger.info("updateDetails")
        detailsPanel.removeAll()
        if (selected != null) {

            // name
            val titleLabel = JBLabel(selected.name).apply {
                // Устанавливаем отступы: сверху, слева, снизу, справа
                border = JBUI.Borders.empty(0, 10, 10, 10)
                font = Font(ProjectSettingsService.getInstance(project).state.fontName, Font.BOLD, ProjectSettingsService.getInstance(project).state.fontSize)
                //toolTipText = selected.name
                setCopyable(true)
            }

            detailsPanel.add(titleLabel, BorderLayout.NORTH)

            // description (editable)
            val descriptionField = LanguageTextField(
                Language.findLanguageByID("TEXT"), project, selected.description, false)
                .apply {
                    setPlaceholder("Дополнительное (не обязательное) описание для блока задачи...")
                    setShowPlaceholderWhenFocused(true)
                    setCaretPosition(0)
                    addSettingsProvider { editor ->
                        //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
                        editor.settings.isLineNumbersShown = false
                        editor.settings.isCaretRowShown = true
                        editor.settings.isUseSoftWraps = false
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
            val editorField = LanguageTextField(
                Language.findLanguageByID("TEXT"), project, selected.text, false)
                .apply {
                    setPlaceholder("Блок кода/данных задачи...")
                    setShowPlaceholderWhenFocused(true)
                    setCaretPosition(0)
                    addSettingsProvider { editor ->
                        //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
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

            val mainEditorComponent = editorField.component

            // 3. Создаем вертикальный разделитель
            val innerSplitter = OnePixelSplitter(true, 0.3f).apply {
                firstComponent = descriptionField
                secondComponent = mainEditorComponent
            }

            // Добавляем сплиттер в центр основной панели деталей
            detailsPanel.add(innerSplitter, BorderLayout.CENTER)

            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                val estimateSizeBtn = JButton("Estimate the size").apply {
                    addActionListener {
                        var sum = 0
                        var num = 0
                        taskSequenceService.items.forEach { it ->
                            num += 1
                            sum += it.text.length
                        }
                        Messages.showInfoMessage("estimated size is $sum bytes in $num steps", title)
                    }
                }

                val sendTaskBtn = JButton("Send to AI").apply {
                    addActionListener {
                        val text = StringBuilder()
                        var sum = 0
                        var num = 0
                        taskSequenceService.items.forEach { it ->
                            num += 1
                            sum += it.text.length
                            text.append(it.description.trimIndent()).append("\n")
                            text.append(wrapCode(it.text.trimIndent()))
                        }
                        //Messages.showInfoMessage("sending to AI $sum bytes in $num steps", title)

                        val problem = StringBuilder()
                        if (rsp.cbProblem.isSelected) {
                            problem.append(rsp.instructionsService.state.selectedProblem).append("\n")
                        }
                        problem.append(text.toString()).append("\n")

                        ChatPanel.instance?.sendInstructionQuestionTask(
                            rsp.instructionsService.state.selectedInstruction,
                            problem.toString()
                            )
                    }

                }
                add(estimateSizeBtn)
                add(sendTaskBtn)
                background = UIUtil.getPanelBackground()
            }
            detailsPanel.add(btnPanel, BorderLayout.SOUTH)

        }
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    override fun createLeftSideActions(): Array<Action> {
        val helpAction = object : AbstractAction("Help") {
            override fun actionPerformed(e: ActionEvent) {
                // открыть браузер с документацией
            }
        }
        return arrayOf(helpAction)
    }

    override fun doOKAction() {
        val taskSequenceService = TaskSequenceService.getInstance(project)

        // Очищаем старый список и записываем новый в текущем порядке из модели
        taskSequenceService.state.items.clear()
        for (i in 0 until (tasksList.model as DefaultListModel).size) {
            taskSequenceService.state.items.add(tasksList.model.getElementAt(i))
        }

        bottomPanel.apply()

        topPanel.apply()

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





