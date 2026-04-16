package com.gigasan.localai

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import kotlin.collections.joinToString

interface ProjectAnalyzer {
    fun analyzePsiFile(psiFile: PsiFile, deep: Boolean): String
    fun psiFileToMemberChooserList(psiFile: PsiFile): List<UniversalMember>
}

fun projectHasKotlinSource(project: Project): Boolean {
    var found = false

    ProjectRootManager.getInstance(project)
        .fileIndex
        .iterateContent { file ->
            if (file.extension == "kt") {
                found = true
                false // остановить обход
            } else {
                true // продолжить
            }
        }

    return found
}

class AnalyzeAction: AnAction("Analyze Project", "Scan Project files for AI context", com.intellij.icons.AllIcons.Actions.DependencyAnalyzer) {

    private val logger = Logger.getInstance("AnalyzeAction")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Запускаем в фоновом режиме, чтобы не было фризов и Exception
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "AI is mapping your project...") {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    // Читать PSI можно только в Read Action
                    com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
                        try {
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
                            logger.warn("isKotlin=$isKotlin")
                            val projectPsiFiles = getProjectPsiFiles(project, extensions)
                            val projectSummary = StringBuilder()

                            logger.warn("Start Project alanyze=${project.name}")
                            projectPsiFiles.forEach { psiFile ->
                                // метод анализа для каждого файла
                                //val lang = identifyContext(psiFile)
                                logger.warn("Start File alanyze=${psiFile.name}")
                                projectSummary.append(projectAnalyzer.analyzePsiFile(psiFile, true) + "\n\n")
                            }

                            // Вывод результата (возвращаемся в UI поток)
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                logger.warn("Project analysis complete!")
                                ChatPanel.instance?.sendExternalMessage(projectSummary.toString())
                                // К примеру, покажем уведомление
                                //com.intellij.openapi.ui.Messages.showInfoMessage(
                                //    project,
                                //    "Found classes: \n${projectMap.take(500)}...", // берем кусочек для превью
                                //    "Analysis Result"
                                //)
                            }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }
        )
    }

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


    fun getProjectMap(project: Project, extensions: List<String>): String {
        val map = StringBuilder()
        val psiManager = PsiManager.getInstance(project)

        // Индекс всех файлов в проекте
        ProjectFileIndex.getInstance(project).iterateContent { virtualFile ->
            // Проверяем расширение

            for (ext in extensions) {
                if (virtualFile.extension == ext) {
                    // Превращаем VirtualFile в PsiFile
                    val psiFile = psiManager.findFile(virtualFile)

                    if (psiFile != null) {
                        map.append("\nFile: ${psiFile.name}\n")

                        // Вот теперь можно запустить Visitor внутри конкретного файла
                        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                            override fun visitElement(element: PsiElement) {
                                super.visitElement(element)
                                // Тут будем вытаскивать классы и методы позже
//                                map.append(
//                                    analyzePsiFile(
//                                        element.containingFile,
//                                        analysis = false
//                                    )
//                                )

                            }
                        })
                    }
                }
            }
            true // Продолжать обход следующего файла
        }
        return map.toString()
    }

    fun identifyContext(file: PsiFile): String {
        val language = file.language.id // Возвратит "Kotlin", "Rust", "JAVA" и т.д.
        val virtualFile = file.virtualFile
        logger.info("language=$language")

        when (language) {
            "Kotlin" -> {
                // Ищем KtNamedFunction, KtClass
                val kotlinProjectAnalyzer = KotlinProjectAnalyzer()
                return kotlinProjectAnalyzer.analyzePsiFile(file, true)
            }
            "Rust" -> {
                // Используем наш ручной парсер для элементов типа "FUNCTION"
                val rustProjectAnalyzer = RustProjectAnalyzer()
                return rustProjectAnalyzer.analyzePsiFile(file, true)
                //val pairList = rustProjectAnalyzer.findRustFunctionRanges(file)
                //element_list.forEach().joinToString("\n") { it.content ?: "" }
                //val result = element_list.joinToString("\n ", { (s, r) -> s + " " + r.toString() })
                //val result = pairList.joinToString(separator = "\n") { (text, range) ->
                    //"$text : $range"
                    //}
                //return result
                }
            }

        // Базовый поиск по PsiNamedElement
    //        val rustProjectAnalyzer = RustProjectAnalyzer()
    //        val universalElementList = rustProjectAnalyzer.getElementsToRefactor(file)
    //        return universalElementList.joinToString { it.text }
        return file.name
    }

}

