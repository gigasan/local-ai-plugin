package com.gigasan.ai.ui

import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.config.storage.WorkItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.ide.progress.ModalTaskOwner.project
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import java.awt.Component
import javax.swing.JList
import javax.swing.ListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.GridLayout



class AdvancedPluginRenderer : SimpleColoredComponent(), ListCellRenderer<WorkItem> {
    private val logger = Logger.getInstance("AdvancedPluginRenderer")
    init {
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: JList<out WorkItem>,
        value: WorkItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        clear()
        icon = when (value.iconType) {
            "Add" -> AllIcons.General.Add
            "Remove" -> AllIcons.General.Remove
            "Delete" -> AllIcons.General.Delete
            "Web" -> AllIcons.General.Web
            else -> AllIcons.General.TodoDefault
        }
        append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("by ${value.author}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        background = if (isSelected) list.selectionBackground else list.background
        return this
    }
}

class MyTwoLineRenderer(fontName: String, fontSize: Int) : JPanel(BorderLayout()), ListCellRenderer<WorkItem> {
    private val titleComponent = SimpleColoredComponent()
    private val authorComponent = SimpleColoredComponent()
    private val iconLabel = JLabel()
    private val customFont = Font(fontName, Font.PLAIN, fontSize)

    init {
        isOpaque = true
        layout = BorderLayout()

        titleComponent.font = customFont

        // Создаем текстовый блок (две строки)
        val textPanel = JPanel(GridLayout(2, 1)).apply {
            isOpaque = false
            add(titleComponent)
            add(authorComponent)
        }

        add(iconLabel, BorderLayout.WEST)
        add(textPanel, BorderLayout.CENTER)
        border = JBUI.Borders.empty(4, 6)
    }

    override fun getListCellRendererComponent(
        list: JList<out WorkItem>,
        value: WorkItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        titleComponent.clear()
        authorComponent.clear()

        // 1. Первая строка
        titleComponent.append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)

        // 2. Вторая строка
        authorComponent.append("by ${value.author}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        // Иконка
        iconLabel.icon = when (value.iconType) {
            "Add" -> AllIcons.General.Add
            else -> AllIcons.General.TodoDefault
        }

        // Цвета фона
        val bg = if (isSelected) list.selectionBackground else list.background
        background = bg
        // Чтобы вложенные панели не перекрывали фон
        titleComponent.background = bg
        authorComponent.background = bg

        return this
    }
}