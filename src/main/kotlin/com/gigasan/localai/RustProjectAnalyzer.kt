package com.gigasan.localai

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.openapi.util.TextRange
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SimpleColoredComponent
import javax.swing.JTree
import com.intellij.codeInspection.options.OptMultiSelector.OptElement
import com.intellij.lang.Language
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.light.LightElement
import kotlin.sequences.forEach

class UniversalMember(
    val element: PsiElement,
    private val presentationName: String,
    private val parentMember: ClassMember? = null // Добавляем родителя
) : ClassMember, OptElement {

    // 1. Возвращает сам объект как делегат для управления состоянием в дереве
    override fun getParentNodeDelegate(): MemberChooserObject = this

    // 2. Текст, который используется для поиска и идентификации
    override fun getText(): String = presentationName
    // additional information to display alongside the main text for the element
    override fun getSecondaryText(): String? = null // Опционально

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
            override fun getLocationString(): String? = null
            override fun getIcon(unused: Boolean) = element.getIcon(0)
        }
    }

    // У большинства элементов нет родителя в плоском списке выбора
    fun getParent(): ClassMember? = parentMember
}



class RustProjectAnalyzer(): ProjectAnalyzer {
    private val db = RustCodeDatabase("RustProjectAnalyzer")
    private val logger = Logger.getInstance("RustProjectAnalyzer")

    init {
        db.clearData()
    }

    // for analyze
    override fun analyzePsiFile(psiFile: PsiFile, deep: Boolean): String {
//        val res = StringBuilder()
        val res = StringBuilder("Rust File: ${psiFile.name}\n\n")
//        logger.warn("analyzePsiFile: $psiFile")

        val rustRawBlocks = parseRustManually(psiFile)
        //logger.warn("analyzePsiFile: rustRawBlocks=${rustRawBlocks.size}\n\n")
        for (rawBlock in rustRawBlocks) {
            res.append("type: " + rawBlock.type).append("\n")
            res.append("name: " + rawBlock.header).append("\n")
            res.append("length: " + rawBlock.range.length).append("\n")
            res.append("range: " + rawBlock.range).append("\n")
            res.append("functions: " + rawBlock.functions).append("\n")
        }

        return res.toString()
    }

    data class CommentBlock(
        val text: String,
        val isDoc: Boolean,
        val range: IntRange,
    )

    fun findComments(text: String, fileName: String): List<CommentBlock> {
        val comments = mutableListOf<CommentBlock>()
        // Регулярка для // и /* ... */
        val pattern = Regex("""(//.*|/\*[\s\S]*?\*/)""")

        pattern.findAll(text).forEach { match ->
            val isDoc = match.value.startsWith("///") || match.value.startsWith("/**") || match.value.startsWith("```")
            comments.add(CommentBlock(match.value, isDoc, match.range))
        }
        return comments
    }

    data class RustRawBlock(
        val type: String, // "impl", "trait", "struct"
        val header: String,
        val body: String,
        val raw: String,
        val range: TextRange,
        val functions: MutableList<RustRawFunction> = mutableListOf()
    ) {
        var id: Int = 0 // Сюда мы запишем ID из базы
    }

    data class RustRawFunction(
        val name: String,
        val fullRange: TextRange,
        val containerName: String? = null,
        val header: String,
        val body: String,
        val raw: String,
        var isTest: Boolean = false,
    )

    class RustLightElement(manager: PsiManager, language: Language, val range: TextRange, val _text: String)
        : LightElement(manager, language) {
        override fun getText(): String = _text
        override fun getTextRange(): TextRange = range
        override fun toString(): String = "RustElement"
    }

    fun cleanForAi(text: String): String {
        text.lines().filter { it.isNotBlank() }
        return text.replace(Regex("\\s+"), " ")
    }

