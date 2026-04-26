package com.gigasan.ai.analysis

import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInspection.options.OptMultiSelector.OptElement
import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.platform.workspace.storage.annotations.Parent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import javax.swing.Icon
import javax.swing.JTree

//// 1. Создаем простой объект-контейнер
//class FileContainer(val name: String, val icon: Icon?) : MemberChooserObject {
//    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
//        component.append(name)
//        component.icon = icon
//    }
//    override fun getParentNodeDelegate(): MemberChooserObject? = null
//    override fun getText(): String = name
//}


// 1. Создаем простой объект для заголовка (Файла)
class FileHeader(val psiFile: PsiFile) : MemberChooserObject, ClassMember {
    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        component.append(psiFile.name)
        component.icon = psiFile.getIcon(0)
    }
    override fun getParentNodeDelegate(): MemberChooserObject? = null
    override fun getText(): String = psiFile.name
}


class SimpleGroupHeader(
    private val name: String,
    private val groupIcon: Icon? = null
) : MemberChooserObject, ClassMember {

    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        component.append(name)
        component.icon = groupIcon ?: AllIcons.Nodes.Folder // Иконка папки по умолчанию
    }

    // Заголовок группы сам не имеет родителя
    override fun getParentNodeDelegate(): MemberChooserObject? = null

    override fun getText(): String = name

    // Важно для корректного сравнения в дереве
    override fun equals(other: Any?): Boolean = (other as? SimpleGroupHeader)?.name == name
    override fun hashCode(): Int = name.hashCode()
}

class UniversalMember(
    val anchorElement: PsiElement,
    private var presentationName: String,
    private val rawContent: String, // Передаем текст из RustRawFunction напрямую
    private val parentHeader: MemberChooserObject, // Передаем общий заголовок
    private val icon: Icon ? = null
) : ClassMember, MemberChooserObject {
    // Внутри UniversalMember
    private val sizeKb = rawContent.length.toFloat() / 1024.0
    private val displaySize: String = String.format("%.2f KB", sizeKb)

    // 1. Возвращает сам объект как делегат для управления состоянием в дереве
    override fun getParentNodeDelegate(): MemberChooserObject = parentHeader

    // 2. Текст, который используется для поиска и идентификации
    override fun getText(): String = rawContent

    // additional information to display alongside the main text for the element
    override fun getSecondaryText(): String? {
        // Показываем превью первых 100 символов
        return rawContent.take(100).replace("\n", " ") + "..."
    }

    // 3. Отрисовка элемента в списке (иконка + текст)
    override fun renderTreeNode(component: SimpleColoredComponent, tree: JTree) {
        // Основное имя жирным или обычным
        component.append(presentationName)

        // Добавляем размер серым цветом (как в IntelliJ)
        component.append("  ($displaySize)", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        // Если это целый файл, добавим пометку
        if (anchorElement is PsiFile) {
            component.append(" [Full Text]", SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES)
        }

        tree.toolTipText = rawContent.take(100).replace("\n", " ") + "..."

        component.icon = icon?: anchorElement.getIcon(0)
    }
}
