use crate::runtime::{
    fetch_available_models, install_hint, load_runtime_settings, locate_agy,
    probe_authentication, save_runtime_settings, start_background_login, AntigravityRuntime,
    RuntimeEvent,
};
use crate::{AppState, PendingRequest};
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

    // Surface runtime availability immediately so the UI is never silent.
    match locate_agy() {
        Ok(_) => {
            emit_native(&app, json!({"type":"setup_status","platform":"windows","runtime":"ready"}));
        }
        Err(_) => {
            emit_native(&app, json!({"type":"setup_status","platform":"windows","runtime":"missing"}));
        }
    }
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
    if let Ok(mut slot) = state.runtime.lock() {
        *slot = None;
    }
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
    if let Ok(mut slot) = state.runtime.lock() {
        *slot = None;
    }
    let history = state.history.lock().map_err(|e| e.to_string())?;
    Ok(history.snapshot(Some(conversation_id))?.to_string())
}

#[tauri::command]
pub fn move_conversation(app: AppHandle, conversation_id: i64, project_id: i64) -> Result<String, String> {
    ensure_idle(&app)?;
    let state = app.state::<AppState>();
    let history = state.history.lock().map_err(|e| e.to_string())?;
    history.move_conversation(conversation_id, project_id)?;
    let current = *state.current_conversation.lock().map_err(|e| e.to_string())?;
    if current == Some(conversation_id) {
        *state.current_project.lock().map_err(|e| e.to_string())? = Some(project_id);
    }
    Ok(history.snapshot(current)?.to_string())
}

#[tauri::command]
pub fn delete_conversation(app: AppHandle, conversation_id: i64) -> Result<String, String> {
    ensure_idle(&app)?;
    let state = app.state::<AppState>();
    let history = state.history.lock().map_err(|e| e.to_string())?;
    history.delete_conversation(conversation_id)?;
    let mut current = state.current_conversation.lock().map_err(|e| e.to_string())?;
    if *current == Some(conversation_id) {
        *current = None;
        if let Ok(mut runtime) = state.runtime.lock() { *runtime = None; }
    }
    Ok(history.snapshot(*current)?.to_string())
}

#[tauri::command]
pub fn get_conversation_id(app: AppHandle) -> Result<Value, String> {
    let state = app.state::<AppState>();
    let conversation = *state.current_conversation.lock().map_err(|e| e.to_string())?;
    let Some(local_id) = conversation else {
        return Ok(json!({"localId": null, "agyConversationId": null}));
    };
    let history = state.history.lock().map_err(|e| e.to_string())?;
    let agy = history.get_agy_conversation_id(local_id)?;
    let title = history.conversation_title(local_id)?;
    Ok(json!({
        "localId": local_id,
        "title": title,
        "agyConversationId": agy,
    }))
}

/// Codex-style: paste another session's Antigravity conversation id and continue.
#[tauri::command]
pub fn resume_conversation(app: AppHandle, agy_id: String) -> Result<String, String> {
    ensure_idle(&app)?;
    let agy_id = agy_id.trim().to_string();
    if agy_id.is_empty() {
        return Err("会话 ID 不能为空".into());
    }
    if agy_id.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return Err("会话 ID 格式无效".into());
    }
    let state = app.state::<AppState>();
    let history = state.history.lock().map_err(|e| e.to_string())?;
    let project = *state.current_project.lock().map_err(|e| e.to_string())?;
    let project = project.unwrap_or(history.ensure_default_project()?);
    let conversation_id = history.create_conversation(Some(project))?;
    history.set_agy_conversation_id(conversation_id, &agy_id)?;
    drop(history);
    *state.current_project.lock().map_err(|e| e.to_string())? = Some(project);
    *state.current_conversation.lock().map_err(|e| e.to_string())? = Some(conversation_id);
    if let Ok(mut slot) = state.runtime.lock() {
        *slot = None;
    }
    emit_status(&app, "已接入共享会话，可继续提问", "success");
    let history = state.history.lock().map_err(|e| e.to_string())?;
    Ok(history.snapshot(Some(conversation_id))?.to_string())
}

