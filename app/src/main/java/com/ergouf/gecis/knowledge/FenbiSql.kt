package com.ergouf.gecis.knowledge

internal object FenbiSql {
    const val NOT_IMPORTED = "fenbi.db is not imported yet."
    const val QUERY_TIMEOUT_MS = 2500L
    const val MAX_ROWS = 50
    const val MAX_IDS = 20
    const val MAX_TABLES = 64
    const val MAX_COLUMNS = 64
    const val MAX_RESULT_BYTES = 65_536
    const val MAX_TEXT_CHARS = 4000
    const val TIMEOUT_MESSAGE =
        "query timed out after 2500ms. This ~4GB bank cannot run unindexed LIKE '%…%' or COUNT(*) on large text columns. Call fenbi_schema, then filter by id / paper_id / equality on indexed columns."

    private val DENY_KEYWORDS = setOf(
        "ATTACH", "DETACH", "INSERT", "UPDATE", "DELETE", "REPLACE", "DROP", "ALTER", "CREATE",
        "PRAGMA", "VACUUM", "REINDEX", "ANALYZE", "GRANT", "REVOKE",
    )
    private val DENY_FUNCTIONS = setOf(
        "LOAD_EXTENSION",
        "SQLITE3_LOAD_EXTENSION",
        "READFILE",
        "WRITEFILE",
        "EVAL",
    )
    private val DENY_RELATIONS = setOf("SQLITE_DBPAGE", "SQLITE3_DBDATA")

    data class PreparedSql(val sql: String, val explain: Boolean)

    fun prepare(sql: String): PreparedSql {
        require('\u0000' !in sql) { "SQL must not contain NUL" }
        val tokens = tokenize(sql)
        require(tokens.idents.isNotEmpty() || tokens.body.isNotBlank()) { "SQL is empty" }
        require(!tokens.extraStatement) { "only a single statement is allowed" }
        require(tokens.idents.isNotEmpty()) { "SQL is empty" }
        var idx = 0
        val isExplain = tokens.idents[0] == "EXPLAIN"
        if (isExplain) {
            idx = 1
            if (tokens.idents.getOrNull(idx) == "QUERY" && tokens.idents.getOrNull(idx + 1) == "PLAN") {
                idx += 2
            }
        }
        val head = tokens.idents.getOrNull(idx).orEmpty()
        require(head == "SELECT" || head == "WITH") {
            "only SELECT / WITH / EXPLAIN QUERY PLAN are allowed"
        }
        for (ident in tokens.idents) {
            require(ident !in DENY_KEYWORDS) { "keyword $ident is not allowed" }
            require(ident !in DENY_RELATIONS) { "relation $ident is not allowed" }
        }
        for (func in tokens.functions) {
            require(func !in DENY_FUNCTIONS) { "function $func is not allowed" }
        }
        for (ident in tokens.quoted) {
            require(ident !in DENY_RELATIONS) { "relation $ident is not allowed" }
        }
        val body = tokens.body.trim().trimEnd(';').trim()
        require(body.isNotEmpty()) { "SQL is empty" }
        val wrapped = if (isExplain) body else "SELECT * FROM ($body) LIMIT $MAX_ROWS"
        return PreparedSql(wrapped, isExplain)
    }

    fun quote(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""

    private data class Tokens(
        val body: String,
        val idents: List<String>,
        val functions: List<String>,
        val quoted: List<String>,
        val extraStatement: Boolean,
    )

    private fun tokenize(sql: String): Tokens {
        val body = StringBuilder(sql.length)
        val idents = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val quoted = mutableListOf<String>()
        var extraStatement = false
        var i = 0
        var sawSemicolon = false
        while (i < sql.length) {
            val c = sql[i]
            if (sawSemicolon) {
                if (c.isWhitespace()) {
                    i++
                    continue
                }
                extraStatement = true
                break
            }
            if (c == '-' && sql.getOrNull(i + 1) == '-') {
                i += 2
                while (i < sql.length && sql[i] != '\n') i++
                continue
            }
            if (c == '/' && sql.getOrNull(i + 1) == '*') {
                i += 2
                while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(sql.length)
                continue
            }
            if (c == '\'') {
                body.append(c)
                i++
                while (i < sql.length) {
                    body.append(sql[i])
                    if (sql[i] == '\'') {
                        if (sql.getOrNull(i + 1) == '\'') {
                            body.append('\'')
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    i++
                }
                continue
            }
            if (c == '"' || c == '`' || c == '[') {
                val close = if (c == '[') ']' else c
                body.append(c)
                i++
                val innerStart = i
                while (i < sql.length) {
                    body.append(sql[i])
                    if (sql[i] == close) {
                        quoted += sql.substring(innerStart, i).uppercase()
                        i++
                        break
                    }
                    i++
                }
                continue
            }
            if (c == ';') {
                sawSemicolon = true
                i++
                continue
            }
            if (isIdentStart(c)) {
                val start = i
                i++
                while (i < sql.length && isIdentPart(sql[i])) i++
                val ident = sql.substring(start, i)
                val upper = ident.uppercase()
                var j = i
                while (j < sql.length && sql[j].isWhitespace()) j++
                if (sql.getOrNull(j) == '(') functions += upper
                idents += upper
                body.append(ident)
                continue
            }
            body.append(c)
            i++
        }
        return Tokens(body.toString(), idents, functions, quoted, extraStatement)
    }

    private fun isIdentStart(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c == '_'

    private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c in '0'..'9'
}
