use rusqlite::{params, Connection};
use serde_json::{json, Value};
use std::path::Path;

pub struct HistoryStore {
    conn: Connection,
}

impl HistoryStore {
    pub fn open(path: &Path) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let conn = Connection::open(path).map_err(|e| e.to_string())?;
        conn.execute_batch("PRAGMA foreign_keys = ON;")
            .map_err(|e| e.to_string())?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS projects (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS conversations (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              project_id INTEGER NOT NULL,
              title TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS messages (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              conversation_id INTEGER NOT NULL,
              role TEXT NOT NULL CHECK(role IN ('user','assistant')),
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_conversations_project_updated
              ON conversations(project_id, updated_at DESC);
            CREATE INDEX IF NOT EXISTS idx_messages_conversation_id
              ON messages(conversation_id, id);
            "#,
        )
        .map_err(|e| e.to_string())?;
        // Migration: bind local conversations to agy conversation ids for copy/resume.
        let has_col: bool = {
            let mut stmt = conn
                .prepare("PRAGMA table_info(conversations)")
                .map_err(|e| e.to_string())?;
            let cols = stmt
                .query_map([], |row| row.get::<_, String>(1))
                .map_err(|e| e.to_string())?
                .filter_map(|r| r.ok())
                .collect::<Vec<_>>();
            cols.iter().any(|c| c == "agy_conversation_id")
        };
        if !has_col {
            conn.execute_batch("ALTER TABLE conversations ADD COLUMN agy_conversation_id TEXT;")
                .map_err(|e| e.to_string())?;
        }
        Ok(Self { conn })
    }

    pub fn set_agy_conversation_id(
        &self,
        conversation_id: i64,
        agy_id: &str,
    ) -> Result<(), String> {
        if agy_id.trim().is_empty() {
            return Ok(());
        }
        self.conn
            .execute(
                "UPDATE conversations SET agy_conversation_id=? WHERE id=?",
                params![agy_id.trim(), conversation_id],
            )
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn get_agy_conversation_id(&self, conversation_id: i64) -> Result<Option<String>, String> {
        let mut stmt = self
            .conn
            .prepare("SELECT agy_conversation_id FROM conversations WHERE id=?")
            .map_err(|e| e.to_string())?;
        let mut rows = stmt.query(params![conversation_id]).map_err(|e| e.to_string())?;
        if let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let value: Option<String> = row.get(0).map_err(|e| e.to_string())?;
            Ok(value.filter(|s| !s.trim().is_empty()))
        } else {
            Ok(None)
        }
    }

    pub fn conversation_title(&self, conversation_id: i64) -> Result<String, String> {
        self.conn
            .query_row(
                "SELECT title FROM conversations WHERE id=?",
                params![conversation_id],
                |row| row.get(0),
            )
            .map_err(|e| e.to_string())
    }

    pub fn list_messages(
        &self,
        conversation_id: i64,
    ) -> Result<Vec<(String, String)>, String> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT role, content FROM messages WHERE conversation_id=? ORDER BY id ASC",
            )
            .map_err(|e| e.to_string())?;
        let rows = stmt
            .query_map(params![conversation_id], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| e.to_string())?;
        let mut out = vec![];
        for row in rows {
            out.push(row.map_err(|e| e.to_string())?);
        }
        Ok(out)
    }

    pub fn ensure_default_project(&self) -> Result<i64, String> {
        if let Some(id) = self.default_project_id()? {
            return Ok(id);
        }
        let now = now_ms();
        self.conn
            .execute(
                "INSERT INTO projects(name, created_at, updated_at) VALUES(?, ?, ?)",
                params!["未分类", now, now],
            )
            .map_err(|e| e.to_string())?;
        Ok(self.last_id()?)
    }

    pub fn create_project(&self, raw_name: &str) -> Result<i64, String> {
        let name: String = raw_name.trim().chars().take(40).collect();
        if name.is_empty() {
            return Err("项目名称不能为空".into());
        }
        let now = now_ms();
        self.conn
            .execute(
                "INSERT INTO projects(name, created_at, updated_at) VALUES(?, ?, ?)",
                params![name, now, now],
            )
            .map_err(|e| e.to_string())?;
        self.last_id()
    }

    pub fn create_conversation(&self, project_id: Option<i64>) -> Result<i64, String> {
        let project = match project_id {
            Some(id) if self.project_exists(id)? => id,
            _ => self.ensure_default_project()?,
        };
        let now = now_ms();
        self.conn
            .execute(
                "INSERT INTO conversations(project_id, title, created_at, updated_at) VALUES(?, ?, ?, ?)",
                params![project, "新对话", now, now],
            )
            .map_err(|e| e.to_string())?;
        self.last_id()
    }

    pub fn conversation_exists(&self, conversation_id: i64) -> Result<bool, String> {
        let mut stmt = self
            .conn
            .prepare("SELECT 1 FROM conversations WHERE id=? LIMIT 1")
            .map_err(|e| e.to_string())?;
        Ok(stmt.exists(params![conversation_id]).unwrap_or(false))
    }

    pub fn project_exists(&self, project_id: i64) -> Result<bool, String> {
        let mut stmt = self
            .conn
            .prepare("SELECT 1 FROM projects WHERE id=? LIMIT 1")
            .map_err(|e| e.to_string())?;
        Ok(stmt.exists(params![project_id]).unwrap_or(false))
    }

    pub fn append_message(&self, conversation_id: i64, role: &str, content: &str) -> Result<(), String> {
        if role != "user" && role != "assistant" {
            return Err("不支持的消息角色".into());
        }
        let text = content.trim();
        if text.is_empty() {
            return Ok(());
        }
        if !self.conversation_exists(conversation_id)? {
            return Err(format!("历史会话不存在：{conversation_id}"));
        }
        let now = now_ms();
        let tx = self.conn.unchecked_transaction().map_err(|e| e.to_string())?;
        tx.execute(
            "INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?, ?, ?, ?)",
            params![conversation_id, role, text, now],
        )
        .map_err(|e| e.to_string())?;
        if role == "user" {
            let title: String = tx
                .query_row(
                    "SELECT title FROM conversations WHERE id=?",
                    params![conversation_id],
                    |row| row.get(0),
                )
                .map_err(|e| e.to_string())?;
            if title == "新对话" {
                tx.execute(
                    "UPDATE conversations SET title=? WHERE id=?",
                    params![title_from(text), conversation_id],
                )
                .map_err(|e| e.to_string())?;
            }
        }
        tx.execute(
            "UPDATE conversations SET updated_at=? WHERE id=?",
            params![now, conversation_id],
        )
        .map_err(|e| e.to_string())?;
        tx.execute(
            "UPDATE projects SET updated_at=? WHERE id=(SELECT project_id FROM conversations WHERE id=?)",
            params![now, conversation_id],
        )
        .map_err(|e| e.to_string())?;
        tx.commit().map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn snapshot(&self, current_conversation_id: Option<i64>) -> Result<Value, String> {
        let current = match current_conversation_id {
            Some(id) if self.conversation_exists(id)? => Some(id),
            _ => None,
        };

        let mut projects = vec![];
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name FROM projects
                 ORDER BY CASE WHEN name=? THEN 0 ELSE 1 END, updated_at DESC, id ASC",
            )
            .map_err(|e| e.to_string())?;
        let project_rows = stmt
            .query_map(params!["未分类"], |row| {
                Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| e.to_string())?;

        for project_row in project_rows {
            let (project_id, name) = project_row.map_err(|e| e.to_string())?;
            let mut conv_stmt = self
                .conn
                .prepare(
                    "SELECT id, title, updated_at FROM conversations
                     WHERE project_id=? ORDER BY updated_at DESC, id DESC",
                )
                .map_err(|e| e.to_string())?;
            let conversations: Vec<Value> = conv_stmt
                .query_map(params![project_id], |row| {
                    Ok(json!({
                        "id": row.get::<_, i64>(0)?,
                        "title": row.get::<_, String>(1)?,
                        "updatedAt": row.get::<_, i64>(2)?,
                    }))
                })
                .map_err(|e| e.to_string())?
                .filter_map(|r| r.ok())
                .collect();
            projects.push(json!({
                "id": project_id,
                "name": name,
                "conversations": conversations,
            }));
        }

        let (messages, current_project_id) = match current {
            Some(id) => {
                let messages = self.messages_json(id)?;
                let project_id = self.project_id_for_conversation(id)?.unwrap_or(self.ensure_default_project()?);
                (messages, project_id)
            }
            None => (json!([]), self.ensure_default_project()?),
        };

        Ok(json!({
            "projects": projects,
            "currentConversationId": current,
            "currentProjectId": current_project_id,
            "messages": messages,
        }))
    }

    fn messages_json(&self, conversation_id: i64) -> Result<Value, String> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, role, content, created_at FROM messages
                 WHERE conversation_id=? ORDER BY id ASC",
            )
            .map_err(|e| e.to_string())?;
        let messages: Vec<Value> = stmt
            .query_map(params![conversation_id], |row| {
                Ok(json!({
                    "id": row.get::<_, i64>(0)?,
                    "role": row.get::<_, String>(1)?,
                    "content": row.get::<_, String>(2)?,
                    "createdAt": row.get::<_, i64>(3)?,
                }))
            })
            .map_err(|e| e.to_string())?
            .filter_map(|r| r.ok())
            .collect();
        Ok(Value::Array(messages))
    }

    fn project_id_for_conversation(&self, conversation_id: i64) -> Result<Option<i64>, String> {
        let mut stmt = self
            .conn
            .prepare("SELECT project_id FROM conversations WHERE id=?")
            .map_err(|e| e.to_string())?;
        let mut rows = stmt.query(params![conversation_id]).map_err(|e| e.to_string())?;
        if let Some(row) = rows.next().map_err(|e| e.to_string())? {
            Ok(Some(row.get(0).map_err(|e| e.to_string())?))
        } else {
            Ok(None)
        }
    }

    fn default_project_id(&self) -> Result<Option<i64>, String> {
        let mut stmt = self
            .conn
            .prepare("SELECT id FROM projects WHERE name=? ORDER BY id ASC LIMIT 1")
            .map_err(|e| e.to_string())?;
        let mut rows = stmt.query(params!["未分类"]).map_err(|e| e.to_string())?;
        if let Some(row) = rows.next().map_err(|e| e.to_string())? {
            Ok(Some(row.get(0).map_err(|e| e.to_string())?))
        } else {
            Ok(None)
        }
    }

    fn last_id(&self) -> Result<i64, String> {
        self.conn
            .query_row("SELECT last_insert_rowid()", [], |row| row.get(0))
            .map_err(|e| e.to_string())
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

fn title_from(text: &str) -> String {
    let single_line = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let chars: Vec<char> = single_line.chars().collect();
    if chars.len() <= 24 {
        single_line
    } else {
        let mut out: String = chars.into_iter().take(24).collect();
        out.push('…');
        out
    }
}
