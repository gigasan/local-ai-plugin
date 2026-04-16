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



class RustCodeDatabase(moduleName: String) {
    val dbPath = "${PathManager.getSystemPath()}/$moduleName.sqlite"

    private var conn: Connection // = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val logger = Logger.getInstance("RustCodeDatabase")

    init {
        val dbFile = File(dbPath).absolutePath

        // 1. Создаем экземпляр драйвера напрямую
        val driver = JDBC()

        // 2. Указываем путь к базе
        val url = "jdbc:sqlite:$dbFile"

        logger.info("Connecting to $url")

        // 3. Подключаемся через драйвер, минуя DriverManager
        conn = driver.connect(url, Properties())
            ?: throw SQLException("Could not connect to SQLite at $url")

        createTables()
    }

    private fun createTables() {
        conn.createStatement().use { stmt ->
            // Файлы
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT UNIQUE,
                    content_size INTEGER, -- Добавили размер
                    content TEXT
                )""")

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
            if (containerId != null) pstmt.setInt(idx++, containerId) else pstmt.setNull(idx++, Types.INTEGER)
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

    fun getExistingFileId(path: String): Int? {
        val sql = "SELECT id FROM files WHERE path = ?"

        return conn.prepareStatement(sql).use { pstmt ->
            pstmt.setString(1, path)
            val rs = pstmt.executeQuery()

            if (rs.next()) {
                rs.getInt("id") // Нашли ID
            } else {
                null // Файла еще нет в базе
            }
        }
    }

    fun getOrInsertFileId(path: String): Int {
        // 1. Пытаемся найти существующий
        val existingId = getExistingFileId(path)
        if (existingId != null) return existingId

        // 2. Если не нашли — вставляем новый
        val insertSql = "INSERT INTO files (path) VALUES (?)"
        conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS).use { pstmt ->
            pstmt.setString(1, path)
            pstmt.executeUpdate()

            val rs = pstmt.generatedKeys
            if (rs.next()) return rs.getInt(1)
        }

        throw SQLException("Failed to get/insert file ID for $path")
    }

    fun clearData() {
        conn.createStatement().use { it.execute("DELETE FROM files") } // Каскадом удалит остальное, если настроить, или чисти всё вручную
    }
}