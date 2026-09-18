use rusqlite::{types::ValueRef, Connection, OpenFlags};
use serde_json::{json, Value};
use std::path::Path;
use std::time::{Duration, Instant};

pub const TOOLS_JSON: &str = include_str!("../../../shared/fenbi-mcp/tools.json");
pub const GEMINI_MD: &str = include_str!("../../../shared/fenbi-mcp/GEMINI.md");
#[cfg(test)]
const SANDBOX_CASES: &str = include_str!("../../../shared/fenbi-mcp/sandbox-cases.json");

pub const NOT_IMPORTED: &str = "fenbi.db is not imported yet.";
pub const QUERY_TIMEOUT_MS: u64 = 2500;
const MAX_ROWS: usize = 50;
const MAX_IDS: usize = 20;
const MAX_TABLES: usize = 64;
const MAX_COLUMNS: usize = 64;
const MAX_RESULT_BYTES: usize = 65_536;
const MAX_TEXT_CHARS: usize = 4000;
const QUERY_TIMEOUT: Duration = Duration::from_millis(QUERY_TIMEOUT_MS);

const DENY_KEYWORDS: &[&str] = &[
    "ATTACH", "DETACH", "INSERT", "UPDATE", "DELETE", "REPLACE", "DROP", "ALTER", "CREATE",
    "PRAGMA", "VACUUM", "REINDEX", "ANALYZE", "GRANT", "REVOKE",
];
const DENY_FUNCTIONS: &[&str] = &[
    "LOAD_EXTENSION",
    "SQLITE3_LOAD_EXTENSION",
    "READFILE",
    "WRITEFILE",
    "EVAL",
];
const DENY_RELATIONS: &[&str] = &["SQLITE_DBPAGE", "SQLITE3_DBDATA"];

const TIMEOUT_MESSAGE: &str = "query timed out after 2500ms. This ~4GB bank cannot run unindexed LIKE '%…%' or COUNT(*) on large text columns. Call fenbi_schema, then filter by id / paper_id / equality on indexed columns.";

pub fn tools_spec() -> Value {
    serde_json::from_str(TOOLS_JSON).unwrap_or_else(|_| json!([]))
}

pub fn schema(path: &Path) -> Result<Value, String> {
    let conn = open_readonly(path)?;
    let mut stmt = conn
        .prepare(
            "SELECT name, type FROM sqlite_master \
             WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' \
             ORDER BY name",
        )
        .map_err(|e| e.to_string())?;
    let listed: Vec<(String, String)> = stmt
        .query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .collect();
    let truncated = listed.len() > MAX_TABLES;
    let mut tables = Vec::new();
    for (name, kind) in listed.into_iter().take(MAX_TABLES) {
        let (columns, col_truncated) = table_columns(&conn, &name)?;
        let indexes = if kind.eq_ignore_ascii_case("table") {
            table_indexes(&conn, &name)?
        } else {
            vec![]
        };
        let approx = if kind.eq_ignore_ascii_case("table") {
            approx_rows(&conn, &name)
        } else {
            None
        };
        tables.push(json!({
            "name": name,
            "type": kind,
            "approxRows": approx,
            "approxRowsNote": "MAX(rowid), not COUNT(*)",
            "columns": columns,
            "indexes": indexes,
            "truncated": col_truncated,
        }));
    }
    Ok(json!({ "tables": tables, "truncated": truncated }))
}

pub fn get_rows(path: &Path, table: &str, ids: &[Value]) -> Result<Value, String> {
    if table.is_empty() {
        return Err("table is required".into());
    }
    if ids.is_empty() || ids.len() > MAX_IDS {
        return Err("ids must contain 1–20 values".into());
    }
    let conn = open_readonly(path)?;
    ensure_user_table(&conn, table)?;
    if !column_exists(&conn, table, "id")? {
        return Err(format!("table {table} has no id column; use fenbi_query"));
    }
    let placeholders = vec!["?"; ids.len()].join(",");
    let sql = format!(
        "SELECT * FROM {} WHERE {} IN ({placeholders}) LIMIT {MAX_IDS}",
        quote(table),
        quote("id")
    );
    let params: Vec<String> = ids.iter().map(value_to_id).collect();
    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let bind: Vec<&dyn rusqlite::types::ToSql> = params.iter().map(|p| p as &dyn rusqlite::types::ToSql).collect();
    serialize_statement(&mut stmt, &bind, Duration::from_secs(5))
}