#[tauri::command]
pub async fn export_conversation(app: AppHandle, format: String) -> Result<String, String> {
    let app2 = app.clone();
    let format2 = format.clone();
    // blocking_save_file must not run on the async runtime thread — use a worker.
    tauri::async_runtime::spawn_blocking(move || export_conversation_blocking(app2, format2))
        .await
        .map_err(|e| format!("导出任务失败: {e}"))?
}

fn export_conversation_blocking(app: AppHandle, format: String) -> Result<String, String> {
    let state = app.state::<AppState>();
    let conversation = *state.current_conversation.lock().map_err(|e| e.to_string())?;
    let Some(local_id) = conversation else {
        return Err("当前没有打开的会话".into());
    };
    let (title, messages, agy_id) = {
        let history = state.history.lock().map_err(|e| e.to_string())?;
        (
            history.conversation_title(local_id)?,
            history.list_messages(local_id)?,
            history.get_agy_conversation_id(local_id)?,
        )
    };

    let ext = match format.as_str() {
        "md" | "markdown" => "md",
        "html" => "html",
        other => return Err(format!("不支持的导出格式：{other}")),
    };
    let default_name = format!("Gecis-{}-{}.{ext}", sanitize_filename(&title), local_id);

    let picked = app
        .dialog()
        .file()
        .set_title("导出会话")
        .set_file_name(&default_name)
        .blocking_save_file();
    let Some(path_buf) = picked.and_then(|p| p.into_path().ok()) else {
        return Ok("cancelled".into());
    };

    let body = if ext == "md" {
        render_markdown_export(&title, local_id, agy_id.as_deref(), &messages)
    } else {
        render_html_export(&title, local_id, agy_id.as_deref(), &messages)
    };
    std::fs::write(&path_buf, body).map_err(|e| format!("写入失败: {e}"))?;
    emit_status(&app, &format!("已导出 {}", path_buf.display()), "success");
    Ok(path_buf.display().to_string())
}

fn sanitize_filename(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '-' | '_' | ' ') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let trimmed = cleaned.trim().to_string();
    if trimmed.is_empty() {
        "会话".into()
    } else {
        trimmed.chars().take(40).collect()
    }
}

fn render_markdown_export(
    title: &str,
    local_id: i64,
    agy_id: Option<&str>,
    messages: &[(String, String)],
) -> String {
    let mut out = String::new();
    out.push_str(&format!("# {title}\n\n"));
    out.push_str(&format!("- 本地会话 ID: `{local_id}`\n"));
    if let Some(agy) = agy_id {
        out.push_str(&format!("- Antigravity 会话 ID: `{agy}`\n"));
        out.push_str(&format!(
            "  - 继续对话：`agy --conversation {agy}`\n"
        ));
    }
    out.push('\n');
    for (role, content) in messages {
        let label = if role == "user" { "用户" } else { "助手" };
        out.push_str(&format!("## {label}\n\n"));
        out.push_str(content.trim());
        out.push_str("\n\n---\n\n");
    }
    out
}

