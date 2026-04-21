# Phase2 MVP Tool Specs

> **范围**：phase2 发版前必须就绪的 4 个 tool。覆盖 3 家族（shell / IDE API / MCP）+ dry-run 落地。
> **非目标**：Claude Code 的 25+ tool 不追求 parity。FileWrite、Grep、Glob、WebFetch、WebSearch、TodoWrite、Agent、Skill、Task* 等**全部 post-MVP**。

---

## 0. 通用约定

### 0.1 命名与包结构
- 包：`com.github.codeplangui.tools.{bash|ide|mcp}`
- Tool 声明：`val BashTool = tool<BashInput, BashOutput> { ... }`（DSL，不是 class 继承）
- Input/Output：每个 tool 一对 data class + kotlinx.serialization JSON Schema

### 0.2 权限策略术语
| 术语 | 含义 |
|---|---|
| `Allow` | 直接执行 |
| `Ask` | UI 弹窗问用户，可改 input 后再决定 |
| `Deny` | 拒绝，把拒绝原因回给 LLM |
| wildcard rule | 形如 `Bash(git *)` 的规则，matcher 由 tool 自己实现 `preparePermissionMatcher` |

### 0.3 Dry-run 落点机制（架构决策）

Tool 可选实现 `preview()` 方法：
```kotlin
suspend fun preview(input: Input, ctx: ToolExecutionContext): PreviewResult?
```
- 返回 `null` = 该 tool 不支持 dry-run（例如 FileRead——读操作无副作用，不需要预览）
- 返回 `PreviewResult` = 结构化描述"将要发生什么"
- harness 在 `checkPermissions` 返回 `Ask` 时，把 preview 结果放进询问对话框
- 用户确认后进入 `call()`；如果 preview 已展示全部要发生的事，`call()` 直接 apply

**MVP 唯一强制实现 preview 的 tool 是 `FileEditTool`**（diff 预览）。BashTool 可以有简化版（打印"将执行：`<命令>`"），MCPTool 不实现（由远端 server 决定）。

---

## 1. BashTool

### 1.1 Input
```kotlin
data class BashInput(
    val command: String,
    val description: String? = null,  // 简短说明，给 LLM/用户看
    val timeoutSeconds: Int = 120,     // 默认 120s，最大 600s
    val runInBackground: Boolean = false  // MVP 先不支持，留字段但忽略
)
```

### 1.2 Output
```kotlin
data class BashOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val truncated: Boolean,
    val interrupted: Boolean = false
)
```
直接复用现有 `ExecutionResult` 的字段即可；MVP 不做持久化到磁盘（Claude Code 那套"大输出转 FileRead 两跳"跳过）。

### 1.3 元数据谓词
- `isConcurrencySafe = false`（shell 有副作用，保守串行化）
- `isReadOnly = false`（大部分命令都可能写，不做命令解析优化）
- `isDestructive`：根据 `command` 做轻量字符串检测（含 `rm `、`mv `、`DROP `、`> /` 等），为 true 则权限层强制 `Ask`
- `interruptBehavior = 'cancel'`（用户发新消息时中断当前 shell）

### 1.4 Permission 策略
1. **全局 deny**（`alwaysDenyRules` 里命中）→ Deny
2. **写规则命中 + 非 destructive** → Allow（用户已授权的命令前缀如 `Bash(npm test)`）
3. **destructive** → 强制 Ask（即使 allow rule 命中）
4. **默认** → Ask
5. `preparePermissionMatcher`：实现 wildcard 匹配（`Bash(git *)` → 匹配任何 `git` 开头的命令）

**复用现有**：workspace path 检查走 `CommandExecutionService.hasPathsOutsideWorkspace`——已经存在，不重写。

### 1.5 call() 实现
- 直接委托 `CommandExecutionService.executeAsyncWithStream(command, timeoutSeconds) { line, isError -> onProgress(...) }`
- 流式输出通过 `onProgress` 上报
- 返回值转换成 `BashOutput`

### 1.6 preview() 实现
```kotlin
PreviewResult(
    summary = "Run: $command",
    details = "Working dir: ${project.basePath}\nTimeout: ${timeoutSeconds}s",
    risk = if (isDestructive(input)) Risk.HIGH else Risk.MEDIUM
)
```
简版，不真 dry-run（shell 本身没有 dry-run 概念）。

### 1.7 参考
- Claude Code：`src/tools/BashTool/BashTool.tsx` (1143 行)；**不抄 `runShellCommand`**（有 sandbox 决策，我们用不上）
- 现有代码：`src/main/kotlin/com/github/codeplangui/execution/CommandExecutionService.kt`（150 行，直接复用）

