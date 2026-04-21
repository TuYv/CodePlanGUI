# Phase2 Tools 架构设计笔记

> **状态**：核心架构已摸清，可以动笔画 Kotlin 抽象。基于 Claude Code v2.1.88 反编译源码（`~/SourceLib/fishNotExist/claude-code-source/`）的一手精读 + agent 精读 + 交叉验证。

## 1. 信息来源与可信度

| 文件 | 读法 | 可信度 |
|---|---|---|
| `src/Tool.ts` (792 行) | 一手完整读 | 高 |
| `src/tools.ts` (389 行) | 一手完整读 | 高 |
| `src/tools/BashTool/BashTool.tsx` (1143 行) | agent 精读，主线程抽查关键行 | 中-高 |
| `src/tools/FileReadTool/FileReadTool.ts` (1183 行) | agent 精读 | 中 |
| `src/services/tools/toolExecution.ts` (1745 行) | agent 精读 + 主线程抽查 L337 `runToolUse` 签名 | 中-高 |
| `src/services/tools/toolOrchestration.ts` (188 行) | agent 精读 | 中 |

**作废的二手结论**：之前一个 Explore agent 给的"7 必选 + 13 可选字段"的说法不准。真实接口约 50 个字段/方法。

---

## 2. Tool 接口真实面貌（基于 Tool.ts 一手读）

### 2.1 核心类型：`Tool<Input, Output, P extends ToolProgressData>` (L362-695)

按职责分 7 组：

#### A. 身份与 schema
| 字段 | 文件位置 | Kotlin 决策 |
|---|---|---|
| `name: string` | L456 | 保留（必选） |
| `aliases?: string[]` | L371 | 保留（用于重命名兼容） |
| `searchHint?: string` | L378 | 保留（ToolSearch 需要） |
| `inputSchema: Input` (Zod) | L394 | 换成 **JSON Schema DSL**（Kotlin 侧用 kotlinx.serialization 或手写 builder） |
| `inputJSONSchema?` | L397 | 保留（MCP tool 直接声明 JSON Schema 时用） |
| `outputSchema?` | L400 | 可选保留 |

#### B. 核心生命周期（**真正的必选四件套**）
| 方法 | 文件位置 | 作用 |
|---|---|---|
| `call(args, context, canUseTool, parentMessage, onProgress)` | L379-385 | 主执行器。返回 `Promise<ToolResult<Output>>` |
| `validateInput?(input, context)` | L489-492 | 权限检查前的句法/语义校验，返回 `ValidationResult` |
| `checkPermissions(input, context)` | L500-503 | tool 特定的权限决策，返回 `PermissionResult` |
| `mapToolResultToToolResultBlockParam(content, toolUseID)` | L557 | 把输出序列化成 Anthropic API 的 `tool_result` block |

#### C. prompt / 描述（LLM 面向）
| 方法 | 文件位置 | 说明 |
|---|---|---|
| `description(input, options)` | L386 | 生成自然语言描述（给 LLM 的） |
| `prompt(options)` | L518 | 返回该 tool 的 system-prompt 段落 |
| `userFacingName(input)` | L524 | 用户看到的 tool 名 |
| `getActivityDescription?(input)` | L546 | spinner 显示用（如 "Reading src/foo.ts"） |
| `getToolUseSummary?(input)` | L539 | 紧凑视图摘要 |

#### D. 元数据谓词（大多可用默认值）
- `isEnabled()` L403 — default `true`
- `isConcurrencySafe(input)` L402 — default `false`
- `isReadOnly(input)` L404 — default `false`
- `isDestructive?(input)` L406 — default `false`
- `isOpenWorld?(input)` L434
- `interruptBehavior?()` L416 — `'cancel' | 'block'`，默认 `'block'`
- `isSearchOrReadCommand?(input)` L429 — UI 折叠用
- `requiresUserInteraction?()` L435
- `isMcp?`, `isLsp?` L436-437

