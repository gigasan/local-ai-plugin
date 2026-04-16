package com.gigasan.ai.analysis

import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInspection.options.OptMultiSelector.OptElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleColoredComponent
import javax.swing.JTree

class UniversalMember(
    val element: PsiElement,
    private var presentationName: String,
    private var parentMember: ClassMember? = null // Добавляем родителя
) : ClassMember, OptElement {

    // 1. Возвращает сам объект как делегат для управления состоянием в дереве
    override fun getParentNodeDelegate(): MemberChooserObject = this

    // 2. Текст, который используется для поиска и идентификации
    override fun getText(): String {
        return element.text
    }

    // additional information to display alongside the main text for the element
    override fun getSecondaryText(): String {
        return "опционально"
    }

//    override fun getOptionsPane(): OptPane {
//        val members: List<UniversalMember> = // ваш список
//            return OptPane.pane(
//                OptMultiSelector(
//                    "myBindId",
//                    members, // Теперь это валидно, так как UniversalMember реализует OptElement
//                    OptMultiSelector.SelectionMode.MULTIPLE
//                )
//            )
//    }

    // 3. Отрисовка элемента в списке (иконка + текст)
    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        component.append(presentationName)
        component.icon = element.getIcon(0)
    }

    // 4. Стандартная презентация (нужна для совместимости)
    fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String = presentationName
            override fun getLocationString(): String = "location string"
            override fun getIcon(unused: Boolean) = element.getIcon(0)
        }
    }

    // У большинства элементов нет родителя в плоском списке выбора
    fun getParent(): ClassMember? = parentMember
}