---

## 2. FileReadTool

### 2.1 Input
```kotlin
data class FileReadInput(
    val path: String,         // 绝对路径 or 相对项目根
    val offset: Int? = null,  // 起始行（1-indexed）
    val limit: Int? = null    // 行数
)
```

### 2.2 Output
```kotlin
data class FileReadOutput(
    val content: String,       // 带行号前缀，格式 "   1→line content"
    val path: String,          // 规范化后的绝对路径
    val totalLines: Int,
    val returnedLines: Int,
    val truncated: Boolean
)
```

### 2.3 元数据谓词
- `isConcurrencySafe = true`（纯读，无副作用）
- `isReadOnly = true`
- `isDestructive = false`

### 2.4 Permission 策略
极简：
1. 路径在 `project.basePath` 或 `additionalWorkingDirectories` 之内 → Allow
2. 之外 → Deny（带消息"文件不在工作区"）

不涉及 wildcard、不需要 `preparePermissionMatcher`。

### 2.5 call() 实现
- 用 `LocalFileSystem.getInstance().findFileByPath(path)` 打开
- 用 VFS 读内容（不走 Files.readAllBytes——走 IntelliJ 的编码感知路径）
- 按行切片、加行号前缀
- 最大读取限制：10000 行或 2MB（arbitrary，向 Claude Code 对齐）
- 大文件返回 `truncated = true` + 建议 offset/limit

### 2.6 preview()
返回 `null` — 读操作无副作用，不需要 dry-run。

### 2.7 参考
- Claude Code：`src/tools/FileReadTool/FileReadTool.ts` (1183 行)、`limits.ts`、`imageProcessor.ts`
- **MVP 不做**：PDF 页读取（`pages` 参数）、Jupyter 笔记本、图像识别
- **关键学习点**：这是第一个**不走 shell 的 handler**，验证"IDE API 家族"的抽象能跑通

---

## 3. FileEditTool

### 3.1 Input
```kotlin
data class FileEditInput(
    val path: String,
    val oldString: String,     // 要替换的原文
    val newString: String,     // 替换成什么
    val replaceAll: Boolean = false
)
```

### 3.2 Output
```kotlin
data class FileEditOutput(
    val path: String,
    val replacementCount: Int,
    val linesChanged: Int,
    val diff: String           // unified diff 格式
)
```

### 3.3 元数据谓词
- `isConcurrencySafe = false`（并发写同文件会冲突）
- `isReadOnly = false`
- `isDestructive = true`（修改磁盘内容）

### 3.4 Permission 策略
1. 路径不在工作区 → Deny
2. Plan mode 下任何 Edit → Deny
3. `acceptEdits` mode → Allow（自动批准）
4. 默认 → **Ask + 附带 preview 的 diff**

### 3.5 call() 实现流程
1. 读文件（复用 `FileReadTool` 的 VFS 逻辑）
2. 验证 `oldString` 在文件中唯一出现（除非 `replaceAll = true`）
3. 生成 diff 用于 preview/output
4. 写回 VFS（`VfsUtil.saveText(...)`）
5. Refresh PSI（让 IntelliJ 感知变更）
6. 返回 `FileEditOutput`

### 3.6 preview() 实现 — **MVP dry-run 唯一强制实现**
```kotlin
PreviewResult(
    summary = "Edit $path: $replacementCount replacement(s)",
    details = diffText,      // unified diff
    risk = Risk.HIGH
)
```
harness 在 `Ask` 流程中展示 diff，用户可选 Accept / Reject / Accept-Always（Accept-Always 写入 `alwaysAllowRules`）。

### 3.7 参考
- Claude Code：`src/tools/FileEditTool/FileEditTool.ts`、`utils.ts`、`types.ts`
- 相关辅助：`src/tools/BashTool/sedEditParser.ts`（sed-like 编辑逻辑，MVP 不做）
- **MVP 不做**：多块替换（Claude Code 有 MultiEdit 变体）、basé sur regex 的替换

---

## 4. MCPProxyTool（家族，不是单个 tool）

### 4.1 实际形态

MCP 不是"一个 tool"，而是**一个 Tool 工厂**：每个 MCP server 提供的远端 tool，在启动时被包装成一个 `Tool` 实例，name 形如 `mcp__{server}__{tool}`。

