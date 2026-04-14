package com.gigasan.localai

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import kotlin.collections.joinToString

class Analyze: AnAction("Analyze Project", "Scan files for AI context", com.intellij.icons.AllIcons.Actions.Refresh) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Запускаем в фоновом режиме, чтобы не было фризов и Exception
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "AI is mapping your project...") {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    // Читать PSI можно только в Read Action
                    com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction {
                        try {

                            val kotlinProjectAnalyzer = KotlinProjectAnalyzer()
                            val rustProjectAnalyzer = RustProjectAnalyzer()

                            val extensions = listOf("rs", "kt")
                            val projectPsiFiles = getProjectPsiFiles(project, extensions)
                            val projectSummary = StringBuilder()

                            projectPsiFiles.forEach { psiFile ->
                                // метод анализа для каждого файла
                                val lang = identifyContext(psiFile)
                                projectSummary.append(kotlinProjectAnalyzer.analyzeKotlinPsiFile(psiFile, true) + "\n\n")
                            }
                            // Вывод результата (возвращаемся в UI поток)
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                // Здесь выведи результат в чат или лог
                                println("Project analysis complete!")
                                ChatPanel.instance?.sendExternalMessage(projectSummary.toString())
                                // К примеру, покажем уведомление
//                                                com.intellij.openapi.ui.Messages.showInfoMessage(
//                                                    project,
//                                                    "Found classes: \n${projectMap.take(500)}...", // берем кусочек для превью
//                                                    "Analysis Result"
//                                                )
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

        when (language) {
            "Kotlin" -> {
                // Ищем KtNamedFunction, KtClass
                val kotlinProjectAnalyzer = KotlinProjectAnalyzer()
                return kotlinProjectAnalyzer.analyzeKotlinPsiFile(file, true)
            }
            "Rust" -> {
                // Используем наш ручной парсер для элементов типа "FUNCTION"
                val rustProjectAnalyzer = RustProjectAnalyzer()
                val pairList = rustProjectAnalyzer.findRustFunctionRanges(file)
                //element_list.forEach().joinToString("\n") { it.content ?: "" }
                //val result = element_list.joinToString("\n ", { (s, r) -> s + " " + r.toString() })
                val result = pairList.joinToString(separator = "\n") { (text, range) ->
                    "$text : $range"
                    }
                return result
                }
            }

        // Базовый поиск по PsiNamedElement
        val rustProjectAnalyzer = RustProjectAnalyzer()
        val universalElementList = rustProjectAnalyzer.getElementsToRefactor(file)
        return universalElementList.joinToString { it.text }
    }

}

