# Gecis Windows 技术选型

状态：**仅选型与边界文档，尚未落地业务代码。**

## 1. 产品边界（与 Android 对齐）

- 只有一个连续对话界面，不做刷题 / 题库列表 / 错题本 / 成绩页。
- 消息支持 Markdown，以及行内 `$...$` / `\(...\)` 和块级 `$$...$$` / `\[...\]` 公式。
- `fenbi.db` 仅作本地只读知识底座，前端不直接接触数据库文件。
- 目标形态：**单安装包** = 本地 WebView UI + 窄原生桥 + 本地 SQLite + 受控 AI runtime。
- 不提供 API Key 流程；用户侧只走 Google 账号登录。

## 2. Android 侧可复用资产

| 资产 | 路径 | Windows 复用方式 |
|---|---|---|
| 聊天壳 HTML/CSS/JS | `app/src/main/assets/index.html` | 原样加载，仅适配桌面窗口/insets |
| marked | `web/payload.lock` 锁定版本 | 构建时同源 staging |
| KaTeX + 字体 | 同上 | 同上 |
| DOMPurify | 同上 | 同上 |
| 会话协议 | NDJSON：`user` / `step_update` / `result` | 协议不变 |
| fenbi 检索策略 | `fenbi_paper_questions` 优先，`questions` 回退 | 逻辑对齐，实现用 `rusqlite` |
| 本地历史模型 | projects → conversations → messages | 同构迁移 |

**不复用（Android 专属）：**

- Termux glibc loader / `libgecis_*.so` / VA39 补丁 / resolver 补丁 / CA 打包
- Android Keystore + 自实现 PKCE loopback（Windows 优先官方 keyring）
- SAF 文档选择器（改用系统文件对话框）

## 3. 推荐技术栈

```text
UI 壳        Tauri 2（系统 WebView2）
前端         复用 Android assets（vanilla HTML + 锁定 vendor）
AI Runtime   官方 Antigravity CLI（运行时探测，不打包引擎）
SQLite       rusqlite（fenbi.db 只读 + 聊天历史）
文件选择     tauri-plugin-dialog
子进程       tauri-plugin-shell
本地状态     SQLite 为主；轻量 KV 可用 tauri-plugin-store
打包         Tauri NSIS / MSI
```

### 3.1 为什么是 Tauri 2

与 Android 架构一一对应，且全部用现成能力：

| Android | Windows / Tauri |
|---|---|
| WebView + `WebViewAssetLoader` | Tauri frontend assets + WebView2 |
| `GecisNative` JS Bridge | `invoke` / `listen`（capability 默认关闭） |
| `ProcessBuilder` + NDJSON | `tauri-plugin-shell` stdin/stdout |
| SAF 选 `fenbi.db` | `tauri-plugin-dialog` |
| SQLite 历史 / 知识库 | `rusqlite` |
| 安装分发 | NSIS / MSI |

体积远小于 Electron，贴合「极简单应用」定位。

### 3.2 明确排除

| 方案 | 原因 |
|---|---|
| Electron | 体积与内存过重 |
| Compose Multiplatform Desktop | 无法复用现有 WebView 壳，UI 重写 |
| Wails / Photino | 可用，但 sidecar/长驻子进程生态弱于 Tauri |
| 裸 WebView2 + C# | 自造 IPC/权限/打包，违反不造轮子 |
| 再打包 Termux 引擎 | Windows 有官方二进制，无必要 |

## 4. AI Runtime：官方安装 + 首次引导

上游：`google-antigravity/antigravity-cli`（**不要**用 `wallentx/antigravity-cli-termux`，那是 Android Termux fork）。

官方 Windows 安装：

```powershell
irm https://antigravity.google/cli/install.ps1 | iex
```

运行策略：

1. 启动时探测 `agy`（PATH 与常见安装目录）。
2. 缺失 → 状态栏引导用户安装（可复制命令，或 `opener` 打开安装说明）。
3. 未登录 → 首条消息触发官方 Google Sign-In（打开系统浏览器；凭证在系统 keyring）。
4. 常驻子进程协议与 Android 相同：

