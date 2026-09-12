---
feature: windows-shell
status: delivered
updated: 2026-09-12
branch: feat/windows-shell
commits: 654ee66bd0c8669cac56720503b80ef8ab418b54..a7ec7102ff359d8ced3fd7fe07b607d06e298a14
---

# Windows Shell

## Report

**What was built** — Gecis Windows 桌面子项目：Tauri 2 + WebView2 壳，复用 Android 聊天交互契约（`GecisNative` / `GecisChat`），实现本地 SQLite 历史、fenbi.db 只读导入与检索增强、系统 `agy` headless NDJSON 流式对话。未打包引擎、未自写 OAuth；缺失 `agy` 时给出安装/登录引导。

**Verification** — `npm install` PASS；`cargo check` PASS（仅 dead_code warning，已 allow）；`npm run build` PASS，产出 `gecis-windows.exe`、NSIS `Gecis_0.1.0_x64-setup.exe`、MSI `Gecis_0.1.0_x64_en-US.msi`。

**Journey log**
- 环境无 Rust，先装 rustup stable 1.98.1 再脚手架，避免 Electron 重栈。
- `git worktree add` 被会话沙箱拦截，用户侧创建 worktree 后文档迁入隔离分支。
- 前端 vendor 从 Android `app/src/main/assets/vendor` 同源 staging，gitignore 构建产物，保留 `scripts/stage-vendor.ps1`。
- 对话协议与 Android 保持 NDJSON 一致，便于双端对照。

## [S1] Problem

Android 版 Gecis 已交付单 APK 对话体验，但 Windows 缺少对应桌面壳：用户无法在 PC 上连续对话、导入 fenbi.db、使用官方 Antigravity CLI 作为 AI runtime。需要在隔离 worktree 中交付可构建的 Windows 子项目，复用现有 WebView 资产与 NDJSON 协议，不重复造轮子。

## [S2] Design

### 目标形态

单 Windows 桌面应用（Tauri 2 + WebView2）：

1. 连续对话 UI（Markdown + KaTeX），复用 Android `index.html` 交互契约。
2. 窄原生桥 `GecisNative`：`sendMessage` / `getHistory` / `createProject` / `newConversation` / `openConversation`。
3. AI runtime：探测系统 `agy`，以 `--input-format stream-json --output-format stream-json --sandbox --print-timeout 5m` 常驻；NDJSON 事件 `step_update` / `result`。
4. 知识库：文件对话框导入 `fenbi.db` 到应用私有目录，校验后只读检索，结果包在 `<fenbi_context>`。
5. 历史：本地 SQLite `gecis_history.db`，项目 → 会话 → 消息。
6. 认证：不自写 PKCE；`agy` 缺失或未登录时，前端状态栏引导官方安装/登录。

### 前端桥契约

`window.GecisNative` 同步/异步方法返回 JSON 字符串快照；原生侧通过 `window.GecisChat.onStatus/onHistory/onNativeEvent/onResumeTurn` 回调。Tauri 用 `invoke` 封装同名 API，事件用 `listen` 注入。

### 运行时定位顺序

1. `GECIS_AGY` 环境变量
2. `agy` / `agy.exe` on PATH
3. `%USERPROFILE%\.local\bin\agy.exe`、`%LOCALAPPDATA%\Programs\...`、`scoop`/`cargo` 常见路径
4. 找不到 → 状态事件提示安装命令

### 错误与边界

- 一次只允许一轮在途；重复发送返回错误。
- fenbi 导入失败不阻断后续对话（无库时跳过增强）。
- 检索空结果时原样发送用户消息。
- 运行时退出/未登录：清 pending，前端可重试；给出安装/登录指引。

## [S3] Out of Scope

- 自实现 Google PKCE loopback / Keystore 等价物
- 打包 `agy.exe` sidecar 或二次分发引擎
- 刷题 UI、题库列表、成绩页
- macOS/Linux 打包
- 自动更新器、代码签名证书申请

## Tasks

- [x] T1: 目录脚手架与 Tauri 配置 — acceptance: `windows/src-tauri` 可被 cargo 识别，`tauri.conf.json` 指向前端入口 (covers: S2)
- [x] T2: 前端桥适配 — acceptance: 页面加载后存在 `GecisNative` shim 与 `GecisChat` 回调 (covers: S2)
- [x] T3: 历史库 SQLite — acceptance: 命令可创建项目/会话并返回 snapshot JSON (covers: S2)
- [x] T4: fenbi 导入与检索增强 — acceptance: 无库时消息原样发送；有库时注入 `<fenbi_context>` (covers: S2)
- [x] T5: agy 运行时流式对话 — acceptance: 探测失败给出指引；成功时 delta/complete 事件推进 UI (covers: S2)
- [x] T6: 构建验证 — acceptance: `cargo check` / `npm run build` 或 `tauri build` 至少编译通过 (covers: S2)
