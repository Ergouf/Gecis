use crate::runtime::{install_hint, locate_agy, AntigravityRuntime, RuntimeEvent};
use crate::AppState;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::mpsc::{channel, Sender};
use std::thread;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_dialog::DialogExt;

#[tauri::command]
pub fn bridge_ready(app: AppHandle) -> Result<Value, String> {
    let state = app.state::<AppState>();
    let conversation = *state.current_conversation.lock().map_err(|e| e.to_string())?;
    let snapshot = {
        let history = state.history.lock().map_err(|e| e.to_string())?;
        history.snapshot(conversation)?
    };
    let _ = app.emit("gecis://history", &snapshot);
    let _ = app.emit("gecis://status", json!({"text": "本地学习助手", "state": "idle"}));
    Ok(snapshot)
}

#[tauri::command]
pub fn get_history(app: AppHandle) -> Result<String, String> {
    let state = app.state::<AppState>();
    let conversation = *state.current_conversation.lock().map_err(|e| e.to_string())?;
    let history = state.history.lock().map_err(|e| e.to_string())?;
    Ok(history.snapshot(conversation)?.to_string())
}

#[tauri::command]
pub fn create_project(app: AppHandle, name: String) -> Result<String, String> {
    ensure_idle(&app)?;
    let state = app.state::<AppState>();
    let project_id = {
        let history = state.history.lock().map_err(|e| e.to_string())?;
        history.create_project(&name)?
    };
    *state.current_project.lock().map_err(|e| e.to_string())? = Some(project_id);
    *state.current_conversation.lock().map_err(|e| e.to_string())? = None;
    let history = state.history.lock().map_err(|e| e.to_string())?;
    Ok(history.snapshot(None)?.to_string())
}

#[tauri::command]
pub fn new_conversation(app: AppHandle, project_id: Option<i64>) -> Result<String, String> {
    ensure_idle(&app)?;
    let state = app.state::<AppState>();
    let history = state.history.lock().map_err(|e| e.to_string())?;
    let resolved_project = project_id
        .filter(|id| history.project_exists(*id).unwrap_or(false))
        .or(*state.current_project.lock().map_err(|e| e.to_string())?)
        .unwrap_or(history.ensure_default_project()?);
    let conversation_id = history.create_conversation(Some(resolved_project))?;
    drop(history);
    *state.current_project.lock().map_err(|e| e.to_string())? = Some(resolved_project);
    *state.current_conversation.lock().map_err(|e| e.to_string())? = Some(conversation_id);
    let history = state.history.lock().map_err(|e| e.to_string())?;
    Ok(history.snapshot(Some(conversation_id))?.to_string())
}

#[tauri::command]
pub fn open_conversation(app: AppHandle, conversation_id: i64) -> Result<String, String> {
    ensure_idle(&app)?;
    let state = app.state::<AppState>();
    {
        let history = state.history.lock().map_err(|e| e.to_string())?;
        if !history.conversation_exists(conversation_id)? {
            return Err("历史会话不存在".into());
        }
    }
    *state.current_conversation.lock().map_err(|e| e.to_string())? = Some(conversation_id);
    let history = state.history.lock().map_err(|e| e.to_string())?;
    Ok(history.snapshot(Some(conversation_id))?.to_string())
}

#[tauri::command]
pub fn runtime_status() -> Result<Value, String> {
    match locate_agy() {
        Ok(locator) => Ok(json!({
            "ok": true,
            "path": locator.path,
            "source": locator.source,
            "hint": install_hint(),
        })),
        Err(message) => Ok(json!({
            "ok": false,
            "error": message,
            "hint": install_hint(),
        })),
    }
}

#[tauri::command]
pub async fn send_message(app: AppHandle, request_id: String, text: String) -> Result<(), String> {
    if text.trim().is_empty() {
        emit_status(&app, "消息不能为空", "error");
        emit_error(&app, &request_id, "消息不能为空");
        return Ok(());
    }
    ensure_idle(&app)?;

    let app_task = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        if let Err(err) = handle_send(app_task, request_id, text) {
            eprintln!("send_message failed: {err}");
        }
    });
    Ok(())
}

fn ensure_idle(app: &AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();
    let runtime = state.runtime.lock().map_err(|e| e.to_string())?;
    if runtime
        .as_ref()
        .map(|r| r.current_request().is_some())
        .unwrap_or(false)
    {
        return Err("当前消息尚未完成".into());
    }
    Ok(())
}