    fun parseContainers(parsingText: String, sourceText: String, comments: List<CommentBlock>, fileName: String): List<RustRawBlock> {
        val blocks = mutableListOf<RustRawBlock>()

        // 1. Ищем ключевые слова, которые НЕ в комментариях
        val containerRegex = Regex("""(?:impl|trait|struct|enum)\b""", RegexOption.MULTILINE)

        val allMatches = containerRegex.findAll(parsingText).filter { match ->
            comments.none { comment -> match.range.first in comment.range }
        }.sortedBy { it.range.first }

        for (match in allMatches) {
            val startOffset = match.range.first

            // 2. Ищем, ЧТО закрывает заголовок: '{' (блок) или ';' (кортежная структура)
            // Но помним про Grid<{ SIZE }>, поэтому используем твой умный поиск
            val bodyStartOffset = findActualBodyStart(parsingText, startOffset)

            // Если findActualBodyStart не нашел '{', возможно это структура с ';'
            // Проверяем, что идет раньше: '{' (через bodyStartOffset) или ';'
            val semicolonIndex = parsingText.indexOf(';', startOffset)

            val isTupleStruct = semicolonIndex != -1 && (bodyStartOffset == -1 || semicolonIndex < bodyStartOffset)

            val endOffset: Int
            val header: String
            val bodyText: String
            val type: String

            if (isTupleStruct) {
                // Случай: struct MyData(u32);
                endOffset = semicolonIndex + 1
                header = parsingText.substring(startOffset, endOffset).trim()
                bodyText = "" // У кортежных структур нет тела в { }
                type = "struct"
            } else if (bodyStartOffset != -1) {
                // Случай: impl/struct/trait { ... }
                header = parsingText.substring(startOffset, bodyStartOffset).trim()

                // Находим закрывающую скобку для этого блока
                endOffset = findClosingBrace(parsingText, bodyStartOffset)

                // Тело — это всё от { до } (включая скобки)
                bodyText = parsingText.substring(bodyStartOffset, endOffset)

                type = determineType(header)
            } else {
                continue // Какой-то странный случай, пропускаем
            }
            val cleanBody = cleanForAi(bodyText)
            val displayTitle = header.replace(Regex("""\s+"""), " ")
            val shortTitle = displayTitle.substringAfter(type).trim()
            blocks.add(RustRawBlock(type, shortTitle, cleanBody, sourceText.substring(startOffset, endOffset), TextRange(startOffset, endOffset)))
        }
        return blocks
    }

    // Вспомогательная функция для определения типа
    private fun determineType(header: String): String {
        return when {
            header.startsWith("impl") -> "impl"
            header.startsWith("trait") -> "trait"
            header.startsWith("struct") -> "struct"
            header.startsWith("enum") -> "enum"
            else -> "block"
        }
    }


    fun parseFunctions(parsingText: String, sourceText: String, comments: List<CommentBlock>, fileName: String, hideTests: Boolean = true): List<RustRawFunction> {
        val functions = mutableListOf<RustRawFunction>()

        // Ищет атрибуты над функцией и саму функцию
        //val fullFnRegex = Regex("""((?:#\[.*\]\s*)*)fn\s+([a-zA-Z0-9_]+)""", RegexOption.MULTILINE)
        // Улучшенная версия: захватывает атрибуты, даже если их много
        //val fullFnRegex = Regex("""((?:^\s*#\[[^\]]*\]\s*)*)^\s*(?:pub\s+(?:\(.*\)\s+)?)?(?:unsafe\s+)?(?:extern\s+"[^"]*"\s+)?fn\s+([a-zA-Z0-9_]+)""",
        //    setOf(RegexOption.MULTILINE))

        // Используем \s* (жадный поиск пробелов) между частями
        val fullFnRegex = Regex(
            // (?m) включает MULTILINE внутри строки
            """(?m)^""" +
            """(?!.*/{2})""" +                      // <--- ЗАПРЕТ: в строке не должно быть //
            """(\s*(?:#\[[^\]]*\]\s*)*)""" +        // Атрибуты (захватываем отступ)
            """(?:pub\s+(?:\(.*\)\s+)?)?""" +       // Видимость
            """(?:unsafe\s+)?""" +                  // Unsafe
            """(?:extern\s+"[^"]*"\s+)?""" +        // Extern
            """fn\s+([a-zA-Z0-9_]+)"""              // Имя функции
        )
        val allFunctionsMatches = fullFnRegex.findAll(parsingText).sortedBy { it.range.first }
        for (fnMatch in allFunctionsMatches) {
            // ... создаем объект функции ...
            val fnStart = fnMatch.range.first

            // val openBraceOffset = parsingText.indexOf('{', fnStart)
            // 1. Находим реальное начало тела {
            val bodyStart = findFunctionBodyStart(parsingText, fnStart)

            if (bodyStart == -1) {
                // Это либо ошибка, либо функция без тела (в trait)
                // Обрабатываем как "только заголовок"
                continue
            }

            // Находим закрывающую скобку
            //val fnEnd = findClosingBrace(parsingText, openBraceOffset)
            // 2. Находим конец тела через счетчик скобок (уже имеющийся findClosingBrace)
            val fnEnd = findClosingBrace(parsingText, bodyStart)

            // 3. Извлекаем данные
            val header = parsingText.substring(fnStart, bodyStart).trim()
            val body = parsingText.substring(bodyStart, fnEnd)
            val raw = sourceText.substring(fnStart, fnEnd)

            // 4. Имя функции (уже есть в твоем Regex в группе 1 или 2)
            val name = fnMatch.groups[2]?.value ?: "unknown"

            val fnFullRange = TextRange(fnStart, fnEnd)
            //val fnText = parsingText.substring(fnStart, fnEnd)
            //val fnRaw = sourceText.substring(fnStart, fnEnd)
            val function = RustRawFunction(
                name = name,
                fullRange = fnFullRange,
                body = cleanForAi(body),
                header = cleanForAi(header),
                raw = raw,
            )

            val isTest = function.header.contains("#[test]") || function.header.contains("#[tokio::test]")
            if (isTest) {
                function.isTest = true
                functions.add(function)
            }

            logger.warn("parseRustManually fileName=${fileName} function lenght=${fnEnd - fnStart} name=${function.name} header=${function.header} range=${fnStart}-${fnEnd}")

            functions.add(function)
        }
        return functions
    }

