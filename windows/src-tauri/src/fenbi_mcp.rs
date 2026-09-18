use crate::knowledge::KnowledgeBase;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// Local streamable-HTTP MCP so print-mode agy can read fenbi.db.
/// Bound to 127.0.0.1 only.
pub struct FenbiMcpServer {
    port: u16,
    shutdown: Arc<AtomicBool>,
    thread: Option<thread::JoinHandle<()>>,
}

impl FenbiMcpServer {
    pub fn start(knowledge_dir: PathBuf) -> Result<Self, String> {
        let listener = TcpListener::bind("127.0.0.1:0")
            .map_err(|e| format!("无法监听 fenbi MCP: {e}"))?;
        listener
            .set_nonblocking(true)
            .map_err(|e| format!("fenbi MCP nonblocking: {e}"))?;
        let port = listener
            .local_addr()
            .map_err(|e| format!("fenbi MCP addr: {e}"))?
            .port();
        let shutdown = Arc::new(AtomicBool::new(false));
        let flag = shutdown.clone();
        let thread = thread::spawn(move || accept_loop(listener, knowledge_dir, flag));
        Ok(Self {
            port,
            shutdown,
            thread: Some(thread),
        })
    }

    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}/mcp", self.port)
    }
}

impl Drop for FenbiMcpServer {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(("127.0.0.1", self.port));
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn accept_loop(listener: TcpListener, knowledge_dir: PathBuf, shutdown: Arc<AtomicBool>) {
    while !shutdown.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _)) => {
                let dir = knowledge_dir.clone();
                thread::spawn(move || {
                    let kb = KnowledgeBase::open(&dir);
                    handle_client(stream, &kb);
                });
            }
            Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50));
            }
            Err(_) => break,
        }
    }
}

fn handle_client(mut stream: TcpStream, kb: &KnowledgeBase) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
    let mut buf = Vec::new();
    let mut tmp = [0u8; 4096];
    let header_end = loop {
        match stream.read(&mut tmp) {
            Ok(0) => return,
            Ok(n) => buf.extend_from_slice(&tmp[..n]),
            Err(_) => return,
        }
        if let Some(pos) = find_header_end(&buf) {
            break pos;
        }
        if buf.len() > 64_000 {
            return;
        }
    };
    let headers = String::from_utf8_lossy(&buf[..header_end]).to_ascii_lowercase();
    let content_length = headers.lines().find_map(|line| {
        line.strip_prefix("content-length:")
            .and_then(|v| v.trim().parse::<usize>().ok())
    });
    let body_start = header_end;
    if let Some(len) = content_length {
        while buf.len() < body_start + len {
            match stream.read(&mut tmp) {
                Ok(0) => break,
                Ok(n) => buf.extend_from_slice(&tmp[..n]),
                Err(_) => break,
            }
        }
    }
    let body = if let Some(len) = content_length {
        let end = (body_start + len).min(buf.len());
        String::from_utf8_lossy(&buf[body_start..end]).into_owned()
    } else {
        String::new()
    };
    if body.trim().is_empty() {
        let _ = write_http(&mut stream, 200, "");
        return;
    }
    match handle_jsonrpc(&body, kb) {
        None => {
            let _ = write_http(&mut stream, 200, "");
        }
        Some(payload) => {
            let _ = write_http(&mut stream, 200, &payload);
        }
    }
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|i| i + 4)
        .or_else(|| buf.windows(2).position(|w| w == b"\n\n").map(|i| i + 2))
}

fn write_http(stream: &mut TcpStream, code: u16, body: &str) -> std::io::Result<()> {
    let bytes = body.as_bytes();
    let reason = if code == 200 { "OK" } else { "Error" };
    let header = format!(
        "HTTP/1.1 {code} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        bytes.len()
    );
    stream.write_all(header.as_bytes())?;
    stream.write_all(bytes)?;
    stream.flush()
}

