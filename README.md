# Gecis

Gecis 是一个面向 Android 的极简 AI 公考自学应用。

## 当前产品边界

- 只有一个连续对话界面，不提供刷题、题库列表、错题本等传统题库 UI。
- 对话消息支持 Markdown，以及行内公式 `$...$` / `\(...\)` 和块级公式 `$$...$$` / `\[...\]`。
- `fenbi.db` 仅作为本地知识底座，由原生层只读访问；前端不直接接触数据库文件。
- 目标形态是单 APK：WebView UI + Android 原生桥 + 本地 SQLite + 内嵌/受控 AI runtime。

当前开发分支：`feat/chat-shell`
