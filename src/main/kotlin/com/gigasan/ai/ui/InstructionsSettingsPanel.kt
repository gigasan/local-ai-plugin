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
import com.gigasan.ai.core.createTooltipRenderer
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
) {
    val logger = Logger.getInstance("InstructionsSettingsPanel")

    fun createInstructionsSettingsPanel(components: InstructionsSettingsPanel): DialogPanel {

        val instructionsModel = DefaultComboBoxModel(instructionsService.state.instructions.toTypedArray())
        val problemsModel = DefaultComboBoxModel(instructionsService.state.problems.toTypedArray())

        fun saveSystems() {
            instructionsService.state.instructions.clear()
            for (i in 0 until instructionsModel.size) {
                instructionsService.state.instructions.add(instructionsModel.getElementAt(i))
            }
        }

        fun savePrompts() {
            instructionsService.state.problems.clear()
            for (i in 0 until problemsModel.size) {
                instructionsService.state.problems.add(problemsModel.getElementAt(i))
            }
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
                    actionButton(AllIcons.Actions.MenuSaveall, "Export") { exportToXml(instructionsModel, "instructions") }
                    actionButton(AllIcons.Actions.MenuOpen, "Import") { importFromXml(instructionsModel, "instructions") { saveSystems() } }
                }

                row {
                    if (cbProblem != null) {
                        cbProblem = checkBox("Problem:")
                            .bindSelected(components.instructionsService.state::enabledProblem)
                            .comment("What needs to be done right now")
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
                    actionButton(AllIcons.Actions.MenuSaveall, "Export") { exportToXml(problemsModel, "problems") }
                    actionButton(AllIcons.Actions.MenuOpen, "Import") { importFromXml(problemsModel, "problems") { savePrompts() } }
                }
            }.apply {
                expanded = instructionsService.state.systemExpanded
                addExpandedListener { isExpanded ->
                    instructionsService.state.systemExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0))
        }

        // isModified / apply / reset оставляем как было
        fun isModified(): Boolean = resultPanel.isModified()
        fun apply() = resultPanel.apply()
        fun reset() = resultPanel.reset()

        return resultPanel
    }

    // ==================== XML Export / Import ====================

    private fun exportToXml(model: DefaultComboBoxModel<String>, type: String) {
        val descriptor = FileSaverDescriptor(
            "Export $type",
            "Select a location to save the XML file"
        ).apply {
            withExtensionFilter("XML files", "xml")
        }

        val dialog = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, null as Project?)

        val virtualFileWrapper = dialog.save("$type.xml") ?: return

        try {
            val targetFile = virtualFileWrapper.file

            val xml = buildXml(model, type)
            FileUtil.writeToFile(targetFile, xml, false)

            Messages.showInfoMessage(
                "Export completed successfully:\n${targetFile.absolutePath}",
                "Success"
            )
        } catch (e: Exception) {
            logger.error(e)
            Messages.showErrorDialog("Error while exporting: ${e.message}", "Error")
        }
    }

    private fun importFromXml(model: DefaultComboBoxModel<String>, type: String, onSave: () -> Unit) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
            .withTitle("Import $type from XML")

        val virtualFile = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            descriptor, null, null
        ) ?: return

        try {
            val file = java.io.File(virtualFile.path)
            val content = com.intellij.openapi.util.io.FileUtil.loadFile(file)

            val imported = parseXml(content, type)

            if (imported.isNotEmpty()) {
                model.removeAllElements()
                imported.forEach { model.addElement(it) }

                onSave()
                Messages.showInfoMessage("Imported ${imported.size} records", "Success")
            } else {
                Messages.showWarningDialog("The file does not contain any records", "Warning")
            }
        } catch (e: Exception) {
            logger.error(e)
            Messages.showErrorDialog("Error while importing: ${e.message}", "Error")
        }
    }

    private fun buildXml(model: DefaultComboBoxModel<String>, type: String): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("<$type>")

        for (i in 0 until model.size) {
            val item = model.getElementAt(i)?.trim() ?: continue
            if (item.isNotBlank()) {
                sb.appendLine("    <item>${escapeXml(item)}</item>")
            }
        }

        sb.appendLine("</$type>")
        return sb.toString()
    }

    private fun parseXml(xml: String, type: String): List<String> {
        val items = mutableListOf<String>()
        val regex = "<item>(.*?)</item>".toRegex(RegexOption.DOT_MATCHES_ALL)

        regex.findAll(xml).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotBlank()) {
                items.add(unescapeXml(content))
            }
        }
        return items
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun unescapeXml(text: String): String =
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    // ==================== Остальные методы без изменений ====================

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