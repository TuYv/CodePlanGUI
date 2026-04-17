# CodePlanGUI

> A lightweight JetBrains IDEA plugin that connects to **any OpenAI-compatible API endpoint** — bringing streaming Chat and one-click Commit Message generation directly into your IDE, without binding to any specific vendor account.

[中文文档](README.zh-CN.md)

---

## Why

Most AI plugins for IntelliJ IDEA require a vendor account (GitHub Copilot, 通义灵码, MarsCode). Developers in China who already have API keys for services like Alibaba Qianwen, Doubao, or DeepSeek have no good option to use them directly inside IDEA.

CodePlanGUI fills that gap: configure any endpoint + key, and get a fully-featured Chat sidebar and Commit Message generator without leaving your IDE.

## Features

| Feature | Details |
|---------|---------|
| **Chat Sidebar** | Streams tokens one-by-one via SSE, rendered as Markdown with syntax-highlighted code blocks |
| **Context-Aware** | Toggle to include the current file (or selected text) in every prompt; auto-truncates at 300 lines / 12,000 chars |
| **Ask AI** | Right-click selected code in the editor → **Ask AI** |
| **Commit Message Generation** | Click ✨ in the Git Commit dialog to generate a message from your staged diff (Conventional Commits format) |
| **Multi-Provider Management** | Add, edit, remove, and switch between any number of OpenAI-compatible endpoints |
| **Test Connection** | Instant connectivity check with specific HTTP status + error body on failure |
| **Secure Key Storage** | API keys are stored in IDEA's built-in `PasswordSafe`, never written to disk in plain text |

## Requirements

- IntelliJ IDEA 2023.1 or later (Community or Ultimate)
- JetBrains Runtime (JBR) — bundled with IDEA, required for the embedded browser (JCEF)
- JDK 17 (for building)

## Installation

### From disk (manual)

1. Build or download `CodePlanGUI-0.1.0.zip` (see [Build](#build))
2. Open IDEA → **Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. Select the zip → **OK** → Restart IDEA

## Configuration

1. Go to **Settings → Tools → CodePlanGUI**
2. In the **Providers** tab, click **Add** to add a provider:

   | Field | Example |
   |-------|---------|
   | Name | `GPT-4o` |
   | Endpoint | `https://api.openai.com/v1` |
   | Model | `gpt-4o` |
   | API Key | stored securely |

3. Click **Test Connection** to verify — you'll see a specific error (HTTP code + body) if it fails
4. Click **OK** to save

**Common provider endpoints:**

| Service | Endpoint |
|---------|---------|
| OpenAI | `https://api.openai.com/v1` |
| DeepSeek | `https://api.deepseek.com/v1` |
| Alibaba Qianwen (百炼) | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Doubao (豆包) | `https://ark.cn-beijing.volces.com/api/v3` |
| Local Ollama | `http://localhost:11434/v1` |

5. Open the **CodePlanGUI** tool window on the right sidebar and start chatting

## Build

```bash
# Build webview and plugin zip
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview buildPlugin

# Output
build/distributions/CodePlanGUI-0.1.0.zip
```

> Requires JDK 17. Gradle 8.5 does **not** support JDK 21+.

## Run in sandbox IDE

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview runIde
```

## Roadmap

See [docs/roadmap.md](docs/roadmap.md) for the full phase plan, engineering tasks, and acceptance criteria.

**Explicitly not available today:**
- Command execution from chat
- Agent mode or slash commands
- Cloud account sync or a hosted backend service

**Not planned:**
- Account / cloud sync (local keys only, zero server)
- Bundled models
- Non-OpenAI-compatible private protocols

---

## Architecture

```
┌─────────────────────────────────────────────┐
│  IDEA Plugin (Kotlin)                        │
│  ├── Tool Window (ChatPanel)                 │
│  ├── Settings (PersistentStateComponent)     │
│  │   └── API Keys → PasswordSafe            │
│  ├── Git Commit Action (GenerateCommitMsg)   │
│  └── JCEF Browser Host                      │
│           ↕  Kotlin ↔ JS Bridge             │
│  ┌─────────────────────────────────────────┐ │
│  │  React 19 Frontend (Chat UI)            │ │
│  │  Ant Design 5 · marked · highlight.js   │ │
│  └─────────────────────────────────────────┘ │
│           ↕  OkHttp SSE                      │
└─────────────────────────────────────────────┘
             ↓
  External AI API (OpenAI-compatible)
  阿里百炼 / 豆包 / DeepSeek / OpenAI / …
```

**Key design decisions:**
- **No bridge daemon** — Unlike plugins that manage a subprocess (e.g. Claude Code CLI), CodePlanGUI calls REST APIs directly from the plugin, keeping the architecture simple.
- **JCEF over native Swing** — Streaming Markdown with syntax highlighting is impractical in Swing; JCEF provides a full browser runtime.
- **`loadHTML` not `loadURL("file://...")`** — Plugin JARs cannot be addressed by file URLs at runtime; the frontend HTML is read via `getResourceAsStream` and injected as a string.
- **`PasswordSafe` for keys** — API keys are stored in IDEA's credential store, never written to `codePlanGUI.xml` which may be committed to VCS.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Plugin shell | Kotlin 1.9 + Gradle (Groovy DSL) |
| IntelliJ Platform | `org.jetbrains.intellij.platform` v2.1.0 |
| Embedded browser | JCEF (JetBrains Chromium Embedded Framework) |
| Frontend | React 19 + TypeScript + Ant Design 5 + Vite |
| HTTP / SSE | OkHttp 4.12 + okhttp-sse |
| Serialization | kotlinx-serialization-json 1.6.3 |
| Concurrency | kotlinx-coroutines-core 1.7.3 |

## License

MIT
