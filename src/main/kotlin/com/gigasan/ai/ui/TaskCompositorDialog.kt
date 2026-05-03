package com.gigasan.ai.ui

import com.gigasan.ai.ui.RequestSettingsPanel
import com.gigasan.ai.ui.ModelSettingsPanel
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.MyPluginData
import com.gigasan.ai.config.storage.MyPluginSettingsService
import com.gigasan.ai.config.storage.PluginSettings
import com.gigasan.ai.config.storage.ProjectSettings
import com.gigasan.ai.config.storage.PromptSettings
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
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.Font

class TaskCompositorDialog(
    private val project: Project,
    private val selectedLast: Boolean = false,
) : DialogWrapper(project) {
    private val logger = Logger.getInstance("TaskCompositorDialog")
    private val pluginSettings = PluginSettings.instance
    private val projectSettings = ProjectSettings.getInstance(project)
    val settings = MyPluginSettingsService.getInstance(project)
    val savedPlugins = settings.state.plugins

    // список (Левая часть)
    val listModel = DefaultListModel<MyPluginData>().apply {
        // Загружаем данные
        addAll(settings.state.plugins)
    }

    val myList = object : JBList<MyPluginData>(listModel) {
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

    val rsp = RequestSettingsPanel(
//        project,
//        endpointSettings = pluginSettings.getSettingsFor(projectSettings.state.backendEndpoint),
//        modelsList = mutableListOf<Model>(),
        promptSettings = PromptSettings.instance,
        )
    var msp = ModelSettingsPanel(
        project,
        modelsComboBox = ComboBox<String>(),
        modelsList = mutableListOf<Model>(),
        isLoading = false,
        endpointSettings = pluginSettings.getSettingsFor(projectSettings.state.backendEndpoint),       // важно: передаём ссылку на существующий объект
        selectedEndpoint = projectSettings.state.backendEndpoint,
    )
    lateinit var topPanel: DialogPanel
    lateinit var bottomPanel: DialogPanel

    init {
        title = "Task Compositor" // Заголовок окна
        setOKButtonText("Apply")
        setCancelButtonText("Close")
        init() // КРИТИЧЕСКИ ВАЖНО: без этого createCenterPanel не вызовется
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

        myList.cellRenderer = MyTwoLineRenderer()

        // контекстное меню
        val group = DefaultActionGroup()
        group.add(object : AnAction("Удалить", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val index = myList.selectedIndex
                if (index != -1) (myList.model as DefaultListModel).remove(index)
                if (index < listModel.size) {
                    myList.selectedIndex = index
                }
            }
        })

        val popupHandler = ActionManager.getInstance()
            .createActionPopupMenu("MyListPopup", group)

        myList.componentPopupMenu = popupHandler.component

        // Создаем контейнер для контента (Правая часть)
        val detailsPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel("Выберите элемент из списка"), BorderLayout.CENTER)
        }.apply { alignmentX = Component.CENTER_ALIGNMENT }

        // Логика переключения
        myList.addListSelectionListener {
            val selected = myList.selectedValue
            updateDetails(detailsPanel, selected)
        }

        // устанавливаем активным первый элемент или последний
        if (!listModel.isEmpty) {
            if (selectedLast) {
                myList.selectedIndex = listModel.size - 1
                myList.ensureIndexIsVisible(myList.selectedIndex)
            } else {
                myList.selectedIndex = 0
                myList.ensureIndexIsVisible(myList.selectedIndex)
            }
        }

        // панель инструментов
        val decorator = ToolbarDecorator.createDecorator(myList)
            .setToolbarPosition(ActionToolbarPosition.TOP)
            .setAddActionName("Добавить новую задачу")
            .setAddAction {
                // Логика добавления нового MyPluginData
                val newData = MyPluginData("New", "author", "1.0", "", "Web", "")
                (myList.model as DefaultListModel).addElement(newData)
            }
            .setRemoveActionName("Удалить задачу")
            .setRemoveAction {
                // Логика удаления
                val index = myList.selectedIndex
                if (index != -1) (myList.model as DefaultListModel).remove(index)
                if (index < listModel.size) {
                    myList.selectedIndex = index
                }
            }
            .setMoveUpActionName("Переместить вверх")
            .setMoveUpAction {
                val index = myList.selectedIndex
                if (index > 0) {
                    val item = listModel.remove(index)
                    listModel.add(index - 1, item)
                    myList.selectedIndex = index - 1
                }
            }
            .setMoveDownActionName("Переместить вниз")
            .setMoveDownAction {
                val index = myList.selectedIndex
                if (index != -1 && index < listModel.size - 1) {
                    val item = listModel.remove(index)
                    listModel.add(index + 1, item)
                    myList.selectedIndex = index + 1
                }
            }

        val panelWithButtons = decorator.createPanel()

        splitter.firstComponent = panelWithButtons
        splitter.secondComponent = detailsPanel

        centerPanel.add(splitter, BorderLayout.CENTER)   // Сплиттер в центре (растянется)

        // 4. Собираем всё в rootPanel
        rootPanel.add(topPanel, BorderLayout.NORTH)    // Панель сверху
        rootPanel.add(centerPanel, BorderLayout.CENTER)    // Панель сверху
        rootPanel.add(bottomPanel, BorderLayout.SOUTH) // Панель снизу

        // Запускаем загрузку моделей сразу при создании панели
        msp.loadModelsAsync(project, projectSettings.state.backendEndpoint, msp)
        
        logger.info("createCenterPanel leave")
        return rootPanel
    }

    private fun updateDetails(detailsPanel: JBPanel<JBPanel<*>>, selected: MyPluginData?) {
        //logger.info("updateDetails")
        detailsPanel.removeAll()
        if (selected != null) {

            // name
            val titleLabel = JBLabel(selected.name).apply {
                // Устанавливаем отступы: сверху, слева, снизу, справа
                border = JBUI.Borders.empty(0, 10, 10, 10)
                font = Font(ProjectSettings.getInstance(project).state.fontName, Font.BOLD, ProjectSettings.getInstance(project).state.fontSize)
                //toolTipText = selected.name
                setCopyable(true)
            }

            detailsPanel.add(titleLabel, BorderLayout.NORTH)

            // description (editable)
            val descriptionField = LanguageTextField(
                Language.findLanguageByID("TEXT"), project, selected.description, false)
                .apply {
                    setPlaceholder("Описание этапа задания...")
                    setShowPlaceholderWhenFocused(true)
                    addSettingsProvider { editor ->
                        //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
                        editor.settings.isLineNumbersShown = false
                        editor.settings.isCaretRowShown = true
                        editor.settings.isUseSoftWraps = false
                        editor.colorsScheme.editorFontName = ProjectSettings.getInstance(project).state.fontName
                        editor.colorsScheme.editorFontSize = ProjectSettings.getInstance(project).state.fontSize
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
                    setPlaceholder("Код для отправки...")
                    setShowPlaceholderWhenFocused(true)
                    addSettingsProvider { editor ->
                        //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
                        editor.settings.isLineNumbersShown = true
                        editor.settings.isCaretRowShown = true
                        editor.settings.isUseSoftWraps = ProjectSettings.getInstance(project).state.useSoftWrap
                        editor.colorsScheme.editorFontName = ProjectSettings.getInstance(project).state.fontName
                        editor.colorsScheme.editorFontSize = ProjectSettings.getInstance(project).state.fontSize
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
                    setCaretPosition(0)
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
                        savedPlugins.forEach { it ->
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
                        savedPlugins.forEach { it ->
                            num += 1
                            sum += it.text.length
                            text.append(it.description).append("\n")
                            text.append(it.text).append("\n")
                        }
                        Messages.showInfoMessage("sending to AI $sum bytes in $num steps", title)
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
        val settings = MyPluginSettingsService.getInstance(project)

        // Очищаем старый список и записываем новый в текущем порядке из модели
        settings.state.plugins.clear()
        for (i in 0 until (myList.model as DefaultListModel).size) {
            settings.state.plugins.add(myList.model.getElementAt(i))
        }

        bottomPanel.apply()

        topPanel.apply()

        super.doOKAction()
    }

    override fun doCancelAction() {
        bottomPanel.reset()
        super.doCancelAction()
    }

    override fun applyFields() {
        super.applyFields()
    }

}





