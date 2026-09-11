package com.ergouf.gecis.knowledge

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * Dedicated read-only adapter for the Fenbi schemas already used by pdf2ppt.
 *
 * Authoritative source: fenbi_paper_questions
 * Legacy fallback: questions
 */
internal class FenbiQuestionRepository(
    private val db: SQLiteDatabase,
) {
    data class Match(
        val source: String,
        val text: String,
        val score: Int,
    )

    private val tables = discoverTables(db)

    init {
        require(tables.isNotEmpty()) {
            "未找到支持的 Fenbi 题目表（fenbi_paper_questions / questions）"
        }
    }

    fun retrieve(query: String, limit: Int): List<Match> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val terms = searchTerms(query)
        if (terms.isEmpty()) return emptyList()

        val candidates = mutableListOf<ScoredQuestion>()

        // Search question stems first. This is the most authoritative signal and avoids scanning
        // long analysis text unless the stem search cannot fill the requested context window.
        for (table in tables) {
            candidates += queryTable(
                table = table,
                terms = terms,
                fields = listOfNotNull(table.stemColumn),
                perTableLimit = candidateLimit(limit),
                query = query,
            )
        }

        if (candidates.distinctBy { it.dedupeKey }.size < limit) {
            for (table in tables) {
                val secondary = listOfNotNull(
                    table.analysisColumn,
                    table.optionsColumn,
                    table.optionsJsonColumn,
                )
                if (secondary.isEmpty()) continue
                candidates += queryTable(
                    table = table,
                    terms = terms,
                    fields = secondary,
                    perTableLimit = candidateLimit(limit),
                    query = query,
                )
            }
        }

        val bestByQuestion = linkedMapOf<String, ScoredQuestion>()
        for (candidate in candidates) {
            val previous = bestByQuestion[candidate.dedupeKey]
            if (previous == null || candidate.score > previous.score) {
                bestByQuestion[candidate.dedupeKey] = candidate
            }
        }

        return bestByQuestion.values
            .sortedWith(
                compareByDescending<ScoredQuestion> { it.score }
                    .thenBy { it.tableRank }
                    .thenBy { sortableId(it.question.id) },
            )
            .take(limit)
            .map {
                Match(
                    source = "${it.question.sourceTable}#${it.question.id}",
                    text = renderQuestion(it.question),
                    score = it.score,
                )
            }
    }

    private fun queryTable(
        table: TableBinding,
        terms: List<String>,
        fields: List<String>,
        perTableLimit: Int,
        query: String,
    ): List<ScoredQuestion> {
        if (fields.isEmpty()) return emptyList()

        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (term in terms) {
            for (field in fields) {
                where += "${quote(field)} LIKE ? ESCAPE '\\'"
                args += "%${escapeLike(term)}%"
            }
        }
        if (where.isEmpty()) return emptyList()

        val sql = """
            SELECT
              ${expr(table.idColumn, "id")},
              ${expr(table.stemColumn, "stem")},
              ${expr(table.optionsColumn, "options")},
              ${expr(table.optionsJsonColumn, "options_json")},
              ${expr(table.answerColumn, "answer")},
              ${expr(table.correctRatioColumn, "correct_ratio")},
              ${expr(table.wrongAnswerColumn, "wrong_answer")},
              ${expr(table.analysisColumn, "analysis")}
            FROM ${quote(table.name)}
            WHERE ${where.joinToString(" OR ")}
            LIMIT $perTableLimit
        """.trimIndent()

        val output = mutableListOf<ScoredQuestion>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val question = QuestionRow(
                    sourceTable = table.name,
                    id = cursor.getString(0).orEmpty(),
                    stem = htmlToText(cursor.getString(1)),
                    options = parseOptions(cursor.getString(2), cursor.getString(3)),
                    answer = canonicalAnswer(cursor.getString(4)),
                    accuracy = formatAccuracy(cursor.getString(5), cursor.getString(6)),
                    wrongAnswer = canonicalAnswer(cursor.getString(6)),
                    analysis = cleanAnalysis(cursor.getString(7)),
                )
                if (question.stem.isBlank() && question.analysis.isBlank()) continue
                output += ScoredQuestion(
                    question = question,
                    score = score(question, query, terms, table.rank),
                    tableRank = table.rank,
                    dedupeKey = dedupeKey(question),
                )
            }
        }
        return output
    }

    private fun score(
        question: QuestionRow,
        query: String,
        terms: List<String>,
        tableRank: Int,
    ): Int {
        val queryId = identity(stripPromptPrefix(query))
        val stemId = identity(question.stem)
        val analysisId = identity(question.analysis)
        val optionId = identity(question.options.joinToString(" "))

        var score = if (tableRank == 0) 40 else 0
        if (queryId.length >= 2) {
            if (stemId == queryId) score += 1200
            if (stemId.contains(queryId)) score += 420
            if (analysisId.contains(queryId)) score += 180
            if (optionId.contains(queryId)) score += 100
        }

        for (term in terms) {
            val termId = identity(term)
            if (termId.length < 2) continue
            val lengthBonus = termId.length.coerceAtMost(10) * 3
            if (stemId.contains(termId)) score += 100 + lengthBonus
            if (analysisId.contains(termId)) score += 38 + lengthBonus
            if (optionId.contains(termId)) score += 20 + lengthBonus
        }

        if (question.answer.isNotBlank()) score += 5
        if (question.analysis.isNotBlank()) score += 8
        if (question.options.size == 4) score += 5
        return score
    }

    private fun renderQuestion(question: QuestionRow): String = buildString {
        append("题目ID：").append(question.id).append('\n')
        append("题干：").append(question.stem.take(MAX_STEM_CHARS)).append('\n')
        if (question.options.isNotEmpty()) {
            append("选项：\n")
            question.options.take(6).forEachIndexed { index, option ->
                val label = ('A'.code + index).toChar()
                append(label).append(". ").append(option.take(MAX_OPTION_CHARS)).append('\n')
            }
        }
        if (question.answer.isNotBlank()) {
            append("正确答案：").append(question.answer).append('\n')
        }
        if (question.accuracy.isNotBlank()) {
            append(question.accuracy).append('\n')
        } else if (question.wrongAnswer.isNotBlank()) {
            append("易错项：").append(question.wrongAnswer).append('\n')
        }
        if (question.analysis.isNotBlank()) {
            append("解析：").append(question.analysis.take(MAX_ANALYSIS_CHARS)).append('\n')
        }
        append("来源：").append(question.sourceTable)
    }

    private fun parseOptions(rawOptions: String?, rawOptionsJson: String?): List<String> {
        parseJsonOptions(rawOptionsJson)?.let { if (it.size == 4) return it }
        parseJsonOptions(rawOptions)?.let { if (it.size == 4) return it }

        val text = htmlToText(rawOptions)
        if (text.isBlank()) return emptyList()
        val matches = RAW_OPTION_RE.findAll(text)
            .map { it.groupValues[2].trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (matches.size == 4) return matches

        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { OPTION_LABEL_RE.replace(it, "").trim() }
            .filter { it.isNotBlank() }
            .toList()
        return if (lines.size == 4) lines else emptyList()
    }

    private fun parseJsonOptions(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.opt(index)
                    val value = when (item) {
                        is JSONObject -> item.optString("text").ifBlank { item.optString("value") }
                        null -> ""
                        else -> item.toString()
                    }
                    val cleaned = htmlToText(value)
                    if (cleaned.isNotBlank()) add(OPTION_LABEL_RE.replace(cleaned, "").trim())
                }
            }
        }.getOrNull()
    }

    private fun searchTerms(query: String): List<String> {
        val cleaned = htmlToText(query).replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return emptyList()

        val output = linkedSetOf<String>()
        val stripped = stripPromptPrefix(cleaned)
        if (stripped.length >= 2) output += stripped

        val chunks = stripped.split(TERM_SPLIT_RE)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .sortedByDescending { it.length }

        for (chunk in chunks) {
            output += chunk
            if (chunk.length in 5..12 && containsCjk(chunk)) {
                for (start in 0..(chunk.length - 4)) {
                    output += chunk.substring(start, start + 4)
                    if (output.size >= MAX_TERMS) break
                }
            } else if (chunk.length > 12) {
                val window = 8
                output += chunk.take(window)
                val middle = ((chunk.length - window) / 2).coerceAtLeast(0)
                output += chunk.substring(middle, middle + window)
                output += chunk.takeLast(window)
            }
            if (output.size >= MAX_TERMS) break
        }

        return output
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_TERMS)
    }

    private fun stripPromptPrefix(value: String): String {
        var result = value.trim()
        var changed: Boolean
        do {
            changed = false
            for (prefix in QUERY_PREFIXES) {
                if (result.startsWith(prefix) && result.length > prefix.length + 1) {
                    result = result.removePrefix(prefix).trimStart('：', ':', '，', ',', ' ')
                    changed = true
                    break
                }
            }
        } while (changed)
        return result
    }

    private fun htmlToText(value: String?): String {
        var text = value.orEmpty().replace('\u000b', '\n')
        text = BLOCK_TAG_RE.replace(text, "\n")
        text = TAG_RE.replace(text, "")
        text = decodeHtmlEntities(text)
        text = text.replace('\u00a0', ' ')
        text = HORIZONTAL_SPACE_RE.replace(text, " ")
        text = AROUND_NEWLINE_RE.replace(text, "\n")
        text = MANY_NEWLINES_RE.replace(text, "\n\n")
        return text.trim()
    }

    private fun cleanAnalysis(value: String?): String {
        var text = htmlToText(value)
        text = SOURCE_TAIL_RE.replace(text, "")
        text = METADATA_TAIL_RE.replace(text, "")
        text = LEADING_ANALYSIS_HEADER_RE.replace(text, "")
        return text.trim()
    }

    private fun canonicalAnswer(value: String?): String {
        var text = htmlToText(value).trim().uppercase()
            .replace('Ａ', 'A').replace('Ｂ', 'B').replace('Ｃ', 'C').replace('Ｄ', 'D')
        for (prefix in ANSWER_PREFIXES) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }
        text = text.trim(' ', '.', '．', '、')
        return if (text.length == 1 && text[0] in 'A'..'D') text else text.take(MAX_ANSWER_CHARS)
    }

    private fun formatAccuracy(rawValue: String?, wrongAnswer: String?): String {
        val raw = htmlToText(rawValue)
        val match = NUMBER_RE.find(raw) ?: return ""
        var number = match.value.toDoubleOrNull() ?: return ""
        if (!number.isFinite()) return ""
        val hasPercent = raw.contains('%') || raw.contains('％')
        if (!hasPercent && number in 0.0..1.0) number *= 100.0
        if (number !in 0.0..100.0) return ""

        val rounded = round(number * 100.0) / 100.0
        val percent = if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            "%.2f".format(java.util.Locale.ROOT, rounded).trimEnd('0').trimEnd('.')
        }
        val wrong = canonicalAnswer(wrongAnswer).takeIf { it.length == 1 && it[0] in 'A'..'D' }
        return buildString {
            append("正确率").append(percent).append('%')
            if (wrong != null) append("  易错项").append(wrong)
        }
    }

    private fun decodeHtmlEntities(value: String): String {
        var result = value
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
        result = NUMERIC_ENTITY_RE.replace(result) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x", ignoreCase = true)) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            code?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
        }
        return result
    }

    private fun identity(value: String): String = htmlToText(value)
        .lowercase()
        .replace(IDENTITY_NOISE_RE, "")

    private fun dedupeKey(question: QuestionRow): String = buildString {
        append(identity(question.stem))
        append('|')
        question.options.forEach { append(identity(it)).append('|') }
    }

    private fun candidateLimit(limit: Int): Int = (limit * 10).coerceIn(32, 96)

    private fun sortableId(value: String): String =
        value.toLongOrNull()?.let { "%020d".format(java.util.Locale.ROOT, it) } ?: value

    private fun expr(column: String?, alias: String): String =
        if (column == null) "NULL AS ${quote(alias)}" else "${quote(column)} AS ${quote(alias)}"

    private fun quote(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun containsCjk(value: String): Boolean = value.any { it.code in 0x3400..0x9FFF }

    private data class QuestionRow(
        val sourceTable: String,
        val id: String,
        val stem: String,
        val options: List<String>,
        val answer: String,
        val accuracy: String,
        val wrongAnswer: String,
        val analysis: String,
    )

    private data class ScoredQuestion(
        val question: QuestionRow,
        val score: Int,
        val tableRank: Int,
        val dedupeKey: String,
    )

    private data class TableBinding(
        val name: String,
        val rank: Int,
        val idColumn: String,
        val stemColumn: String,
        val optionsColumn: String?,
        val optionsJsonColumn: String?,
        val answerColumn: String?,
        val correctRatioColumn: String?,
        val wrongAnswerColumn: String?,
        val analysisColumn: String?,
    )

    companion object {
        private const val MAX_TERMS = 8
        private const val MAX_STEM_CHARS = 2_200
        private const val MAX_OPTION_CHARS = 900
        private const val MAX_ANALYSIS_CHARS = 3_200
        private const val MAX_ANSWER_CHARS = 40

        private val TAG_RE = Regex("<[^>]+>")
        private val BLOCK_TAG_RE = Regex("</?(?:p|div|br|li|tr|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val OPTION_LABEL_RE = Regex("^\\s*[A-D][.．、]\\s*", RegexOption.IGNORE_CASE)
        private val RAW_OPTION_RE = Regex(
            "(?:^|\\s|\\|)([A-D])[.．、]\\s*(.*?)(?=(?:(?:\\s*\\|\\s*|\\s+)[A-D][.．、]\\s*)|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val SOURCE_TAIL_RE = Regex("\\s*【文段出处】.*$", RegexOption.DOT_MATCHES_ALL)
        private val METADATA_TAIL_RE = Regex(
            "\\s*【正确率】\\s*\\d+(?:\\.\\d+)?\\s*[%％]?(?:\\s*【易错项】\\s*[A-D])?.*$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val LEADING_ANALYSIS_HEADER_RE = Regex(
            "^(?:【答案】\\s*[A-D]\\s*)?(?:【解析】\\s*)+",
            RegexOption.IGNORE_CASE,
        )
        private val TERM_SPLIT_RE = Regex("[\\s，。！？；：,.!?;:、（）()\\[\\]{}<>《》]+")
        private val HORIZONTAL_SPACE_RE = Regex("[ \\t]+")
        private val AROUND_NEWLINE_RE = Regex("\\s*\\n\\s*")
        private val MANY_NEWLINES_RE = Regex("\\n{3,}")
        private val NUMBER_RE = Regex("\\d+(?:\\.\\d+)?")
        private val NUMERIC_ENTITY_RE = Regex("&#(x?[0-9A-Fa-f]+);")
        private val IDENTITY_NOISE_RE = Regex("[\\p{P}\\p{Z}\\s]+")
        private val ANSWER_PREFIXES = listOf("【答案】", "答案：", "答案:")
        private val QUERY_PREFIXES = listOf(
            "请解释一下", "请解释", "解释一下", "解释", "请问一下", "请问",
            "什么是", "怎么做", "如何理解", "如何", "帮我解释", "帮我看一下", "帮我看",
        )

        fun requireSupportedSchema(db: SQLiteDatabase) {
            require(discoverTables(db).isNotEmpty()) {
                "所选 SQLite 未找到 Fenbi 题目表（fenbi_paper_questions / questions）"
            }
        }

        private fun discoverTables(db: SQLiteDatabase): List<TableBinding> {
            val available = mutableMapOf<String, Set<String>>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN (?, ?)",
                arrayOf("fenbi_paper_questions", "questions"),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0) ?: continue
                    val columns = mutableSetOf<String>()
                    db.rawQuery("PRAGMA table_info(${quoteStatic(table)})", null).use { info ->
                        val nameIndex = info.getColumnIndex("name")
                        while (info.moveToNext()) {
                            if (nameIndex >= 0 && !info.isNull(nameIndex)) {
                                columns += info.getString(nameIndex)
                            }
                        }
                    }
                    available[table] = columns
                }
            }

            val bindings = mutableListOf<TableBinding>()
            available["fenbi_paper_questions"]?.let { columns ->
                if ("id" in columns && "stem" in columns) {
                    bindings += TableBinding(
                        name = "fenbi_paper_questions",
                        rank = 0,
                        idColumn = "id",
                        stemColumn = "stem",
                        optionsColumn = "options".takeIf(columns::contains),
                        optionsJsonColumn = "options_json".takeIf(columns::contains),
                        answerColumn = "correct_answer".takeIf(columns::contains),
                        correctRatioColumn = "correct_ratio".takeIf(columns::contains),
                        wrongAnswerColumn = "wrong_answer".takeIf(columns::contains),
                        analysisColumn = "analysis".takeIf(columns::contains),
                    )
                }
            }
            available["questions"]?.let { columns ->
                if ("id" in columns && "stem" in columns) {
                    bindings += TableBinding(
                        name = "questions",
                        rank = 1,
                        idColumn = "id",
                        stemColumn = "stem",
                        optionsColumn = "options".takeIf(columns::contains),
                        optionsJsonColumn = null,
                        answerColumn = "answer".takeIf(columns::contains),
                        correctRatioColumn = "correct_ratio".takeIf(columns::contains),
                        wrongAnswerColumn = "wrong_answer".takeIf(columns::contains),
                        analysisColumn = "analysis".takeIf(columns::contains),
                    )
                }
            }
            return bindings.sortedBy { it.rank }
        }

        private fun quoteStatic(identifier: String): String =
            "\"${identifier.replace("\"", "\"\"")}\""
    }
}
