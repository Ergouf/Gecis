package com.ergouf.gecis.knowledge

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class FenbiSqlGateway(private val dbFile: File) {
    fun schema(): JSONObject = openReadOnly().use { db ->
        val listed = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                listed += cursor.getString(0) to cursor.getString(1)
            }
        }
        val truncated = listed.size > FenbiSql.MAX_TABLES
        val tables = JSONArray()
        listed.take(FenbiSql.MAX_TABLES).forEach { (name, kind) ->
            val (columns, colTruncated) = tableColumns(db, name)
            val indexes = if (kind.equals("table", ignoreCase = true)) tableIndexes(db, name) else JSONArray()
            val approx = if (kind.equals("table", ignoreCase = true)) approxRows(db, name) else JSONObject.NULL
            tables.put(
                JSONObject()
                    .put("name", name)
                    .put("type", kind)
                    .put("approxRows", approx)
                    .put("approxRowsNote", "MAX(rowid), not COUNT(*)")
                    .put("columns", columns)
                    .put("indexes", indexes)
                    .put("truncated", colTruncated),
            )
        }
        JSONObject().put("tables", tables).put("truncated", truncated)
    }

    fun getRows(table: String, ids: JSONArray): JSONObject {
        require(table.isNotEmpty()) { "table is required" }
        require(ids.length() in 1..FenbiSql.MAX_IDS) { "ids must contain 1–20 values" }
        return openReadOnly().use { db ->
            require(userTableExists(db, table)) { "unknown table $table" }
            require(columnExists(db, table, "id")) { "table $table has no id column; use fenbi_query" }
            val placeholders = List(ids.length()) { "?" }.joinToString(",")
            val sql =
                "SELECT * FROM ${FenbiSql.quote(table)} WHERE ${FenbiSql.quote("id")} IN ($placeholders) LIMIT ${FenbiSql.MAX_IDS}"
            val args = Array(ids.length()) { i -> jsonToId(ids.get(i)) }
            serializeQuery(db, sql, args, timeoutMs = 5_000L)
        }
    }

    fun query(sql: String): JSONObject {
        val prepared = FenbiSql.prepare(sql)
        return openReadOnly().use { db ->
            runCatching { db.rawQuery("PRAGMA query_only=ON", null).close() }
            serializeQuery(db, prepared.sql, emptyArray(), FenbiSql.QUERY_TIMEOUT_MS)
        }
    }

    private fun openReadOnly(): SQLiteDatabase {
        require(dbFile.isFile) { FenbiSql.NOT_IMPORTED }
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        runCatching { db.rawQuery("PRAGMA query_only=ON", null).close() }
        runCatching { db.rawQuery("PRAGMA temp_store=MEMORY", null).close() }
        return db
    }

    private fun serializeQuery(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
        timeoutMs: Long,
    ): JSONObject {
        val signal = CancellationSignal()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val cancel = scheduler.schedule({ signal.cancel() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            db.rawQuery(sql, args.takeIf { it.isNotEmpty() }, signal).use { cursor -> serializeCursor(cursor) }
        } catch (error: OperationCanceledException) {
            throw IllegalStateException(FenbiSql.TIMEOUT_MESSAGE)
        } catch (error: Throwable) {
            val message = error.message.orEmpty()
            if (message.contains("cancel", ignoreCase = true) || message.contains("interrupt", ignoreCase = true)) {
                throw IllegalStateException(FenbiSql.TIMEOUT_MESSAGE)
            }
            throw error
        } finally {
            cancel.cancel(false)
            scheduler.shutdownNow()
        }
    }

    private fun serializeCursor(cursor: Cursor): JSONObject {
        val columns = JSONArray()
        val names = Array(cursor.columnCount) { i ->
            val name = cursor.getColumnName(i)
            columns.put(name)
            name
        }
        val rows = JSONArray()
        var truncated = false
        var used = 0
        while (cursor.moveToNext()) {
            if (rows.length() >= FenbiSql.MAX_ROWS) {
                truncated = true
                break
            }
            val obj = JSONObject()
            names.forEachIndexed { idx, name -> obj.put(name, cellJson(cursor, idx)) }
            val encoded = obj.toString()
            if (used + encoded.length > FenbiSql.MAX_RESULT_BYTES && rows.length() > 0) {
                truncated = true
                break
            }
            used += encoded.length
            rows.put(obj)
        }
        return JSONObject()
            .put("columns", columns)
            .put("rows", rows)
            .put("truncated", truncated)
    }

    private fun cellJson(cursor: Cursor, idx: Int): Any {
        return when (cursor.getType(idx)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx)
            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("\$blobBytes", cursor.getBlob(idx).size)
            else -> clipText(cursor.getString(idx) ?: "")
        }
    }

    private fun clipText(text: String): String {
        val count = text.length
        if (count <= FenbiSql.MAX_TEXT_CHARS) return text
        val clipped = text.take(FenbiSql.MAX_TEXT_CHARS)
        return "$clipped…[truncated ${count - FenbiSql.MAX_TEXT_CHARS} chars]"
    }

    private fun userTableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE name = ? AND type IN ('table','view') AND name NOT LIKE 'sqlite_%'",
            arrayOf(table),
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getLong(0) > 0
        }
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info(${FenbiSql.quote(table)})", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Pair<JSONArray, Boolean> {
        val rows = JSONArray()
        db.rawQuery("PRAGMA table_info(${FenbiSql.quote(table)})", null).use { cursor ->
            val name = cursor.getColumnIndex("name")
            val type = cursor.getColumnIndex("type")
            val notnull = cursor.getColumnIndex("notnull")
            val pk = cursor.getColumnIndex("pk")
            while (cursor.moveToNext()) {
                rows.put(
                    JSONObject()
                        .put("name", cursor.getString(name))
                        .put("type", if (type >= 0) cursor.getString(type).orEmpty() else "")
                        .put("notnull", if (notnull >= 0) cursor.getLong(notnull) else 0)
                        .put("pk", if (pk >= 0) cursor.getLong(pk) else 0),
                )
            }
        }
        val truncated = rows.length() > FenbiSql.MAX_COLUMNS
        if (!truncated) return rows to false
        val clipped = JSONArray()
        for (i in 0 until FenbiSql.MAX_COLUMNS) clipped.put(rows.getJSONObject(i))
        return clipped to true
    }

    private fun tableIndexes(db: SQLiteDatabase, table: String): JSONArray {
        val out = JSONArray()
        val listed = mutableListOf<Pair<String, Boolean>>()
        db.rawQuery("PRAGMA index_list(${FenbiSql.quote(table)})", null).use { cursor ->
            val name = cursor.getColumnIndex("name")
            val unique = cursor.getColumnIndex("unique")
            while (cursor.moveToNext()) {
                listed += cursor.getString(name) to (unique >= 0 && cursor.getLong(unique) != 0L)
            }
        }
        listed.forEach { (indexName, unique) ->
            val columns = JSONArray()
            db.rawQuery("PRAGMA index_info(${FenbiSql.quote(indexName)})", null).use { cursor ->
                val col = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (col >= 0) columns.put(cursor.getString(col))
                }
            }
            out.put(
                JSONObject()
                    .put("name", indexName)
                    .put("unique", unique)
                    .put("columns", columns),
            )
        }
        return out
    }

    private fun approxRows(db: SQLiteDatabase, table: String): Any {
        db.rawQuery("SELECT MAX(rowid) FROM ${FenbiSql.quote(table)}", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return JSONObject.NULL
            return cursor.getLong(0)
        }
    }

    private fun jsonToId(value: Any): String = when (value) {
        is Number -> value.toString()
        JSONObject.NULL -> ""
        else -> value.toString()
    }
}