#### E. Hook / 权限辅助
| 字段 | 文件位置 | Kotlin 决策 |
|---|---|---|
| `preparePermissionMatcher?(input)` | L514 | **保留**——为 hook 模式匹配（如 `"Bash(git *)"`）准备闭包 |
| `backfillObservableInput?(input)` | L481 | 保留——给 observer 看的 input 可与 API-bound input 不同 |
| `getPath?(input)` | L506 | 保留——文件路径 tool 通用接口 |
| `toAutoClassifierInput(input)` | L556 | **可砍**——ML 分类器是 Ant 内部特性，默认返回 `''` 等价于不进分类器 |

#### F. 资源限制
| 字段 | 文件位置 | 说明 |
|---|---|---|
| `maxResultSizeChars` | L466 | **必填**。超过这个就持久化到磁盘，返回预览。设 `Infinity` 则永不持久化（如 Read） |
| `shouldDefer?` | L442 | 是否走 ToolSearch 延迟加载 |
| `alwaysLoad?` | L449 | 与 shouldDefer 对立，保证首轮就进 prompt |
| `strict?` | L472 | API 侧严格模式 |

#### G. UI 渲染（14 个方法，**全部砍掉**）
`renderToolUseMessage`、`renderToolResultMessage?`、`renderToolUseProgressMessage?`、`renderToolUseQueuedMessage?`、`renderToolUseRejectedMessage?`、`renderToolUseErrorMessage?`、`renderToolUseTag?`、`renderGroupedToolUse?`、`isResultTruncated?`、`isTransparentWrapper?`、`extractSearchText?`、`userFacingNameBackgroundColor?`、等等——全部是 React 输出。JetBrains 用 **IntelliJ UI DSL / Swing / JCEF webview** 替代。

### 2.2 关键支撑类型

#### `ToolUseContext` (L158-300) — 超级上下文对象
- ~30 个字段：abort controller、file state、app state、MCP clients、hooks、agent id、message list、限额配置、progress 回调、session 生命周期钩子等
- **Kotlin 对应物**：需要一个 `ToolExecutionContext` data class，但大部分字段不要照抄——挑本插件真正要用的（abort、file state cache、permission context、message list、app state 就够了）

#### `ToolPermissionContext` (L123-138)
```
mode: PermissionMode
additionalWorkingDirectories: Map<string, AdditionalWorkingDirectory>
alwaysAllowRules / alwaysDenyRules / alwaysAskRules: ToolPermissionRulesBySource
isBypassPermissionsModeAvailable: boolean
shouldAvoidPermissionPrompts?: boolean
prePlanMode?: PermissionMode
```
**Kotlin 几乎可以 1:1 移植**——这是权限的全局配置。

#### `ValidationResult` (L95-101)
```
| { result: true }
| { result: false, message: string, errorCode: number }
```
**Kotlin**：`sealed class ValidationResult { Ok; data class Failed(...) }`

#### `ToolResult<T>` (L321-336)
```
data: T
newMessages?: Message[]   // tool 可以注入新消息到对话
contextModifier?: (ctx) => ctx   // 修改上下文（非 concurrency-safe tool 专用）
mcpMeta?: { _meta?, structuredContent? }
```
**Kotlin**：核心是 `data`，其他按需加。

#### `ToolCallProgress<P>` (L338-340)
`(progress: { toolUseID, data: P }) => void` — 流式回调。

### 2.3 `buildTool` 工厂模式 (L757-792)

**关键设计模式**：每个 tool 不是 class 实例，而是 `buildTool({...})` 返回的对象。工厂为 7 个常省略的键填默认值：
- `isEnabled: () => true`
- `isConcurrencySafe: () => false`
- `isReadOnly: () => false`
- `isDestructive: () => false`
- `checkPermissions: () => { behavior: 'allow', updatedInput }`
- `toAutoClassifierInput: () => ''`
- `userFacingName: () => name`

**Kotlin 对应物**（建议）：
```kotlin
fun tool(block: ToolBuilder.() -> Unit): Tool = ToolBuilder().apply(block).build()

// 使用：
val BashTool = tool {
    name = "Bash"
    inputSchema = bashInputSchema
    call { args, ctx -> ... }
    checkPermissions { input, ctx -> ... }
    isDestructive = { it.command.containsDestructiveOp() }
}
```
Kotlin DSL + `apply` 比 TS 的对象 spread 更自然。