pub fn query(path: &Path, sql: &str) -> Result<Value, String> {
    query_with_timeout(path, sql, QUERY_TIMEOUT)
}

fn query_with_timeout(path: &Path, sql: &str, timeout: Duration) -> Result<Value, String> {
    let prepared = prepare_sql(sql)?;
    let conn = open_readonly(path)?;
    conn.busy_timeout(timeout).ok();
    let started = Instant::now();
    conn.progress_handler(1_000, Some(move || started.elapsed() > timeout));
    let mut stmt = match conn.prepare(&prepared.sql) {
        Ok(s) => s,
        Err(err) => {
            if is_interrupt(&err) {
                return Err(TIMEOUT_MESSAGE.into());
            }
            return Err(err.to_string());
        }
    };
    match serialize_statement(&mut stmt, &[], timeout.saturating_sub(started.elapsed())) {
        Ok(value) => Ok(value),
        Err(err) if err == TIMEOUT_MESSAGE || err.contains("interrupted") => {
            Err(TIMEOUT_MESSAGE.into())
        }
        Err(err) => Err(err),
    }
}

fn open_readonly(path: &Path) -> Result<Connection, String> {
    let conn = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|e| format!("无法打开 fenbi.db: {e}"))?;
    let _ = conn.execute_batch("PRAGMA query_only=ON; PRAGMA temp_store=MEMORY;");
    Ok(conn)
}

fn ensure_user_table(conn: &Connection, table: &str) -> Result<(), String> {
    let exists: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE name = ?1 AND type IN ('table','view') AND name NOT LIKE 'sqlite_%'",
            [table],
            |row| row.get(0),
        )
        .map_err(|e| e.to_string())?;
    if exists == 0 {
        return Err(format!("unknown table {table}"));
    }
    Ok(())
}

fn column_exists(conn: &Connection, table: &str, column: &str) -> Result<bool, String> {
    let sql = format!("PRAGMA table_info({})", quote(table));
    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let found = stmt
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .any(|name| name == column);
    Ok(found)
}

fn table_columns(conn: &Connection, table: &str) -> Result<(Vec<Value>, bool), String> {
    let sql = format!("PRAGMA table_info({})", quote(table));
    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let rows: Vec<Value> = stmt
        .query_map([], |row| {
            Ok(json!({
                "name": row.get::<_, String>(1)?,
                "type": row.get::<_, String>(2).unwrap_or_default(),
                "notnull": row.get::<_, i64>(3).unwrap_or(0),
                "pk": row.get::<_, i64>(5).unwrap_or(0),
            }))
        })
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .collect();
    let truncated = rows.len() > MAX_COLUMNS;
    Ok((rows.into_iter().take(MAX_COLUMNS).collect(), truncated))
}

fn table_indexes(conn: &Connection, table: &str) -> Result<Vec<Value>, String> {
    let sql = format!("PRAGMA index_list({})", quote(table));
    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let listed: Vec<(String, i64)> = stmt
        .query_map([], |row| Ok((row.get::<_, String>(1)?, row.get::<_, i64>(2).unwrap_or(0))))
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .collect();
    let mut out = Vec::new();
    for (name, unique) in listed {
        let info_sql = format!("PRAGMA index_info({})", quote(&name));
        let mut info = conn.prepare(&info_sql).map_err(|e| e.to_string())?;
        let columns: Vec<String> = info
            .query_map([], |row| row.get::<_, String>(2))
            .map_err(|e| e.to_string())?
            .filter_map(|r| r.ok())
            .collect();
        out.push(json!({ "name": name, "unique": unique != 0, "columns": columns }));
    }
    Ok(out)
}

