# Gecis Windows

Windows 桌面子项目（Tauri 2）。当前状态：**可构建、可安装**；AI 对话依赖本机已安装的官方 Antigravity CLI。

## 快速开始

```powershell
# 0) 若 dist/vendor 为空，先 stage 锁定 vendor
powershell -File scripts/stage-vendor.ps1

# 1) 安装依赖
npm install

# 2) 开发
npm run dev

# 3) 发布安装包
npm run build
```

产物：

- `src-tauri/target/release/gecis-windows.exe`
- `src-tauri/target/release/bundle/nsis/Gecis_0.1.0_x64-setup.exe`
- `src-tauri/target/release/bundle/msi/Gecis_0.1.0_x64_en-US.msi`

## 运行前提

1. Windows 10/11（WebView2 Runtime）
2. 官方 Antigravity CLI（不是 GUI 版 Antigravity.exe）：

   ```powershell
   irm https://antigravity.google/cli/install.ps1 | iex
   ```

   默认安装到 `%LOCALAPPDATA%\agy\bin\agy.exe`。应用会自动扫描该路径；也可设置 `GECIS_AGY`。

3. 若未登录：直接提问，应用会在会话内提示连接 Google 账号；授权后自动继续原问题
4. 题库：在左侧栏底部选择 fenbi.db；题库可选，不会阻塞普通提问

未安装 `agy` 时应用仍可打开；发送消息会给出安装/登录提示。

## 功能

- 连续对话 UI（Markdown + KaTeX）
- Windows 宽屏常驻会话侧栏；窄窗与 Android 使用抽屉
- 登录、运行环境和网络问题通过会话内行动卡引导处理
- **模型直接读库**：MCP 工具 `fenbi_schema` / `fenbi_get` / `fenbi_query`，由 Gemini/Antigravity 对本地 fenbi.db 做沙箱 SELECT（不在应用侧预检索）。Gecis 的 `agy` 使用独立 `USERPROFILE`（对话、登录、探测共用），不会加载用户全局 MCP（例如 serena）。本地回环（界面 `*.localhost`、MCP、登录回调）不走系统代理。
- 本地 SQLite 会话历史（项目 / 会话 / 消息）
- 系统 `agy` headless NDJSON 流式回答

## e2e

真端到端（启动 GUI、驱动输入框、断言助手气泡）：

```powershell
cd windows
npm install
npm run e2e
```

通过时输出 `E2E reply: …` 与 `1 passing`。依赖本机 `tauri-driver` 与 Edge WebView2。

Runtime 层冒烟（非 UI）：

```powershell
npm run e2e:runtime
```

## 目录

| 路径 | 说明 |
|---|---|
| `dist/` | 前端静态资源（复用 Android 交互契约） |
| `src-tauri/` | Rust 壳：runtime / knowledge / history / commands |
| `scripts/make_icons.py` | 生成应用图标 |
| `../docs/compose/spec/windows-shell.md` | 特性规格 |

## 与 Android 的关系

- 前端交互与 `GecisNative` 契约对齐
- fenbi MCP 工具对齐（`fenbi_schema` / `fenbi_get` / `fenbi_query`）
- 不打包引擎，不自写 OAuth；认证交官方 keyring
