package com.ergouf.gecis.knowledge

import android.database.sqlite.SQLiteDatabase

internal object FenbiQuestionRepository {
    fun requireSupportedSchema(db: SQLiteDatabase) {
        require(hasQuestionTable(db)) {
            "所选 SQLite 未找到 Fenbi 题目表（fenbi_paper_questions / questions）"
        }
    }

    private fun hasQuestionTable(db: SQLiteDatabase): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN (?, ?)",
            arrayOf("fenbi_paper_questions", "questions"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(0) ?: continue
                if (hasIdAndStem(db, table)) return true
            }
        }
        return false
    }

    private fun hasIdAndStem(db: SQLiteDatabase, table: String): Boolean {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { info ->
            val nameIndex = info.getColumnIndex("name")
            while (info.moveToNext()) {
                if (nameIndex >= 0 && !info.isNull(nameIndex)) {
                    columns += info.getString(nameIndex)
                }
            }
        }
        return "id" in columns && "stem" in columns
    }

    private fun quote(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""
}
