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
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.ui.JBColor
import org.jetbrains.kotlin.training.ift.kotlinLanguageId
import javax.swing.*
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.openapi.actionSystem.*
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.ide.util.MemberChooser // Для выбора функций
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile



class RefactorDialog(private val project: Project): DialogWrapper(project, true) {
    private lateinit var languageTextField: LanguageTextField
    private lateinit var taskField: LanguageTextField
    private val logger = Logger.getInstance("RefactorDialog")

    // Splitter — это контейнер, который сам управляет пропорциями сторон
    private val splitter = OnePixelSplitter(false, 0.6f) // false = вертикальный разделитель, 0.6 = 60% слева

    init {
        title = "AI Refactoring Preparation"
        setOKButtonText("Send to AI")
        logger.info("RefactorDialog initialized")
        init()
    }

    private fun openProjectMemberChooser() {
        val chooserFactory = TreeClassChooserFactory.getInstance(project)

        // Создаем диалог выбора класса
        val chooser = chooserFactory.createAllProjectScopeChooser("Select Class to Refactor")

        chooser.showDialog()
        val selectedClass = chooser.selected ?: return

        // Теперь, когда класс выбран, предложим выбрать метод (функцию)
        val methods = selectedClass.methods
        if (methods.isEmpty()) {
            languageTextField.text = selectedClass.text
            return
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
                languageTextField.text = combinedText
            } else {
                languageTextField.text = selectedClass.text
            }
        }
    }

    // =========================================================================

    override fun createCenterPanel(): JComponent {
        // --- Левая часть: Код + Тулбар ---
        languageTextField = LanguageTextField(Language.findLanguageByID("kotlin"), project, "", false)
        val leftPanel = JPanel(BorderLayout())
        val toolbar = createCodeToolbar() // Создаем панель с кнопкой выбора
        leftPanel.add(toolbar, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(languageTextField), BorderLayout.CENTER)

        // --- Правая часть: Задание ---
        taskField = LanguageTextField(Language.findLanguageByID("TEXT"), project, "", false)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = JBScrollPane(taskField)
        splitter.dividerWidth = 3

        return splitter
    }

    private fun createCodeToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()

        // Кнопка выбора файла/класса
        val selectAction = object : AnAction("Import Code", "Select class or function from project", AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) {
                openProjectMemberChooser()
            }
        }

        actionGroup.add(selectAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("RefactorDialogToolbar", actionGroup, true)
        toolbar.targetComponent = languageTextField

        val component = EditorHeaderComponent()
        component.add(toolbar.component)
        return component
    }

    override fun getInitialSize(): Dimension? {
        // Динамический расчет размера: 80% от окна IDE
        val ideWindow = WindowManager.getInstance().getFrame(project)
        return if (ideWindow != null) {
            Dimension((ideWindow.width * 0.8).toInt(), (ideWindow.height * 0.7).toInt())
        } else {
            super.getInitialSize()
        }
    }

    // Метод для программной установки кода перед показом
    fun setCode(code: String) {
        languageTextField.text = code
    }
    fun setTask(text: String) {
        taskField.text = text
    }

    fun getTask(): String = taskField.text
    fun getModifiedCode(): String = languageTextField.text

    override fun doOKAction() {
        super.doOKAction()
        // Здесь можно добавить логику при нажатии OK
        ChatPanel.instance?.sendExternalMessage(languageTextField.text)
    }

    override fun doCancelAction() {
        super.doCancelAction()
    }

}