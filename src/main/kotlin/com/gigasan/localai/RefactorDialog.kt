package com.gigasan.localai

import com.intellij.codeInsight.generation.PsiMethodMember
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.LanguageTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ide.util.MemberChooser // Для выбора функций
import com.intellij.ide.util.TreeFileChooserFactory
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBPanel
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
        logger.info("initialized")
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

        val fileImportCodeAction = object : AnAction(
            "TreeFileChooserFactory Import Code",
            "Select class or function from project",
            AllIcons.Actions.ShowCode
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                codeTextField.text += openProjectFileMemberChooser() + "\n"
                //codeTextField.text + "\n"
            }
        }
        actionGroup.add(fileImportCodeAction)

        if (PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))) {
            // использовать TreeClassChooserFactory
            val classImportCodeAction = object : AnAction(
                "TreeClassChooserFactory Import Code",
                "Select class or function from project",
                AllIcons.Actions.AddMulticaret
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    codeTextField.text += openProjectClassMemberChooser().toString() + "\n"
                    //codeTextField.text + "\n"
                }
            }
            actionGroup.add(classImportCodeAction)
        } else {
            // fallback UI
        }

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
    // Внутри твоего RefactorDialog
    override fun doValidate(): ValidationInfo? {
        logger.info("doValidate ")
        if (codeTextField.text.isBlank()) {
            return ValidationInfo("Please select some code first", codeTextField)
        }
        return null // null означает, что всё хорошо, кнопка OK активна
    }

    private fun openProjectFileMemberChooser(): String {
        logger.info("openProjectMemberChooser Opening File Chooser for Rust/Kotlin")

        val fileChooserFactory = TreeFileChooserFactory.getInstance(project)

        // 1. Создаем диалог выбора ФАЙЛА (он точно отобразится)
        val chooser = fileChooserFactory.createFileChooser(
            "openProjectMemberChooser Select File to Refactor",
            null, // начальный файл
            null, // тип файла (можно ограничить через FileType)
            null  // фильтр
        )

        chooser.showDialog()

        val selectedFile = chooser.selectedFile ?: return ""
        logger.info("Selected file: ${selectedFile.name}")

        val isKotlin = projectHasKotlinSource(project)
        val projectAnalyzer = if (isKotlin) {
            KotlinProjectAnalyzer()
        } else {
            RustProjectAnalyzer()
        }

        val extensions = if (isKotlin) {
            listOf("kt")
        } else {
            listOf("rs")
        }
        //val projectAnalyzer = RustProjectAnalyzer()

        // 2. Теперь, когда файл есть, парсим его содержимое вручную
        val members:List<UniversalMember> = projectAnalyzer.psiFileToMemberChooserList(selectedFile)

        if (members.isEmpty()) return ""

        // Передаем список напрямую (members уже реализуют ClassMember)
        val memberChooser = MemberChooser(members.toTypedArray(), true, true, project)
        memberChooser.title = "Select Rust/Kotlin Elements"

        if (memberChooser.showAndGet()) {
            val selectedElements = memberChooser.selectedElements
            return selectedElements?.joinToString("\n\n") { it.text } ?: ""
        }
        return ""
    }


    private fun openProjectClassMemberChooser() {
        logger.info("openProjectMemberChooserAuto Opening File Chooser for Rust/Kotlin")
        val chooserFactory = TreeClassChooserFactory.getInstance(project)

        // Создаем диалог выбора класса
        val chooser = chooserFactory.createAllProjectScopeChooser("Select Class to Refactor")

        chooser.showDialog()
        val selectedClass = chooser.selected ?: return

        // Теперь, когда класс выбран, предложим выбрать метод (функцию)
        val methods = selectedClass.methods
        if (methods.isEmpty()) {
            codeTextField.text = selectedClass.text
            return
        }

        // Оборачиваем PsiMethod в PsiMethodMember
        val methodMembers = methods.map { PsiMethodMember(it) }.toTypedArray()

        // Вызываем стандартное окно выбора методов
        val memberChooser = MemberChooser(methodMembers, true, true, project)
        memberChooser.title = "openProjectMemberChooserAuto Select Methods to Refactor"

        if (memberChooser.showAndGet()) {
            val selectedMethods = memberChooser.selectedElements
            val combinedText = selectedMethods?.joinToString("\n\n") { it.text }
            if (!combinedText.isNullOrEmpty()) {
                codeTextField.text = combinedText
            } else {
                codeTextField.text = selectedClass.text
            }
        }
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