fn emit_status(app: &AppHandle, text: &str, state: &str) {
    let _ = app.emit("gecis://status", json!({"text": text, "state": state}));
}

fn emit_native(app: &AppHandle, event: Value) {
    let _ = app.emit("gecis://native-event", event);
}

fn emit_error(app: &AppHandle, request_id: &str, message: &str) {
    emit_native(
        app,
        json!({"type": "error", "requestId": request_id, "text": message}),
    );
}

fn handle_send(app: AppHandle, request_id: String, text: String) -> Result<(), String> {
    emit_status(&app, "正在准备对话…", "working");
    persist_user_message(&app, &request_id, &text)?;

    let augmented = augment_message(&app, &request_id, &text)?;

    emit_status(&app, "正在检索并思考…", "working");
    {
        let state = app.state::<AppState>();
        let mut runtime_slot = state.runtime.lock().map_err(|e| e.to_string())?;
        if runtime_slot.is_none() {
            match AntigravityRuntime::spawn() {
                Ok(runtime) => {
                    *runtime_slot = Some(runtime);
                    emit_status(&app, "AI runtime 已连接", "success");
                }
                Err(message) => {
                    let hint = install_hint();
                    emit_status(&app, "未找到 Antigravity CLI", "error");
                    emit_error(
                        &app,
                        &request_id,
                        &format!(
                            "{message}\n安装：{}\n登录：{}",
                            hint["install"].as_str().unwrap_or(""),
                            hint["login"].as_str().unwrap_or("")
                        ),
                    );
                    return Ok(());
                }
            }
        }
        let runtime = runtime_slot.as_mut().ok_or("AI runtime 不可用")?;
        if let Err(err) = runtime.send(&request_id, &augmented) {
            emit_error(&app, &request_id, &err);
            emit_status(&app, "发送失败", "error");
            return Ok(());
        }
    }

    let (done_tx, done_rx) = channel::<()>();
    let app_pump = app.clone();
    let request_id_pump = request_id.clone();
    thread::spawn(move || pump_runtime_events(app_pump, request_id_pump, done_tx));
    let _ = done_rx.recv_timeout(Duration::from_secs(10 * 60));
    Ok(())
}

fn persist_user_message(app: &AppHandle, _request_id: &str, text: &str) -> Result<(), String> {
    let state = app.state::<AppState>();
    let history = state.history.lock().map_err(|e| e.to_string())?;
    let current = *state.current_conversation.lock().map_err(|e| e.to_string())?;
    let conversation_id = match current {
        Some(id) if history.conversation_exists(id)? => id,
        _ => {
            let project = *state.current_project.lock().map_err(|e| e.to_string())?;
            history.create_conversation(project)?
        }
    };
    *state.current_conversation.lock().map_err(|e| e.to_string())? = Some(conversation_id);
    if let Err(err) = history.append_message(conversation_id, "user", text) {
        emit_status(app, &format!("历史记录保存失败：{err}"), "error");
    }
    let snapshot = history.snapshot(Some(conversation_id))?;
    let _ = app.emit("gecis://history", snapshot);
    Ok(())
}

fn augment_message(app: &AppHandle, request_id: &str, text: &str) -> Result<String, String> {
    let state = app.state::<AppState>();
    let needs_import = {
        let knowledge = state.knowledge.lock().map_err(|e| e.to_string())?;
        !knowledge.has_database()
    };

    if needs_import {
        emit_status(app, "请选择 fenbi.db", "working");
        if let Some(path) = pick_fenbi_file(app) {
            emit_status(app, "正在导入 fenbi.db…", "working");
            let mut knowledge = state.knowledge.lock().map_err(|e| e.to_string())?;
            match knowledge.import_from(&path) {
                Ok(name) => emit_status(app, &format!("{name} 导入成功"), "success"),
                Err(err) => {
                    emit_status(app, &format!("导入失败：{err}"), "error");
                    emit_error(app, request_id, &format!("fenbi.db 导入失败：{err}"));
                    return Err(err);
                }
            }
        } else {
            emit_status(app, "未导入 fenbi.db，将直接提问", "working");
        }
    }

    let knowledge = state.knowledge.lock().map_err(|e| e.to_string())?;
    Ok(knowledge.augment_user_message(text))
}

