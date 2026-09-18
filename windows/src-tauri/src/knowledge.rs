use rusqlite::Connection;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

const SQLITE_HEADER: &[u8] = b"SQLite format 3\0";

const INDEX_SPECS: &[(&str, &str, &str)] = &[
    ("fenbi_paper_questions", "paper_id", "gecis_idx_fpq_paper_id"),
    ("fenbi_paper_questions", "question_id", "gecis_idx_fpq_question_id"),
    ("fenbi_paper_questions", "fenbi_question_id", "gecis_idx_fpq_fenbi_question_id"),
    ("fenbi_paper_questions", "section_name", "gecis_idx_fpq_section_name"),
    ("fenbi_paper_questions", "subject", "gecis_idx_fpq_subject"),
    ("questions", "paper_id", "gecis_idx_q_paper_id"),
    ("images", "image_hash", "gecis_idx_images_image_hash"),
];

pub struct KnowledgeBase {
    dir: PathBuf,
}

impl KnowledgeBase {
    pub fn open(dir: &Path) -> Self {
        let _ = std::fs::create_dir_all(dir);
        Self {
            dir: dir.to_path_buf(),
        }
    }

    pub fn has_database(&self) -> bool {
        self.database_path()
            .map(|p| p.is_file() && p.metadata().map(|m| m.len() > 16).unwrap_or(false))
            .unwrap_or(false)
    }

    pub(crate) fn database_path(&self) -> Option<PathBuf> {
        let path = self.dir.join("fenbi.db");
        path.is_file().then_some(path)
    }

    pub fn import_from(&mut self, source: &Path) -> Result<String, String> {
        let data = std::fs::read(source).map_err(|e| format!("无法读取所选文件: {e}"))?;
        if data.len() < SQLITE_HEADER.len() || &data[..SQLITE_HEADER.len()] != SQLITE_HEADER {
            return Err("所选文件不是有效的 SQLite 数据库".into());
        }

        let temp = self.dir.join("fenbi.db.importing");
        std::fs::write(&temp, &data).map_err(|e| format!("写入临时文件失败: {e}"))?;
        if let Err(err) = validate_fenbi(&temp) {
            let _ = std::fs::remove_file(&temp);
            return Err(err);
        }

        let target = self.dir.join("fenbi.db");
        let previous = self.dir.join("fenbi.db.previous");
        let _ = std::fs::remove_file(&previous);
        if target.exists() {
            let _ = std::fs::rename(&target, &previous);
        }
        if let Err(err) = std::fs::rename(&temp, &target) {
            let _ = std::fs::rename(&previous, &target);
            let _ = std::fs::remove_file(&temp);
            return Err(format!("无法保存 fenbi.db: {err}"));
        }
        let _ = std::fs::remove_file(&previous);
        Ok(source
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| "fenbi.db".into()))
    }

    pub fn ensure_indexes(&self) {
        let Some(path) = self.database_path() else {
            return;
        };
        let Ok(conn) = Connection::open(&path) else {
            return;
        };
        for &(table, column, index_name) in INDEX_SPECS {
            let _ = create_index_if_needed(&conn, table, column, index_name);
        }
    }
}

fn validate_fenbi(path: &Path) -> Result<(), String> {
    let conn = Connection::open_with_flags(
        path,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|e| format!("无法打开数据库: {e}"))?;
    let check: String = conn
        .query_row("PRAGMA quick_check(1)", [], |row| row.get(0))
        .map_err(|e| format!("完整性检查失败: {e}"))?;
    if !check.eq_ignore_ascii_case("ok") {
        return Err(format!("fenbi.db 完整性检查失败：{check}"));
    }
    if !has_question_table(&conn)? {
        return Err("所选 SQLite 未找到 Fenbi 题目表（fenbi_paper_questions / questions）".into());
    }
    Ok(())
}

fn has_question_table(conn: &Connection) -> Result<bool, String> {
    let mut stmt = conn
        .prepare(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('fenbi_paper_questions','questions')",
        )
        .map_err(|e| e.to_string())?;
    let names: Vec<String> = stmt
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .collect();
    for name in names {
        let columns = table_columns(conn, &name)?;
        if columns.contains("id") && columns.contains("stem") {
            return Ok(true);
        }
    }
    Ok(false)
}

fn create_index_if_needed(
    conn: &Connection,
    table: &str,
    column: &str,
    index_name: &str,
) -> Result<(), String> {
    let columns = table_columns(conn, table)?;
    if !columns.contains(column) {
        return Ok(());
    }
    if column_is_indexed(conn, table, column)? {
        return Ok(());
    }
    let sql = format!(
        "CREATE INDEX IF NOT EXISTS {} ON {} ({})",
        quote(index_name),
        quote(table),
        quote(column)
    );
    conn.execute(&sql, []).map_err(|e| e.to_string())?;
    Ok(())
}

fn column_is_indexed(conn: &Connection, table: &str, column: &str) -> Result<bool, String> {
    let sql = format!("PRAGMA index_list({})", quote(table));
    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let names: Vec<String> = stmt
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .collect();
    for name in names {
        let info_sql = format!("PRAGMA index_info({})", quote(&name));
        let mut info = conn.prepare(&info_sql).map_err(|e| e.to_string())?;
        let cols: Vec<String> = info
            .query_map([], |row| row.get::<_, String>(2))
            .map_err(|e| e.to_string())?
            .filter_map(|r| r.ok())
            .collect();
        if cols.first().map(|c| c.as_str()) == Some(column) {
            return Ok(true);
        }
    }
    Ok(false)
}

fn table_columns(conn: &Connection, table: &str) -> Result<BTreeSet<String>, String> {
    let sql = format!("PRAGMA table_info({})", quote(table));
    let mut stmt = conn.prepare(&sql).map_err(|e| e.to_string())?;
    let names = stmt
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|e| e.to_string())?
        .filter_map(|r| r.ok())
        .collect();
    Ok(names)
}

fn quote(ident: &str) -> String {
    format!("\"{}\"", ident.replace('"', "\"\""))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    fn fixture_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("gecis-kb-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn validate_requires_id_and_stem() {
        let dir = fixture_dir("validate");
        let path = dir.join("fenbi.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("CREATE TABLE other (id INTEGER);").unwrap();
        drop(conn);
        assert!(validate_fenbi(&path).is_err());
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(
            "CREATE TABLE fenbi_paper_questions (id INTEGER PRIMARY KEY, stem TEXT, paper_id INTEGER);",
        )
        .unwrap();
        drop(conn);
        assert!(validate_fenbi(&path).is_ok());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn ensure_indexes_creates_uncovered_column() {
        let dir = fixture_dir("idx");
        let path = dir.join("fenbi.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(
            "CREATE TABLE fenbi_paper_questions (
                id INTEGER PRIMARY KEY,
                stem TEXT,
                paper_id INTEGER,
                question_id INTEGER
            );",
        )
        .unwrap();
        drop(conn);
        let kb = KnowledgeBase::open(&dir);
        kb.ensure_indexes();
        let conn = Connection::open(&path).unwrap();
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='gecis_idx_fpq_paper_id'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 1);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
