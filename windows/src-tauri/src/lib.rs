mod commands;
mod fenbi_mcp;
mod fenbi_sql;
mod history;
mod knowledge;
mod runtime;

use history::HistoryStore;
use knowledge::KnowledgeBase;
use runtime::AntigravityRuntime;
use std::sync::Mutex;
use tauri::image::Image;
use tauri::{Emitter, Manager};

#[derive(Clone)]
pub struct PendingRequest {
    pub request_id: String,
    pub text: String,
}

/// Used by `cargo run --bin e2e_chat` for end-to-end verification.
pub fn runtime_e2e(prompt: &str, timeout: std::time::Duration) -> Result<String, String> {
    runtime::e2e_chat_once(prompt, timeout)
}

pub struct AppState {
    pub history: Mutex<HistoryStore>,
    pub knowledge: Mutex<KnowledgeBase>,
    pub runtime: Mutex<Option<AntigravityRuntime>>,
    pub current_conversation: Mutex<Option<i64>>,
    pub current_project: Mutex<Option<i64>>,
    pub pending_auth: Mutex<Option<PendingRequest>>,
    pub auth_status: Mutex<String>,
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    #[cfg(windows)]
    {
        // WebView2 follows the system proxy. Clients like v2rayN then intercept
        // tauri.localhost / ipc.localhost and the window shows ERR_CONNECTION_REFUSED.
        std::env::set_var(
            "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
            "--proxy-bypass-list=<-loopback>;localhost;127.0.0.1;*.localhost",
        );
    }
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            if let Some(window) = app.get_webview_window("main") {
                // Keep a 256 px source for the live window icon. Passing the
                // default 32 px bitmap makes Windows upscale it on high-DPI
                // taskbars and visibly softens the fox.
                let icon = Image::from_bytes(include_bytes!("../icons/icon.png"))?;
                window.set_icon(icon)?;
            }
            let data_dir = app
                .path()
                .app_data_dir()
                .unwrap_or_else(|_| dirs::data_dir().unwrap_or_default().join("Gecis"));
            runtime::set_app_data_dir(data_dir.clone());
            std::fs::create_dir_all(&data_dir).map_err(|e| format!("无法创建数据目录: {e}"))?;
            let history = HistoryStore::open(&data_dir.join("gecis_history.db"))
                .map_err(|e| format!("历史库初始化失败: {e}"))?;
            let knowledge = KnowledgeBase::open(&data_dir.join("knowledge"));
            let default_project = history
                .ensure_default_project()
                .map_err(|e| format!("默认项目创建失败: {e}"))?;
            app.manage(AppState {
                history: Mutex::new(history),
                knowledge: Mutex::new(knowledge),
                runtime: Mutex::new(None),
                current_conversation: Mutex::new(None),
                current_project: Mutex::new(Some(default_project)),
                pending_auth: Mutex::new(None),
                auth_status: Mutex::new("unknown".into()),
            });
            let app_handle = app.handle().clone();
            tauri::async_runtime::spawn_blocking(move || {
                // Startup must not launch CLI probes: CLI bootstrap may spawn its own
                // console children. Authentication is resolved by the first real request.
                let state = app_handle.state::<AppState>();
                let auth = state.auth_status.lock().map(|v| v.clone()).unwrap_or_else(|_| "unknown".into());
                let _ = app_handle.emit(
                    "gecis://native-event",
                    serde_json::json!({"type":"setup_status","platform":"windows","auth":auth}),
                );
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::bridge_ready,
            commands::send_message,
            commands::retry_message,
            commands::get_history,
            commands::create_project,
            commands::new_conversation,
            commands::open_conversation,
            commands::move_conversation,
            commands::delete_conversation,
            commands::runtime_status,
            commands::start_login,
            commands::install_runtime,
            commands::import_fenbi,
            commands::get_conversation_id,
            commands::export_conversation,
            commands::resume_conversation,
            commands::get_setup_status,
            commands::get_runtime_settings,
            commands::get_available_models,
            commands::set_runtime_settings,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Gecis Windows application");
}