fn pick_fenbi_file(app: &AppHandle) -> Option<PathBuf> {
    let picked = app
        .dialog()
        .file()
        .add_filter("SQLite", &["db", "sqlite", "sqlite3"])
        .set_title("选择 fenbi.db")
        .blocking_pick_file();
    picked.and_then(|path| path.into_path().ok())
}

fn pump_runtime_events(app: AppHandle, request_id: String, done_tx: Sender<()>) {
    loop {
        let events = poll(&app);
        let mut finished = false;

        for event in events {
            match event {
                RuntimeEvent::Status { text, state } => emit_status(&app, &text, &state),
                RuntimeEvent::Delta { request_id: rid, text } => {
                    let rid = if rid.is_empty() {
                        request_id.clone()
                    } else {
                        rid
                    };
                    emit_native(&app, json!({"type":"delta","requestId": rid, "text": text}));
                    emit_status(&app, "正在回答…", "working");
                }
                RuntimeEvent::Complete { request_id: rid, text } => {
                    let rid = if rid.is_empty() {
                        request_id.clone()
                    } else {
                        rid
                    };
                    emit_native(&app, json!({"type":"complete","requestId": rid, "text": text}));
                    emit_status(&app, "已就绪", "idle");
                    save_assistant(&app, &text);
                    finished = true;
                }
                RuntimeEvent::Error { request_id: rid, message } => {
                    let rid = if rid.is_empty() {
                        request_id.clone()
                    } else {
                        rid
                    };
                    emit_native(&app, json!({"type":"error","requestId": rid, "text": message}));
                    emit_status(&app, "发生错误", "error");
                    finished = true;
                }
                RuntimeEvent::AuthRequired { request_id: rid } => {
                    let rid = if rid.is_empty() {
                        request_id.clone()
                    } else {
                        rid
                    };
                    let hint = install_hint();
                    emit_native(
                        &app,
                        json!({
                            "type":"error",
                            "requestId": rid,
                            "text": format!(
                                "需要登录 Antigravity CLI。请在终端运行 agy 完成 Google 登录。\n安装：{}\n{}",
                                hint["install"].as_str().unwrap_or(""),
                                hint["login"].as_str().unwrap_or("")
                            )
                        }),
                    );
                    emit_status(&app, "请先登录 Antigravity CLI", "error");
                    finished = true;
                }
            }
        }

        if finished {
            break;
        }

        let still_pending = {
            let state = app.state::<AppState>();
            state
                .runtime
                .lock()
                .ok()
                .and_then(|slot| slot.as_ref().map(|r| r.current_request().is_some()))
                .unwrap_or(false)
        };
        if !still_pending {
            thread::sleep(Duration::from_millis(80));
            for event in poll(&app) {
                match event {
                    RuntimeEvent::Complete { text, request_id: rid } => {
                        let rid = if rid.is_empty() {
                            request_id.clone()
                        } else {
                            rid
                        };
                        emit_native(&app, json!({"type":"complete","requestId": rid, "text": text}));
                        emit_status(&app, "已就绪", "idle");
                        save_assistant(&app, &text);
                    }
                    RuntimeEvent::Error { message, request_id: rid } => {
                        let rid = if rid.is_empty() {
                            request_id.clone()
                        } else {
                            rid
                        };
                        emit_native(&app, json!({"type":"error","requestId": rid, "text": message}));
                        emit_status(&app, "发生错误", "error");
                    }
                    _ => {}
                }
            }
            break;
        }

        thread::sleep(Duration::from_millis(40));
    }
    let _ = done_tx.send(());
}

fn poll(app: &AppHandle) -> Vec<RuntimeEvent> {
    let state = app.state::<AppState>();
    let result = match state.runtime.lock() {
        Ok(mut slot) => match slot.as_mut() {
            Some(runtime) => runtime.poll_events(),
            None => vec![],
        },
        Err(_) => vec![],
    };
    result
}

fn save_assistant(app: &AppHandle, text: &str) {
    if text.trim().is_empty() {
        return;
    }
    let state = app.state::<AppState>();
    let Ok(history) = state.history.lock() else {
        return;
    };
    let Ok(conversation) = state.current_conversation.lock() else {
        return;
    };
    if let Some(id) = *conversation {
        let _ = history.append_message(id, "assistant", text);
        if let Ok(snapshot) = history.snapshot(Some(id)) {
            let _ = app.emit("gecis://history", snapshot);
        }
    }
}