pub(crate) fn handle_jsonrpc(raw: &str, kb: &KnowledgeBase) -> Option<String> {
    let req: Value = serde_json::from_str(raw).ok()?;
    let method = req.get("method").and_then(|v| v.as_str()).unwrap_or("");
    let id = req.get("id").cloned();
    if method == "notifications/initialized" || method.starts_with("notifications/") {
        return None;
    }
    let result = match method {
        "initialize" => {
            let protocol = req
                .pointer("/params/protocolVersion")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .unwrap_or("2024-11-05");
            json!({
                "protocolVersion": protocol,
                "capabilities": { "tools": {} },
                "serverInfo": { "name": "gecis-fenbi", "version": "0.2.0" }
            })
        }
        "tools/list" => json!({ "tools": crate::fenbi_sql::tools_spec() }),
        "ping" => json!({}),
        "tools/call" => call_tool(req.get("params").unwrap_or(&Value::Null), kb),
        _ => {
            return Some(
                json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "error": { "code": -32601, "message": format!("method not found: {method}") }
                })
                .to_string(),
            );
        }
    };
    Some(json!({ "jsonrpc": "2.0", "id": id, "result": result }).to_string())
}

fn call_tool(params: &Value, kb: &KnowledgeBase) -> Value {
    let name = params.get("name").and_then(|v| v.as_str()).unwrap_or("");
    if !matches!(name, "fenbi_schema" | "fenbi_get" | "fenbi_query") {
        return json!({
            "content": [{ "type": "text", "text": format!("unknown tool {name}") }],
            "isError": true
        });
    }
    if !kb.has_database() {
        return json!({
            "content": [{ "type": "text", "text": crate::fenbi_sql::NOT_IMPORTED }],
            "isError": false
        });
    }
    let Some(path) = kb.database_path() else {
        return json!({
            "content": [{ "type": "text", "text": crate::fenbi_sql::NOT_IMPORTED }],
            "isError": false
        });
    };
    let args = params.get("arguments").cloned().unwrap_or(json!({}));
    let result = match name {
        "fenbi_schema" => crate::fenbi_sql::schema(&path),
        "fenbi_get" => {
            let table = args.get("table").and_then(|v| v.as_str()).unwrap_or("");
            let ids = args.get("ids").and_then(|v| v.as_array()).cloned().unwrap_or_default();
            crate::fenbi_sql::get_rows(&path, table, &ids)
        }
        "fenbi_query" => {
            let sql = args.get("sql").and_then(|v| v.as_str()).unwrap_or("");
            crate::fenbi_sql::query(&path, sql)
        }
        _ => unreachable!(),
    };
    match result {
        Ok(value) => json!({
            "content": [{ "type": "text", "text": serde_json::to_string_pretty(&value).unwrap_or_else(|_| "{}".into()) }],
            "isError": false
        }),
        Err(err) => json!({
            "content": [{ "type": "text", "text": err }],
            "isError": true
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn empty_kb() -> KnowledgeBase {
        KnowledgeBase::open(&PathBuf::from("target/fenbi-mcp-test-empty"))
    }

    #[test]
    fn initialize_and_list_tools() {
        let kb = empty_kb();
        let init = handle_jsonrpc(
            r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"#,
            &kb,
        )
        .unwrap();
        assert!(init.contains("gecis-fenbi"));
        let listed = handle_jsonrpc(r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#, &kb).unwrap();
        assert!(init.contains("0.2.0"), "{init}");
        assert!(listed.contains("fenbi_schema"), "{listed}");
        assert!(listed.contains("fenbi_get"), "{listed}");
        assert!(listed.contains("fenbi_query"), "{listed}");
        assert!(!listed.contains("search_fenbi"), "{listed}");
        let expected = crate::fenbi_sql::TOOLS_JSON;
        assert!(listed.contains("Inspect the user's local fenbi.db"), "{listed}");
        assert!(expected.contains("fenbi_schema"));
        assert!(handle_jsonrpc(
            r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
            &kb
        )
        .is_none());
    }

    #[test]
    fn tools_call_without_db_is_not_an_error() {
        let kb = empty_kb();
        for (name, args) in [
            ("fenbi_schema", "{}"),
            ("fenbi_get", r#"{"table":"fenbi_paper_questions","ids":[1]}"#),
            ("fenbi_query", r#"{"sql":"SELECT 1"}"#),
        ] {
            let raw = format!(
                r#"{{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{{"name":"{name}","arguments":{args}}}}}"#
            );
            let body = handle_jsonrpc(&raw, &kb).unwrap();
            assert!(body.contains("fenbi.db is not imported yet"), "{name}: {body}");
            assert!(
                body.contains("\"isError\":false") || body.contains("\"isError\": false"),
                "{name}: {body}"
            );
        }
    }
}