fn approx_rows(conn: &Connection, table: &str) -> Option<i64> {
    let sql = format!("SELECT MAX(rowid) FROM {}", quote(table));
    conn.query_row(&sql, [], |row| row.get::<_, Option<i64>>(0))
        .ok()
        .flatten()
}

fn serialize_statement(
    stmt: &mut rusqlite::Statement,
    params: &[&dyn rusqlite::types::ToSql],
    _timeout: Duration,
) -> Result<Value, String> {
    let columns: Vec<String> = stmt.column_names().iter().map(|s| (*s).to_string()).collect();
    let mut rows = Vec::new();
    let mut truncated = false;
    let mut used = 0usize;
    let mut rows_iter = stmt.query(params).map_err(|e| map_sqlite_err(e))?;
    while let Some(row) = rows_iter.next().map_err(|e| map_sqlite_err(e))? {
        if rows.len() >= MAX_ROWS {
            truncated = true;
            break;
        }
        let mut object = serde_json::Map::new();
        for (idx, name) in columns.iter().enumerate() {
            object.insert(name.clone(), cell_json(row, idx));
        }
        let value = Value::Object(object);
        let encoded = serde_json::to_string(&value).unwrap_or_default();
        if used + encoded.len() > MAX_RESULT_BYTES && !rows.is_empty() {
            truncated = true;
            break;
        }
        used += encoded.len();
        rows.push(value);
    }
    Ok(json!({ "columns": columns, "rows": rows, "truncated": truncated }))
}

fn cell_json(row: &rusqlite::Row, idx: usize) -> Value {
    match row.get_ref(idx) {
        Ok(ValueRef::Null) => Value::Null,
        Ok(ValueRef::Integer(v)) => json!(v),
        Ok(ValueRef::Real(v)) => json!(v),
        Ok(ValueRef::Text(bytes)) => clip_text(&String::from_utf8_lossy(bytes)),
        Ok(ValueRef::Blob(bytes)) => json!({ "$blobBytes": bytes.len() }),
        Err(_) => Value::Null,
    }
}

fn clip_text(text: &str) -> Value {
    let count = text.chars().count();
    if count <= MAX_TEXT_CHARS {
        json!(text)
    } else {
        let clipped: String = text.chars().take(MAX_TEXT_CHARS).collect();
        json!(format!("{clipped}…[truncated {} chars]", count - MAX_TEXT_CHARS))
    }
}

