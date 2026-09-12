package com.ergouf.gecis.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Local-only conversation library: project folders -> conversations -> messages. */
class ChatHistoryStore(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // History access is serialized by MainActivity. WAL adds no benefit here and has shown
        // device/ROM compatibility issues when enabled from onConfigure, so keep the default
        // single-file journal mode and only enable foreign-key enforcement.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE conversations (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              project_id INTEGER NOT NULL,
              title TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              agy_conversation_id TEXT,
              FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              conversation_id INTEGER NOT NULL,
              role TEXT NOT NULL CHECK(role IN ('user','assistant')),
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_conversations_project_updated ON conversations(project_id, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_conversation_id ON messages(conversation_id, id)")
        ensureDefaultProject(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE conversations ADD COLUMN agy_conversation_id TEXT")
            }
        }
    }

    @Synchronized
    fun setAgyConversationId(conversationId: Long, agyId: String) {
        if (agyId.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE conversations SET agy_conversation_id=? WHERE id=?",
            arrayOf(agyId.trim(), conversationId),
        )
    }

    @Synchronized
    fun getAgyConversationId(conversationId: Long): String? {
        return readableDatabase.rawQuery(
            "SELECT agy_conversation_id FROM conversations WHERE id=?",
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }

    @Synchronized
    fun conversationTitle(conversationId: Long): String {
        return readableDatabase.rawQuery(
            "SELECT title FROM conversations WHERE id=?",
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "会话"
        }
    }

    @Synchronized
    fun listMessages(conversationId: Long): List<Pair<String, String>> {
        return readableDatabase.rawQuery(
            "SELECT role, content FROM messages WHERE conversation_id=? ORDER BY id ASC",
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getString(1))
                }
            }
        }
    }

    @Synchronized
    fun ensureDefaultProject(): Long = ensureDefaultProject(writableDatabase)

    @Synchronized
    fun createProject(rawName: String): Long {
        val name = rawName.trim().take(MAX_PROJECT_NAME)
        require(name.isNotBlank()) { "项目名称不能为空" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO projects(name, created_at, updated_at) VALUES(?, ?, ?)",
            arrayOf(name, now, now),
        )
        return lastInsertRowId(db)
    }

    @Synchronized
    fun createConversation(projectId: Long?): Long {
        val db = writableDatabase
        val resolvedProject = projectId?.takeIf { projectExists(db, it) } ?: ensureDefaultProject(db)
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO conversations(project_id, title, created_at, updated_at) VALUES(?, ?, ?, ?)",
            arrayOf(resolvedProject, DEFAULT_CONVERSATION_TITLE, now, now),
        )
        return lastInsertRowId(db)
    }

    @Synchronized
    fun conversationExists(conversationId: Long): Boolean =
        conversationExists(readableDatabase, conversationId)

    @Synchronized
    fun projectExists(projectId: Long): Boolean = projectExists(readableDatabase, projectId)

    @Synchronized
    fun appendMessage(conversationId: Long, role: String, content: String) {
        require(role == "user" || role == "assistant") { "不支持的消息角色" }
        val text = content.trim()
        if (text.isBlank()) return

        val db = writableDatabase
        require(conversationExists(db, conversationId)) { "历史会话不存在：$conversationId" }
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?, ?, ?, ?)",
                arrayOf(conversationId, role, text, now),
            )
            if (role == "user") {
                db.rawQuery(
                    "SELECT title FROM conversations WHERE id=?",
                    arrayOf(conversationId.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getString(0) == DEFAULT_CONVERSATION_TITLE) {
                        db.execSQL(
                            "UPDATE conversations SET title=? WHERE id=?",
                            arrayOf(titleFrom(text), conversationId),
                        )
                    }
                }
            }
            db.execSQL(
                "UPDATE conversations SET updated_at=? WHERE id=?",
                arrayOf(now, conversationId),
            )
            db.execSQL(
                "UPDATE projects SET updated_at=? WHERE id=(SELECT project_id FROM conversations WHERE id=?)",
                arrayOf(now, conversationId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun moveConversation(conversationId: Long, projectId: Long) {
        val db = writableDatabase
        require(conversationExists(db, conversationId)) { "历史会话不存在：$conversationId" }
        require(projectExists(db, projectId)) { "目标项目不存在：$projectId" }
        val now = System.currentTimeMillis()
        db.execSQL(
            "UPDATE conversations SET project_id=?, updated_at=? WHERE id=?",
            arrayOf(projectId, now, conversationId),
        )
        db.execSQL("UPDATE projects SET updated_at=? WHERE id=?", arrayOf(now, projectId))
    }

    @Synchronized
    fun snapshot(currentConversationId: Long?): String {
        val db = readableDatabase
        val current = currentConversationId?.takeIf { conversationExists(db, it) }
        val root = JSONObject()
        val projects = JSONArray()

        db.rawQuery(
            "SELECT id, name FROM projects ORDER BY CASE WHEN name=? THEN 0 ELSE 1 END, updated_at DESC, id ASC",
            arrayOf(DEFAULT_PROJECT_NAME),
        ).use { projectCursor ->
            while (projectCursor.moveToNext()) {
                val projectId = projectCursor.getLong(0)
                val project = JSONObject()
                    .put("id", projectId)
                    .put("name", projectCursor.getString(1))
                val conversations = JSONArray()
                db.rawQuery(
                    "SELECT id, title, updated_at FROM conversations WHERE project_id=? ORDER BY updated_at DESC, id DESC",
                    arrayOf(projectId.toString()),
                ).use { conversationCursor ->
                    while (conversationCursor.moveToNext()) {
                        conversations.put(
                            JSONObject()
                                .put("id", conversationCursor.getLong(0))
                                .put("title", conversationCursor.getString(1))
                                .put("updatedAt", conversationCursor.getLong(2)),
                        )
                    }
                }
                project.put("conversations", conversations)
                projects.put(project)
            }
        }

        root.put("projects", projects)
        if (current != null) {
            root.put("currentConversationId", current)
            root.put("messages", messagesJson(db, current))
            root.put("currentProjectId", projectIdForConversation(db, current))
        } else {
            root.put("currentConversationId", JSONObject.NULL)
            root.put("currentProjectId", ensureDefaultProjectId(db))
            root.put("messages", JSONArray())
        }
        return root.toString()
    }

    private fun messagesJson(db: SQLiteDatabase, conversationId: Long): JSONArray {
        val messages = JSONArray()
        db.rawQuery(
            "SELECT id, role, content, created_at FROM messages WHERE conversation_id=? ORDER BY id ASC",
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages.put(
                    JSONObject()
                        .put("id", cursor.getLong(0))
                        .put("role", cursor.getString(1))
                        .put("content", cursor.getString(2))
                        .put("createdAt", cursor.getLong(3)),
                )
            }
        }
        return messages
    }

    private fun projectIdForConversation(db: SQLiteDatabase, conversationId: Long): Long = db.rawQuery(
        "SELECT project_id FROM conversations WHERE id=?",
        arrayOf(conversationId.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else ensureDefaultProjectId(db)
    }

    private fun ensureDefaultProject(db: SQLiteDatabase): Long {
        val existing = ensureDefaultProjectId(db)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO projects(name, created_at, updated_at) VALUES(?, ?, ?)",
            arrayOf(DEFAULT_PROJECT_NAME, now, now),
        )
        return ensureDefaultProjectId(db)
    }

    private fun ensureDefaultProjectId(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT id FROM projects WHERE name=? ORDER BY id ASC LIMIT 1",
        arrayOf(DEFAULT_PROJECT_NAME),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun conversationExists(db: SQLiteDatabase, conversationId: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM conversations WHERE id=? LIMIT 1",
        arrayOf(conversationId.toString()),
    ).use { it.moveToFirst() }

    private fun projectExists(db: SQLiteDatabase, projectId: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM projects WHERE id=? LIMIT 1",
        arrayOf(projectId.toString()),
    ).use { it.moveToFirst() }

    private fun lastInsertRowId(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT last_insert_rowid()",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst()) { "无法读取 SQLite 插入 ID" }
        cursor.getLong(0)
    }

    private fun titleFrom(text: String): String {
        val singleLine = text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= MAX_TITLE_CHARS) singleLine else singleLine.take(MAX_TITLE_CHARS) + "…"
    }

    companion object {
        private const val DATABASE_NAME = "gecis_history.db"
        private const val DATABASE_VERSION = 2
        private const val DEFAULT_PROJECT_NAME = "未分类"
        private const val DEFAULT_CONVERSATION_TITLE = "新对话"
        private const val MAX_PROJECT_NAME = 40
        private const val MAX_TITLE_CHARS = 24
    }
}
