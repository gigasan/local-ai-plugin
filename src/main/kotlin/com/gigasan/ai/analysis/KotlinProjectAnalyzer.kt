package com.gigasan.ai.analysis

import com.gigasan.ai.core.toHumanReadableSize
import com.gigasan.ai.ui.chat.HtmlProcessor.wrapCode
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.base.KaKeywordsRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaAnnotationRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationArgumentsRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationListRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationQualifierRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationUseSiteTargetRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.contextReceivers.KaContextReceiversRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KaRendererTypeApproximator
import org.jetbrains.kotlin.analysis.api.renderer.types.KaExpandedTypeRenderingMode
import org.jetbrains.kotlin.analysis.api.renderer.types.KaTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaCapturedTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaClassTypeQualifierRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaDefinitelyNotNullTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaDynamicTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaErrorTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaFlexibleTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaFunctionalTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaIntersectionTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaTypeNameRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaTypeParameterTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaTypeProjectionRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaUnresolvedClassErrorTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaUsualClassTypeRenderer
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

// Kotlin
class KotlinProjectAnalyzer: ProjectAnalyzer, DeepProjectAnalyzer {

    private val logger = Logger.getInstance("KotlinProjectAnalyzer")

    @OptIn(KaExperimentalApi::class)
    private val kaAnnotationsRenderer = KaAnnotationRenderer.Companion {
        annotationListRenderer = KaAnnotationListRenderer.FOR_SOURCE
        annotationFilter = KaRendererAnnotationsFilter.NONE // Или .ALL, если нужны @Override/@Internal
        annotationsQualifiedNameRenderer = KaAnnotationQualifierRenderer.WITH_SHORT_NAMES
        annotationUseSiteTargetRenderer = KaAnnotationUseSiteTargetRenderer.WITH_NON_DEFAULT_USE_SITE
        annotationArgumentsRenderer = KaAnnotationArgumentsRenderer.IF_ANY
    }

    @OptIn(KaExperimentalApi::class)
    private val kaTypeRenderer = KaTypeRenderer.Builder().apply {
        // Показываем алиасы с пояснением: TaskId /* String */
        expandedTypeRenderingMode = KaExpandedTypeRenderingMode.RENDER_ABBREVIATED_TYPE_WITH_EXPANDED_TYPE_COMMENT

        // Короткие имена классов: AnActionEvent вместо com.intellij...AnActionEvent
        classIdRenderer = KaClassTypeQualifierRenderer.WITH_SHORT_NAMES
        usualClassTypeRenderer = KaUsualClassTypeRenderer.AS_CLASS_TYPE_WITH_TYPE_ARGUMENTS

        // Аннотации: делаем их короткими, чтобы не забивать лог
        //annotationsRenderer = KaAnnotationRendererForSource.WITH_SHORT_NAMES
        annotationsRenderer = kaAnnotationsRenderer

        // Стандартные представления для сложных типов
        capturedTypeRenderer = KaCapturedTypeRenderer.AS_PROJECTION
        definitelyNotNullTypeRenderer = KaDefinitelyNotNullTypeRenderer.AS_TYPE_INTERSECTION
        dynamicTypeRenderer = KaDynamicTypeRenderer.AS_DYNAMIC_WORD
        flexibleTypeRenderer = KaFlexibleTypeRenderer.AS_SHORT
        functionalTypeRenderer = KaFunctionalTypeRenderer.AS_FUNCTIONAL_TYPE
        intersectionTypeRenderer = KaIntersectionTypeRenderer.AS_INTERSECTION
        errorTypeRenderer = KaErrorTypeRenderer.WITH_ERROR_MESSAGE
        typeParameterTypeRenderer = KaTypeParameterTypeRenderer.AS_SOURCE
        unresolvedClassErrorTypeRenderer = KaUnresolvedClassErrorTypeRenderer.WITH_ERROR_MESSAGE
        typeNameRenderer = KaTypeNameRenderer.UNQUOTED
        typeApproximator = KaRendererTypeApproximator.TO_DENOTABLE // Или аналогичный стандартный
        typeProjectionRenderer = KaTypeProjectionRenderer.WITH_VARIANCE
        contextReceiversRenderer = KaContextReceiversRendererForSource.WITH_LABELS
        keywordsRenderer = KaKeywordsRenderer.AS_WORD
    }.build()

    // ============ ProjectRefactor implementation ============

    // for refactor
    override fun psiFileToMemberChooserList(header: MemberChooserObject, psiFile: PsiFile): List<UniversalMember> {
        val result = mutableListOf<UniversalMember>()

        // Ищем все классы в файле
        val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
        for (ktClass in classes) {
            //res.append(analyzeClassDetailed(ktClass))

            val member = UniversalMember(
                anchorElement = ktClass,
                presentationName = ktClass.name.toString(),
                rawContent = ktClass.text,
                header,
                //AllIcons.Nodes.Class,
            )
            result.add(member)
        }
        return result
    }

