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
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import kotlin.text.trim
import com.gigasan.ai.config.storage.InstructionsService
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.layout.selected
import java.io.File
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt   // если нужно

data class RequestSettingsPanel(
    //var project: Project,
    var instructionsService: InstructionsService,
    var cbProblem: JCheckBox
) {
    val logger = Logger.getInstance("RequestSettingsPanel")

    fun createRequestSettingsPanel(components: RequestSettingsPanel): DialogPanel {

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

            collapsibleGroup("Directive Compound") {

                row {
                    val cbInstruction = checkBox("Instruction:")
                        .bindSelected(components.instructionsService.state::enabledInstruction)
                        .comment("Общие правила, роль, ограничения")

                    comboBox(instructionsModel)
                        .enabledIf(cbInstruction.selected)
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
                    actionButton(AllIcons.General.Add) { handleAdd(instructionsModel, "Задайте общие правила, роль, ограничения... :", "Добавление инструкции") { saveSystems() } }
                    actionButton(AllIcons.Actions.Edit) { handleEdit(instructionsModel, "Измените инструкцию:", "Редактирование") { saveSystems() } }
                    actionButton(AllIcons.General.Remove) { handleDelete(instructionsModel, "Удалить инструкцию?") { saveSystems() } }

                    // === Новые кнопки ===
                    actionButton(AllIcons.Actions.MenuSaveall) { exportToXml(instructionsModel, "instructions") }
                    actionButton(AllIcons.Actions.MenuOpen) { importFromXml(instructionsModel, "instructions") { saveSystems() } }
                }

                row {
                    cbProblem = checkBox("Problem:")
                        .bindSelected(components.instructionsService.state::enabledProblem)
                        .comment("Что нужно сделать прямо сейчас")
                        .component

                    comboBox(problemsModel)
                        .enabledIf(cbProblem.selected)
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
                    actionButton(AllIcons.General.Add) { handleAdd(problemsModel, "Что нужно сделать прямо сейчас... :", "Добавление задачи") { savePrompts() } }
                    actionButton(AllIcons.Actions.Edit) { handleEdit(problemsModel, "Измените задачу:", "Редактирование") { savePrompts() } }
                    actionButton(AllIcons.General.Remove) { handleDelete(problemsModel, "Удалить задачу?") { savePrompts() } }

                    // === Новые кнопки ===
                    actionButton(AllIcons.Actions.MenuSaveall) { exportToXml(problemsModel, "problems") }
                    actionButton(AllIcons.Actions.MenuOpen) { importFromXml(problemsModel, "problems") { savePrompts() } }
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
            "Выберите место для сохранения XML-файла"
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
                "Экспорт успешно завершён:\n${targetFile.absolutePath}",
                "Успешно"
            )
        } catch (e: Exception) {
            logger.error(e)
            Messages.showErrorDialog("Ошибка при экспорте: ${e.message}", "Ошибка")
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
                Messages.showInfoMessage("Импортировано ${imported.size} записей", "Успешно")
            } else {
                Messages.showWarningDialog("Файл не содержит записей", "Предупреждение")
            }
        } catch (e: Exception) {
            logger.error(e)
            Messages.showErrorDialog("Ошибка при импорте: ${e.message}", "Ошибка")
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

    private fun Row.actionButton(icon: Icon, action: () -> Unit) = button("") { action() }
        .customize(UnscaledGaps(left = 8))
        .applyToComponent {
            this.icon = icon
            text = null
            margin = JBUI.emptyInsets()
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            putClientProperty("JButton.buttonType", "square")
        }

    private fun createTooltipRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent
            if (index >= 0 && value != null) c.toolTipText = value.toString()
            return c
        }
    }

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
        if (Messages.showYesNoDialog(
                "$msg «$selected»?",
                "Подтверждение",
                Messages.getQuestionIcon()
            ) == Messages.YES
        ) {
            model.removeElement(selected)
            onSave()
        }
    }
}