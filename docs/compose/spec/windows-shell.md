---
feature: windows-shell
status: delivered
updated: 2026-09-12
branch: feat/windows-shell
commits: 654ee66bd0c8669cac56720503b80ef8ab418b54..HEAD
---

# Windows Shell

## Report

**What was built** — Gecis Windows 桌面子项目：Tauri 2 + WebView2 壳，复用 Android 聊天交互契约，本地 SQLite 历史、官方 `agy` headless NDJSON 流式对话。fenbi 检索改为 **MCP 工具 `search_fenbi`，由模型自行决定是否调用、搜什么关键词**；程序不再预检索/注入 `<fenbi_context>`。Android 同步：内置 HTTP MCP，启动前写入私有 HOME 的 `mcp_config.json`。

**Verification** — `cargo check` PASS；`cargo run --bin e2e_chat -- "只回复两个字：收到"` → `E2E OK / 收到`；`npm run build` PASS（NSIS/MSI）。

**Journey log**
- stream-json 实际字段是 `conversation_id` + `text_delta` + `result.status=SUCCESS`，不是 request_id。
- 「模型决定搜索」→ MCP `search_fenbi`，两端去掉 KnowledgeAugmentingRuntime 预注入。
- Windows e2e 走与应用相同的 spawn/parser 路径才算数。

## [S1] Problem

Android 版 Gecis 已交付单 APK 对话体验，但 Windows 缺少对应桌面壳。实现后实测「发送消息无认证弹窗、无反应」：用户环境只有 Antigravity GUI，无 `agy` CLI；发送链路还存在阻塞对话框与吞错问题。

## [S2] Design

### 目标形态

单 Windows 桌面应用（Tauri 2 + WebView2）：

1. 连续对话 UI（Markdown + KaTeX），复用 Android `index.html` 交互契约。
2. 窄原生桥 `GecisNative`：`sendMessage` / `getHistory` / `createProject` / `newConversation` / `openConversation`，以及 `runtimeStatus` / `startLogin` / `installRuntime` / `importFenbi`。
3. AI runtime：探测系统 `agy`，以 `--input-format stream-json --output-format stream-json --sandbox --print-timeout 5m` 常驻；NDJSON 事件 `step_update` / `result`。
4. 知识库：顶栏显式导入 `fenbi.db`；**模型通过 MCP 工具 `search_fenbi` 自行决定检索**，应用不预计算查询词、不注入上下文。
5. 历史：本地 SQLite `gecis_history.db`。
6. 认证：不自写 PKCE；未登录时 `start_login` 打开新控制台运行 `agy`。

### 运行时定位顺序

1. `GECIS_AGY`
2. PATH 上的 `agy` / `agy.exe`
3. `%LOCALAPPDATA%\agy\bin\agy.exe`（官方安装器默认路径）
4. scoop / `~/.local/bin` 等常见路径
5. 找不到 → 状态栏 + 助手气泡给出安装命令

### 错误与边界

- 发送路径任何失败必须 `emit_error` 到当前 requestId。
- 无 fenbi 库时消息原样发送，不弹框。
- 15s 无 runtime 事件则超时提示并重置 runtime，避免假死。
- AuthRequired / 缺失 CLI 时自动尝试打开登录窗口或提示安装。

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
- [x] T7: 修复无认证/无响应 — acceptance: 探测 `%LOCALAPPDATA%\agy\bin`；发送失败回传 UI；fenbi 不阻塞；登录/安装入口可用 (covers: S2)
- [x] T8: fenbi 由模型检索 + Windows e2e — acceptance: 注册 MCP `search_fenbi`，去掉预注入；`cargo run --bin e2e_chat` 返回模型回复 (covers: S2)