    override fun rawFileToMemberChooserList(header: MemberChooserObject, psiFile: PsiFile): List<UniversalMember> {
        // Создаем один элемент, где PSI-элементом выступает сам файл.
        // Это позволит getText() вернуть всё содержимое файла целиком.
        val fileMember = UniversalMember(
            anchorElement = psiFile,
            presentationName = "Plain text (${psiFile.text.length.toLong().toHumanReadableSize()})",
            rawContent = psiFile.text,
            header,
            AllIcons.Nodes.Unknown
        )

        return listOf(fileMember)
    }

    // ============ DeepProjectAnalyzer implementation ============

    override fun getFileOutline(psiFile: PsiFile): FileSummary {
        TODO("Not yet implemented")
    }

    override fun getEntityDetail(
        psiFile: PsiFile,
        entityName: String
    ): CodeEntity? {
        TODO("Not yet implemented")
    }

    override fun findUsages(
        psiFile: PsiFile,
        symbolName: String
    ): List<CodeReference> {
        TODO("Not yet implemented")
    }

    override fun formatForAI(summary: FileSummary): String {
        TODO("Not yet implemented")
    }

    // ============ ProjectAnalyzer implementation ============

    override fun analyzePsiFile(psiFile: PsiFile, deep: Boolean): String {
        val ktFile = psiFile as? KtFile ?: return "Не Kotlin-файл"

        val res = StringBuilder("Dense report of Kotlin file: ${psiFile.name}\n\n")
        val codeRes = StringBuilder()

        // 1. Свободные (top-level) функции — всегда показываем
        val topLevelFunctions = ktFile.declarations.filterIsInstance<KtNamedFunction>()
        if (topLevelFunctions.isNotEmpty()) {
            codeRes.append("=== Top-level functions ===\n")
            topLevelFunctions.forEach { func ->
                val params = func.valueParameterList?.text ?: "()"
                val returnType = func.typeReference?.text ?: "Unit"
                codeRes.append(" - ${func.name}$params : $returnType\n")
            }
        }

        // 2. Все классы и объекты (включая nested)
        val classesAndObjects = PsiTreeUtil.findChildrenOfType(psiFile, KtClassOrObject::class.java)

        if (deep) {
            // Здесь вызываешь свой analyzeClassDetailed (просто смени тип параметра на KtClassOrObject)
            for (element in classesAndObjects) {
                codeRes.append(analyzeClassDetailed(element))
            }
        } else {
            for (element in classesAndObjects) {
                // Определяем тип класса/объекта
                val kind = when {
                    element is KtClass && element.isInterface() -> "interface"
                    element is KtClass && element.isData() -> "data class"
                    element is KtClass && element.isSealed() -> "sealed class"
                    element is KtClass && element.isInner() -> "inner class"
                    element is KtClass -> "class"
                    else -> "object"
                }
                if (kind == "data class") {
                    codeRes.append(textExtractDataClassElement(element, KotlinClassType.DATA_CLASS))
                } else if (kind == "object" && element.name == null) {
                    // noname element, try restore call chain
                    val contextChain = buildContextChain(element)
                    val chain = mutableListOf<String>()
                    contextChain.forEach { chain.add(it.substringBefore("{")) }
                    codeRes.append("[chain:${chain.size}]$kind: ${chain}\n")

//                    val call = PsiTreeUtil.getParentOfType(element, KtCallExpression::class.java)
//                    val key = call?.valueArguments?.getOrNull(0)?.getArgumentExpression()?.text?:""
//                    val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
//                    val functionName = function?.name
//                    val clazz = PsiTreeUtil.getParentOfType(element, KtClass::class.java)
//                    val className = clazz?.name
//                    val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java)
//                    val propertyName = property?.name
//                    codeRes.append("$kind: inside of class ${className}\n")
//                    codeRes.append("$kind: inside of func ${functionName}\n")
//                    codeRes.append("$kind: property: ${propertyName}\n")
//                    codeRes.append("$kind: key: ${key}\n")

                } else {
                    codeRes.append("$kind: ${element.name}\n")
                }

                // Properties
                val properties = element.body?.properties ?: emptyList()
                if (properties.isNotEmpty()) {
                    codeRes.append("  Properties:\n")
                    properties.forEach { prop ->
                        val typeT = prop.typeReference?.text ?: "Inferred"
                        codeRes.append("    - ${prop.name}: $typeT\n")
                    }
                }

                // Methods (class functions)
                val functions = element.body?.functions ?: emptyList()
                if (functions.isNotEmpty()) {
                    codeRes.append("  Functions:\n")
                    functions.forEach { func ->
                        val params = func.valueParameterList?.text ?: "()"
                        val returnType = func.typeReference?.text ?: "Unit"
                        codeRes.append("    - ${func.name}$params : $returnType\n")
                    }
                }
            }
        }

//        codeRes.append(textExtractClassElements(psiFile, KotlinClassType.DATA_CLASS))

        // text header (not md)
        //res.append("------\n\n")
        res.append("  -plain=${psiFile.text.length}\n")
        res.append("  -dense=${codeRes.length}\n")

        return res.append(wrapCode(codeRes.toString(), "kotlin")).toString()
    }

