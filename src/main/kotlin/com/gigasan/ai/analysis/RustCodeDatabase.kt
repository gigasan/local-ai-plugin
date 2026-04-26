package com.gigasan.ai.analysis

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import org.sqlite.JDBC
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.Properties



class RustCodeDatabase private constructor(moduleName: String) {   // ← private constructor

    val dbPath = "${PathManager.getSystemPath()}/$moduleName.sqlite"

    private val conn: Connection
    private val logger = Logger.getInstance("RustCodeDatabase")

    init {
        val dbFile = File(dbPath).absolutePath
        val driver = JDBC()
        val url = "jdbc:sqlite:$dbFile"

        logger.info("Connecting to $url (singleton instance)")

        conn = driver.connect(url, Properties())
            ?: throw SQLException("Could not connect to SQLite at $url")

        conn.autoCommit = true          // ← явно включаем автокоммит
        createTables()
    }

    // ==================== SINGLETON ====================
    companion object {
        private var instance: RustCodeDatabase? = null

        fun getInstance(moduleName: String = "RustProjectAnalyzer"): RustCodeDatabase {
            return instance ?: synchronized(this) {
                instance ?: RustCodeDatabase(moduleName).also { instance = it }
            }
        }
    }

    // ===================================================
    private fun createTables() {
        conn.createStatement().use { stmt ->
            // Файлы
            stmt.execute("""
            CREATE TABLE IF NOT EXISTS files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT UNIQUE NOT NULL,
                content_size INTEGER DEFAULT 0,
                content TEXT,
                last_analyzed TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

            // Комментарии (многострочные и возможно однострочные, doc комментарии)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS comments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_id INTEGER,
                    text TEXT,
                    is_doc BOOLEAN,
                    content_size INTEGER,
                    range_start INTEGER,
                    range_end INTEGER,
                    FOREIGN KEY(file_id) REFERENCES files(id)
                )""")

            // Контейнеры (Struct, Enum, Impl)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS containers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_id INTEGER,
                    type TEXT, -- 'struct', 'enum', 'impl', 'trait'
                    header TEXT, -- например, 'PreprocessContext'
                    body TEXT,
                    content_size INTEGER,
                    raw TEXT,
                    range_start INTEGER,
                    range_end INTEGER,
                    FOREIGN KEY(file_id) REFERENCES files(id)
                )""")

            // Функции
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS functions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_id INTEGER,
                    container_id INTEGER, 
                    name TEXT,
                    header TEXT,        -- Чистый заголовок для UI
                    body TEXT, -- Очищенный код (например, без атрибутов или с нормализованными отступами)
                    raw TEXT,   -- Весь блок «как есть» из файла (с комментариями и пробелами)
                    content_size INTEGER, -- Добавили размер
                    is_test BOOLEAN,
                    range_start INTEGER,
                    range_end INTEGER,
                    FOREIGN KEY(file_id) REFERENCES files(id),
                    FOREIGN KEY(container_id) REFERENCES containers(id)
                )""")
        }
    }

    data class CommentResult(
        val text: String,
        val isDoc: Boolean,
        val range: IntRange,
    )

    fun insertComment(fileId: Int, cm: CommentResult) {
        val sql = """
        INSERT INTO comments (file_id, text, is_doc, content_size, range_start, range_end)
        VALUES (?, ?, ?, ?, ?, ?)
    """
        conn.prepareStatement(sql).use { pstmt ->
            var idx = 1
            pstmt.setInt(idx++, fileId)
            //if (containerId != null) pstmt.setInt(idx++, containerId) else pstmt.setNull(idx++, java.sql.Types.INTEGER)
            pstmt.setString(idx++, cm.text)
            pstmt.setBoolean(idx++, cm.isDoc)
            pstmt.setInt(idx++, cm.text.length)
            pstmt.setInt(idx++, cm.range.first)
            pstmt.setInt(idx++, cm.range.last)
            pstmt.executeUpdate()
        }
    }

    data class FunctionResult(
        val name: String,
        val header: String,
        val body: String,
        val raw: String,
        val range: IntRange,
        val isTest: Boolean
    )

    // При вставке в БД:
    fun insertFunction(fileId: Int, containerId: Int?, fn: FunctionResult) {
        val sql = """
        INSERT INTO functions (file_id, container_id, name, header, body, raw, content_size, is_test, range_start, range_end)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
        conn.prepareStatement(sql).use { pstmt ->
            var idx = 1
            pstmt.setInt(idx++, fileId)

            if (containerId != null) {
                pstmt.setInt(idx++, containerId)
            } else {
                pstmt.setNull(idx++, Types.INTEGER)   // ← важно!
            }

            pstmt.setString(idx++, fn.name)
            pstmt.setString(idx++, fn.header)
            pstmt.setString(idx++, fn.body)
            pstmt.setString(idx++, fn.raw)
            pstmt.setInt(idx++, fn.raw.length)
            pstmt.setBoolean(idx++, fn.isTest)
            pstmt.setInt(idx++, fn.range.first)
            pstmt.setInt(idx++, fn.range.last)

            pstmt.executeUpdate()
        }
    }

    data class ContainerResult(
        val type: String,
        val header: String,
        val body: String,
//        val cleanContent: String,
        val raw: String,
        val range: IntRange,
    )

    // При вставке в БД:
    fun insertContainer(fileId: Int, cnt: ContainerResult): Int {
        val sql = """
        INSERT INTO containers (file_id, type, header, body, content_size, raw, range_start, range_end)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """
        return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { pstmt ->
            var idx = 1
            pstmt.setInt(idx++, fileId)
            pstmt.setString(idx++, cnt.type)
            pstmt.setString(idx++, cnt.header)
            pstmt.setString(idx++, cnt.body)
            pstmt.setInt(idx++, cnt.body.length)
            pstmt.setString(idx++, cnt.raw)
            pstmt.setInt(idx++, cnt.range.first)
            pstmt.setInt(idx++, cnt.range.last)
            pstmt.executeUpdate()
            val rs = pstmt.generatedKeys
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    fun getOrInsertFileId(path: String): Int {
        logger.info("getOrInsertFileId → path: $path")

        if (path.isBlank()) {
            throw IllegalArgumentException("Path cannot be blank")
        }

        // Проверяем существование
        getExistingFileId(path)?.let { existingId ->
            logger.info("✅ File already exists → id=$existingId")
            return existingId
        }

        // Вставляем (теперь точно будет только один раз)
        val insertSql = """
            INSERT OR IGNORE INTO files (path, content_size, content) 
            VALUES (?, 0, '')
        """
        conn.prepareStatement(insertSql).use { pstmt ->
            pstmt.setString(1, path)
            val rows = pstmt.executeUpdate()
            logger.info("INSERT OR IGNORE → rows affected: $rows")
        }

        // Берём id
        return getExistingFileId(path) ?: throw SQLException("Failed to get file id for $path")
    }

    fun getExistingFileId(path: String): Int? {
        val sql = "SELECT id FROM files WHERE path = ? LIMIT 1"
        return conn.prepareStatement(sql).use { pstmt ->
            pstmt.setString(1, path)
            val rs = pstmt.executeQuery()
            if (rs.next()) {
                val id = rs.getInt("id")
                logger.debug("getExistingFileId found id=$id for $path")
                if (rs.wasNull()) null else id
            } else {
                logger.debug("getExistingFileId → not found for $path")
                null
            }
        }
    }


    fun clearData() {
        conn.createStatement().use { it.execute("DELETE FROM files") } // Каскадом удалит остальное, если настроить, или чисти всё вручную
    }

    fun clearFileData(fileId: Int) {
        logger.info("Clearing old data for fileId=$fileId")

        var deletedFunctions = 0
        var deletedContainers = 0
        var deletedComments = 0

        try {
            // Удаляем функции
            conn.prepareStatement("DELETE FROM functions WHERE file_id = ?").use { pstmt ->
                pstmt.setInt(1, fileId)
                deletedFunctions = pstmt.executeUpdate()
            }

            // Удаляем контейнеры
            conn.prepareStatement("DELETE FROM containers WHERE file_id = ?").use { pstmt ->
                pstmt.setInt(1, fileId)
                deletedContainers = pstmt.executeUpdate()
            }

            // Удаляем комментарии
            conn.prepareStatement("DELETE FROM comments WHERE file_id = ?").use { pstmt ->
                pstmt.setInt(1, fileId)
                deletedComments = pstmt.executeUpdate()
            }

            logger.info("Old data cleared for fileId=$fileId | functions=$deletedFunctions, containers=$deletedContainers, comments=$deletedComments")

        } catch (e: Exception) {
            logger.error("Error while clearing data for fileId=$fileId", e)
        }
    }








    // 1. Сначала добавь в класс RustCodeDatabase (в самый конец класса)
//    эти data-классы для результатов запросов (чтобы не дублировать логику вставки).
//    Они почти идентичны твоим insert-классам, но с id и готовые к чтению.

    data class QueriedContainer(
        val id: Int,
        val type: String,      // 'struct', 'enum', 'impl', 'trait'
        val header: String,    // готовый заголовок для UI (то, что ты сохранял)
        val body: String,
        val raw: String,
        val range: IntRange
    )

    data class QueriedFunction(
        val id: Int,
        val name: String,
        val header: String,
        val body: String,
        val raw: String,
        val range: IntRange,
        val isTest: Boolean
    )

// 2. Добавь эти три метода для выборки (вставь их после createTables() или после insert-методов)

    /**
     * Все контейнеры файла (struct, enum, impl, trait) в порядке появления в файле
     */
    fun getContainers(fileId: Int): List<QueriedContainer> {
        val sql = """
            SELECT id, type, header, body, raw, range_start, range_end
            FROM containers 
            WHERE file_id = ?
            ORDER BY range_start ASC
        """
        return conn.prepareStatement(sql).use { pstmt ->
            pstmt.setInt(1, fileId)
            val rs = pstmt.executeQuery()
            val result = mutableListOf<QueriedContainer>()
            while (rs.next()) {
                result.add(
                    QueriedContainer(
                        id = rs.getInt("id"),
                        type = rs.getString("type"),
                        header = rs.getString("header"),
                        body = rs.getString("body"),
                        raw = rs.getString("raw"),
                        range = rs.getInt("range_start")..rs.getInt("range_end")
                    )
                )
            }
            result
        }
    }

    /**
     * Топ-левел функции (те, у которых container_id IS NULL)
     */
    fun getTopLevelFunctions(fileId: Int): List<QueriedFunction> {
        val sql = """
        SELECT id, name, header, body, raw, range_start, range_end, is_test
        FROM functions 
        WHERE file_id = ? 
          AND (container_id IS NULL OR container_id = 0)
        ORDER BY range_start ASC
    """
        return conn.prepareStatement(sql).use { pstmt ->
            pstmt.setInt(1, fileId)
            val rs = pstmt.executeQuery()
            val result = mutableListOf<QueriedFunction>()
            while (rs.next()) {
                result.add(
                    QueriedFunction(
                        id = rs.getInt("id"),
                        name = rs.getString("name"),
                        header = rs.getString("header"),
                        body = rs.getString("body"),
                        raw = rs.getString("raw"),
                        range = rs.getInt("range_start")..rs.getInt("range_end"),
                        isTest = rs.getBoolean("is_test")
                    )
                )
            }
            result
        }
    }

    /**
     * Функции внутри конкретного контейнера (методы impl/trait и т.д.)
     */
    fun getFunctionsForContainer(fileId: Int, containerId: Int): List<QueriedFunction> {
        val sql = """
            SELECT id, name, header, body, raw, range_start, range_end, is_test
            FROM functions 
            WHERE file_id = ? AND container_id = ?
            ORDER BY range_start ASC
        """
        return conn.prepareStatement(sql).use { pstmt ->
            pstmt.setInt(1, fileId)
            pstmt.setInt(2, containerId)
            val rs = pstmt.executeQuery()
            val result = mutableListOf<QueriedFunction>()
            while (rs.next()) {
                result.add(
                    QueriedFunction(
                        id = rs.getInt("id"),
                        name = rs.getString("name"),
                        header = rs.getString("header"),
                        body = rs.getString("body"),
                        raw = rs.getString("raw"),
                        range = rs.getInt("range_start")..rs.getInt("range_end"),
                        isTest = rs.getBoolean("is_test")
                    )
                )
            }
            result
        }
    }

    /**
     * Удобный метод, который сразу собирает весь "dense report"
     * (чтобы в analyzePsiFile было максимально похоже на Kotlin-версию)
     */
    fun buildDenseReport(fileId: Int, deep: Boolean): StringBuilder {
        val codeRes = StringBuilder()
        logger.warn("buildDenseReport start $fileId")
        // 1. Топ-левел функции — всегда показываем (аналогично Kotlin)
        val topLevelFunctions = getTopLevelFunctions(fileId)
        if (topLevelFunctions.isNotEmpty()) {
            codeRes.append("=== Top-level functions ===\n")
            topLevelFunctions.forEach { fn ->
                logger.warn("buildDenseReport === Top-level functions === ${fn.name}")
                val testMark = if (fn.isTest) " [TEST]" else ""
                codeRes.append(" - ${fn.name}${fn.header}$testMark\n")
            }
            codeRes.append("\n")
        }

        // 2. Все контейнеры (struct / enum / impl / trait)
        val containers = getContainers(fileId)
        if (containers.isNotEmpty()) {
            codeRes.append("=== Containers ===\n")
            for (container in containers) {
                // Тип + header (точно как ты сохранял)
                codeRes.append("${container.type}: ${container.header}\n")

                // Если deep — можно добавить тело (или raw, или что угодно)
                if (deep) {
                    val preview = container.body.take(300) + if (container.body.length > 300) "..." else ""
                    codeRes.append("  body preview: $preview\n")
                }

                // Функции внутри этого контейнера
                val containerFunctions = getFunctionsForContainer(fileId, container.id)
                if (containerFunctions.isNotEmpty()) {
                    codeRes.append("  Functions:\n")
                    containerFunctions.forEach { fn ->
                        val testMark = if (fn.isTest) " [TEST]" else ""
                        codeRes.append("   - ${fn.name}${fn.header}$testMark\n")
                    }
                }
                codeRes.append("\n")
            }
        }

        // Опционально: можно добавить секцию комментариев, если захочешь
        // (я оставил закомментированным, потому что в Kotlin-версии их нет)
        /*
        val comments = getComments(fileId) // если добавишь метод
        if (comments.isNotEmpty()) {
            codeRes.append("=== Comments ===\n")
            // ...
        }
        */

        return codeRes
    }


    fun debugPrintFileContent(fileId: Int) {
        logger.warn("=== DEBUG: Content of file_id = $fileId ===")

        // Сколько всего функций
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM functions WHERE file_id = $fileId")
            if (rs.next()) logger.warn("Total functions: ${rs.getInt("cnt")}")

            val rs2 = stmt.executeQuery("""
            SELECT name, container_id, range_start 
            FROM functions 
            WHERE file_id = $fileId 
            ORDER BY range_start
        """)
            while (rs2.next()) {
                val container = rs2.getObject("container_id")?.toString() ?: "NULL"
                logger.warn("  Function '${rs2.getString("name")}' | container_id = $container | start=${rs2.getInt("range_start")}")
            }
        }

        // Сколько контейнеров
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM containers WHERE file_id = $fileId")
            if (rs.next()) logger.warn("Total containers: ${rs.getInt("cnt")}")
        }
    }


}