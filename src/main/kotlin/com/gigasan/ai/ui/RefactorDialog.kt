package com.gigasan.ai.ui

import com.gigasan.ai.analysis.FileHeader
import com.gigasan.ai.analysis.ProjectAnalyzerFactory
import com.gigasan.ai.analysis.UniversalMember
import com.gigasan.ai.analysis.debugName
import com.gigasan.ai.core.projectHasKotlinSource
import com.gigasan.ai.ui.chat.ChatPanel
import com.intellij.codeInsight.generation.PsiMethodMember
import com.intellij.icons.AllIcons
import com.intellij.ide.util.MemberChooser
import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.ide.util.TreeFileChooser
import com.intellij.ide.util.TreeFileChooserFactory
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.LanguageTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBPanel
import org.jetbrains.annotations.NotNull
import org.jetbrains.kotlin.lombok.utils.capitalize
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class RefactorDialog(private val project: Project) : DialogWrapper(project, true) {

    private lateinit var taskField: LanguageTextField
    private lateinit var codeTextField: LanguageTextField

//    private lateinit var codeLen: JBLabel
//    private lateinit var codeLen: JBLabel

    private val logger = Logger.getInstance("RefactorDialog")
    private val splitter = OnePixelSplitter(false, 0.3f) // вертикальный, 30% слева
    private var language: String? = null


    init {
        title = "RefactorDialog AI Refactoring Preparation"
        setOKButtonText("Send to AI")
        logger.info("initialized")
        init()
    }

    override fun createCenterPanel(): JComponent {



        // === ПАНЕЛЬ (текстовое задание) ===
        taskField = LanguageTextField(Language.findLanguageByID("TEXT"), project, "", false)

        taskField.addSettingsProvider { editor ->
            //editor.settings.setRightMargin(editor.calculateVisibleRange().end)
            editor.settings.isLineNumbersShown = true
            editor.settings.isCaretRowShown = true
            editor.settings.isUseSoftWraps = true
            editor.colorsScheme.editorFontName = "JetBrains Mono"
            editor.colorsScheme.editorFontSize = 14
            
            editor.scrollPane.apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = 50
                verticalScrollBar.blockIncrement = 420
                horizontalScrollBar.unitIncrement = 28
            }
        }

        val statsLabel = JLabel("0 символов • 0 слов")



        // === ПАНЕЛЬ (Исходный код) ===
        //codeTextField = LanguageTextField(Language.findLanguageByID("Markdown"), project, "", false)
        codeTextField = LanguageTextField(Language.findLanguageByID("TEXT"), project, "", false)

        val editor = codeTextField.getEditor()
        if (editor != null) {
            editor.getDocument().addDocumentListener(object : DocumentListener {
                public override fun documentChanged(@NotNull event: DocumentEvent) {
                    val text: String = event.document.text
                    val length = text.length

                    println("Символов: $length")
                }
            })
        }

        codeTextField.addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = true
            editor.settings.isIndentGuidesShown = true
            editor.settings.isFoldingOutlineShown = false
            editor.settings.isCaretRowShown = true
            editor.colorsScheme.editorFontName = "JetBrains Mono"
            editor.colorsScheme.editorFontSize = 14

            // ← Всё управление скроллом теперь здесь (встроенный scrollPane редактора)
            editor.scrollPane.apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = 50
                verticalScrollBar.blockIncrement = 420
                horizontalScrollBar.unitIncrement = 28
            }
        }

        codeTextField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val text = event.document.text

                val chars = text.length
                val words = text.trim().takeIf { it.isNotEmpty() }
                    ?.split("\\s+".toRegex())
                    ?.size ?: 0

                statsLabel.text = "$chars символов • $words слов"
            }
        })


        val leftPanel = JBPanel<JBPanel<*>>(BorderLayout())   // обёртка для единообразия
        leftPanel.add(taskField, BorderLayout.CENTER)

        val rightPanel = JBPanel<JBPanel<*>>(BorderLayout())
        val toolbar = createCodeToolbar()
        rightPanel.add(toolbar, BorderLayout.NORTH)
        rightPanel.add(codeTextField, BorderLayout.CENTER)   // ← без внешнего JBScrollPane!
        rightPanel.add(statsLabel, BorderLayout.SOUTH)


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

    fun getGitRoot(project: Project): VirtualFile? {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)

        return vcsManager.allVcsRoots
            .firstOrNull { it.vcs?.name == "Git" }
            ?.path
    }

    fun getGitRootForCurrentFile(project: Project, editor: Editor): VirtualFile? {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
            ?: return null

        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        return vcsManager.getVcsRootObjectFor(file)?.path
    }

    // Обнови createCodeToolbar — добавь новую кнопку
    private fun createCodeToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()

        // === ТВОИ СУЩЕСТВУЮЩИЕ КНОПКИ ===
        val fileImportCodeAction = object : AnAction(
            "Import File", "Import file from project", AllIcons.Actions.ShowCode
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                codeTextField.text += openProjectFileMemberChooser() + "\n"
            }
        }
        actionGroup.add(fileImportCodeAction)

        val cleanCodeAction = object : AnAction(
            "Clean",
            "Clean code area",
            AllIcons.General.Delete
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                codeTextField.text = ""
            }
        }
        actionGroup.add(cleanCodeAction)


        // === НОВАЯ КНОПКА — АНАЛИЗ ПРОЕКТА ===
