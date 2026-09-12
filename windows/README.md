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

3. 若未登录：点顶栏「登录」打开新终端完成 Google 登录
4. 题库：顶栏「导入题库」选择 fenbi.db（发送消息不会弹框阻塞）

未安装 `agy` 时应用仍可打开；发送消息会给出安装/登录提示。

## 功能

- 连续对话 UI（Markdown + KaTeX）
- 本地 `fenbi.db` 导入与只读检索增强（`<fenbi_context>`）
- 本地 SQLite 会话历史（项目 / 会话 / 消息）
- 系统 `agy` headless NDJSON 流式回答

## 目录

| 路径 | 说明 |
|---|---|
| `dist/` | 前端静态资源（复用 Android 交互契约） |
| `src-tauri/` | Rust 壳：runtime / knowledge / history / commands |
| `scripts/make_icons.py` | 生成应用图标 |
| `../docs/compose/spec/windows-shell.md` | 特性规格 |

## 与 Android 的关系

- 前端交互与 `GecisNative` 契约对齐
- fenbi 检索策略对齐（`fenbi_paper_questions` 优先）
- 不打包引擎，不自写 OAuth；认证交官方 keyring
