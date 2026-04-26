package com.gigasan.ai.analysis

import com.intellij.psi.PsiFile

interface ProjectAnalyzer {
    fun analyzePsiFile(psiFile: PsiFile, deep: Boolean=false): String
}

// Обобщенная сущность (функция, метод, структура, интерфейс, трейт)
data class CodeEntity(
    val name: String,
    val type: String, // "class", "function", "trait", "impl", "struct"
    val signature: String,
    val documentation: String?,
    val range: IntRange,
    val subEntities: List<CodeEntity> = emptyList()
)

data class FileSummary(
    val filePath: String,
    val imports: List<String>,
    val outline: List<CodeEntity> // Только заголовки/сигнатуры
)

annotation class CodeReference

interface AiProjectAnalyzer {
    // 1. Быстрый обзор: импорты + сигнатуры (то, что у тебя уже есть)
    // Позволяет AI понять "что тут вообще есть"
    fun getFileOutline(psiFile: PsiFile): FileSummary

    // 2. Глубокий анализ: получение тела конкретной сущности по имени или позиции
    // Нужно, когда AI говорит: "Покажи мне реализацию функции 'parseRustManually'"
    fun getEntityDetail(psiFile: PsiFile, entityName: String): CodeEntity?

    // 3. Контекстный поиск: найти где используется сущность внутри этого файла или проекта
    // Помогает восстановить связи (например, найти все impl для конкретного trait в Rust)
    fun findUsages(psiFile: PsiFile, symbolName: String): List<CodeReference>

    // 4. (Опционально) Форматирование для промпта
    fun formatForAI(summary: FileSummary): String
}
