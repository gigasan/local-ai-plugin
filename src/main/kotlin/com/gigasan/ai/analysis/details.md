A file analysis interface for AI tools for various languages. Kotlin and Rust are currently planned.

Привет! Задача отличная. Чтобы интерфейс был по-настоящему полезен для AI-агента, он должен поддерживать «ленивую» загрузку или **многоуровневую детализацию**. AI не может (и не должен) «заглатывать» весь код сразу из-за лимитов контекстного окна и шума.

Для поддержки Rust и Kotlin одновременно, нам нужно абстрагироваться от «классов» в сторону «контейнеров» и «сущностей», так как в Rust функции часто живут в `impl` отдельно от структур.

Вот предложение по расширению интерфейса, разделенное на уровни абстракции:

---

### 1. Уровни детализации (Data Models)

Сначала определим, что именно мы возвращаем. Вместо чистого `String`, лучше возвращать структуру, которую легко превратить и в Markdown для промпта, и в JSON для инструментов.

```kotlin
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
```

---

### 2. Расширенный интерфейс `ProjectAnalyzer`

Теперь разделим функционал на «карту», «детали» и «поиск».

```kotlin
interface ProjectAnalyzer {
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
```

---

### 3. Нюансы для Kotlin и Rust

При реализации этого интерфейса для разных языков, обрати внимание на следующие различия:

| Особенность | Kotlin (PSI) | Rust (через твой парсер или аналоги) |
| :--- | :--- | :--- |
| **Вложенность** | Классы часто содержат методы внутри. | `struct` и `impl` могут быть разнесены в разные части файла. |
| **Точки входа** | `KtClass`, `KtNamedFunction`. | `RsStructItem`, `RsImplItem`, `RsFunction`. |
| **Связи** | Наследование (Extends/Implements). | Реализация трейтов (`impl Trait for Struct`). |



---

### Как это будет работать в связке с AI:

1.  **Этап "Map":** Ты вызываешь `getFileOutline`. AI получает сжатый Markdown (твои текущие 10x).
2.  **Этап "Selection":** AI видит в списке `fun parseRustManually(...)` и понимает, что логика там.
3.  **Этап "Zoom":** AI запрашивает `getEntityDetail(..., "parseRustManually")`. Ты отдаешь ему полный код этой функции с комментариями.
4.  **Этап "Cross-Ref":** Если внутри функции вызывается `db.insertFunction`, AI может запросить `findUsages("insertFunction")`, чтобы понять, куда уходят данные.

### Что стоит добавить в будущем:
* **Dependency Graph:** Список зависимостей файла (не просто импорты, а какие локальные файлы он задействует).
* **Token Count Estimation:** Метод, который будет примерно считать токены ответа, чтобы не «взорвать» контекстное окно.
* **Type Hierarchy:** Для Kotlin это важно (иерархия классов), для Rust — кто реализует выбранный трейт.

В твоем текущем коде `parseRustManually` уже есть логика разделения на контейнеры и функции — это идеальная база для метода `getFileOutline`. Тебе осталось только вынести поиск конкретного тела функции в `getEntityDetail`.

Как ты планируешь хранить индекс для контекстного поиска — в оперативной памяти или будешь перепаршивать файлы на лету?

Kotlin - realtime parser and Rust - sqlite db

Это классическая дилемма при создании инструментов для IDE: **«Живое дерево» (PSI)** против **«Статического индекса» (База данных)**.

Разница в подходах на самом деле является преимуществом, если правильно абстрагировать интерфейс. Kotlin дает тебе максимальную точность «здесь и сейчас» для открытого файла, а SQLite для Rust позволяет делать мгновенные глобальные запросы по всему проекту, не перепаршивая тысячи файлов.

Чтобы интерфейс `ProjectAnalyzer` работал в обоих случаях, нужно уйти от прямой зависимости от `PsiFile` в сигнатурах методов и добавить понятие **Scope** (Область видимости).

---

### Унифицированный интерфейс

Предлагаю разделить интерфейс на две части: **Навигация** (что есть в проекте) и **Чтение** (детали конкретной сущности).

```kotlin
interface ProjectAnalyzer {
    // Вместо PsiFile используем String (путь) или VirtualFile, 
    // чтобы Rust-реализация могла просто лезть в БД по пути.
    fun getFileOutline(path: String): FileSummary?

    // Получение деталей. Для Rust это будет SELECT из БД, 
    // для Kotlin — поиск элемента в PSI-дереве по смещению или имени.
    fun getEntityMarkup(entityId: String): String 

    // Глобальный поиск (сила твоей SQLite базы)
    fun searchSymbols(query: String): List<CodeSymbol>
}
```

---

### Как подружить подходы



#### 1. Реализация для Kotlin (On-the-fly)
Для Kotlin твоя реализация `ProjectAnalyzer` будет работать как «прокси» к PSI. Когда AI просит данные, ты:
1. Находишь `PsiFile` по пути.
2. Проходишься по дереву (твои текущие алгоритмы сжатия).
3. Возвращаешь результат.
   *Плюс:* Всегда актуальные данные, даже если пользователь только что удалил пол-класса.

#### 2. Реализация для Rust (Index-based)
Здесь `ProjectAnalyzer` работает как клиент к SQLite:
1. `getFileOutline` — это один быстрый запрос: `SELECT * FROM containers WHERE file_path = ?`.
2. `getEntityMarkup` — `SELECT body FROM functions WHERE id = ?`.
   *Плюс:* Невероятная скорость. AI может мгновенно получить контекст из другого конца проекта.

---

### Предлагаемая структура данных (мост между языками)

Чтобы AI понимал структуру, нам нужны универсальные ID. Для Rust это могут быть `rowid` из твоей базы, для Kotlin — `path:offset`.

```kotlin
data class CodeSymbol(
    val id: String,         // Универсальный ID для запроса деталей
    val name: String,
    val kind: SymbolKind,   // Enum: FUNCTION, STRUCT, TRAIT, CLASS и т.д.
    val preview: String,    // Та самая сжатая сигнатура (10x меньше кода)
    val location: SymbolLocation
)

enum class SymbolKind {
    CONTAINER, // struct, class, impl, trait
    FUNCTION,  // fn, fun, method
    PROPERTY   // field, val/var
}
```

### Главный совет по расширению:
Добавь в интерфейс метод **`sync(path: String)`**.
* В Kotlin он может ничего не делать (или обновлять локальные кеши).
* В Rust он будет триггером для твоего `parseRustManually`, чтобы обновить данные в SQLite, если файл изменился.

Так AI-инструмент сможет сначала вызвать `sync`, а потом гарантированно работать с актуальным индексом.

Каким образом ты планируешь связывать Rust-сущности между разными файлами в SQLite? Уже думал о таблице связей (imports/calls)?