fn value_to_id(value: &Value) -> String {
    match value {
        Value::Number(n) => n.to_string(),
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

fn quote(ident: &str) -> String {
    format!("\"{}\"", ident.replace('"', "\"\""))
}

fn map_sqlite_err(err: rusqlite::Error) -> String {
    if is_interrupt(&err) {
        TIMEOUT_MESSAGE.to_string()
    } else {
        err.to_string()
    }
}

fn is_interrupt(err: &rusqlite::Error) -> bool {
    let text = err.to_string().to_ascii_lowercase();
    text.contains("interrupt") || text.contains("cancelled") || text.contains("canceled")
}

#[derive(Debug)]
struct PreparedSql {
    sql: String,
}

fn prepare_sql(sql: &str) -> Result<PreparedSql, String> {
    if sql.contains('\0') {
        return Err("SQL must not contain NUL".into());
    }
    let tokens = tokenize(sql)?;
    if tokens.idents.is_empty() && tokens.body.trim().is_empty() {
        return Err("SQL is empty".into());
    }
    if tokens.extra_statement {
        return Err("only a single statement is allowed".into());
    }
    let idents = &tokens.idents;
    if idents.is_empty() {
        return Err("SQL is empty".into());
    }
    let first = idents[0].as_str();
    let mut idx = 0usize;
    let is_explain = first == "EXPLAIN";
    if is_explain {
        idx = 1;
        if idents.get(idx).map(|s| s.as_str()) == Some("QUERY")
            && idents.get(idx + 1).map(|s| s.as_str()) == Some("PLAN")
        {
            idx += 2;
        }
    }
    let head = idents.get(idx).map(|s| s.as_str()).unwrap_or("");
    if head != "SELECT" && head != "WITH" {
        return Err("only SELECT / WITH / EXPLAIN QUERY PLAN are allowed".into());
    }
    for ident in &tokens.idents {
        if DENY_KEYWORDS.contains(&ident.as_str()) {
            return Err(format!("keyword {ident} is not allowed"));
        }
        if DENY_RELATIONS.contains(&ident.as_str()) {
            return Err(format!("relation {ident} is not allowed"));
        }
    }
    for func in &tokens.functions {
        if DENY_FUNCTIONS.contains(&func.as_str()) {
            return Err(format!("function {func} is not allowed"));
        }
    }
    for ident in &tokens.quoted {
        if DENY_RELATIONS.contains(&ident.as_str()) {
            return Err(format!("relation {ident} is not allowed"));
        }
    }
    let body = tokens.body.trim().trim_end_matches(';').trim();
    if body.is_empty() {
        return Err("SQL is empty".into());
    }
    let sql = if is_explain {
        body.to_string()
    } else {
        format!("SELECT * FROM ({body}) LIMIT {MAX_ROWS}")
    };
    Ok(PreparedSql { sql })
}

struct Tokens {
    body: String,
    idents: Vec<String>,
    functions: Vec<String>,
    quoted: Vec<String>,
    extra_statement: bool,
}

fn tokenize(sql: &str) -> Result<Tokens, String> {
    let mut body = String::with_capacity(sql.len());
    let mut idents = Vec::new();
    let mut functions = Vec::new();
    let mut quoted = Vec::new();
    let mut extra_statement = false;
    let chars: Vec<char> = sql.chars().collect();
    let mut i = 0usize;
    let mut saw_semicolon = false;
    while i < chars.len() {
        let c = chars[i];
        if saw_semicolon {
            if c.is_whitespace() {
                i += 1;
                continue;
            }
            extra_statement = true;
            break;
        }
        if c == '-' && chars.get(i + 1) == Some(&'-') {
            i += 2;
            while i < chars.len() && chars[i] != '\n' {
                i += 1;
            }
            continue;
        }
        if c == '/' && chars.get(i + 1) == Some(&'*') {
            i += 2;
            while i + 1 < chars.len() && !(chars[i] == '*' && chars[i + 1] == '/') {
                i += 1;
            }
            i = (i + 2).min(chars.len());
            continue;
        }
        if c == '\'' {
            body.push(c);
            i += 1;
            while i < chars.len() {
                body.push(chars[i]);
                if chars[i] == '\'' {
                    if chars.get(i + 1) == Some(&'\'') {
                        body.push('\'');
                        i += 2;
                        continue;
                    }
                    i += 1;
                    break;
                }
                i += 1;
            }
            continue;
        }
        if c == '"' || c == '`' || c == '[' {
            let close = if c == '[' { ']' } else { c };
            body.push(c);
            i += 1;
            let inner_start = i;
            while i < chars.len() {
                body.push(chars[i]);
                if chars[i] == close {
                    let inner: String = chars[inner_start..i].iter().collect();
                    quoted.push(inner.to_ascii_uppercase());
                    i += 1;
                    break;
                }
                i += 1;
            }
            continue;
        }
        if c == ';' {
            saw_semicolon = true;
            i += 1;
            continue;
        }
        if is_ident_start(c) {
            let start = i;
            i += 1;
            while i < chars.len() && is_ident_part(chars[i]) {
                i += 1;
            }
            let ident: String = chars[start..i].iter().collect();
            let upper = ident.to_ascii_uppercase();
            let mut j = i;
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            if chars.get(j) == Some(&'(') {
                functions.push(upper.clone());
            }
            idents.push(upper);
            body.push_str(&ident);
            continue;
        }
        body.push(c);
        i += 1;
    }
    Ok(Tokens {
        body,
        idents,
        functions,
        quoted,
        extra_statement,
    })
}

fn is_ident_start(c: char) -> bool {
    c.is_ascii_alphabetic() || c == '_'
}

fn is_ident_part(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use std::path::PathBuf;

    fn cases() -> Vec<Value> {
        serde_json::from_str(SANDBOX_CASES).unwrap()
    }

    #[test]
    fn sandbox_cases_match_shared_json() {
        for case in cases() {
            let sql = case["sql"].as_str().unwrap();
            let expect = case["expect"].as_str().unwrap();
            let reason = case["reason"].as_str().unwrap();
            let result = prepare_sql(sql);
            match expect {
                "accept" => assert!(result.is_ok(), "{reason}: {sql} -> {result:?}"),
                "reject" => assert!(result.is_err(), "{reason}: {sql} should reject"),
                other => panic!("unknown expect {other}"),
            }
        }
    }

    #[test]
    fn select_is_wrapped_with_limit() {
        let prepared = prepare_sql("SELECT * FROM t").unwrap();
        assert!(prepared.sql.starts_with("SELECT * FROM ("));
        assert!(prepared.sql.contains("LIMIT 50"));
    }

    #[test]
    fn explain_is_not_wrapped() {
        let prepared = prepare_sql("EXPLAIN QUERY PLAN SELECT id FROM t").unwrap();
        assert!(prepared.sql.starts_with("EXPLAIN"));
        assert!(!prepared.sql.starts_with("SELECT * FROM"));
    }

    fn fixture_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("gecis-fenbi-sql-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn schema_and_get_and_blob_redaction() {
        let dir = fixture_dir("schema");
        let path = dir.join("fenbi.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(
            "CREATE TABLE fenbi_paper_questions (
                id INTEGER PRIMARY KEY,
                stem TEXT,
                analysis TEXT,
                paper_id INTEGER,
                photo BLOB
            );
            INSERT INTO fenbi_paper_questions (id, stem, analysis, paper_id, photo)
            VALUES (42, '题干', '解析', 7, x'010203');",
        )
        .unwrap();
        drop(conn);
        let listed = schema(&path).unwrap();
        let tables = listed["tables"].as_array().unwrap();
        assert!(tables.iter().any(|t| t["name"] == "fenbi_paper_questions"));
        let got = get_rows(&path, "fenbi_paper_questions", &[json!(42)]).unwrap();
        assert_eq!(got["rows"][0]["id"], 42);
        assert_eq!(got["rows"][0]["photo"]["$blobBytes"], 3);
        assert!(got["rows"][0]["photo"].get("$blobBytes").is_some());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn query_limit_caps_rows() {
        let dir = fixture_dir("limit");
        let path = dir.join("fenbi.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE fenbi_paper_questions (id INTEGER PRIMARY KEY, stem TEXT);")
            .unwrap();
        for i in 1..=80 {
            conn.execute(
                "INSERT INTO fenbi_paper_questions (id, stem) VALUES (?1, 'x')",
                [i],
            )
            .unwrap();
        }
        drop(conn);
        let result = query(&path, "SELECT id FROM fenbi_paper_questions").unwrap();
        assert!(result["rows"].as_array().unwrap().len() <= 50);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn recursive_query_times_out() {
        let dir = fixture_dir("timeout");
        let path = dir.join("fenbi.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(
            "CREATE TABLE fenbi_paper_questions (id INTEGER PRIMARY KEY, stem TEXT);
             INSERT INTO fenbi_paper_questions (id, stem) VALUES (1, 'a');",
        )
        .unwrap();
        drop(conn);
        let err = query_with_timeout(
            &path,
            "WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM t) SELECT MAX(x) FROM t",
            Duration::from_millis(80),
        )
        .unwrap_err();
        assert!(err.contains("timed out") || err.contains("interrupt"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
