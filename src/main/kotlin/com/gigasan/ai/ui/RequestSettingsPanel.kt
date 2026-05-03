package com.gigasan.ai.ui

import com.gigasan.ai.config.storage.EndpointSettings
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.ProjectSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import kotlin.text.trim
import com.gigasan.ai.config.storage.PromptSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogPanel
import kotlin.collections.component1
import kotlin.collections.component2

data class RequestSettingsPanel(
    // Ссылки на внешние объекты (чтобы можно было обновлять UI из асинхронного кода)
//    var project: Project,
//    var endpointSettings: EndpointSettings,
//    var modelsList: MutableList<Model> = mutableListOf(),
    var promptSettings: PromptSettings,
) {
    val logger = Logger.getInstance("RequestSettingsPanel")




    // Функция-расширение для Panel
    fun createRequestSettingsPanel(components: RequestSettingsPanel): DialogPanel {

        //val projectSettings = ProjectSettings.getInstance(project)

        // Подготавливаем модели (предполагаем, что они доступны через настройки или вынесены)
        val systemsModel = DefaultComboBoxModel(promptSettings.state.systems.toTypedArray())
        val promptsModel = DefaultComboBoxModel(promptSettings.state.prompts.toTypedArray())

        // Сохраняем текущий список в настройки
        fun saveSystems() {
            promptSettings.state.systems.clear()
            for (i in 0 until systemsModel.size) {
                promptSettings.state.systems.add(systemsModel.getElementAt(i))
            }
        }

        // Сохраняем текущий список в настройки
        fun savePrompts() {
            promptSettings.state.prompts.clear()
            for (i in 0 until promptsModel.size) {
                promptSettings.state.prompts.add(promptsModel.getElementAt(i))
            }
        }



        val resultPanel = panel {

            collapsibleGroup("Request Settings") {

                row("Система:") {
                    comboBox(systemsModel)
                        .align(AlignX.FILL) // РАСТЯГИВАЕМ НА ВСЮ ШИРИНУ
                        .resizableColumn()
                        .applyToComponent {
                            isEditable = false
                            setRenderer(createTooltipRenderer())
                        }
                        .bindItem(
                            getter = { components.promptSettings.state.selectedSystem },
                            setter = { newName ->
                                components.promptSettings.state.selectedSystem = newName.orEmpty()
                            }
                        )
                    // Создаем группу кнопок Система
                    actionButton(AllIcons.General.Add) { handleAdd(systemsModel, "Введите текст новой системы:", "Добавление системы") { saveSystems() } }
                    actionButton(AllIcons.Actions.Edit) { handleEdit(systemsModel, "Измените систему:", "Редактирование") { saveSystems() } }
                    actionButton(AllIcons.General.Remove) { handleDelete(systemsModel, "Удалить систему?") { saveSystems() } }
                }

                row("Промпт:") {
                    comboBox(promptsModel)
                        .align(AlignX.FILL) // РАСТЯГИВАЕМ
                        .resizableColumn()
                        .applyToComponent {
                            isEditable = false
                            setRenderer(createTooltipRenderer())
                        }
                        .bindItem(
                            getter = { components.promptSettings.state.selectedPrompt },
                            setter = { newName ->
                                components.promptSettings.state.selectedPrompt = newName.orEmpty()
                            }
                        )
                    // Создаем группу кнопок Промпт
                    actionButton(AllIcons.General.Add) { handleAdd(promptsModel, "Введите текст нового промпта:", "Добавление промпта") { savePrompts() } }
                    actionButton(AllIcons.Actions.Edit) { handleEdit(promptsModel, "Измените промпт:", "Редактирование") { savePrompts() } }
                    actionButton(AllIcons.General.Remove) { handleDelete(promptsModel, "Удалить промпт?") { savePrompts() } }

                }
            }.apply {
                expanded = promptSettings.state.systemExpanded // Разворачиваем группу сразу после создания
                // 2. Слушаем изменения (клик пользователя по стрелочке)
                addExpandedListener { isExpanded ->
                    promptSettings.state.systemExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0))
        }


        fun isModified(): Boolean {
            return resultPanel.isModified()
        }

        fun apply() {
            resultPanel.apply()
        }

        fun reset() {
            resultPanel.reset()
        }

        return resultPanel
    }

    // Вспомогательная функция для создания кнопок в одном стиле
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

    // Вспомогательная функция для рендерера с тултипом
    private fun createTooltipRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent
            if (index >= 0 && value != null) c.toolTipText = value.toString() else c.toolTipText = null
            return c
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