```text
agy --input-format stream-json --output-format stream-json --sandbox --print-timeout 5m
```

5. 每轮用户消息一行 NDJSON；消费 `step_update.step_update.text_delta` 与 `result`；同时只允许一轮在途。

### 认证差异说明

| | Android | Windows（本方案） |
|---|---|---|
| 凭证存储 | 自加密 vault（Keystore） | 官方系统 keyring |
| 登录方式 | 自实现 PKCE + loopback:51121 | 官方浏览器 Google Sign-In |
| 引擎分发 | APK 内嵌 ARM64 payload | 用户官方安装器；Gecis 不打包 |

若产品必须「完全自有 OAuth client / 不弹系统安装」，再考虑 sidecar `bundle.externalBin` 打包 `agy.exe` 与自实现 PKCE——**默认不做**。

## 5. 安全边界（对齐 Android）

- WebView/前端只走窄桥：发消息、读历史快照、项目/会话切换；不暴露 `fenbi.db` 路径、不暴露凭证文件路径。
- `fenbi.db`：选中后拷入应用私有目录，校验 SQLite header + `PRAGMA quick_check(1)`，只读打开；检索结果包在 `<fenbi_context>`，按不可信参考材料处理。
- 历史库仅本地，不上传。
- 运行 `agy` 带 `--sandbox`，永不加跳过权限类参数。
- 前端 vendor（marked/katex/dompurify）构建时锁定，不加载远程 JS。

## 6. 目标目录结构

```text
windows/
  DESIGN.md          # 本文档
  README.md          # 子项目入口说明
  package.json       # 后续 Tauri 脚手架（本轮可为空壳说明）
  src/               # 后续：桥接适配层（invoke 封装），前端优先复用 ../app/src/main/assets
  src-tauri/         # 后续：Rust 壳（runtime / knowledge / history 模块）
```

前端资产构建策略（二选一，实现阶段定）：

- **A（推荐）**：`build.rs` 或 npm script 从 `../app/src/main/assets` + 锁定 vendor staging 到 `windows/dist`
- **B**：`windows/dist` 直接引用上级 assets，避免双份拷贝

## 7. 模块映射（实现阶段蓝图，非本轮代码）

| Android 模块 | Windows 对应职责 |
|---|---|
| `MainActivity` + Bridge | Tauri main window + `#[tauri::command]` |
| `AntigravityRuntime` | shell 插件 spawn `agy`，NDJSON 读写 |
| `KnowledgeAugmentingRuntime` | 先 fenbi 检索再写入 stdin |
| `FenbiKnowledgeBase` | dialog 选文件 + rusqlite 只读 |
| `ChatHistoryStore` | rusqlite 同构表结构 |
| `AntigravityOAuthCoordinator` | 探测登录态；失败时引导官方登录（不自写 PKCE） |
| `web/stage-assets.sh` | Windows 等价构建脚本（PowerShell/Node） |

## 8. 依赖与许可注意

- Tauri 2 / 官方 plugins：MIT/Apache，可商用。
- Antigravity CLI：受 Google 条款约束；应用需同意其数据使用说明，且**不要**二次分发未授权二进制（因此默认不 sidecar 打包）。
- fenbi.db 由用户自备；应用只读、不修改原件。

## 9. 里程碑（建议）

1. **M0 选型冻结**（本文件）— 完成
2. **M1 脚手架**：`create-tauri-app`，加载现有 `index.html`，空对话可输入
3. **M2 Runtime**：探测/引导 `agy`，打通 NDJSON 流式回答
4. **M3 知识库**：导入 `fenbi.db` + 有界检索增强
5. **M4 历史**：本地 SQLite 会话库 + 侧栏
6. **M5 打包**：NSIS 安装器 + 版本号/图标

## 10. 决策记录

| 日期 | 决策 | 备注 |
|---|---|---|
| 2026-08 | Windows 壳选 Tauri 2 | 复用 WebView 资产，不造轮子 |
| 2026-08 | AI 用官方安装 + 首次引导 | 不打包引擎；认证交 keyring |
| 2026-08 | 前端继续 vanilla + 锁定 vendor | 与 Android 同源，避免 React 重写 |
