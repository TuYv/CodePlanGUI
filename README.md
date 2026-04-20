# CodePlanGUI

> A lightweight JetBrains IDEA plugin that connects to **any OpenAI-compatible API endpoint** — bringing streaming Chat and one-click Commit Message generation directly into your IDE, without binding to any specific vendor account.

---

## English

### Why

Most AI plugins for IntelliJ IDEA require a vendor account (GitHub Copilot, 通义灵码, MarsCode). Developers in China who already have API keys for services like Alibaba Qianwen, Doubao, or DeepSeek have no good option to use them directly inside IDEA.

CodePlanGUI fills that gap: configure any endpoint + key, and get a fully-featured Chat sidebar and Commit Message generator without leaving your IDE.

### Features

| Feature | Details |
|---------|---------|
| **Chat Sidebar** | Streams tokens one-by-one via SSE, rendered as Markdown with syntax-highlighted code blocks |
| **Context-Aware** | Toggle to include the current file (or selected text) in every prompt; auto-truncates at 300 lines / 12,000 chars |
| **Ask AI** | Right-click selected code in the editor → **Ask AI** |
| **Commit Message Generation** | Click ✨ in the Git Commit dialog to generate a message from your staged diff (Conventional Commits format) |
| **Multi-Provider Management** | Add, edit, remove, and switch between any number of OpenAI-compatible endpoints |
| **Test Connection** | Instant connectivity check with specific HTTP status + error body on failure |
| **Secure Key Storage** | API keys are stored in IDEA's built-in `PasswordSafe`, never written to disk in plain text |

### Requirements

- IntelliJ IDEA 2023.1 or later (Community or Ultimate)
- JetBrains Runtime (JBR) — bundled with IDEA, required for the embedded browser (JCEF)
- JDK 17 (for building)

### Installation

#### From disk (manual)

1. Build or download `CodePlanGUI-0.1.0.zip` (see [Build](#build))
2. Open IDEA → **Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. Select the zip → **OK** → Restart IDEA

### Configuration

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

### Build

```bash
# Build webview and plugin zip
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview buildPlugin

# Output
build/distributions/CodePlanGUI-0.1.0.zip
```

> Requires JDK 17. Gradle 8.5 does **not** support JDK 21+.

### Run in sandbox IDE

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview runIde
```

### Roadmap

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

## 中文

### 背景

IntelliJ IDEA 生态中，绝大多数 AI 插件（GitHub Copilot、通义灵码、MarsCode 等）都绑定了各自的账号体系，无法使用已有的国内 AI API Key。

CodePlanGUI 解决这个问题：自由配置任意 OpenAI 兼容接口的 endpoint 和 Key，在 IDEA 内获得流式 Chat 侧边栏和一键生成 Commit Message，不切标签页。

### 功能特性

| 功能 | 说明 |
|------|------|
| **Chat 侧边栏** | SSE 逐 token 流式输出，Markdown 渲染，代码块语法高亮带复制按钮 |
| **上下文注入** | 可切换是否将当前文件（或选中内容）附加到每条消息，自动截断至 300 行 / 1.2 万字符 |
| **Ask AI** | 选中编辑器代码 → 右键 → Ask AI，带选中片段发送 |
| **Commit Message 生成** | 在 Git 提交对话框点击 ✨，自动读取暂存区 diff 生成 Conventional Commits 格式提交信息 |
| **多 Provider 管理** | 可添加、编辑、删除、切换任意数量的 OpenAI 兼容接口 |
| **连接测试** | 一键验证，失败时显示具体 HTTP 状态码和错误详情 |
| **安全存储** | API Key 存入 IDEA 内置 `PasswordSafe`，不以明文落盘，不同步到 VCS |

### 环境要求

- IntelliJ IDEA 2023.1 及以上（社区版或旗舰版均可）
- JetBrains Runtime（JBR）— IDEA 已内置，内嵌浏览器（JCEF）依赖此运行时
- JDK 17（构建时需要）

### 安装

#### 从本地文件安装

1. 构建或下载 `CodePlanGUI-0.1.0.zip`（见 [构建](#构建)）
2. 打开 IDEA → **Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. 选择 zip 文件 → **OK** → 重启 IDEA

### 配置

1. 进入 **Settings → Tools → CodePlanGUI**
2. 在 **Providers** 标签页点击 **Add** 添加 Provider：

   | 字段 | 示例 |
   |------|------|
   | Name | `豆包` |
   | Endpoint | `https://ark.cn-beijing.volces.com/api/v3` |
   | Model | `doubao-pro-4k` |
   | API Key | 安全存储 |

3. 点击 **Test Connection** 验证连通性，失败时会显示具体错误
4. 点 **OK** 保存

**常用 Provider 地址参考：**

| 服务 | Endpoint |
|------|---------|
| OpenAI | `https://api.openai.com/v1` |
| DeepSeek | `https://api.deepseek.com/v1` |
| 阿里百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 字节豆包 | `https://ark.cn-beijing.volces.com/api/v3` |
| 本地 Ollama | `http://localhost:11434/v1` |

5. 打开右侧 **CodePlanGUI** Tool Window，开始对话

### 构建

```bash
# 构建 webview 并打包插件 zip
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview buildPlugin

# 产物路径
build/distributions/CodePlanGUI-0.1.0.zip
```

> 需要 JDK 17。Gradle 8.5 **不支持** JDK 21+。

### 在沙箱 IDE 中运行

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview runIde
```

### 迭代计划

完整分阶段计划见 [docs/roadmap.md](docs/roadmap.md)。

**当前明确不具备：**
- 在 Chat 中直接执行命令
- Agent 模式或 slash command
- 云端账号体系或托管后端服务

**不做：**
- 账号体系 / 云端同步（始终本地 key，零服务器）
- 内置模型
- 非 OpenAI 兼容的私有协议

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