---

## 3. Tool 装配与注册（tools.ts 一手读）

### 3.1 关键函数

| 函数 | 文件位置 | 职责 |
|---|---|---|
| `getAllBaseTools()` | L193-251 | 返回硬编码的 tool 数组（40+ 个），feature flag 在此 gate |
| `filterToolsByDenyRules(tools, ctx)` | L262-269 | **预过滤**：deny rule 匹配的 tool 根本不进 prompt |
| `getTools(permissionContext)` | L271-327 | 单环境入口：simple mode / REPL mode / 普通 mode |
| `assembleToolPool(ctx, mcpTools)` | L345-367 | built-in + MCP 合并，按 name 排序（prompt-cache 稳定性），built-in 优先 |
| `getMergedTools(ctx, mcpTools)` | L383-389 | 简单合并，用于 token 估算 |

### 3.2 关键设计选择

1. **Tool 是 singleton，不是 class**：`export const BashTool = buildTool({...})`。Kotlin 对应 `object BashTool : Tool`，或 `val BashTool = tool { ... }`。

2. **注册即硬编码数组**：没有装饰器、没有动态发现、没有插件系统（对基础 tool 而言）。MCP tool 是例外，走独立通道。

3. **Feature flag 在注册层 gate**：tool 本身总被 import，是否包含进 `getAllBaseTools()` 由 `feature('FLAG')` / `process.env` 决定。**我们插件可用类似模式：`plugin.xml` 或 settings 里的开关决定哪些 tool 注册进 pool**。

4. **deny-rule 预过滤是关键架构选择**：全局禁用的 tool **不进 prompt**，省 token + 防 LLM 乱试。这是值得抄的设计。

5. **MCP tool 与 built-in 平级**：`assembleToolPool` 把两者合并，built-in 名字冲突时胜出。Kotlin 侧 MCP 路由应该是独立模块，最后在装配层合流。

6. **排序保证 prompt-cache 稳定**：built-in 排完 + MCP 排完再 concat，`uniqBy` 保持插入顺序。**我们 MVP 可不做**——除非真的接 Anthropic prompt cache，否则只是工程洁癖。

---

## 3.5 执行入口层（toolExecution.ts + toolOrchestration.ts 一手抽查 + agent 精读）

### 3.5.1 顶层入口：`runToolUse()` — 流式执行器

**签名**（`toolExecution.ts:337`，已抽查验证）：
```typescript
export async function* runToolUse(
  toolUse: ToolUseBlock,
  assistantMessage: AssistantMessage,
  canUseTool: CanUseToolFn,
  toolUseContext: ToolUseContext,
): AsyncGenerator<MessageUpdateLazy, void>
```

**关键架构事实**：**这是 `AsyncGenerator`，不是普通 async 函数**。执行过程边跑边 yield 消息更新（进度、结果、错误）。**Kotlin 对应物是 `Flow<MessageUpdate>`，不是 `suspend fun`**。这影响整个执行层的建模方式。

**路由逻辑**（L343-356）：
1. 先在 `toolUseContext.options.tools` 查找（模型已知的 tool 列表）
2. 没找到 → 回退到 `getAllBaseTools()` 查 alias（向后兼容废弃别名）
3. 还找不到 → yield 错误 `tool_result` block（L396-408）

### 3.5.2 权限/Hook 编排（最容易低估的复杂度）

**流水线**（`toolExecution.ts`）：
```
用户输入 → validateInput() [Tool 方法]
  ↓
PreToolUse hooks (L800-820)
  ↓
canUseTool via resolveHookPermissionDecision (L921)
  ├─ hooks 决策
  ├─ 交互式对话（如需）
  └─ 自动分类器（Ant 特性）
  ↓
若 deny → PermissionDenied hooks (L1081)
  ↓
tool.call() (L1207)
  ↓
mapToolResultToToolResultBlockParam() (L1280+)
  ↓
yield tool_result block
```

**异步管道**：每一层都是 async for-await，中间可插入外部逻辑（如 `SedEditPermissionRequest` 改写 input）。不是一次性决策。

