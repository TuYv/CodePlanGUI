# Phase2 Milestone 1 Summary

> **目标**：搭起 tools 抽象的最小闭环骨架，不接 UI、不替换 command-mode。
> **结果**：骨架就位，`tool → runToolUse → Flow<ToolUpdate>` 全链路打通，7/7 单测绿。

## 交付清单

### 新增代码（`src/main/kotlin/com/github/codeplangui/tools/`）

| 文件 | 行数 | 职责 |
|---|---|---|
| `Tool.kt` | ~70 | `Tool<Input, Output>` 接口定义，含 4 件必选（parse / call / mapResult / checkPermissions）+ 默认方法（isEnabled / isConcurrencySafe 等） |
| `ToolTypes.kt` | ~100 | `ValidationResult` / `PermissionResult` / `ToolResult` / `PreviewResult` / `Progress` / `ToolUpdate` / `ToolResultBlock` / `ToolUseBlock` |
| `ToolExecutionContext.kt` | ~40 | 执行上下文（`project`、`toolUseId`、`abortJob`、`permissionContext`）。Claude Code 那 30+ 字段的 ToolUseContext 砍到 4 个 |
| `ToolBuilder.kt` | ~110 | `tool { }` DSL builder，对应 Claude Code 的 `buildTool()` 工厂 |
| `ToolExecutor.kt` | ~85 | `runToolUse()` — Flow-based 流式执行器，对应 Claude Code 的 `AsyncGenerator` runToolUse |
| `bash/BashTool.kt` | ~170 | 第一个具体 tool，把现有 `CommandExecutionService` 包一层；含 destructive-command 启发式检测 |

### 新增测试

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `ToolExecutorTest.kt` | 7 | happy path、progress 转发、validate 失败、permission deny、permission ask（MVP 自动批准）、execute 异常、parse 异常 |

## 验收

- `./gradlew compileKotlin` ✅（Corretto 17，**不能用 Java 25**）
- `./gradlew test --tests ToolExecutorTest` ✅ **BUILD SUCCESSFUL**（7 tests）
- 无新增编译 warning

## 锁定的设计选择

1. **流式执行层**：`runToolUse` 返回 `Flow<ToolUpdate>`，对应 Claude Code 的 AsyncGenerator。不是 suspend fun、不是 callback。
2. **权限三态**：`PermissionResult` = `Allow(updatedInput) | Ask(reason, preview?) | Deny(reason)`。`Ask` 可携带 `PreviewResult` 供 UI 展示 dry-run。
3. **Tool 是 singleton**：`val BashTool: Tool<...> = tool { ... }`，不是 class 继承。对应 Claude Code 的 `buildTool()` 模式。
4. **JSON Schema 是原始 JsonObject**：MVP 不引入 schema DSL，kotlinx.serialization 的 `buildJsonObject { }` 足够用。
5. **Progress 是 sealed class**：`Stdout / Stderr / Status` 三态。Claude Code 按 tool 类型分化（BashProgress/MCPProgress 等），MVP 不分化。
6. **ToolExecutionContext 字段极简**：只留真用到的 4 个字段。Claude Code 有 30+ 字段，按需再加。

## 与老代码的关系

**零侵入**。`execution/` 包下 command-mode 代码一行没动：
- `CommandExecutionService.kt`（现有，已被 `BashTool` 复用）
- `ExecutionResult.kt`（现有，已被 `BashTool.toBashOutput` 适配）
- `ShellPlatform.kt`（现有，通过 `CommandExecutionService` 间接复用）

`tools/` 包是完全新建的并行体系，cutover 时才会删 command-mode（M5）。

## 未做（明确 M1 不在 scope）

- ❌ BashTool 独立测试（需要 `mockkStatic(CommandExecutionService.Companion)`，留给 M2 开工时）
- ❌ UI 接线（ChatPanel、BridgeHandler 还没接入 tools 调度层）
- ❌ FileReadTool、FileEditTool、MCPProxyTool（M2-M4）
- ❌ `PermissionResult.Ask` 的真实用户询问（当前自动批准，M3 做）
- ❌ 并发 batch 调度（`runToolUseBatch`，M2）
- ❌ Tool 注册表 / `assembleToolPool`（现在 BashTool 是孤立 val，M2 引入装配层）

## 对 M2 的交接

**M2 scope**：FileReadTool + 并发 batch 执行器 + Tool 注册表。

**入场清单**：
1. 读 `phase2-tools-design-notes.md §3.5.3` — 并发调度的 partition 逻辑
2. 读 `phase2-mvp-tool-specs.md §2` — FileReadTool spec
3. 决定 `runToolUseBatch` 签名（建议 `Flow<ToolUpdate>` 合流）
4. 决定 `ToolExecutionContext` 是否需要加 `FileStateCache`（FileRead 用于去重已读文件）

**已知风险**：
- `ToolExecutionContext` 字段在 M2 必然需要扩（加 `FileStateCache` 或等价物）。这是预料中的——接口字段"第一版必错"的佐证
- 现在 `BashTool.checkPermissions` 的白名单匹配只认 `"command *"` 简单前缀，M3 引入正式权限规则时要改

## 参考

- Brief：`docs/phase2-tools-refactor-brief.md`
- Design notes：`docs/phase2-tools-design-notes.md`
- MVP specs：`docs/phase2-mvp-tool-specs.md`
- Claude Code 源码：`~/SourceLib/fishNotExist/claude-code-source/`（`src/Tool.ts`、`src/tools.ts`、`src/services/tools/toolExecution.ts`）
