package com.gigasan.ai.actions

import com.intellij.openapi.ui.Messages
import com.gigasan.ai.analysis.KotlinProjectAnalyzer
import com.gigasan.ai.analysis.RustProjectAnalyzer
import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.core.projectHasKotlinSource
import com.gigasan.ai.ui.RefactorDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

class AnalyzeAction : AnAction("Analyze Project", "Scan project and open refactor dialog", AllIcons.Actions.DependencyAnalyzer) {
    private val logger = Logger.getInstance("AnalyzeAction")
    private val settings = PluginSettingsService.instance
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        runProjectAnalysisAndOpenDialog(project)
    }
    override fun update(e: AnActionEvent) {
        val project = e.project ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val prov = DefaultChatConfigProvider(project)
        val model = prov.buildEndpointSetting().selectedModelName

//        val dynamicText = if (model.isNotBlank()) {
//            "Refactor with Local AI ($model)"
//        } else {
//            "Refactor with Local AI (No model selected)"
//        }
//        e.presentation.setText(dynamicText)
        // Можно также выключать кнопку, если модель не выбрана
        e.presentation.isEnabled = model.isNotBlank()
        e.presentation.isEnabledAndVisible = settings.state.enableCodeAnalysis
    }
    private fun runProjectAnalysisAndOpenDialog(project: Project) {
        val isKotlin = projectHasKotlinSource(project)
        val extensions = if (isKotlin) listOf("kt") else listOf("rs")

        // Создаём анализатор **один раз** снаружи
        val projectAnalyzer = if (isKotlin) {
            KotlinProjectAnalyzer()
        } else {
            RustProjectAnalyzer()
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "AI Project Analysis", true) {

                private val summary = StringBuilder()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "Collecting project files..."

                    val projectPsiFiles = ApplicationManager.getApplication().runReadAction<List<PsiFile>> {
                        getProjectPsiFiles(project, extensions)
                    }

                    if (projectPsiFiles.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, "No source files found", "Analysis")
                        }
                        return
                    }

                    val total = projectPsiFiles.size
                    summary.append("=== FULL PROJECT ANALYSIS ===\n")
                    summary.append("Project: ${project.name}\n")
                    summary.append("Files analyzed: $total\n")
                    summary.append("\n")
                    var totalLength: Long = 0
                    var denseLength: Long = 0
                    projectPsiFiles.forEachIndexed { index, psiFile ->
                        if (indicator.isCanceled) throw ProcessCanceledException()

                        val progress = (index + 1.0) / total
                        indicator.fraction = progress
                        indicator.text = "Analyzing ${psiFile.name} (${index + 1}/$total)"

                        totalLength += psiFile.virtualFile.length
                        val fileResult: String = ApplicationManager.getApplication().runReadAction<String> {
                            val dense = projectAnalyzer.analyzePsiFile(psiFile, deep = false)
                            denseLength += dense.length
                            dense
                        }

                        summary.append(fileResult.ifBlank { "// No output from ${psiFile.name}" })
                        summary.append("\n")
                    }
                }

                override fun onSuccess() {
                    ApplicationManager.getApplication().invokeLater {
                        openRefactorDialogWithResult(project, summary.toString())
                    }
                }

                override fun onCancel() {
                    ApplicationManager.getApplication().invokeLater {
                        WindowManager.getInstance().getStatusBar(project)?.info ="Analysis cancelled"
                    }
                }

                override fun onThrowable(error: Throwable) {
                    logger.warn("Analysis failed", error)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Analysis failed: ${error.message}", "Error")
                    }
                }
            }
        )
    }

    private fun openRefactorDialogWithResult(project: Project, analysisResult: String) {
        val dialog = RefactorDialog(project)
        dialog.setTask("Насколько глубоко тебе понятен этот проект? Есть ли не понятные артефакты?")
        dialog.setCode(analysisResult)           // ← вставляем результат анализа

        dialog.showAndGet()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    fun getProjectPsiFiles(project: Project, extensions: List<String>): List<PsiFile> {
        val resultList = mutableListOf<PsiFile>()
        val psiManager = PsiManager.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)

        fileIndex.iterateContent { virtualFile ->
            // 1. Проверяем, что это файл, а не директория, и смотрим расширение
            for (ext in extensions) {
                if (!virtualFile.isDirectory && virtualFile.extension == ext) {

                    // 2. Превращаем VirtualFile в PsiFile
                    val psiFile = psiManager.findFile(virtualFile)

                    if (psiFile != null) {
                        // Просто добавляем файл в список
                        resultList.add(psiFile)
                    }
                }
            }
            true // Продолжать обход
        }

        return resultList
    }

}