**Kotlin 设计影响**：权限链必须支持**中间修改 input**（`PermissionResult.Allow` 带 `updatedInput`）。不能假设 input 是不可变的。

### 3.5.3 并发 tool_use 处理

**一轮模型可能返回 ≥1 个 tool_use**。`toolOrchestration.ts` 处理这个：

| 函数 | 位置 | 职责 |
|---|---|---|
| `partitionToolCalls()` | L91-116 | 按 `isConcurrencySafe(input)` 把 tool_use 列表切成"安全并发组"和"必须串行组" |
| `runToolsConcurrently()` | L152-177 | 并发执行安全组，最大并发数 `CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY`（默认 10，L10） |
| 串行执行路径 | L118-150 | 非安全 tool 逐个跑，每跑完一个更新 context |

**陷阱**：`isConcurrencySafe` 返回 `false` 的 tool（如 BashTool、FileEditTool）会**串行化整批 batch**。如果一轮里混了 safe 和 unsafe tool，partitioning 的顺序/聚合策略会影响用户体验。

**上下文隔离**：并发执行时，每个 tool 可能 mutate context（`ToolResult.contextModifier`）。`queuedContextModifiers` 缓冲保证串行 tool 看到的 context 是前面 tool 更新后的版本。

### 3.5.4 BashTool.call() 实现参考（BashTool.tsx:624-750）

| 步骤 | 位置 | 做什么 |
|---|---|---|
| 1. 创建输出累加器 | L636 | `EndTruncatingAccumulator`（保留尾部） |
| 2. 调用执行器 | L646 | `runShellCommand()` 异步生成器，内部 spawn + stream |
| 3. 消费生成器 | L663 | 每个 yield 点调 `onProgress()` 上报流式进度 |
| 4. 收尾判断 | L683-720 | exit code、中断状态、stderr 处理 |
| 5. 大输出持久化 | L728-743 | 超阈值写 `tool-results/` 目录，只返回路径 |

**Kotlin 移植难点**（不能直接抄的部分）：
- `runShellCommand()` 封装了本地进程管理、timeout、sandbox 决策——**JetBrains 侧必须用 `GeneralCommandLine` + `ProcessHandler` 重写**
- 大输出磁盘持久化的"两跳读取"（Bash 写文件 → FileRead 读文件）在 IDE 里冗余，改成**内存缓冲 + 滚动截断**即可

### 3.5.5 FileReadTool 作为"最小 tool"样本（FileReadTool.ts）

**实际用到的接口字段**（从 1183 行代码中可见）：
- `name`、`inputSchema`、`outputSchema`、`validateInput`、`checkPermissions`、`call`、`prompt`、`description`
- `isConcurrencySafe` **硬编码 `true`**（L373，非函数）——Kotlin DSL 允许同样写法
- `isReadOnly` **硬编码 `true`**（L376）
- 权限层只调一个辅助：`checkReadPermissionForTool()` (L400)，**远比 BashTool 的 7 块权限辅助简单**

**推论：Kotlin Tool 接口真实必选集（从实际 tool 样本反推，而非从接口声明反推）**：

```
必选（所有 tool 都填）：
- name
- inputSchema (+ 可选 outputSchema)
- call
- checkPermissions
- description
- prompt
- mapToolResultToToolResultBlockParam

可选但常填：
- validateInput (复杂 input 的 tool 必填)
- isReadOnly / isConcurrencySafe (并发决策依赖)
- isDestructive (权限提示决策用)

几乎不用填（有默认）：
- isEnabled / isOpenWorld / requiresUserInteraction / interruptBehavior
- 所有 render* 方法（Kotlin 侧全砍）
- toAutoClassifierInput（ML 分类器，不做）
```

这比之前二手摘要给的"7 必选 + 13 可选"更贴近实际。

---

## 4. Kotlin Tool 接口建议签名（修订版）

**关键修订**：原本把 `call()` 写成 `suspend fun` 是错的——Claude Code 的 `runToolUse` 是 `AsyncGenerator`，执行过程持续 yield 消息。Kotlin 对应 `Flow<ToolUpdate>`。