//        val analyzeProjectAction = object : AnAction(
//            "Analyze Full Project",
//            "Run analysis of all project files",
//            AllIcons.Actions.DependencyAnalyzer
//        ) {
//            override fun actionPerformed(e: AnActionEvent) {
//                startProjectAnalysis()
//            }
//        }
//        actionGroup.add(analyzeProjectAction)

        // ... (твой classImportCodeAction если нужен)

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("RefactorDialogToolbar", actionGroup, true)

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

        val isKotlin = projectHasKotlinSource(project)

        val factory = project.service<ProjectAnalyzerFactory>()
        val projectAnalyzer = factory.create(project)

        if (isKotlin && projectAnalyzer.debugName() != "KotlinAnalyzerFactory") { return "" }
        if (!isKotlin && projectAnalyzer.debugName() != "RustAnalyzerFactory") { return "" }

        val extensions = if (isKotlin) {
            listOf("kt", "java", "js", "kts", "xml", "css", "html", "txt", "svg", "json", "md")
        } else {
            listOf("rs", "toml", "txt", "mtl", "obj", "ron", "xml", "json", "md")
        }

        if (isKotlin) {
            language = "kotlin"
        } else {
            language = "rust"
        }

        val srcVirtualFile = project.baseDir.findChild("src")
            ?: project.baseDir

        val psiManager = PsiManager.getInstance(project)
        val srcPsiDir = psiManager.findDirectory(srcVirtualFile)
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()

        val chooser = TreeFileChooserFactory.getInstance(project)
            .createFileChooser(
                "TreeFileChooserFactory Select file",
                null as PsiFile?,
                null as FileType?,
                object : TreeFileChooser.PsiFileFilter {

                    private val ignoredDirs = setOf(
                        ".git", ".idea", ".gradle", ".kotlin", "gradle", "libs",
                        "node_modules", "build", "dist", "out"
                    )

                    override fun accept(file: PsiFile): Boolean {
                        val vFile = file.virtualFile ?: return false

                        if (vFile.isDirectory) return false

                        // ❌ проверяем родителей (ключевой момент)
                        var parent = vFile.parent
                        while (parent != null) {
                            if (parent.name in ignoredDirs) return false
                            parent = parent.parent
                        }

                        val ext = vFile.extension?.lowercase()
                        return ext in extensions
                    }
                }
            )
        chooser.showDialog()

        val selectedFile = chooser.selectedFile ?: return ""
        logger.info("Selected file: ${selectedFile.name}")
        val selectedExt = selectedFile.virtualFile.extension ?: return ""
        logger.info("Selected ext: $selectedExt")

        val header = FileHeader(selectedFile)
        // 2. Теперь, когда файл есть, парсим его содержимое вручную
        val members:List<UniversalMember> = if (selectedExt == extensions[0]) {
            projectAnalyzer.psiFileToMemberChooserList(header, selectedFile)
        } else {
            projectAnalyzer.rawFileToMemberChooserList(header, selectedFile)
        }

        if (members.isEmpty()) return ""

        // Передаем список напрямую (members уже реализуют ClassMember)
        val memberChooser = MemberChooser(members.toTypedArray(), true, true, project)
        memberChooser.title = "Select ${language?:"".capitalize()} Elements"

        memberChooser.show()
        if (memberChooser.selectedElements?.isNotEmpty() == true) {
            val isCopyJavaDoc = memberChooser.isCopyJavadoc
            val isAnn = memberChooser.isInsertOverrideAnnotation
            val selectedElements = memberChooser.selectedElements
            return selectedElements?.joinToString("\n") {
                it.text
            } ?: ""
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
        ChatPanel.instance?.sendExternalMessage(taskField.text + codeTextField.text)
            //wrapCodeBlock(taskField.text, codeTextField.text, language)
        //)
    }

    override fun doCancelAction() {
        super.doCancelAction()
    }

}