    @OptIn(KaExperimentalApi::class)
    fun analyzeClassDetailed(ktClass: KtClassOrObject): String {
        val sb = StringBuilder()

        // Открываем сессию анализа
        analyze(ktClass) {
            // В новых версиях API ktClass.symbol возвращает KaClassSymbol
            val classSymbol = ktClass.symbol as? KaClassSymbol ?: return@analyze

            // 1. Наследование (Supertypes)
            // Фильтруем Any, чтобы не засорять лог
            val superTypes = classSymbol.superTypes
                .map { type ->
                    type.render(
                        // Попробуй один из этих пресетов:
                        renderer = kaTypeRenderer,
                        position = Variance.INVARIANT
                    )
                }
                .filter { it != "Any" }

            val inheritance = if (superTypes.isNotEmpty()) " : ${superTypes.joinToString()}" else ""
            sb.append("Class: ${ktClass.name}$inheritance\n\n")

            // 2. Свойства (Properties)
            classSymbol.memberScope.callables
                .filterIsInstance<KaPropertySymbol>()
                .forEach { prop ->
                    sb.append(
                        "  * Property: ${prop.name}: ${
                            prop.returnType.render(
                                renderer = kaTypeRenderer,
                                position = Variance.INVARIANT
                            )
                        }\n\n"
                    )
                }

            // 3. Функции (Methods)
            // Берем только те, что объявлены в этом классе (не наследуемые от Any/Object)
            ktClass.declarations.filterIsInstance<KtNamedFunction>().forEach { function ->
                val funcSymbol = function.symbol as? KaNamedFunctionSymbol ?: return@forEach

                sb.append(formatMethodBody(function, funcSymbol) + "\n\n")


            //
            //                val isOverride = if (funcSymbol.allOverriddenSymbols.iterator().hasNext()) "override " else ""
            //
            //                // Собираем параметры
            //                val params = funcSymbol.valueParameters.joinToString(", ") { p ->
            //                    "${p.name}: ${p.returnType.render(
            //                        renderer = kaTypeRenderer,
            //                        position = Variance.INVARIANT
            //                    )}"
            //                }
            //
            //                val returnType = funcSymbol.returnType.render(
            //                    renderer = kaTypeRenderer,
            //                    position = Variance.INVARIANT
            //                )
            //                sb.append("  - ${isOverride}fun ${function.name}($params): $returnType\n")
            //
            //                // --- ДОБАВЛЯЕМ ИЗВЛЕЧЕНИЕ ТЕЛА ---
            //                val body = function.bodyExpression
            //                if (body != null) {
            //                    val bodyText = body.text
            //                    // Если тело короткое (однострочник), пишем как есть
            //                    if (bodyText.length < 100 && !bodyText.contains("\n")) {
            //                        sb.append(" = $bodyText\n")
            //                    } else {
            //                        // Если длинное — берем первые пару строк или помечаем наличие тела
            //                        // Для AI лучше давать первые 3-5 строк самого важного кода
            //                        val lines = bodyText.lines()
            //                        val preview = lines.take(5).joinToString("\n      ")
            //                        sb.append(" {\n      $preview\n      ${if (lines.size > 5) "... [еще ${lines.size - 5} строк]" else ""}\n    }\n\n")
            //                    }
            //                } else {
            //                    sb.append("\n\n") // Абстрактный метод или интерфейс
                            //}


            }
        }
        return sb.toString()
    }

    // Список "скучных" методов платформы
    private val TRIVIAL_METHODS = setOf(
        "getDisplayName", "isModified", "apply", "reset", "createComponent", "dispose", "getIcon"
    )

    fun formatMethodBody(function: KtNamedFunction, funcSymbol: KaNamedFunctionSymbol): String {
        val isOverride = funcSymbol.isOverride
        val body = function.bodyExpression ?: return "\n"

        // Если это стандартный override из списка "скучных" — не даем тело
        if (isOverride && TRIVIAL_METHODS.contains(function.name)) {
            return " = { ... platform override ... }\n"
        }

        val bodyText = body.text
        // Однострочники возвращаем целиком
        if (bodyText.length < 100 && !bodyText.contains("\n")) {
            return " = $bodyText\n"
        }

        // Для сложных методов — берем превью
        val lines = bodyText.lines()
        val preview = lines.take(10).joinToString("\n      ") // 10 строк — золотая середина

        // Если в preview уже есть первая скобка, можно просто выводить:
        //return "\n      $preview\n"
        return if (preview[0] == '{') {
            "\n      $preview\n      ${if (lines.size > 10) "... [еще ${lines.size - 10} строк]" else ""}\n\n"
        } else {
            " {\n      $preview\n      ${if (lines.size > 10) "... [еще ${lines.size - 10} строк]" else ""}\n    }\n"
        }



    }


    enum class KotlinClassType {
        INTERFACE,
        CLASS,
        DATA_CLASS,
        SEALED_CLASS,
        INNER_CLASS,
        OBJECT,
    }

    fun textExtractClassElements(psiFile: PsiFile, type: KotlinClassType): String {
        val codeRes = StringBuilder()
        // Все классы и объекты (включая nested)
        val classesAndObjects = PsiTreeUtil.findChildrenOfType(psiFile, KtClassOrObject::class.java)
        for (element in classesAndObjects) {
            val kind = when {
                //element is KtObject -> "object"
                element is KtClass && element.isInterface() -> KotlinClassType.INTERFACE
                element is KtClass && element.isData() -> KotlinClassType.DATA_CLASS
                element is KtClass && element.isSealed() -> KotlinClassType.SEALED_CLASS
                element is KtClass && element.isInner() -> KotlinClassType.INNER_CLASS
                element is KtClass -> KotlinClassType.CLASS
                else -> KotlinClassType.OBJECT
            }
            if (type == kind)  {
                codeRes.append(element.text.substringBefore("{")+"\n")
            }

        }
        return codeRes.toString()
    }

    fun textExtractDataClassElement(element: KtClassOrObject, type: KotlinClassType, deep: Boolean=false): String {
        val codeRes = StringBuilder()

        val kind = when {
            //element is KtObject -> "object"
            element is KtClass && element.isInterface() -> KotlinClassType.INTERFACE
            element is KtClass && element.isData() -> KotlinClassType.DATA_CLASS
            element is KtClass && element.isSealed() -> KotlinClassType.SEALED_CLASS
            element is KtClass && element.isInner() -> KotlinClassType.INNER_CLASS
            element is KtClass -> KotlinClassType.CLASS
            else -> KotlinClassType.OBJECT
        }
        if (type == kind)  {
            codeRes.append(element.text.substringBefore("{")+"\n")
        }

        return codeRes.toString()
    }

    fun buildContextChain(element: PsiElement): List<String> {
        val result = mutableListOf<String>()
        var current: PsiElement? = element

        while (current != null) {
            when (current) {
                is KtNamedFunction -> result += "fun:${current.name}"
                is KtClass -> result += "class:${current.name}"
                is KtProperty -> result += "prop:${current.name}"

                is KtCallExpression -> {
                    val callee = current.calleeExpression?.text
                    result += "call:$callee"

                    // если это put("hideFind", ...)
                    val firstArg = current.valueArguments.firstOrNull()
                        ?.getArgumentExpression()?.text

                    if (firstArg != null) {
                        result += "arg:$firstArg"
                    }
                }

                is KtFile -> {
                    result += "file:${current.name}"
                    break
                }
            }

            current = current.parent
        }

        return result.reversed()
    }




    /* analyze simplefied

    // 1. Получаем имя класса и его предков
    val classSymbol = ktClass.symbol as? KaClassSymbol
    val className = ktClass.name ?: "Unknown"

    // Достаем супертипы напрямую через их символы
    val superTypesText = classSymbol?.superTypes
        ?.mapNotNull { type ->
            // Достаем имя типа (например, AnAction) без рендерера
            (type.expandedSymbol as? KaClassSymbol)?.name?.asString()
        }
        ?.filter { it != "Any" }
        ?.joinToString() ?: ""

    val inheritance = if (superTypesText.isNotEmpty()) " : $superTypesText" else ""
    sb.append("Class: $className$inheritance\n")

    // 2. Получаем методы
    ktClass.declarations.filterIsInstance<KtNamedFunction>().forEach { function ->
        val funcSymbol = function.symbol as? KaNamedFunctionSymbol ?: return@forEach

        val isOverride = if (funcSymbol.allOverriddenSymbols.any()) "override " else ""

        // Параметры: вытаскиваем только имена типов
        val params = funcSymbol.valueParameters.joinToString(", ") { p ->
            val typeName = (p.returnType.expandedSymbol as? KaClassSymbol)?.name?.asString() ?: "Any"
            "${p.name}: $typeName"
        }

        // Возвращаемый тип
        val retTypeName = (funcSymbol.returnType.expandedSymbol as? KaClassSymbol)?.name?.asString() ?: "Unit"

        sb.append("  - ${isOverride}fun ${function.name}($params): $retTypeName\n")
}  */

}