package com.gigasan.localai

import com.intellij.codeInsight.generation.PsiMethodMember
import com.intellij.icons.AllIcons
import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.EditorHeaderComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.LanguageTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ide.util.MemberChooser // Для выбора функций
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.components.JBComponent
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class RefactorDialog(private val project: Project) : DialogWrapper(project, true) {

    private lateinit var codeTextField: LanguageTextField
    private lateinit var taskField: LanguageTextField
    private val logger = Logger.getInstance("RefactorDialog")

    private val splitter = OnePixelSplitter(false, 0.6f) // вертикальный, 60% слева

    init {
        title = "AI Refactoring Preparation"
        setOKButtonText("Send to AI")
        logger.info("RefactorDialog initialized")
        init()
    }

    override fun createCenterPanel(): JComponent {

        // === ПАНЕЛЬ (текстовое задание) ===
        taskField = LanguageTextField(Language.findLanguageByID("TEXT"), project, "", false)

        taskField.addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = true
            editor.settings.isCaretRowShown = true

            editor.scrollPane.apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = 50
                verticalScrollBar.blockIncrement = 420
                horizontalScrollBar.unitIncrement = 28
            }
        }

        // === ПАНЕЛЬ (Исходный код) ===
        codeTextField = LanguageTextField(Language.findLanguageByID("kotlin"), project, "", false)

        codeTextField.addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = true
            editor.settings.isIndentGuidesShown = true
            editor.settings.isFoldingOutlineShown = false
            editor.settings.isCaretRowShown = true

            // ← Всё управление скроллом теперь здесь (встроенный scrollPane редактора)
            editor.scrollPane.apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = 50
                verticalScrollBar.blockIncrement = 420
                horizontalScrollBar.unitIncrement = 28
            }
        }

        val leftPanel = JBPanel<JBPanel<*>>(BorderLayout())   // обёртка для единообразия
        leftPanel.add(taskField, BorderLayout.CENTER)


        val rightPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val toolbar = createCodeToolbar()
        rightPanel.add(toolbar, BorderLayout.NORTH)
        rightPanel.add(codeTextField, BorderLayout.CENTER)   // ← без внешнего JBScrollPane!


        // === SPLITTER ===
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        splitter.dividerWidth = 2
        splitter.autoscrolls = true

        // Минимальные размеры (чтобы не схлопывалось совсем)
        leftPanel.minimumSize = Dimension(200, 200)
        rightPanel.minimumSize = Dimension(250, 200)

        return splitter
    }

    private fun createCodeToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()

        val selectAction = object : AnAction(
            "Import Code",
            "Select class or function from project",
            AllIcons.Actions.AddMulticaret
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                codeTextField.text += openProjectMemberChooser()
            }
        }
        actionGroup.add(selectAction)

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("RefactorDialogToolbar", actionGroup, true)

        // ← КРИТИЧНО ИСПРАВЛЕНО
        toolbar.orientation = SwingConstants.HORIZONTAL
        toolbar.targetComponent = codeTextField

        return toolbar.component
    }

    override fun getInitialSize(): Dimension? {
        val ideWindow = WindowManager.getInstance().getFrame(project)
        return if (ideWindow != null) {
            Dimension((ideWindow.width * 0.85).toInt(), (ideWindow.height * 0.75).toInt())
        } else {
            super.getInitialSize()
        }
    }

    override fun show() {
        super.show()
        // Даём диалогу полностью отобразиться и только потом фокусируем редактор
        SwingUtilities.invokeLater {
            taskField.requestFocusInWindow()
        }
    }

    // =========================================================================



    private fun openProjectMemberChooser(): String {
        val chooserFactory = TreeClassChooserFactory.getInstance(project)

        // Создаем диалог выбора класса
        val chooser = chooserFactory.createAllProjectScopeChooser("Select Class to Refactor")

        chooser.showDialog()
        val selectedClass = chooser.selected ?: return ""

        // Теперь, когда класс выбран, предложим выбрать метод (функцию)
        val methods = selectedClass.methods
        if (methods.isEmpty()) {

            return selectedClass.text
        }

        // Оборачиваем PsiMethod в PsiMethodMember
        val methodMembers = methods.map { PsiMethodMember(it) }.toTypedArray()

        // Вызываем стандартное окно выбора методов
        val memberChooser = MemberChooser(methodMembers, true, true, project)
        memberChooser.title = "Select Methods to Refactor"

        if (memberChooser.showAndGet()) {
            val selectedMethods = memberChooser.selectedElements
            val combinedText = selectedMethods?.joinToString("\n\n") { it.text }
            if (!combinedText.isNullOrEmpty()) {
                return combinedText
            } else {
                return selectedClass.text
            }
        }
        return ""
    }



    // Метод для программной установки кода перед показом
    fun setCode(code: String) {
        codeTextField.text = code
    }
    fun setTask(text: String) {
        taskField.text = text
    }

    fun getTask(): String = taskField.text
    fun getModifiedCode(): String = codeTextField.text

    override fun doOKAction() {
        super.doOKAction()
        // Здесь можно добавить логику при нажатии OK
        ChatPanel.instance?.sendExternalMessage(codeTextField.text)
    }

    override fun doCancelAction() {
        super.doCancelAction()
    }

}