```kotlin
// Tool 本身的 call() 仍然是一次性 suspend（返回最终结果）
// 但调度层（runToolUse 对应物）是 Flow-producing
interface Tool<Input : Any, Output : Any> {
    val name: String
    val aliases: List<String> get() = emptyList()
    val inputSchema: JsonSchema
    val outputSchema: JsonSchema? get() = null
    val maxResultSizeChars: Int get() = DEFAULT_MAX_RESULT_SIZE

    // 核心方法
    suspend fun call(
        input: Input,
        context: ToolExecutionContext,
        canUseTool: CanUseToolFn,
        onProgress: ((Progress) -> Unit)? = null,
    ): ToolResult<Output>

    suspend fun validateInput(input: Input, ctx: ToolExecutionContext): ValidationResult = ValidationResult.Ok

    suspend fun checkPermissions(input: Input, ctx: ToolExecutionContext): PermissionResult =
        PermissionResult.Allow(input)  // 默认放行，委托给全局 permission 层

    fun mapResultToApiBlock(output: Output, toolUseId: String): ToolResultBlock

    // prompt/描述（LLM 面向）
    suspend fun description(input: Input, options: PromptOptions): String
    suspend fun prompt(options: PromptOptions): String

    // 元数据谓词（默认值）
    fun isEnabled(): Boolean = true
    fun isConcurrencySafe(input: Input): Boolean = false  // 默认保守：串行化
    fun isReadOnly(input: Input): Boolean = false
    fun isDestructive(input: Input): Boolean = false

    // Hook 辅助
    fun getPath(input: Input): String? = null
    suspend fun preparePermissionMatcher(input: Input): ((String) -> Boolean)? = null

    // 用户侧显示
    fun userFacingName(input: Input?): String = name
    fun getActivityDescription(input: Input?): String? = null
}

sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Failed(val message: String, val errorCode: Int) : ValidationResult()
}

sealed class PermissionResult {
    data class Allow(val updatedInput: Any) : PermissionResult()
    data class Deny(val message: String) : PermissionResult()
    data class Ask(val message: String) : PermissionResult()
}

data class ToolResult<T>(
    val data: T,
    val newMessages: List<Message> = emptyList(),
    val contextModifier: ((ToolExecutionContext) -> ToolExecutionContext)? = null,
)

// 调度层入口（对应 runToolUse）——Flow 而非 suspend fun
fun runToolUse(
    toolUse: ToolUseBlock,
    tools: List<Tool<*, *>>,
    canUseTool: CanUseToolFn,
    context: ToolExecutionContext,
): Flow<ToolUpdate>  // 流式发出 progress、permission-ask、result、error

// 并发调度层（对应 partitionToolCalls + runToolsConcurrently）
suspend fun runToolUseBatch(
    toolUses: List<ToolUseBlock>,
    tools: List<Tool<*, *>>,
    canUseTool: CanUseToolFn,
    context: ToolExecutionContext,
    maxConcurrency: Int = 10,
): Flow<ToolUpdate>
```

**Builder DSL（对应 `buildTool`）**：
```kotlin
inline fun <reified I : Any, reified O : Any> tool(
    block: ToolBuilder<I, O>.() -> Unit,
): Tool<I, O> = ToolBuilder<I, O>().apply(block).build()

// 使用：
val BashTool = tool<BashInput, BashOutput> {
    name = "Bash"
    inputSchema = buildJsonSchema { ... }
    call { input, ctx -> ... }
    checkPermissions { input, ctx -> ... }
    isConcurrencySafe { false }
    isDestructive { it.command.containsDestructiveOp() }
    // 未指定的字段自动用默认
}
```

**`ToolExecutionContext` 初版字段**（基于 Claude Code `ToolUseContext` L158-300 的精简子集）：
```kotlin
data class ToolExecutionContext(
    val abortSignal: Job,                          // 中断
    val permissionContext: ToolPermissionContext,  // 全局权限
    val messages: List<Message>,                   // 对话历史
    val project: Project,                          // IntelliJ Project（替代 appState）
    val fileStateCache: FileStateCache,            // 文件状态缓存（Read/Edit 协作用）
    val setToolJSX: ((ToolJSX?) -> Unit)? = null,  // 可空，用于 AskUserQuestion 类
    val mcpClients: List<MCPClient> = emptyList(), // MCP 接入
    val toolUseId: String,
    val onProgress: ((Progress) -> Unit)? = null,
    // Claude Code 里还有 ~25 个字段，MVP 不要
)
```

