package com.ergouf.gecis.knowledge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File

/** Owns the app-private read-only copy of fenbi.db. */
class FenbiKnowledgeBase(private val context: Context) {
    enum class ImportPhase {
        COPYING,
        VALIDATING,
        SAVING,
        INDEXING,
    }

    data class ImportProgress(
        val phase: ImportPhase,
        val bytesCopied: Long = 0L,
        val totalBytes: Long? = null,
    ) {
        val fraction: Double?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { (bytesCopied.toDouble() / it.toDouble()).coerceIn(0.0, 1.0) }
    }

    private val databaseDir = File(context.noBackupFilesDir, "knowledge").apply { mkdirs() }
    private val databaseFile = File(databaseDir, DATABASE_NAME)
    private val lock = Any()

    fun hasDatabase(): Boolean = databaseFile.isFile && databaseFile.length() > SQLITE_HEADER.size

    fun databaseFilePath(): File = databaseFile

    fun importedDisplayName(uri: Uri): String? = queryDocumentMetadata(uri).first

    fun importFrom(
        uri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ) {
        synchronized(lock) {
            databaseDir.mkdirs()
            val temp = File(databaseDir, "$DATABASE_NAME.importing")
            temp.delete()
            val totalBytes = queryDocumentMetadata(uri).second

            try {
                onProgress(ImportProgress(ImportPhase.COPYING, totalBytes = totalBytes))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var copied = 0L
                        var lastProgressAt = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            copied += count

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                                (totalBytes != null && copied >= totalBytes)
                            ) {
                                onProgress(
                                    ImportProgress(
                                        ImportPhase.COPYING,
                                        bytesCopied = copied,
                                        totalBytes = totalBytes,
                                    ),
                                )
                                lastProgressAt = now
                            }
                        }
                        output.flush()
                        onProgress(
                            ImportProgress(
                                ImportPhase.COPYING,
                                bytesCopied = copied,
                                totalBytes = totalBytes ?: copied,
                            ),
                        )
                    }
                } ?: throw IllegalArgumentException("无法读取所选 fenbi.db")

                onProgress(
                    ImportProgress(
                        ImportPhase.VALIDATING,
                        bytesCopied = temp.length(),
                        totalBytes = totalBytes ?: temp.length(),
                    ),
                )
                validateDatabase(temp)

                onProgress(
                    ImportProgress(
                        ImportPhase.SAVING,
                        bytesCopied = temp.length(),
                        totalBytes = totalBytes ?: temp.length(),
                    ),
                )
                val previous = File(databaseDir, "$DATABASE_NAME.previous")
                previous.delete()
                if (databaseFile.exists() && !databaseFile.renameTo(previous)) {
                    throw IllegalStateException("无法替换旧的 fenbi.db")
                }
                if (!temp.renameTo(databaseFile)) {
                    if (previous.exists()) previous.renameTo(databaseFile)
                    throw IllegalStateException("无法保存 fenbi.db")
                }
                previous.delete()
                onProgress(
                    ImportProgress(
                        ImportPhase.INDEXING,
                        bytesCopied = databaseFile.length(),
                        totalBytes = totalBytes ?: databaseFile.length(),
                    ),
                )
                runCatching { ensureIndexes() }
            } finally {
                temp.delete()
            }
        }
    }

    private fun ensureIndexes() {
        if (!hasDatabase()) return
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { db ->
            INDEX_SPECS.forEach { (table, column, indexName) ->
                runCatching { createIndexIfNeeded(db, table, column, indexName) }
            }
        }
    }

    private fun createIndexIfNeeded(db: SQLiteDatabase, table: String, column: String, indexName: String) {
        if (!tableHasColumn(db, table, column) || columnIsIndexed(db, table, column)) return
        db.execSQL("CREATE INDEX IF NOT EXISTS ${quoteIdent(indexName)} ON ${quoteIdent(table)} (${quoteIdent(column)})")
    }

    private fun tableHasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info(${quoteIdent(table)})", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun columnIsIndexed(db: SQLiteDatabase, table: String, column: String): Boolean {
        val names = mutableListOf<String>()
        db.rawQuery("PRAGMA index_list(${quoteIdent(table)})", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) names += cursor.getString(nameIndex)
            }
        }
        names.forEach { indexName ->
            db.rawQuery("PRAGMA index_info(${quoteIdent(indexName)})", null).use { cursor ->
                val col = cursor.getColumnIndex("name")
                if (cursor.moveToFirst() && col >= 0 && cursor.getString(col) == column) {
                    return true
                }
            }
        }
        return false
    }

    private fun quoteIdent(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""

    private fun queryDocumentMetadata(uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            name to size?.takeIf { it > 0L }
        } ?: (null to null)
    }.getOrDefault(null to null)

    private fun validateDatabase(file: File) {
        require(file.length() >= SQLITE_HEADER.size) { "所选文件不是有效的 SQLite 数据库" }
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            val read = input.read(header)
            require(read == SQLITE_HEADER.size && header.contentEquals(SQLITE_HEADER)) {
                "所选文件不是有效的 SQLite 数据库"
            }
        }

        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        db.use {
            val result = it.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else ""
            }
            require(result.equals("ok", ignoreCase = true)) {
                "fenbi.db 完整性检查失败：$result"
            }
            FenbiQuestionRepository.requireSupportedSchema(it)
        }
    }

    companion object {
        private const val DATABASE_NAME = "fenbi.db"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 120L
        private val INDEX_SPECS = listOf(
            Triple("fenbi_paper_questions", "paper_id", "gecis_idx_fpq_paper_id"),
            Triple("fenbi_paper_questions", "question_id", "gecis_idx_fpq_question_id"),
            Triple("fenbi_paper_questions", "fenbi_question_id", "gecis_idx_fpq_fenbi_question_id"),
            Triple("fenbi_paper_questions", "section_name", "gecis_idx_fpq_section_name"),
            Triple("fenbi_paper_questions", "subject", "gecis_idx_fpq_subject"),
            Triple("questions", "paper_id", "gecis_idx_q_paper_id"),
            Triple("images", "image_hash", "gecis_idx_images_image_hash"),
        )

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
