use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;

pub enum RuntimeEvent {
    Status { text: String, state: String },
    Delta { request_id: String, text: String },
    Complete { request_id: String, text: String },
    Error { request_id: String, message: String },
    AuthRequired { request_id: String },
}

pub struct AgyLocator {
    pub path: PathBuf,
    pub source: String,
}

pub fn locate_agy() -> Result<AgyLocator, String> {
    if let Ok(path) = std::env::var("GECIS_AGY") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Ok(AgyLocator {
                source: "GECIS_AGY".into(),
                path,
            });
        }
    }

    if let Ok(path) = which::which("agy") {
        return Ok(AgyLocator {
            source: "PATH".into(),
            path,
        });
    }
    if let Ok(path) = which::which("agy.exe") {
        return Ok(AgyLocator {
            source: "PATH".into(),
            path,
        });
    }

    let mut candidates: Vec<PathBuf> = vec![];
    if let Some(home) = dirs::home_dir() {
        candidates.push(home.join(".local/bin/agy.exe"));
        candidates.push(home.join(".local/bin/agy"));
        candidates.push(home.join("scoop/shims/agy.exe"));
        candidates.push(home.join("scoop/apps/antigravity-cli/current/agy.exe"));
    }
    if let Some(local) = dirs::data_local_dir() {
        candidates.push(local.join("agy/bin/agy.exe"));
        candidates.push(local.join("Programs/agy/agy.exe"));
        candidates.push(local.join("Microsoft/WinGet/Links/agy.exe"));
    }
    if let Some(profile) = std::env::var_os("USERPROFILE") {
        let profile = PathBuf::from(profile);
        candidates.push(
            profile
                .join("AppData/Local/agy/bin/agy.exe"),
        );
        candidates.push(profile.join(".local/bin/agy.exe"));
        candidates.push(profile.join("scoop/shims/agy.exe"));
    }

    for path in candidates {
        if path.is_file() {
            return Ok(AgyLocator {
                source: path.display().to_string(),
                path,
            });
        }
    }

    Err(
        "未找到 Antigravity CLI（agy）。请先安装：irm https://antigravity.google/cli/install.ps1 | iex".into(),
    )
}

pub struct AntigravityRuntime {
    child: Child,
    stdin: ChildStdin,
    events: Receiver<RuntimeEvent>,
    pending: Option<String>,
    buffer: String,
    stderr_tail: Vec<String>,
}

impl AntigravityRuntime {
    pub fn spawn() -> Result<Self, String> {
        let locator = locate_agy()?;
        let mut command = Command::new(&locator.path);
        command
            .args([
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--sandbox",
                "--print-timeout",
                "5m",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        if let Some(dir) = app_data_dir() {
            let _ = std::fs::create_dir_all(&dir);
            command.current_dir(&dir);
        }

        let mut child = command
            .spawn()
            .map_err(|e| format!("无法启动 {}：{e}", locator.path.display()))?;
        let stdin = child.stdin.take().ok_or("AI runtime stdin 不可用")?;
        let stdout = child.stdout.take().ok_or("AI runtime stdout 不可用")?;
        let stderr = child.stderr.take().ok_or("AI runtime stderr 不可用")?;

        let (tx, rx) = channel();
        spawn_stdout_reader(stdout, tx.clone());
        spawn_stderr_reader(stderr, tx);

        Ok(Self {
            child,
            stdin,
            events: rx,
            pending: None,
            buffer: String::new(),
            stderr_tail: vec![],
        })
    }

    pub fn send(&mut self, request_id: &str, text: &str) -> Result<(), String> {
        if text.trim().is_empty() {
            return Err("消息不能为空".into());
        }
        if self.pending.is_some() {
            return Err("上一条消息仍在生成中".into());
        }
        let payload = json!({
            "event": "user",
            "message": { "content": text }
        });
        let line = serde_json::to_string(&payload).map_err(|e| e.to_string())?;
        self.stdin
            .write_all(line.as_bytes())
            .and_then(|_| self.stdin.write_all(b"\n"))
            .and_then(|_| self.stdin.flush())
            .map_err(|e| format!("无法写入 AI runtime：{e}"))?;
        self.pending = Some(request_id.to_string());
        self.buffer.clear();
        Ok(())
    }

    pub fn poll_events(&mut self) -> Vec<RuntimeEvent> {
        let mut out = vec![];
        while let Ok(event) = self.events.try_recv() {
            match event {
                RuntimeEvent::Delta { request_id, text } => {
                    self.buffer.push_str(&text);
                    out.push(RuntimeEvent::Delta { request_id, text });
                }
                RuntimeEvent::Complete { request_id, text } => {
                    let final_text = if text.trim().is_empty() {
                        self.buffer.clone()
                    } else {
                        text
                    };
                    self.pending = None;
                    self.buffer.clear();
                    out.push(RuntimeEvent::Complete {
                        request_id,
                        text: final_text,
                    });
                }
                RuntimeEvent::Error { request_id, message } => {
                    self.pending = None;
                    self.buffer.clear();
                    let message = self.with_diagnostics(message);
                    out.push(RuntimeEvent::Error { request_id, message });
                }
                RuntimeEvent::AuthRequired { request_id } => {
                    self.pending = None;
                    self.buffer.clear();
                    out.push(RuntimeEvent::AuthRequired { request_id });
                }
                RuntimeEvent::Status { text, state } => {
                    out.push(RuntimeEvent::Status { text, state });
                }
            }
        }
        if self.pending.is_some() {
            if let Ok(status) = self.child.try_wait() {
                if status.is_some() {
                    let request_id = self.pending.take().unwrap_or_default();
                    let message = self.with_diagnostics("AI runtime 已退出".into());
                    out.push(RuntimeEvent::Error { request_id, message });
                }
            }
        }
        out
    }

    pub fn current_request(&self) -> Option<String> {
        self.pending.clone()
    }

    pub fn abandon_pending(&mut self) {
        self.pending = None;
        self.buffer.clear();
    }

    fn with_diagnostics(&mut self, base: String) -> String {
        let stderr = self.stderr_tail.join(" | ");
        if stderr.is_empty() {
            base
        } else {
            format!("{base}\n运行时：{stderr}")
        }
    }

    #[allow(dead_code)]
    fn remember_stderr(&mut self, line: &str) {
        let sanitized = line.trim().chars().take(240).collect::<String>();
        if sanitized.is_empty() {
            return;
        }
        self.stderr_tail.push(sanitized);
        if self.stderr_tail.len() > 6 {
            self.stderr_tail.remove(0);
        }
    }
}

impl Drop for AntigravityRuntime {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn spawn_stdout_reader(stdout: std::process::ChildStdout, tx: Sender<RuntimeEvent>) {
    thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            let Ok(line) = line else { break };
            let Ok(event) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            match event.get("event").and_then(|v| v.as_str()) {
                Some("step_update") => {
                    let delta = event
                        .pointer("/step_update/text_delta")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    if delta.is_empty() {
                        continue;
                    }
                    let request_id = event
                        .pointer("/step_update/request_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let _ = tx.send(RuntimeEvent::Delta {
                        request_id,
                        text: delta,
                    });
                }
                Some("result") => {
                    let result = event.get("result").cloned().unwrap_or(Value::Null);
                    let status = result
                        .get("status")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let error_text = result
                        .get("error")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let response = result
                        .get("response")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let request_id = result
                        .get("request_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    if is_auth_required(&error_text) || status == "error" && is_auth_required(&status) {
                        let _ = tx.send(RuntimeEvent::AuthRequired { request_id });
                    } else if !error_text.is_empty() || ["error", "failed"].contains(&status.as_str()) {
                        let _ = tx.send(RuntimeEvent::Error {
                            request_id,
                            message: if error_text.is_empty() {
                                format!("AI runtime 返回状态：{status}")
                            } else {
                                error_text
                            },
                        });
                    } else {
                        let _ = tx.send(RuntimeEvent::Complete { request_id, text: response });
                    }
                }
                _ => {}
            }
        }
    });
}

