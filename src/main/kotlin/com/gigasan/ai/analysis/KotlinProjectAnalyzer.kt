package com.gigasan.ai.analysis

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
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
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.types.Variance

// Kotlin
class KotlinProjectAnalyzer: ProjectAnalyzer {

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

    // for refactor
    override fun psiFileToMemberChooserList(psiFile: PsiFile): List<UniversalMember> {
        val result = mutableListOf<UniversalMember>()

        // Ищем все классы в файле
        val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)
            for (ktClass in classes) {
                //res.append(analyzeClassDetailed(ktClass))

                val member = UniversalMember(
                    element = ktClass,
                    presentationName = ktClass.name.toString(),
                    parentMember = null,
                )
                result.add(member)
            }
        return result




//
//
////        val text = file.text
////
////        val filePath = file.virtualFile.path
////        val fileId = db.getOrInsertFileId(filePath)
////        val sourceText = file.text
////
////        // 0. ищем комментарии
////        val comments = findComments(sourceText, file.name)
//        logger.warn("psiFileToMemberChooserList fileName=${file.name} detected ${comments.size} comments")
////        for (comment in comments) {
////            val cont = RustCodeDatabase.CommentResult(
////                text = comment.text,
////                isDoc = comment.isDoc,
////                range = comment.range,
////            )
////            db.insertComment(fileId, cont)
////        }
//
//        logger.warn("psiFileToMemberChooserList fileName=${file.name} text.length=${sourceText.length}")
//
//        // 2. Ищем ВСЕ функции в файле
////        val functions = parseFunctions(sourceText, sourceText, comments, file.name)
////        logger.warn("psiFileToMemberChooserList fileName=${file.name} detected ${functions.size} functions")
////
////
////        for (func in functions) {
////            val element = file.findElementAt(func.fullRange.startOffset)
////            if (element != null) {
////                val parent = element.parent // Скорее всего это будет просто текстовый узел
////                result.add(UniversalMember(parent, func.name))
////            }
////        }
//
//        return result
    }

    // for analyze
    override fun analyzePsiFile(psiFile: PsiFile, deep: Boolean): String {
        val res = StringBuilder("Kotlin File: ${psiFile.name}\n\n")

        // Ищем все классы в файле
        val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)

        if (deep) {
            for (ktClass in classes) {
                res.append(analyzeClassDetailed(ktClass))
            }
        } else {
            for (ktClass in classes) {
                res.append("Class: ${ktClass.name}\n\n")

                // Свойства (поля)
                val properties = ktClass.getProperties()
                if (properties.isNotEmpty()) {
                    res.append("  Properties:\n\n")
                    properties.forEach { res.append("    - ${it.name}: ${it.typeReference?.text ?: "Inferred"}\n\n") }
                }

                // Методы (функции)
                val functions = ktClass.getChildrenOfType<KtNamedFunction>()
                if (functions.isNotEmpty()) {
                    res.append("  Methods:\n\n")
                    functions.forEach { func ->
                        val params = func.valueParameterList?.text ?: "()"
                        res.append("    - ${func.name}$params\n\n")
                    }
                }
            }
        }
        return res.toString()
    }

    @OptIn(KaExperimentalApi::class)
    fun analyzeClassDetailed(ktClass: KtClass): String {
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


    // Внутри твоего метода анализа:
    @OptIn(KaAllowAnalysisOnEdt::class)
    fun analyzeFile(ktFile: KtFile) {
        // В K2 очень строго с потоками. Если ты в UI-потоке, нужно это:
        allowAnalysisOnEdt {
            analyze(ktFile) {
                // Теперь здесь доступны все методы FIR анализа
                //val classSymbol = ktFile.
                // ... логика сбора данных ...
            }
        }
    }

    @OptIn(KaExperimentalApi::class)
    fun analyzeKaModule(kaModule: KaModule): String {
        val sb = StringBuilder()
        analyze(kaModule) {
            sb.append("File: ${kaModule.moduleDescription}\n")
        }
        return sb.toString()
    }

    companion object {
//        fun log() {
//            logger.info("Анализ завершен. Найдено файлов: ${0}")
//        }
    }
}