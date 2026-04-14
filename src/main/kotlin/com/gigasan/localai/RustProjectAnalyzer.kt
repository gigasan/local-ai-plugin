package com.gigasan.localai


//import org.rust.lang.core.psi.RsFunction
//import org.rust.lang.core.psi.RsStructItem
//import org.rust.lang.core.psi.RsImplItem

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.openapi.util.TextRange
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiNamedElement
import com.intellij.ui.SimpleColoredComponent
import javax.swing.Icon
import javax.swing.JTree


private val LOG = Logger.getInstance("RustProjectAnalyzer")


class UniversalMember(
    val element: PsiElement,
    private val presentationName: String
) : ClassMember {

    // 1. Возвращает сам объект как делегат для управления состоянием в дереве
    override fun getParentNodeDelegate(): MemberChooserObject = this

    // 2. Текст, который используется для поиска и идентификации
    override fun getText(): String = presentationName

    // 3. Отрисовка элемента в списке (иконка + текст)
    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        component.append(presentationName)
        component.icon = element.getIcon(0)
    }

    // 4. Стандартная презентация (нужна для совместимости)
    fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String = presentationName
            override fun getLocationString(): String? = null
            override fun getIcon(unused: Boolean) = element.getIcon(0)
        }
    }

    // У большинства элементов нет родителя в плоском списке выбора
    fun getParent(): ClassMember? = null
}



class RustProjectAnalyzer() {

    fun findRustFunctionRanges(file: PsiFile): List<Pair<String, TextRange>> {
        val result = mutableListOf<Pair<String, TextRange>>()

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                // В RustTokenType функции обычно называются "FUNCTION" или "fn"
                // Проверяем через отладку: element.node.elementType.toString()
                if (element.node.elementType.toString().contains("FUNCTION", ignoreCase = true)) {
                    val name = element.firstChild?.nextSibling?.text ?: "unknown_fn"
                    result.add(name to element.textRange)
                }
                super.visitElement(element)
            }
        })
        return result
    }

    fun collectRustStructure(file: PsiFile): List<UniversalMember> {
        val members = mutableListOf<UniversalMember>()

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = element.node.elementType.toString()

                // Простая логика: ищем функции, структуры и импорты по именам типов в дереве
                when {
                    type.contains("FUNCTION") -> {
                        // Ищем имя: обычно это следующий за 'fn' токен
                        val name = element.children.firstOrNull { it.text != "fn" && it.text.isNotBlank() }?.text ?: "fn"
                        members.add(UniversalMember(element, "Function: $name"))
                    }
                    type.contains("STRUCT_ITEM") -> {
                        val name = element.children.firstOrNull { it.text != "struct" && it.text.isNotBlank() }?.text ?: "struct"
                        members.add(UniversalMember(element, "Struct: $name"))
                    }
                    type.contains("USE_ITEM") -> {
                        members.add(UniversalMember(element, "Import: ${element.text.take(30)}..."))
                    }
                }
                // super.visitElement(element) // Если хочешь искать только на верхнем уровне, убери это
            }
        })
        return members
    }

    fun getElementsToRefactor(file: PsiFile): List<UniversalMember> {
        val result = mutableListOf<UniversalMember>()
        val langId = file.language.id

        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val typeName = element.node.elementType.toString().uppercase()

                val isFunction = when(langId) {
                    "Rust" -> typeName.contains("FUNCTION") || typeName == "FN"
                    "Kotlin" -> typeName == "FUN"
                    else -> typeName.contains("METHOD")
                }

                if (isFunction) {
                    // Пытаемся найти имя элемента (обычно это PsiNamedElement)
                    val name = (element as? PsiNamedElement)?.name ?: "Unnamed"
                    result.add(UniversalMember(element, "[$langId] $name"))
                }

                // Для Rust не идем глубже функций, чтобы не собирать внутренние переменные
                if (!isFunction) super.visitElement(element)
            }
        })
        return result
    }

//    Rust реализация - требует подключенной библиотеки с элементами RsStructItem RsImplItem RsFunction
//    fun getFileStructure(psiFile: PsiFile): String {
//        val structure = StringBuilder("Structure of ${psiFile.name}:\n")
//
//        // 1. Ищем все структуры (struct)
//        val structs = PsiTreeUtil.findChildrenOfType(psiFile, RsStructItem::class.java)
//        structs.forEach {
//            structure.append("  Struct: ${it.name}\n")
//        }
//
//        // 2. Ищем все реализации (impl), чтобы вытащить методы
//        val impls = PsiTreeUtil.findChildrenOfType(psiFile, RsImplItem::class.java)
//        impls.forEach { impl ->
//            val typeName = impl.typeReference?.text ?: "Unknown"
//            structure.append("  Implementation for $typeName:\n")
//
//            // Вытаскиваем функции внутри impl
//            impl.members?.functionList?.forEach { function ->
//                structure.append("    - Method: ${function.name}${function.valueParameterList?.text ?: "()"}\n")
//            }
//        }
//
//        // 3. Свободные функции (вне структур)
//        val functions = PsiTreeUtil.findChildrenOfType(psiFile, RsFunction::class.java)
//        functions.filter { it.parent == psiFile }.forEach {
//            structure.append("  Standalone Function: ${it.name}\n")
//        }
//
//        return structure.toString()
//    }



}