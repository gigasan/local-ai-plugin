package com.gigasan.localai

import javax.swing.*
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.ComponentEvent
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter

data class TaskData(
    val id: String,
    var title: String,
    var description: String,
    var content: String,
    var zoneType: String,
    var job: String,
)

class TaskManagerPanel : JPanel() {
    var onTasksChanged: (() -> Unit)? = null

    private val taskList = mutableListOf<TaskData>()

    // Панель с задачами
    private val tasksPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {

        layout = BorderLayout() // Главное исправление: BorderLayout для TaskManagerPanel
        val scrollPane  = JScrollPane(tasksPanel)
        scrollPane .verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane .horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        add(scrollPane, BorderLayout.CENTER)


        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val width = tasksPanel.width
                tasksPanel.components.forEach { comp ->
                    comp.maximumSize = Dimension(width, comp.preferredSize.height)
                }
            }
        })
    }

    // Добавление новой задачи
    fun addTask(task: TaskData, position: Int? = null) {
        val taskPanel = createTaskPanel(task)

        // Максимальная ширина = ширина контейнера
        taskPanel.maximumSize = Dimension(Int.MAX_VALUE, taskPanel.preferredSize.height)

        if (position == null || position >= tasksPanel.componentCount) {
            tasksPanel.add(taskPanel)
            taskList.add(task)
        } else {
            tasksPanel.add(taskPanel, position)
            taskList.add(position, task)
        }

        tasksPanel.revalidate()
        tasksPanel.repaint()
        onTasksChanged?.invoke()
        //ChatPanel.instance?.rebuildMarkdownFromTasks()
    }

    fun updateTask(task: TaskData) {
        onTasksChanged?.invoke()
    }

    fun removeTask(id: String) {
        val index = taskList.indexOfFirst { it.id == id }
        if (index >= 0) {
            tasksPanel.remove(index)
            taskList.removeAt(index)
            tasksPanel.revalidate()
            tasksPanel.repaint()
            onTasksChanged?.invoke()
        }
    }

    private fun createTaskPanel(task: TaskData): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createLineBorder(Color.GRAY, 1)
        //panel.preferredSize = Dimension(0, 40) // фиксируем только высоту

        // Иконка
        val icon = JLabel(task.title)
        icon.border = BorderFactory.createEmptyBorder(0,5,0,5)
        panel.add(icon, BorderLayout.WEST)

        // Центральная часть — вертикальный Box для двух строк
        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)

        fun setup(area: JTextArea, onChange: (String) -> Unit) {
            area.autoscrolls = true
            area.lineWrap = true
            area.wrapStyleWord = true
            area.isEditable = true
            area.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = update()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = update()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = update()

                private fun update() {
                    onChange(area.text)
                    onTasksChanged?.invoke()
                }
            })
        }

        // Верхняя строка — description
//        val descriptionArea = JTextArea(task.description)
//        descriptionArea.lineWrap = false
//        //descriptionArea.wrapStyleWord = true
//        descriptionArea.isEditable = false
//        descriptionArea.maximumSize = Dimension(Int.MAX_VALUE, descriptionArea.preferredSize.height) // фиксируем высоту
//        centerPanel.add(descriptionArea)

        // Нижняя строка — задание для агента
        val agentTaskArea = JTextArea(task.job)
        //agentTaskArea.maximumSize = Dimension(Int.MAX_VALUE, agentTaskArea.preferredSize.height)
//        agentTaskArea.lineWrap = false
//        //agentTaskArea.wrapStyleWord = true
//        agentTaskArea.isEditable = true
        setup(agentTaskArea) { task.job = it }
        centerPanel.add(agentTaskArea)

        panel.add(centerPanel, BorderLayout.CENTER)

        val buttonsPanel = JPanel()
        buttonsPanel.layout = GridLayout(1, 2) // 1 строки, 2 колонка

        val btnSize = Dimension(34, 34)

        val removeBtn = createIconButton("✖", "Удалить")

        //val removeBtn = JButton("❌")
        removeBtn.preferredSize = btnSize
        removeBtn.addActionListener {
            tasksPanel.remove(panel)
            taskList.remove(task)
            tasksPanel.revalidate()
            tasksPanel.repaint()
            onTasksChanged?.invoke()
        }
        buttonsPanel.add(removeBtn)

        //val sendBtn = JButton("➤")
        val sendBtn = createIconButton("➤", "Отправить")
        sendBtn.preferredSize = btnSize
        sendBtn.addActionListener {
            ChatPanel.instance?.sendTask(task)
            tasksPanel.remove(panel)
            taskList.remove(task)
            tasksPanel.revalidate()
            tasksPanel.repaint()
            onTasksChanged?.invoke()
        }
        buttonsPanel.add(sendBtn)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(buttonsPanel, BorderLayout.NORTH)

        panel.add(wrapper, BorderLayout.EAST)

        return panel
    }

    fun addHoverEffect(btn: JButton) {
        btn.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                btn.isContentAreaFilled = true
                btn.background = Color(220, 220, 220)
            }

            override fun mouseExited(e: MouseEvent) {
                btn.isContentAreaFilled = false
            }
        })
    }

    fun createIconButton(symbol: String, tooltip: String? = null): JButton {
        val btn = JButton(symbol)

        btn.isFocusPainted = false
        btn.isBorderPainted = false
        btn.isContentAreaFilled = false
        btn.isOpaque = false

        btn.margin = Insets(2, 6, 2, 6)
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        tooltip?.let { btn.toolTipText = it }

        return btn
    }


    fun getAllTasks(): List<TaskData> {
        // Синхронизируем описание из UI
        tasksPanel.components.forEachIndexed { index, comp ->
            val panel = comp as JPanel
            val centerPanel = panel.components.find { it is JPanel } as? JPanel
            if (centerPanel != null) {
                val textAreas = centerPanel.components.filterIsInstance<JTextArea>()
                //val descriptionArea = textAreas.getOrNull(0)
                val agentTaskArea = textAreas.getOrNull(1)

                //if (descriptionArea != null) taskList[index].description = descriptionArea.text
                if (agentTaskArea != null) taskList[index].job = agentTaskArea.text
            }
        }
        return taskList
    }

    fun getTasksAt(indices: List<Int>): List<TaskData> {
        getAllTasks()
        return indices.mapNotNull { taskList.getOrNull(it) }
    }

    data class IconText(
        val icons: String,  // эмодзи в начале строки
        val text: String    // остальной текст
    )

    fun splitIconsAndText(line: String): IconText {
        // Регекс на все подряд идущие символы категории "Other Symbol" (\p{So}) в начале строки
        val regex = Regex("^\\p{So}+")
        val match = regex.find(line)
        val icons = match?.value ?: ""
        val text = line.removePrefix(icons).trimStart()
        return IconText(icons, text)
    }

}