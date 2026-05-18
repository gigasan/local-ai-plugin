package com.gigasan.ai.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.*
import kotlin.text.trim
import com.gigasan.ai.config.storage.InstructionsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.core.createTooltipRenderer
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.layout.selected
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea

data class InstructionsSettingsPanel(
    var project: Project,
    var instructionsService: InstructionsService,
    var title: String = "Instruction Set",
    var cbProblem: JCheckBox? = null,
    var applyImmediately: Boolean = false,
) {
    val logger = Logger.getInstance("InstructionsSettingsPanel")

    fun createInstructionsSettingsPanel(
        components: InstructionsSettingsPanel,
        onChanged: (() -> Unit)? = null // Добавляем опциональный колбэк
    ): DialogPanel {

        val instructionsModel = DefaultComboBoxModel(instructionsService.state.instructions.toTypedArray())
        val problemsModel = DefaultComboBoxModel(instructionsService.state.problems.toTypedArray())

        // Хелпер для мгновенного сохранения, если флаг активен
        fun autoApply() {
            if (applyImmediately) {
                // Мы вызываем встроенный метод DSL панели, чтобы прогнать все setter-ы из bind-методов
                // Но так как нам нужно точечно, ниже в компонентах мы добавим прямые вызовы.
            }
        }

        fun saveSystems() {
            instructionsService.state.instructions.clear()
            for (i in 0 until instructionsModel.size) {
                instructionsService.state.instructions.add(instructionsModel.getElementAt(i))
            }
            onChanged?.invoke()
        }

        fun savePrompts() {
            instructionsService.state.problems.clear()
            for (i in 0 until problemsModel.size) {
                instructionsService.state.problems.add(problemsModel.getElementAt(i))
            }
            onChanged?.invoke()
        }

        fun updateSystems() {
            instructionsModel.removeAllElements()
            instructionsService.state.instructions.forEach { instruction -> instructionsModel.addElement(instruction) }
            instructionsService.state.selectedInstruction = instructionsService.state.instructions[0]
            onChanged?.invoke()
        }

        fun updatePrompts() {
            problemsModel.removeAllElements()
            instructionsService.state.problems.forEach { problem -> problemsModel.addElement(problem) }
            instructionsService.state.selectedProblem = instructionsService.state.problems[0]
            onChanged?.invoke()
        }

        val resultPanel = panel {

            collapsibleGroup(title) {

                row {
                    label("Instruction:")
                        .comment("General rules, role, restrictions")

                    comboBox(instructionsModel)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .applyToComponent {
                            isEditable = false
                            setRenderer(createTooltipRenderer())
                            // ОБРАБОТКА ФЛАГА:
                            if (applyImmediately) {
                                addActionListener {
                                    instructionsService.state.selectedInstruction = (selectedItem as? String).orEmpty()
                                    // ВЫЗОВ КОЛБЭКА
                                    onChanged?.invoke()
                                }
                            }
                        }
                        .bindItem(
                            getter = { components.instructionsService.state.selectedInstruction },
                            setter = { newName -> components.instructionsService.state.selectedInstruction = newName.orEmpty() }
                        )

                    // Кнопки управления Instruction
                    actionButton(AllIcons.General.Add, "Adding instructions") { handleAdd(instructionsModel, "Set general rules, role, restrictions... :") { saveSystems() } }
                    actionButton(AllIcons.Actions.Edit, "Editing") { handleEdit(instructionsModel, "Change the instructions:") { saveSystems() } }
                    actionButton(AllIcons.General.Remove, "Removing") { handleDelete(instructionsModel, "Delete the instruction?") { saveSystems() } }

                    // === Новые кнопки ===
                    actionButton(AllIcons.Actions.MenuSaveall, "Export") { exportToJson(instructionsModel, "instructions") }
                    actionButton(AllIcons.Actions.MenuOpen, "Import") { importFromJson(instructionsModel, "instructions") { saveSystems() } }
                    actionButton(AllIcons.Actions.Restart, "Reset") { instructionsService.loadDefaultInstructions() { updateSystems() } }
                }

                row {
                    if (cbProblem != null) {
                        cbProblem = checkBox("Problem:")
                            .bindSelected(components.instructionsService.state::enabledProblem)
                            .comment("What needs to be done right now")
                            .applyToComponent {
                                // ОБРАБОТКА ФЛАГА для чекбокса:
                                if (applyImmediately) {
                                    addActionListener {
                                        instructionsService.state.enabledProblem = isSelected
                                        // ВЫЗОВ КОЛБЭКА
                                        onChanged?.invoke()
                                    }
                                }
                            }
                            .component
                    } else {
                        label("Problem:")
                            .comment("What needs to be done right now")
                    }

                    comboBox(problemsModel)
                        .enabledIf(cbProblem?.selected ?: JBCheckBox().apply { isSelected = true }.selected)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .applyToComponent {
                            isEditable = false
                            setRenderer(createTooltipRenderer())
                            // ОБРАБОТКА ФЛАГА:
                            if (applyImmediately) {
                                addActionListener {
                                    instructionsService.state.selectedProblem = (selectedItem as? String).orEmpty()
                                    // ВЫЗОВ КОЛБЭКА
                                    onChanged?.invoke()
                                }
                            }
                        }
                        .bindItem(
                            getter = { components.instructionsService.state.selectedProblem },
                            setter = { newName -> components.instructionsService.state.selectedProblem = newName.orEmpty() }
                        )

                    // Кнопки управления Problem
                    actionButton(AllIcons.General.Add, "Adding a task") { handleAdd(problemsModel, "What needs to be done right now... :") { savePrompts() } }
                    actionButton(AllIcons.Actions.Edit, "Editing") { handleEdit(problemsModel, "Change the task:") { savePrompts() } }
                    actionButton(AllIcons.General.Remove, "Removing") { handleDelete(problemsModel, "Delete task?") { savePrompts() } }

                    // === Новые кнопки ===
                    actionButton(AllIcons.Actions.MenuSaveall, "Export") { exportToJson(problemsModel, "problems") }
                    actionButton(AllIcons.Actions.MenuOpen, "Import") { importFromJson(problemsModel, "problems") { savePrompts() } }
                    actionButton(AllIcons.Actions.Restart, "Reset") { instructionsService.loadDefaultProblems() { updatePrompts() } }

                }
            }.apply {
                expanded = ProjectSettingsService.getInstance(project).state.instructionSetExpanded
                addExpandedListener { isExpanded ->
                    ProjectSettingsService.getInstance(project).state.instructionSetExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0))
        }

        // isModified / apply / reset оставляем как было
        fun isModified(): Boolean = resultPanel.isModified()
        fun apply() = resultPanel.apply()
        fun reset() = resultPanel.reset()

        return resultPanel
    }

    // ==================== Export / Import ====================
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun exportToJson(model: DefaultComboBoxModel<String>, typeData: String) {
        val descriptor = FileSaverDescriptor("Export $typeData to JSON", "Choose destination file").apply {
            withExtensionFilter("JSON files", "json")
        }
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileWrapper = dialog.save("$typeData.json") ?: return

        try {
            val dataList = mutableListOf<String>()
            for (i in 0 until model.size) {
                dataList.add(model.getElementAt(i))
            }

            val jsonString = gson.toJson(dataList)
            FileUtil.writeToFile(fileWrapper.file, jsonString)

            Messages.showInfoMessage("Export successful", "Success")
        } catch (e: Exception) {
            Messages.showErrorDialog("Export failed: ${e.message}", "Error")
        }
    }

    private fun importFromJson(model: DefaultComboBoxModel<String>, typeData: String, onSave: () -> Unit) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Import $typeData from JSON")

        val virtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        try {
            val typeToken = object : TypeToken<List<String>>() {}.type
            val jsonString = FileUtil.loadFile(java.io.File(virtualFile.path))
            val sequence: MutableList<String> = gson.fromJson(jsonString, typeToken)

            // 1. Обновляем UI модель
            model.removeAllElements()
            sequence.forEach { model.addElement(it) }
            onSave()
            Messages.showInfoMessage("Imported ${sequence.size} $typeData", "Success")

        } catch (e: Exception) {
            logger.error(e)
            Messages.showErrorDialog("Import failed: ${e.message}", "Error")
        }
    }

    private fun Row.actionButton(icon: Icon, tooltip: String, action: () -> Unit) = button("") { action() }
        .customize(UnscaledGaps(left = 8))
        .applyToComponent {
            this.icon = icon
            this.toolTipText = tooltip
            text = null
            margin = JBUI.emptyInsets()
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            putClientProperty("JButton.buttonType", "square")
        }

    private fun handleAdd(model: DefaultComboBoxModel<String>, title: String, onSave: () -> Unit) {
        val dialog = MultiLineInputDialog(project, "Add New Item", "")

        if (dialog.showAndGet()) { // showAndGet возвращает true, если нажата кнопка OK
            val result = dialog.getText()
            if (result.isNotBlank()) {
                model.addElement(result.trim())
                onSave()
            }
        }
    }

    private fun handleEdit(model: DefaultComboBoxModel<String>, title: String, onSave: () -> Unit) {
        val selected = model.selectedItem as? String ?: return
        val dialog = MultiLineInputDialog(project, "Edit Item", selected)

        if (dialog.showAndGet()) {
            val edited = dialog.getText()
            if (edited.isNotBlank() && edited.trim() != selected) {
                val index = model.getIndexOf(selected)
                model.removeElement(selected)
                model.insertElementAt(edited.trim(), index)
                model.selectedItem = edited.trim()
                onSave()
            }
        }
    }

    private fun handleDelete(model: DefaultComboBoxModel<String>, msg: String, onSave: () -> Unit) {
        val selected = model.selectedItem as? String ?: return
        if (Messages.showYesNoDialog(
                "$msg «$selected»?",
                "Confirmation",
                Messages.getQuestionIcon()
            ) == Messages.YES
        ) {
            model.removeElement(selected)
            onSave()
        }
    }
}

class MultiLineInputDialog(
    project: Project?,
    title: String,
    initialText: String = ""
) : DialogWrapper(project) {

    private val textArea = JBTextArea(10, 50).apply {
        text = initialText
        lineWrap = true
        wrapStyleWord = true
        // Упрощаем вставку переноса строки по Enter,
        // так как в диалогах Enter обычно нажимает "OK"
        emptyText.text = "Введите текст здесь..."
    }

    init {
        this.title = title
        init() // Инициализация компонентов диалога
    }

    override fun createCenterPanel(): JComponent {
        // Оборачиваем в ScrollPane, чтобы текст не улетал за границы
        return JBScrollPane(textArea)
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    fun getText(): String = textArea.text
}