---

## 5. 已经确定的架构决策

1. ✅ Tool = singleton object / 工厂构建，**不是 class 继承**
2. ✅ 注册 = 硬编码数组，feature flag 在装配层 gate，**不是装饰器**
3. ✅ 权限三态（Allow / Ask / Deny）+ deny-rule 预过滤进 prompt 前 + permission 可修改 input
4. ✅ Schema 用 JSON Schema（kotlinx.serialization 友好），不照抄 Zod
5. ✅ UI 层全部砍掉，用 IntelliJ UI DSL
6. ✅ ML 分类器（toAutoClassifierInput）暂不做，默认返回空
7. ✅ `maxResultSizeChars` 必选；Claude Code 的磁盘持久化在插件里简化为**内存缓冲 + 滚动截断**
8. ✅ **执行层是 Flow-based（流式），不是 suspend fun**。`runToolUse` 对应 `Flow<ToolUpdate>`。
9. ✅ 并发调度：按 `isConcurrencySafe` partition，安全组并发（默认上限 10），不安全组串行（每跑完更新 context）
10. ✅ Hook 编排是 async 管道（PreToolUse → canUseTool → PermissionDenied），每一层可拦截和修改 input

## 6. 还没想透的点

- [ ] `Progress` 类型怎么设计——Claude Code 按 tool 类型分了 `BashProgress` / `MCPProgress` / `REPLToolProgress` 等。Kotlin 侧走泛型 `P : Progress` 还是 sealed class？
- [ ] `ToolUpdate`（Flow 的元素）的具体形状：进度、权限询问、结果、错误都走同一个 Flow？还是拆成多个 channel？
- [ ] MCP 接入具体走什么库（Kotlin 侧有没有 MCP SDK，还是自己实现 stdio/HTTP+SSE？）
- [ ] JCEF webview vs 纯 Swing 做 tool 结果渲染——现有 CodePlanGUI 是 JCEF 路线，tool 结果是直接塞到 webview 的对话流里，还是 tool 层生成中立格式由 webview 渲染？
- [ ] `ToolResult.contextModifier` 在 Kotlin 下用什么类型？（纯函数还是 sealed 行为？）
- [ ] 一次性切换策略下，现有 command-mode 的 `CommandExecutionService` 是否可以拆分出底层 `ProcessRunner` 给新的 `BashTool` 复用

## 7. Milestone 进展

- **M1（骨架）** ✅ 已完成，详见 `docs/phase2-milestone-1-summary.md`
  - Tool 接口 + ToolBuilder DSL + runToolUse Flow 执行器 + BashTool 骨架
  - 7/7 单测通过，编译绿，和 command-mode 零耦合
- **M2（FileReadTool + 并发调度）** ⏸️ 待开工
  - 加 `runToolUseBatch`（按 `isConcurrencySafe` partition）
  - 加 Tool 注册表（对应 Claude Code `getAllBaseTools` / `assembleToolPool`）
  - 加 FileReadTool，验证"非 shell handler"家族可跑
  - `ToolExecutionContext` 可能需要扩 `FileStateCache` 字段
- **M3（FileEditTool + 真询问流程）** ⏸️ 待开工
  - 补 preview() 真实落地（diff 渲染）
  - 替换 `runToolUse` 里 Ask 自动批准的占位，接真 UI 回调
  - 正式权限规则系统（替代 BashTool 现在简单的前缀匹配）
- **M4（MCPProxyTool）** ⏸️ 待开工
- **M5（cutover）** ⏸️ 待开工：UI 接线 + 删 command-mode，独立 PR

## 8. 参考

- 源码根目录：`~/SourceLib/fishNotExist/claude-code-source/`
- 相关文件：`src/Tool.ts`、`src/tools.ts`、`src/tools/BashTool/`、`src/tools/FileReadTool/`
- 前置文档：`docs/phase2-tools-refactor-brief.md`