    fun prepareTextForParsing(text: String): String {
        // 1. Убираем однострочные комментарии //, заменяя их пробелами до конца строки
        val noSingleComments = text.replace(Regex("""//.*""")) { match ->
            " ".repeat(match.value.length)
        }

        //val cleanLine = line.substringBefore("//").trim()

        // 2. Убираем многострочные комментарии /* ... */, заменяя их пробелами
        // Это критично для твоего блока RenderPassNode!
        val cleanText = noSingleComments.replace(Regex("""/\*[\s\S]*?\*/""")) { match ->
            " ".repeat(match.value.length)
        }

        return cleanText
    }

    fun parseRustManually(file: PsiFile): List<RustRawBlock> {
        val filePath = file.virtualFile.path
        val fileId = db.getOrInsertFileId(filePath)
        val sourceText = file.text
        val blocks = mutableListOf<RustRawBlock>()
        val moduleLevelFunctions = mutableListOf<RustRawFunction>()
        logger.warn("parseRustManually fileName=${file.name} text.length=${sourceText.length}")


        // 0. ищем комментарии
        val comments = findComments(sourceText, file.name)
        logger.warn("parseRustManually fileName=${file.name} detected ${comments.size} comments")
        for (comment in comments) {
            val cont = RustCodeDatabase.CommentResult(
                text = comment.text,
                isDoc = comment.isDoc,
                range = comment.range,
            )
            db.insertComment(fileId, cont)
        }

        // "Прозрачная" копия чистая от комментариев
        val parsingText = prepareTextForParsing(sourceText)

        // 1. Сначала собираем все контейнеры (impl, struct, trait, enum)
        val containers = parseContainers(parsingText, sourceText, comments, file.name)
        logger.warn("parseRustManually fileName=${file.name} detected ${containers.size} containers")
        for (container in containers) {
            val cont = RustCodeDatabase.ContainerResult(
                header = container.header,
                type = container.type,
                body = container.body,
                raw = container.raw,
                range = container.range.toIntRange(),
            )
            container.id = db.insertContainer(fileId, cont)
        }

        // -----------------------------------------------------
        // дальше надо проверять

        // 2. Ищем ВСЕ функции в файле
        val functions = parseFunctions(parsingText, sourceText, comments, file.name)
        logger.warn("parseRustManually fileName=${file.name} detected ${functions.size} functions")

        // 3. Распределяем функции
        for (fn in functions) {
            // Ищем, не лежит ли начало функции внутри какого-то контейнера
            val parent = containers.find { it.range.contains(fn.fullRange.startOffset) }

            if (parent != null && (parent.type == "impl" || parent.type == "trait")) {
                    parent.functions.add(fn)
                    val func = RustCodeDatabase.FunctionResult(
                        name = fn.name,
                        header = fn.header,
                        body = fn.body,
                        raw = fn.raw,
                        range = fn.fullRange.toIntRange(),
                        isTest = fn.isTest,
                    )
                    db.insertFunction(fileId, parent.id, func)
            } else {
                // Свободная функция (module-level)
                val func = RustCodeDatabase.FunctionResult(
                    name = fn.name,
                    header = fn.header,
                    body = fn.body,
                    raw = fn.raw,
                    range = fn.fullRange.toIntRange(),
                    isTest = fn.isTest,
                )
                db.insertFunction(fileId, 0, func)
                moduleLevelFunctions.add(fn)
            }
        }

        logger.warn("parseRustManually fileName=${file.name} detected ${moduleLevelFunctions.size} moduleLevelFunctions")

        return blocks

    }