fn render_html_export(
    title: &str,
    local_id: i64,
    agy_id: Option<&str>,
    messages: &[(String, String)],
) -> String {
    let mut blocks = String::new();
    for (role, content) in messages {
        let label = if role == "user" { "用户" } else { "助手" };
        let class = if role == "user" { "user" } else { "assistant" };
        let escaped = content
            .replace('&', "&amp;")
            .replace('<', "&lt;")
            .replace('>', "&gt;")
            .replace('"', "&quot;");
        blocks.push_str(&format!(
            r#"<section class="message {class}">
  <div class="meta">{label}</div>
  <div class="bubble" data-md="{escaped}"></div>
</section>
"#
        ));
    }
    let agy_line = match agy_id {
        Some(agy) => format!(
            "<li>Antigravity 会话 ID：<code>{agy}</code> · 继续：<code>agy --conversation {agy}</code></li>"
        ),
        None => String::new(),
    };
    format!(
        r##"<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<title>{title}</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css" />
<style>
  body {{ font-family: Inter, system-ui, "Segoe UI", sans-serif; background:#f7f7f5; color:#171717; margin:0; padding:32px; }}
  main {{ max-width: 760px; margin: 0 auto; }}
  h1 {{ letter-spacing:-.03em; }}
  .meta {{ font-size:12px; color:#777; margin-bottom:6px; }}
  .message {{ margin: 0 0 28px; line-height:1.72; font-size:16px; }}
  .user .bubble {{ max-width:82%; margin-left:auto; background:#e9e9e5; border-radius:20px 20px 5px 20px; padding:12px 16px; white-space:pre-wrap; }}
  .assistant .bubble {{ max-width:100%; }}
  .assistant img {{ max-width:100%; border-radius:8px; }}
  .assistant pre {{ background:#efefeb; padding:12px; border-radius:10px; overflow:auto; }}
  .assistant code {{ font-family: Consolas, monospace; background:#ecece8; padding:.1em .3em; border-radius:4px; }}
  .assistant pre code {{ background:transparent; padding:0; }}
  .assistant table {{ border-collapse:collapse; width:100%; }}
  .assistant th,.assistant td {{ border-bottom:1px solid #deded9; padding:8px 10px; text-align:left; }}
  .katex-display {{ overflow-x:auto; }}
  ul.ids {{ color:#555; font-size:14px; }}
</style>
</head>
<body>
<main>
<h1>{title}</h1>
<ul class="ids">
<li>本地会话 ID：<code>{local_id}</code></li>
{agy_line}
</ul>
{blocks}
</main>
<script src="https://cdn.jsdelivr.net/npm/marked@14.1.0/marked.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/dompurify@3.1.6/dist/purify.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
<script>
function protectMath(source) {{
  const expressions = [];
  const p = 'GECISMATHTOKEN', s = 'ENDTOKEN';
  const re = /\\$\\$[\\s\\S]*?\\$\\$|\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$[^$\\n]+?\\$/g;
  const text = source.replace(re, (m) => {{ expressions.push(m); return p + (expressions.length-1) + s; }});
  return {{ text, expressions, p, s }};
}}
function restoreMath(html, pm) {{
  const re = new RegExp(pm.p + '(\\\\d+)' + pm.s, 'g');
  return html.replace(re, (_, i) => (pm.expressions[Number(i)]||'').replace(/[&<>]/g, c => ({{'&':'&amp;','<':'&lt;','>':'&gt;'}}[c])));
}}
function renderBubble(el) {{
  const md = el.getAttribute('data-md') || '';
  const pm = protectMath(md);
  const dirty = marked.parse(pm.text, {{ gfm:true, breaks:true }});
  const clean = DOMPurify.sanitize(dirty, {{ USE_PROFILES: {{ html:true }}, FORBID_TAGS:['iframe','object','embed','style','form','input','button','script'] }});
  el.innerHTML = restoreMath(clean, pm);
  if (window.renderMathInElement) {{
    renderMathInElement(el, {{
      delimiters:[
        {{left:'$$',right:'$$',display:true}},
        {{left:'\\\\[',right:'\\\\]',display:true}},
        {{left:'$',right:'$',display:false}},
        {{left:'\\\\(',right:'\\\\)',display:false}}
      ],
      ignoredTags:['script','noscript','style','textarea','pre','code','option'],
      throwOnError:false, trust:false
    }});
  }}
}}
document.querySelectorAll('.bubble[data-md]').forEach(renderBubble);
</script>
</body>
</html>
"##
    )
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

/// Setup checklist for the empty state: hide steps the user already finished.
#[tauri::command]
pub fn get_runtime_settings() -> Result<Value, String> {
    Ok(load_runtime_settings())
}

#[tauri::command]
pub fn get_available_models() -> Result<Value, String> {
    fetch_available_models(Duration::from_secs(20))
}

#[tauri::command]
pub fn set_runtime_settings(app: AppHandle, model: String, effort: Option<String>) -> Result<Value, String> {
    let _ = effort;
    ensure_idle(&app)?;
    let settings = save_runtime_settings(&model)?;
    if let Ok(mut slot) = app.state::<AppState>().runtime.lock() {
        *slot = None;
    }
    emit_status(&app, "模型设置已应用", "success");
    Ok(settings)
}

#[tauri::command]
pub fn get_setup_status(app: AppHandle) -> Result<Value, String> {
    let has_agy = locate_agy().is_ok();
    let state = app.state::<AppState>();
    let auth = state.auth_status.lock().map(|v| v.clone()).unwrap_or_else(|_| "unknown".into());
    let has_fenbi = state.knowledge.lock().map(|k| k.has_database()).unwrap_or(false);
    Ok(json!({
        "platform": "windows",
        "runtime": if has_agy { "ready" } else { "missing" },
        "auth": auth,
        "knowledge": { "available": has_fenbi, "name": if has_fenbi { Some("fenbi.db") } else { None } },
        "agyInstalled": has_agy,
        "hasFenbi": has_fenbi,
    }))
}

#[tauri::command]
pub fn start_login(app: AppHandle, request_id: Option<String>) -> Result<String, String> {
    if let Ok(mut auth) = app.state::<AppState>().auth_status.lock() {
        *auth = "checking".into();
    }
    emit_status(&app, "正在打开 Google 登录…", "working");
    let app_task = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        if let Err(err) = start_background_login() {
            emit_status(&app_task, &format!("无法启动登录：{err}"), "error");
            return;
        }
        for _ in 0..60 {
            if probe_authentication(Duration::from_secs(8)) {
                if let Ok(mut auth) = app_task.state::<AppState>().auth_status.lock() {
                    *auth = "connected".into();
                }
                emit_native(&app_task, json!({"type":"setup_status","platform":"windows","auth":"connected"}));
                emit_status(&app_task, "Google 账号已连接", "success");
                let pending = app_task.state::<AppState>().pending_auth.lock().ok().and_then(|mut value| value.take());
                if let Some(pending) = pending.filter(|value| request_id.as_deref().map(|id| id == value.request_id).unwrap_or(true)) {
                    if let Ok(mut runtime) = app_task.state::<AppState>().runtime.lock() { *runtime = None; }
                    let retry_app = app_task.clone();
                    tauri::async_runtime::spawn_blocking(move || {
                        if let Err(err) = handle_send(retry_app.clone(), pending.request_id.clone(), pending.text, false) {
                            emit_error(&retry_app, &pending.request_id, &err);
                        }
                    });
                }
                return;
            }
            thread::sleep(Duration::from_secs(2));
        }
        if let Ok(mut auth) = app_task.state::<AppState>().auth_status.lock() { *auth = "required".into(); }
        emit_status(&app_task, "尚未完成 Google 登录", "error");
        if let Some(id) = request_id.as_deref() {
            emit_action_required(
                &app_task, id, "auth", "账号连接没有完成",
                "请完成浏览器中的授权，然后重试。",
                json!([{"id":"login","label":"重新登录","primary":true}]),
            );
        }
    });
    Ok("started".into())
}

#[tauri::command]
pub fn install_runtime(app: AppHandle) -> Result<String, String> {
    emit_status(&app, "正在安装 Antigravity CLI…", "working");
    let mut command = crate::runtime::background_command("powershell");
    command
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "irm https://antigravity.google/cli/install.ps1 | iex",
        ])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    let output = command
        .output()
        .map_err(|e| format!("无法启动安装脚本: {e}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    // Re-resolve after install (PATH may not refresh in this process).
    match locate_agy() {
        Ok(locator) => {
            emit_status(
                &app,
                &format!("安装成功：{}", locator.path.display()),
                "success",
            );
            Ok(format!(
                "已安装 {}\n{stdout}\n{stderr}",
                locator.path.display()
            ))
        }
        Err(err) => {
            let message = format!(
                "安装脚本已执行，但本进程仍未找到 agy。\n{err}\n\nstdout:\n{stdout}\nstderr:\n{stderr}\n\n请确认 {} 存在，或设置 GECIS_AGY 后重启应用。",
                install_hint()["defaultPath"].as_str().unwrap_or("")
            );
            emit_status(&app, "安装后未找到 agy，请重启应用", "error");
            Err(message)
        }
    }
}

#[tauri::command]
pub async fn import_fenbi(app: AppHandle) -> Result<String, String> {
    let app2 = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let Some(path) = pick_fenbi_file(&app2) else {
            return Ok("cancelled".into());
        };
        let state = app2.state::<AppState>();
        let mut knowledge = state.knowledge.lock().map_err(|e| e.to_string())?;
        match knowledge.import_from(&path) {
            Ok(name) => {
                emit_status(&app2, &format!("{name} 导入成功"), "success");
                Ok(name)
            }
            Err(err) => {
                emit_status(&app2, &format!("导入失败：{err}"), "error");
                Err(err)
            }
        }
    })
    .await
    .map_err(|e| format!("导入任务失败: {e}"))?
}

#[tauri::command]
pub async fn send_message(app: AppHandle, request_id: String, text: String) -> Result<(), String> {
    if text.trim().is_empty() {
        emit_status(&app, "消息不能为空", "error");
        emit_error(&app, &request_id, "消息不能为空");
        return Ok(());
    }
    match ensure_idle(&app) {
        Ok(()) => {}
        Err(err) => {
            emit_status(&app, &err, "error");
            emit_error(&app, &request_id, &err);
            return Ok(());
        }
    }

    let app_task = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        if let Err(err) = handle_send(app_task.clone(), request_id.clone(), text, true) {
            emit_status(&app_task, "发送失败", "error");
            emit_error(&app_task, &request_id, &err);
        }
    });
    Ok(())
}

#[tauri::command]
pub async fn retry_message(app: AppHandle, request_id: String, text: String) -> Result<(), String> {
    ensure_idle(&app)?;
    if let Ok(mut runtime) = app.state::<AppState>().runtime.lock() {
        *runtime = None;
    }
    let app_task = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        if let Err(err) = handle_send(app_task.clone(), request_id.clone(), text, false) {
            emit_error(&app_task, &request_id, &err);
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

fn emit_action_required(
    app: &AppHandle,
    request_id: &str,
    kind: &str,
    title: &str,
    message: &str,
    actions: Value,
) {
    emit_native(app, json!({
        "type": "action_required",
        "requestId": request_id,
        "kind": kind,
        "title": title,
        "message": message,
        "actions": actions,
        "autoResume": kind == "auth",
    }));
}

fn handle_send(app: AppHandle, request_id: String, text: String, persist: bool) -> Result<(), String> {
    emit_status(&app, "正在准备对话…", "working");
    if persist {
        persist_user_message(&app, &text)?;
    }

    // Model decides when/how to search via MCP `search_fenbi`. No program-side pre-retrieval.
    let augmented = text.clone();

    emit_status(&app, "正在连接 AI runtime…", "working");
    {
        let state = app.state::<AppState>();
        let mut runtime_slot = state.runtime.lock().map_err(|e| e.to_string())?;
        if runtime_slot.is_none() {
            let resume = {
                let conversation = *state.current_conversation.lock().map_err(|e| e.to_string())?;
                match conversation {
                    Some(id) => state
                        .history
                        .lock()
                        .map_err(|e| e.to_string())?
                        .get_agy_conversation_id(id)
                        .ok()
                        .flatten(),
                    None => None,
                }
            };
            match AntigravityRuntime::spawn_with_conversation(resume.as_deref()) {
                Ok(runtime) => {
                    *runtime_slot = Some(runtime);
                }
                Err(message) => {
                    emit_status(&app, "未找到 Antigravity CLI", "error");
                    emit_action_required(
                        &app,
                        &request_id,
                        "runtime",
                        "缺少 Antigravity 运行环境",
                        &message,
                        json!([
                            {"id":"install","label":"安装运行环境","primary":true},
                            {"id":"copy_install","label":"复制安装命令"}
                        ]),
                    );
                    return Ok(());
                }
            }
        }
        let runtime = runtime_slot.as_mut().ok_or("AI runtime 不可用")?;
        if let Err(err) = runtime.send(&request_id, &augmented) {
            emit_status(&app, "发送失败", "error");
            emit_error(&app, &request_id, &err);
            return Ok(());
        }
    }

    emit_status(&app, "正在思考…", "working");
    let (done_tx, done_rx) = channel::<()>();
    let app_pump = app.clone();
    let request_id_pump = request_id.clone();
    let text_pump = text.clone();
    thread::spawn(move || pump_runtime_events(app_pump, request_id_pump, text_pump, done_tx));
    match done_rx.recv_timeout(Duration::from_secs(10 * 60)) {
        Ok(()) => Ok(()),
        Err(_) => {
            emit_status(&app, "AI runtime 超时", "error");
            emit_action_required(
                &app, &request_id, "network", "等待回答超时",
                "请检查网络后重试。", json!([{"id":"retry","label":"重试","primary":true}]),
            );
            Ok(())
        }
    }
}

fn persist_user_message(app: &AppHandle, text: &str) -> Result<(), String> {
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

fn pick_fenbi_file(app: &AppHandle) -> Option<PathBuf> {
    // Keep dialog off the UI/async threads; caller is already on a worker.
    let picked = app
        .dialog()
        .file()
        .add_filter("SQLite", &["db", "sqlite", "sqlite3"])
        .set_title("选择 fenbi.db")
        .blocking_pick_file();
    picked.and_then(|path| path.into_path().ok())
}

fn pump_runtime_events(app: AppHandle, request_id: String, original_text: String, done_tx: Sender<()>) {
    let mut idle_ticks = 0u32;
    let mut phase = "正在思考…".to_string();
    loop {
        let events = poll(&app);
        if !events.is_empty() {
            idle_ticks = 0;
        } else {
            idle_ticks = idle_ticks.saturating_add(1);
            if idle_ticks > 0 && idle_ticks % 100 == 0 {
                let label = if idle_ticks >= 200 && !phase.contains("查询") {
                    "仍在思考…".to_string()
                } else {
                    phase.clone()
                };
                emit_status(&app, &label, "working");
            }
        }
        let mut finished = false;

        for event in events {
            match event {
                RuntimeEvent::Status { text, state } => {
                    if state == "working" && !text.is_empty() {
                        phase = text.clone();
                    }
                    emit_status(&app, &text, &state);
                }
                RuntimeEvent::ConversationId { id } => {
                    let state = app.state::<AppState>();
                    let local_id = state.current_conversation.lock().ok().and_then(|c| *c);
                    if let Some(local_id) = local_id {
                        if let Ok(history) = state.history.lock() {
                            let _ = history.set_agy_conversation_id(local_id, &id);
                        }
                    }
                }
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
                    emit_action_required(
                        &app, &rid, "network", "没有完成回答", &message,
                        json!([{"id":"retry","label":"重试","primary":true}]),
                    );
                    emit_status(&app, "发生错误", "error");
                    finished = true;
                }
                RuntimeEvent::AuthRequired { request_id: rid } => {
                    let rid = if rid.is_empty() {
                        request_id.clone()
                    } else {
                        rid
                    };
                    if let Ok(mut pending) = app.state::<AppState>().pending_auth.lock() {
                        *pending = Some(PendingRequest { request_id: rid.clone(), text: original_text.clone() });
                    }
                    if let Ok(mut auth) = app.state::<AppState>().auth_status.lock() { *auth = "required".into(); }
                    emit_action_required(
                        &app, &rid, "auth", "需要连接 Google 账号",
                        "连接账号后会自动继续刚才的问题。",
                        json!([{"id":"login","label":"去登录","primary":true}]),
                    );
                    emit_status(&app, "等待连接 Google 账号", "working");
                    finished = true;
                }
            }
        }

        if finished {
            break;
        }

        // ~4 minutes without any runtime event while pending → fail visibly.
        if idle_ticks >= 6000 {
            let pending = {
                let state = app.state::<AppState>();
                state
                    .runtime
                    .lock()
                    .ok()
                    .and_then(|slot| slot.as_ref().map(|r| r.current_request().is_some()))
                    .unwrap_or(false)
            };
            if pending {
                emit_action_required(
                    &app, &request_id, "network", "AI 暂无响应",
                    "请检查网络；如果刚完成登录，可以直接重试。",
                    json!([{"id":"retry","label":"重试","primary":true}]),
                );
                emit_status(&app, "AI runtime 无响应，请检查登录/网络", "error");
                // Clear pending so the next send can retry.
                if let Ok(mut slot) = app.state::<AppState>().runtime.lock() {
                    if let Some(runtime) = slot.as_mut() {
                        runtime.abandon_pending();
                    }
                    *slot = None;
                }
                finished = true;
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