fn spawn_stderr_reader(stderr: std::process::ChildStderr, tx: Sender<RuntimeEvent>) {
    thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            let Ok(line) = line else { break };
            eprintln!("agy: {line}");
            if is_auth_required(&line) {
                let _ = tx.send(RuntimeEvent::AuthRequired {
                    request_id: String::new(),
                });
            }
        }
    });
}

fn is_auth_required(message: &str) -> bool {
    const MARKERS: [&str; 8] = [
        "unauthenticated",
        "unauthorized",
        "not logged in",
        "please login",
        "please sign in",
        "oauth",
        "login required",
        "401",
    ];
    let lower = message.to_lowercase();
    MARKERS.iter().any(|m| lower.contains(m))
}

fn app_data_dir() -> Option<PathBuf> {
    dirs::data_dir().map(|d| d.join("Gecis"))
}

pub fn install_hint() -> Value {
    json!({
        "install": "irm https://antigravity.google/cli/install.ps1 | iex",
        "login": "安装后在终端运行 agy 完成 Google 登录，或设置 GECIS_AGY 指向 agy.exe",
        "env": "GECIS_AGY",
        "defaultPath": default_install_path(),
    })
}

pub fn default_install_path() -> String {
    dirs::data_local_dir()
        .map(|p| p.join("agy/bin/agy.exe").display().to_string())
        .unwrap_or_else(|| "%LOCALAPPDATA%\\agy\\bin\\agy.exe".into())
}

/// Launch interactive `agy` in a new console so the user can finish Google Sign-In.
pub fn start_interactive_login() -> Result<String, String> {
    let locator = locate_agy()?;
    let agy = locator.path.display().to_string();
    #[cfg(target_os = "windows")]
    {
        let mut command = Command::new("cmd");
        command
            .args(["/c", "start", "Gecis - Antigravity Login", &agy])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        command
            .spawn()
            .map_err(|e| format!("无法打开登录窗口: {e}"))?;
        Ok(format!("已打开登录窗口：{agy}\n请在新终端完成 Google 登录后回到 Gecis 重试。"))
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = agy;
        Err("当前平台请手动运行 agy 登录".into())
    }
}

pub fn probe_version() -> Result<String, String> {
    let locator = locate_agy()?;
    let output = Command::new(&locator.path)
        .arg("--help")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .map_err(|e| format!("无法执行 {}: {e}", locator.path.display()))?;
    if !output.status.success() && output.stdout.is_empty() {
        return Err(format!(
            "{} 无法运行（exit={:?}）",
            locator.path.display(),
            output.status.code()
        ));
    }
    Ok(locator.path.display().to_string())
}
