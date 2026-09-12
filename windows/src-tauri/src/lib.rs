mod commands;
mod history;
mod knowledge;
mod runtime;

use history::HistoryStore;
use knowledge::KnowledgeBase;
use runtime::AntigravityRuntime;
use std::sync::Mutex;
use tauri::Manager;

pub struct AppState {
    pub history: Mutex<HistoryStore>,
    pub knowledge: Mutex<KnowledgeBase>,
    pub runtime: Mutex<Option<AntigravityRuntime>>,
    pub current_conversation: Mutex<Option<i64>>,
    pub current_project: Mutex<Option<i64>>,
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let data_dir = app
                .path()
                .app_data_dir()
                .unwrap_or_else(|_| dirs::data_dir().unwrap_or_default().join("Gecis"));
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
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::bridge_ready,
            commands::send_message,
            commands::get_history,
            commands::create_project,
            commands::new_conversation,
            commands::open_conversation,
            commands::runtime_status,
            commands::start_login,
            commands::install_runtime,
            commands::import_fenbi,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Gecis Windows application");
}