    fun findFunctionBodyStart(text: String, startOffset: Int): Int {
        var angleDepth = 0   // < >
        var parenDepth = 0   // ( )
        var braceDepth = 0   // { } (внутри сигнатуры)

        for (i in startOffset until text.length) {
            val char = text[i]

            when (char) {
                '<' -> angleDepth++
                '>' -> angleDepth--
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> {
                    // Если мы не внутри < > и не внутри ( ),
                    // значит это НАСТОЯЩЕЕ начало тела функции
                    if (angleDepth <= 0 && parenDepth <= 0) return i
                    else braceDepth++
                }
                '}' -> braceDepth--
                ';' -> {
                    // Если встретили ; вне скобок — это объявление функции в trait без тела
                    if (angleDepth <= 0 && parenDepth <= 0) return -1
                }
            }
        }
        return -1
    }

    fun findActualBodyStart(text: String, startOffset: Int): Int {
        var angleBracketDepth = 0
        for (i in startOffset until text.length) {
            val char = text[i]
            if (char == '<') angleBracketDepth++
            if (char == '>') angleBracketDepth--

            // Если мы не внутри < > и встретили {, это начало тела!
            if (char == '{' && angleBracketDepth <= 0) {
                return i
            }
        }
        return -1
    }

    fun findClosingBrace(text: String, startOffset: Int): Int {
        var balance = 0
        var foundFirst = false
        for (i in startOffset until text.length) {
            if (text[i] == '{') {
                balance++
                foundFirst = true
            } else if (text[i] == '}') {
                balance--
            }
            if (foundFirst && balance == 0) return i + 1
        }
        return startOffset
    }

    // for refactor
    override fun psiFileToMemberChooserList(psiFile: PsiFile): List<UniversalMember> {
        val result = mutableListOf<UniversalMember>()
        val text = psiFile.text

        val filePath = psiFile.virtualFile.path
        val fileId = db.getOrInsertFileId(filePath)
        val sourceText = psiFile.text

        // 0. ищем комментарии
        val comments = findComments(sourceText, psiFile.name)
        logger.warn("parseRustManually fileName=${psiFile.name} detected ${comments.size} comments")
        for (comment in comments) {
            val cont = RustCodeDatabase.CommentResult(
                text = comment.text,
                isDoc = comment.isDoc,
                range = comment.range,
            )
            db.insertComment(fileId, cont)
        }

        logger.warn("psiFileToMemberChooserList fileName=${psiFile.name} text.length=${sourceText.length}")

        // 2. Ищем ВСЕ функции в файле
        val functions = parseFunctions(sourceText, sourceText, comments, psiFile.name)
        logger.warn("psiFileToMemberChooserList fileName=${psiFile.name} detected ${functions.size} functions")


        for (func in functions) {
            val element = psiFile.findElementAt(func.fullRange.startOffset)
            if (element != null) {
                val parent = element.parent // Скорее всего это будет просто текстовый узел
                result.add(UniversalMember(parent, func.name))
            }
        }

        return result
    }

//    Rust реализация - требует rust plugin с библиотеками RsStructItem RsImplItem RsFunction
//    import org.rust.lang.core.psi.RsFunction
//    import org.rust.lang.core.psi.RsStructItem
//    import org.rust.lang.core.psi.RsImplItem
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