```kotlin
class MCPTool(
    private val mcpClient: MCPClient,
    private val serverName: String,
    private val remoteToolName: String,
    private val remoteSchema: JsonSchema,
    private val remoteDescription: String,
) : Tool<JsonElement, JsonElement> {
    override val name = "mcp__${serverName}__${remoteToolName}"
    override val inputSchema = remoteSchema
    override suspend fun call(input, ctx, ...) = mcpClient.callTool(remoteToolName, input)
    // ... 其余默认
}
```

### 4.2 MVP 覆盖范围

**做**：
- **stdio 传输**：从配置（`~/.claude-code-gui/mcp.json` 或插件 settings）读 server 列表，每个 server = 一条 `command + args`，spawn 子进程 + stdin/stdout JSON-RPC
- **server 启动**：插件启动时连接所有配置的 server，拉取 `tools/list`，注册成 Tool
- **单次 tool 调用 roundtrip**：`tools/call` 请求 → 等结果 → 返回
- **name 规范**：强制 `mcp__{server}__{tool}` 前缀

**不做**（post-MVP）：
- HTTP / SSE 传输
- OAuth / auth 头
- server 热重连、生命周期 UI
- MCP `resources` / `prompts`（只支持 `tools`）
- server 分组 / 启用-禁用开关 UI

### 4.3 Input / Output
透传：input 直接用 `JsonElement`（远端 schema 验证），output 也是 `JsonElement`。不做类型展开。

### 4.4 元数据谓词
- `isConcurrencySafe = false`（默认保守，远端不可控）
- `isReadOnly = false`（不知道远端会做什么）
- `isDestructive = false`（同上，除非 server 通过 `_meta` 声明）

### 4.5 Permission 策略
1. Deny rule 命中 `mcp__server` 前缀 → 该 server 所有 tool 都 filter 掉（不进 prompt）
2. Deny rule 命中 `mcp__server__tool` 精确 → 只禁该 tool
3. 默认 → Ask（首次使用），用户可选 Accept-Always 加白名单

这和 Claude Code 的 `filterToolsByDenyRules` 语义一致。

### 4.6 preview() 实现
返回 `null` — MCP tool 的 preview 应该由远端 server 负责（通过 `tools/call` with dry-run flag），MVP 阶段不做。

### 4.7 MVP 成功标准
- 能配一个外部 stdio MCP server（建议用 `@modelcontextprotocol/server-everything` 做 smoke test）
- 插件启动时能连上、能拉 tools、能 list 出来
- LLM 能调一个 mcp tool，结果能回对话流

### 4.8 参考
- Claude Code：`src/tools/MCPTool/MCPTool.ts`、`src/services/mcp/`（MVP 开工前需另读）
- 标准：https://modelcontextprotocol.io/
- **待定**：Kotlin 侧有没有 MCP SDK，还是自己写 stdio JSON-RPC

---

## 5. Scope 边界总结

| Tool | 开发优先级 | 预估复杂度 | 阻塞项 |
|---|---|---|---|
| BashTool | P0 | 低（复用 CommandExecutionService） | 无 |
| FileReadTool | P0 | 低（VFS 调用 + 切片） | 无 |
| FileEditTool | P1 | 中（diff 生成 + preview UI） | 取决于 AskUserQuestion 流程怎么做 |
| MCPProxyTool | P1 | 中-高（stdio JSON-RPC + server 管理） | Kotlin 侧 MCP SDK 选型 |

**先做 P0 两个 tool + Tool 接口骨架 + runToolUse 对应 Flow → 第一个 milestone PR**。
P1 两个 tool 在骨架就绪后并行推。

## 6. 下一步

1. 写 Tool 接口骨架（`Tool.kt`、`ToolExecutionContext.kt`、`ToolResult.kt`、`PermissionResult.kt`、`ValidationResult.kt`、`PreviewResult.kt`）
2. 写 DSL Builder（`toolBuilder.kt` + `tool { }` 顶级函数）
3. 写 `runToolUse` 对应的 Flow-based 执行器
4. 写第一个 tool：`BashTool`（复用 CommandExecutionService）
5. 跑通 "LLM → runToolUse → BashTool.call → Flow 流式结果" 的最小闭环
6. **到此 milestone 1 结束**，合入 phase2 分支（不接线 UI、不替换 command-mode）

后续 milestone：
- M2：FileReadTool + 正式的 `ToolExecutionContext` 字段敲定
- M3：FileEditTool + preview 机制 + AskUserQuestion-like 询问流程
- M4：MCPProxyTool + stdio 通道
- M5：UI 接线 + 删除 command-mode（**cutover PR**）
