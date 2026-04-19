# CodePlanGUI

> 轻量级 JetBrains IDEA 插件，可对接**任意 OpenAI 兼容 API**，在 IDE 内直接获得流式 Chat 与一键生成 Commit Message 能力，不绑定任何厂商账号。

[English](README.md)

---

## 背景

IntelliJ IDEA 生态中，绝大多数 AI 插件（GitHub Copilot、通义灵码、MarsCode 等）都绑定了各自的账号体系，无法使用已有的国内 AI API Key。

CodePlanGUI 解决这个问题：自由配置任意 OpenAI 兼容接口的 endpoint 和 Key，在 IDEA 内获得流式 Chat 侧边栏和一键生成 Commit Message，不切标签页。

## 功能特性

| 功能 | 说明 |
|------|------|
| **Chat 侧边栏** | SSE 逐 token 流式输出，Markdown 渲染，代码块语法高亮带复制按钮 |
| **上下文注入** | 可切换是否将当前文件（或选中内容）附加到每条消息，自动截断至 300 行 / 1.2 万字符 |
| **Ask AI** | 选中编辑器代码 → 右键 → Ask AI，带选中片段发送 |
| **Commit Message 生成** | 在 Git 提交对话框点击 ✨，自动读取暂存区 diff 生成 Conventional Commits 格式提交信息 |
| **多 Provider 管理** | 可添加、编辑、删除、切换任意数量的 OpenAI 兼容接口 |
| **连接测试** | 一键验证，失败时显示具体 HTTP 状态码和错误详情 |
| **安全存储** | API Key 存入 IDEA 内置 `PasswordSafe`，不以明文落盘，不同步到 VCS |

## 环境要求

- IntelliJ IDEA 2023.1 及以上（社区版或旗舰版均可）
- JetBrains Runtime（JBR）— IDEA 已内置，内嵌浏览器（JCEF）依赖此运行时
- JDK 17（构建时需要）

## 安装

### 从本地文件安装

1. 构建或下载 `CodePlanGUI-0.1.0.zip`（见 [构建](#构建)）
2. 打开 IDEA → **Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. 选择 zip 文件 → **OK** → 重启 IDEA

## 配置

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

## 构建

```bash
# 构建 webview 并打包插件 zip
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview buildPlugin

# 产物路径
build/distributions/CodePlanGUI-0.1.0.zip
```

> 需要 JDK 17。Gradle 8.5 **不支持** JDK 21+。

## 在沙箱 IDE 中运行

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview runIde
```

## 迭代计划

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

## 架构

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

**关键设计决策：**
- **不引入 bridge 守护进程** —— 与那些托管子进程（如 Claude Code CLI）的插件不同，CodePlanGUI 直接从插件层调用 REST API，架构更简单。
- **JCEF 而非原生 Swing** —— Swing 难以胜任流式 Markdown 与语法高亮渲染，JCEF 提供完整的浏览器运行时。
- **`loadHTML` 而非 `loadURL("file://...")`** —— 插件 JAR 内的资源在运行时无法通过 file URL 寻址，前端 HTML 通过 `getResourceAsStream` 读出后以字符串注入。
- **API Key 走 `PasswordSafe`** —— 存入 IDEA 凭据库，绝不写入可能被提交到 VCS 的 `codePlanGUI.xml`。

## 技术栈

| 层 | 技术 |
|----|------|
| 插件外壳 | Kotlin 1.9 + Gradle（Groovy DSL） |
| IntelliJ Platform | `org.jetbrains.intellij.platform` v2.1.0 |
| 内嵌浏览器 | JCEF（JetBrains Chromium Embedded Framework） |
| 前端 | React 19 + TypeScript + Ant Design 5 + Vite |
| HTTP / SSE | OkHttp 4.12 + okhttp-sse |
| 序列化 | kotlinx-serialization-json 1.6.3 |
| 并发 | kotlinx-coroutines-core 1.7.3 |

## 许可协议

MIT
