# 统一事件系统与消息分组设计

**日期：** 2026-04-19
**状态：** Draft
**影响范围：** BridgeHandler, useBridge, App.tsx, ChatService, 新增分组组件

---

## 背景

CodePlanGUI 使用 Kotlin 后端通过 JBCefJSQuery 与 React 前端通信。当前在 `window.__bridge` 上注册了 15 个独立回调（`onStart`、`onToken`、`onEnd`、`onError`、`onStructuredError`、`onContextFile`、`onTheme`、`onStatus`、`onExecutionCard`、`onApprovalRequest`、`onExecutionStatus`、`onRestoreMessages`、`onLog`、`onContinuation`、`onRemoveMessage`）。每个回调独立通过 `useState` 更新 React 状态。这带来了几个问题：

1. **状态逻辑分散**——15 个 `useCallback` 钩子分布在 App.tsx 中，难以推理状态转换。
2. **排序 hack**——`ChatService.kt` 中使用了 lazy `notifyStart` + `notifyRemoveMessage` + `bridgeNotifiedStart` 集合来保证执行卡片出现在最终助手文本气泡之前。
3. **可扩展性差**——添加 reasoning 支持或多轮工具调用需要更多回调和更多 hack。

本设计按顺序引入两个变更：
- **Phase 1：** 统一事件通道——单一 `onEvent(type, payload)` 替代 15 个回调。
- **Phase 2：** 前端消息分组——`MessageGroup[]` 替代扁平 `Message[]` 用于渲染，消除后端排序 hack。

---

## Phase 1：统一事件通道

### 1.1 事件类型协议

所有事件使用统一格式：

```
StreamEvent = { type: string, payload: JSON 字符串 }
```

事件类型与现有回调一一对应：

| 事件类型 | Payload | 替代 |
|---|---|---|
| `start` | `{msgId: string}` | `onStart` |
| `token` | `{text: string}` | `onToken` |
| `end` | `{msgId: string}` | `onEnd` |
| `round_end` | `{msgId: string}` | *(新增)* — 轮次以 tool_calls 结束但非最终结束 |
| `error` | `{message: string}` | `onError` |
| `structured_error` | `{type: string, message: string, action?: string}` | `onStructuredError` |
| `status` | `{providerName, model, connectionState, contextFile}` | `onStatus` |
| `context_file` | `{fileName: string}` | `onContextFile` |
| `theme` | `{mode: "dark" \| "light"}` | `onTheme` |
| `execution_card` | `{requestId, command, description}` | `onExecutionCard` |
| `approval_request` | `{requestId, command, description}` | `onApprovalRequest` |
| `execution_status` | `{requestId, status, result}` | `onExecutionStatus` |
| `log` | `{requestId, line, type}` | `onLog` |
| `continuation` | `{current: number, max: number}` | `onContinuation` |
| `remove_message` | `{msgId: string}` | `onRemoveMessage` — Phase 1 保留，Phase 2 可移除 |
| `restore_messages` | `{messages: string}`（JSON 编码的数组） | `onRestoreMessages` |

注意：`round_end` 在 Phase 1 作为空操作占位符引入，使 Phase 2 可以消费它而无需再次修改 Kotlin。Phase 1 中后端不会发送它。

### 1.2 Kotlin 端：BridgeHandler 改动

**文件：** `BridgeHandler.kt`

新增一个核心方法，生成 JS 字符串但不执行（调用方决定调度策略）：

```kotlin
private fun buildEventJS(type: String, payload: Map<String, Any?>): String {
    // 使用项目现有的 kotlinx.serialization 进行安全的 JSON 编码
    // 调用方传入 Map（而非预编码字符串）以避免双重编码问题
    val jsonPayload = json.encodeToString(payload)
    return "window.__bridge.onEvent('$type', $jsonPayload)"
}
```

每个现有 `notifyXxx` 方法改为内部调用 `buildEventJS`，但保持相同的公开签名。调度策略（立即 vs 批量）不变：

| 方法 | 策略 | 调度方式 |
|---|---|---|
| `notifyStart` | `flushAndPush`（立即，先刷空待处理批次） | `flushAndPush(buildEventJS("start", ...))` |
| `notifyToken` | `enqueueJS`（16ms 批量） | `enqueueJS(buildEventJS("token", ...))` |
| `notifyEnd` | `flushAndPush` | `flushAndPush(buildEventJS("end", ...))` |
| `notifyError` | `flushAndPush` | `flushAndPush(buildEventJS("error", ...))` |
| `notifyStructuredError` | `flushAndPush` | `flushAndPush(buildEventJS("structured_error", ...))` |
| `notifyExecutionCard` | `flushAndPush` | `flushAndPush(buildEventJS("execution_card", ...))` |
| `notifyApprovalRequest` | `flushAndPush` | `flushAndPush(buildEventJS("approval_request", ...))` |
| `notifyExecutionStatus` | `flushAndPush` | `flushAndPush(buildEventJS("execution_status", ...))` |
| `notifyLog` | `enqueueJS` | `enqueueJS(buildEventJS("log", ...))` |
| `notifyContinuation` | `pushJS`（立即，不刷空） | `pushJS(buildEventJS("continuation", ...))` |
| `notifyRemoveMessage` | `flushAndPush` | `flushAndPush(buildEventJS("remove_message", ...))` |
| `notifyRestoreMessages` | `flushAndPush` | `flushAndPush(buildEventJS("restore_messages", ...))` |
| `notifyStatus` | `flushAndPush` | `flushAndPush(buildEventJS("status", ...))` |
| `notifyContextFile` | `pushJS`（立即，不刷空） | `pushJS(buildEventJS("context_file", ...))` |
| `notifyTheme` | `pushJS`（立即，不刷空） | `pushJS(buildEventJS("theme", ...))` |

注意：`pushJS` 立即发送但不刷空待处理批次。这是 `notifyContextFile` 和 `notifyTheme` 的当前行为，保持不变。这些事件是非关键性的，不需要相对于待处理 token 保持顺序。

**为 Phase 2 准备的新方法：**

```kotlin
fun notifyRoundEnd(msgId: String) =
    flushAndPush(buildEventJS("round_end", mapOf("msgId" to msgId)))
```

此方法在 Phase 1 添加但不调用。Phase 2 激活它。

**重要实现细节：** `buildEventJS` 仅生成 JS 字符串，不执行。调用方（`notifyXxx`）决定将其传给 `enqueueJS`、`flushAndPush` 还是 `pushJS`，与现有方式一致。这保留了当前的顺序保证：结构事件总是在执行前刷空待处理 token。

**`ChatService.kt`：零改动。** 继续调用 `bridgeHandler?.notifyStart(msgId)` 等。

### 1.3 前端：Bridge 接口变更

**文件：** `types/bridge.d.ts`

```typescript
interface Bridge {
  // 单一事件通道（Kotlin → JS）
  onEvent: (type: string, payloadJson: string) => void

  // 动作方法（JS → Kotlin）— 不变
  sendMessage: (text: string, includeContext: boolean) => void
  approvalResponse: (requestId: string, action: string, addToWhitelist?: boolean) => void
  cancelStream: () => void
  newChat: () => void
  openSettings: () => void
  debugLog: (message: string) => void
  frontendReady: () => void
}
```

### 1.4 前端：useBridge Hook

**文件：** `useBridge.ts`

用单一 `onEvent` 处理器替代 15 个独立回调注册。现有的 `bridge_ready` 生命周期和动作方法注入模式保持不变——仅事件接收端变更。

```typescript
type EventHandler = (type: string, payload: any) => void

export function useBridge(onEvent: EventHandler): boolean {
  const [bridgeReady, setBridgeReady] = useState(false)
  const frontendReadySentRef = useRef(false)
  const onEventRef = useRef(onEvent)
  onEventRef.current = onEvent

  const setup = useCallback(() => {
    // 不要完全覆盖 window.__bridge。
    // BridgeHandler 通过 JBCefJSQuery 注入动作方法（sendMessage、approvalResponse 等）。
    // 我们仅在现有对象上设置 onEvent 处理器，
    // 或在 BridgeHandler 尚未注入时创建占位对象。
    if (!window.__bridge) {
      window.__bridge = {} as any
    }
    window.__bridge.onEvent = (type: string, payloadJson: string) => {
      try {
        const payload = JSON.parse(payloadJson)
        onEventRef.current(type, payload)
      } catch (e) {
        console.warn(`[CodePlanGUI] Failed to parse event payload: type=${type}`, e)
      }
    }
    setBridgeReady(true)

    // 如果尚未发送，发送 frontendReady 信号
    if (!frontendReadySentRef.current && window.__bridge.frontendReady) {
      frontendReadySentRef.current = true
      window.__bridge.frontendReady()
    }
  }, [])

  useEffect(() => {
    setup()
    // 监听 bridge_ready 事件（BridgeHandler JS 注入后触发）
    document.addEventListener("bridge_ready", setup)
    return () => document.removeEventListener("bridge_ready", setup)
  }, [setup])

  return bridgeReady
}
```

与当前实现的关键区别：
- 15 个独立回调参数（`onStart`、`onToken` 等）被单一 `onEvent` 处理器替代。
- `onEventRef` 模式确保始终调用最新的处理器而无需每次渲染重新注册。
- `bridge_ready` 事件监听器、`frontendReady` 信号和 `bridgeReady` 状态全部保留。

### 1.5 前端：eventReducer

**文件：** 新文件 `eventReducer.ts`（从 App.tsx 逻辑中提取）

```typescript
interface AppState {
  messages: Message[]
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: "dark" | "light"
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
}

export function eventReducer(state: AppState, type: string, payload: any): AppState {
  switch (type) {
    case "start":
      return {
        ...state,
        isLoading: true,
        error: null,
        messages: [...state.messages, { id: payload.msgId, role: "assistant", content: "", isStreaming: true }],
      }

    case "token":
      return {
        ...state,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, content: m.content + payload.text } : m
        ),
      }

    case "end":
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
      }

    case "round_end":
      // Phase 1：为向后兼容，等同于 end
      return state

    case "error":
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
        error: { type: "runtime", message: payload.message },
      }

    case "structured_error":
      return {
        ...state,
        isLoading: false,
        messages: state.messages.map(m =>
          m.isStreaming ? { ...m, isStreaming: false } : m
        ),
        error: { type: payload.type, message: payload.message, action: payload.action },
      }

    case "execution_card":
      return {
        ...state,
        messages: [...state.messages, {
          id: payload.requestId,
          role: "execution" as const,
          content: "",
          execution: { requestId: payload.requestId, command: payload.command, status: "running" as ExecutionStatus },
        }],
      }

    case "approval_request":
      return {
        ...state,
        approvalRequestId: payload.requestId,
        approvalCommand: payload.command,
        approvalDescription: payload.description,
        approvalOpen: true,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, status: "waiting" as ExecutionStatus } }
            : m
        ),
      }

    case "execution_status": {
      const result = parseExecutionResultPayload(payload.result)
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, status: payload.status as ExecutionStatus, result } }
            : m
        ),
      }
    }

    case "log":
      return {
        ...state,
        messages: state.messages.map(m =>
          m.id === payload.requestId
            ? { ...m, execution: { ...m.execution!, logs: [...(m.execution?.logs || []), { text: payload.line, type: payload.type as LogEntry["type"] }] } }
            : m
        ),
      }

    case "continuation":
      return { ...state, continuationInfo: { current: payload.current, max: payload.max } }

    case "remove_message":
      return { ...state, messages: state.messages.filter(m => m.id !== payload.msgId) }

    case "restore_messages":
      // payload.messages 是 JSON 编码的字符串（后端双重编码）。
      // useBridge 中的外层 JSON.parse 已解码事件 payload，
      // 因此 payload.messages 是需要第二次解析的字符串。
      return { ...state, messages: restoreFlatMessages(JSON.parse(payload.messages)) }

    case "status":
      return { ...state, status: applyBridgeStatus(state.status, payload) }

    case "context_file":
      return { ...state, status: applyContextFile(state.status, payload.fileName) }

    case "theme":
      return { ...state, themeMode: payload.mode }

    default:
      return state
  }
}
```

### 1.6 前端：App.tsx 改动

用单一处理器替代 15 个 `useCallback`：

```typescript
const emitFrontendDebugLog = useCallback((message: string) => {
  window.__bridge?.debugLog(message)
}, [])

const handleEvent = useCallback((type: string, payload: any) => {
  // 调试日志副作用（从原始回调中保留）
  if (type === "execution_card") {
    emitFrontendDebugLog(`[approval-ui] received execution card requestId=${payload.requestId} command=${payload.command}`)
  } else if (type === "approval_request") {
    emitFrontendDebugLog(`[approval-ui] received approval request requestId=${payload.requestId}`)
  } else if (type === "execution_status") {
    emitFrontendDebugLog(`[approval-ui] received execution status requestId=${payload.requestId} status=${payload.status}`)
  }

  setState(prev => eventReducer(prev, type, payload))
}, [emitFrontendDebugLog])
```

`useBridge` 接收 `handleEvent` 而非 15 个独立回调。

App.tsx 中所有其他逻辑（handleSend、handleKeyDown、handleNewChat、handleApprovalAllow/Deny、handleCancel）保持不变——它们使用 `window.__bridge?.sendMessage()` 等动作方法，不是事件回调。

### 1.7 Phase 1 改动总结

| 文件 | 改动 |
|---|---|
| `BridgeHandler.kt` | 内部重写 `notifyXxx` 方法使用 `buildEventJS`；新增 `notifyRoundEnd` |
| `useBridge.ts` | 15 个回调参数替换为单一 `EventHandler`；保留 bridge_ready 生命周期 |
| `App.tsx` | 15 个 `useCallback` 替换为单一 `handleEvent` + `setState` reducer；保留调试日志副作用 |
| 新增：`eventReducer.ts` | 纯函数，将事件映射到状态变更 |
| `types/bridge.d.ts` | 更新 `Bridge` 接口 |
| `ChatService.kt` | **零改动** |

---

## Phase 2：前端消息分组

### 2.1 数据模型

```typescript
type MessageGroup =
  | { type: "human"; id: string; message: { id: string; content: string } }
  | { type: "assistant"; id: string; children: AssistantChild[]; isStreaming: boolean }

type AssistantChild =
  | { kind: "execution"; data: ExecutionCardData }
  | { kind: "text"; id: string; content: string; isStreaming: boolean }
```

关键属性：
- `MessageGroup` 是**纯前端概念**。后端和持久化层不感知。
- 每个组有自己的 `id`（assistant 组从 `msgId` 派生）。
- `assistant` 组的 `children` 是有序列表——执行卡片和文本按事件到达顺序排列。
- 单个 assistant 组跨越一次用户回合的所有 API 轮次（包括多轮工具调用和自动续写）。
- **msgId 不变量**：`ChatService.kt` 在单个用户回合的所有 API 轮次中使用相同的 `msgId`（`activeMessageId`）。这意味着 Round 1 的 `start(msgId)` 和 Round 2 的 `start(msgId)` 携带相同的 `msgId`。组的 `id` 字段使用此 `msgId`，`start` 处理器通过检查最后一个组是否仍在流式传输来检测续写轮次。

### 2.2 事件 → 组映射：groupReducer

**文件：** `groupReducer.ts`（替代 Phase 1 的 `eventReducer.ts`）

```typescript
interface GroupState {
  groups: MessageGroup[]
  // 非消息状态保持不变
  isLoading: boolean
  error: BridgeError | null
  status: BridgeStatus
  themeMode: "dark" | "light"
  approvalOpen: boolean
  approvalRequestId: string
  approvalCommand: string
  approvalDescription: string
  continuationInfo: { current: number; max: number } | null
  // 跟踪当前轮次的文本子项，用于 round_end 时可能的丢弃
  currentRoundTextIndex: number | null
}

export function groupReducer(state: GroupState, type: string, payload: any): GroupState {
  switch (type) {
    case "start": {
      // 创建新的 assistant 组（如果是续写轮次则复用）
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type === "assistant" && lastGroup.isStreaming) {
        // 这是续写/工具调用轮次——复用现有组
        return { ...state, isLoading: true, error: null, currentRoundTextIndex: null }
      }
      return {
        ...state,
        isLoading: true,
        error: null,
        currentRoundTextIndex: null,
        groups: [...state.groups, { type: "assistant", id: payload.msgId, children: [], isStreaming: true }],
      }
    }

    case "token": {
      // 查找或创建当前轮次的文本子项
      const lastGroup = state.groups[state.groups.length - 1]
      if (lastGroup?.type !== "assistant") return state

      const group = lastGroup as AssistantGroup
      let newTextIndex = state.currentRoundTextIndex

      if (newTextIndex !== null && group.children[newTextIndex]?.kind === "text") {
        // 追加到现有文本子项
        const updated = [...group.children]
        updated[newTextIndex] = {
          ...updated[newTextIndex],
          content: (updated[newTextIndex] as TextChild).content + payload.text,
        }
        const groups = [...state.groups]
        groups[groups.length - 1] = { ...group, children: updated }
        return { ...state, groups }
      }

      // 创建新文本子项
      const textChild: AssistantChild = { kind: "text", id: `text-${Date.now()}`, content: payload.text, isStreaming: true }
      newTextIndex = group.children.length  // 新追加子项的索引
      const groups = [...state.groups]
      groups[groups.length - 1] = { ...group, children: [...group.children, textChild] }
      return { ...state, groups, currentRoundTextIndex: newTextIndex }
    }

    case "execution_card": {
      return updateLastAssistant(state, group => ({
        ...group,
        children: [...group.children, {
          kind: "execution",
          data: { requestId: payload.requestId, command: payload.command, status: "running" as ExecutionStatus },
        }],
      }))
    }

    case "log": {
      return updateLastAssistant(state, group => ({
        ...group,
        children: group.children.map(child =>
          child.kind === "execution" && child.data.requestId === payload.requestId
            ? { ...child, data: { ...child.data, logs: [...(child.data.logs || []), { text: payload.line, type: payload.type }] } }
            : child
        ),
      }))
    }

    case "execution_status": {
      const result = parseExecutionResultPayload(payload.result)
      return updateLastAssistant(state, group => ({
        ...group,
        children: group.children.map(child =>
          child.kind === "execution" && child.data.requestId === payload.requestId
            ? { ...child, data: { ...child.data, status: payload.status, result } }
            : child
        ),
      }))
    }

    case "approval_request": {
      return {
        ...updateLastAssistant(state, group => ({
          ...group,
          children: group.children.map(child =>
            child.kind === "execution" && child.data.requestId === payload.requestId
              ? { ...child, data: { ...child.data, status: "waiting" } }
              : child
          ),
        })),
        approvalRequestId: payload.requestId,
        approvalCommand: payload.command,
        approvalDescription: payload.description,
        approvalOpen: true,
      }
    }

    case "round_end": {
      // 丢弃当前轮次的文本子项（tool_calls 之前的中间 token）
      if (state.currentRoundTextIndex !== null) {
        return updateLastAssistant({
          ...state,
          currentRoundTextIndex: null,
        }, group => ({
          ...group,
          children: group.children.filter((_, i) => i !== state.currentRoundTextIndex),
        }))
      }
      return state
    }

    case "end": {
      return updateLastAssistant({
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
      }, group => ({
        ...group,
        isStreaming: false,
        children: group.children.map(child =>
          child.kind === "text" ? { ...child, isStreaming: false } : child
        ),
      }))
    }

    case "error": {
      const groups = state.groups.map(g => {
        if (g.type === "assistant" && g.isStreaming) {
          return {
            ...g,
            isStreaming: false,
            children: g.children.map(child =>
              child.kind === "text" ? { ...child, isStreaming: false } : child
            ),
          }
        }
        return g
      })
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
        groups,
        error: { type: "runtime", message: payload.message },
      }
    }

    case "structured_error": {
      const groups = state.groups.map(g => {
        if (g.type === "assistant" && g.isStreaming) {
          return {
            ...g,
            isStreaming: false,
            children: g.children.map(child =>
              child.kind === "text" ? { ...child, isStreaming: false } : child
            ),
          }
        }
        return g
      })
      return {
        ...state,
        isLoading: false,
        continuationInfo: null,
        currentRoundTextIndex: null,
        groups,
        error: { type: payload.type, message: payload.message, action: payload.action },
      }
    }

    case "continuation":
      return { ...state, continuationInfo: { current: payload.current, max: payload.max } }

    case "restore_messages":
      return { ...state, groups: restoreToGroups(JSON.parse(payload.messages)) }

    // 非消息事件直接透传
    case "status":
      return { ...state, status: applyBridgeStatus(state.status, payload) }
    case "context_file":
      return { ...state, status: applyContextFile(state.status, payload.fileName) }
    case "theme":
      return { ...state, themeMode: payload.mode }

    default:
      return state
  }
}

// 辅助函数：更新数组中最后一个 assistant 组
function updateLastAssistant(state: GroupState, updater: (g: AssistantGroup) => AssistantGroup, extraState?: Partial<GroupState>): GroupState {
  const lastIdx = findLastAssistantIndex(state.groups)
  if (lastIdx === -1) return state
  const groups = [...state.groups]
  groups[lastIdx] = updater(groups[lastIdx] as AssistantGroup)
  return { ...state, ...extraState, groups }
}
```

### 2.3 轮次 Token 丢弃行为

当轮次以 `round_end`（tool_calls）结束时，该轮次创建的任何文本子项都会被丢弃。这实现了用户的选择："Round 1 的 token 被丢弃，仅显示执行卡片 + Round 2 的最终回答。"

典型工具调用回合的事件序列：

```
用户发送消息
  → 前端：创建 human 组

Round 1:
  start(msgId)           → 创建 assistant 组（或复用）
  token("我来帮你...")    → 追加文本子项（由 currentRoundTextIndex 跟踪）
  execution_card(cmd1)   → 追加执行子项
  execution_status(done) → 更新执行子项
  round_end(msgId)       → 丢弃文本子项，保留执行子项

Round 2:
  start(msgId)           → 复用现有 assistant 组（isStreaming 仍为 true）
  token("这是结果...")    → 追加新文本子项
  end(msgId)             → 结束组

结果：assistant 组 children = [execution_card_1, text("这是结果...")]
```

### 2.4 Phase 2 后端改动

**文件：** `ChatService.kt`

**移除：**
- `bridgeNotifiedStart` 可变集合——不再需要
- `onFinishReason` 中的 `notifyRemoveMessage` 调用（第 477 行）——不再需要
- `onToken` 中的 lazy `notifyStart` 逻辑（第 422-425 行）——`notifyStart` 现在在轮次开始时无条件发送

**新增：**
- 当 `finish_reason = "tool_calls"` 时调用 `notifyRoundEnd(msgId)`（替代 `notifyRemoveMessage` 块）
- 在 `startStreamingRound()` 开头调用 `notifyStart(msgId)`（当前是延迟的）

**简化后的 `onFinishReason("tool_calls")`：**

```kotlin
// 改动前（hack 方式）
if (msgId in bridgeNotifiedStart) {
    bridgeHandler?.notifyRemoveMessage(msgId)
    bridgeNotifiedStart.remove(msgId)
}
scope.launch { handleToolCallComplete(msgId, capturedBuffer) }

// 改动后（干净方式）
bridgeHandler?.notifyRoundEnd(msgId)
scope.launch { handleToolCallComplete(msgId, capturedBuffer) }
```

**简化后的 `onToken`（不再有延迟初始化）：**

```kotlin
onToken = { token ->
    if (activeMessageId == msgId) {
        responseBuffer.append(token)
        // 无延迟 notifyStart 检查——start 已在前面发送
        bridgeHandler?.notifyToken(token)
    }
}
```

**`startStreamingRound()` 现在在顶部发送 `notifyStart`：**

```kotlin
private fun startStreamingRound(msgId: String, request: Request, toolsEnabled: Boolean) {
    bridgeHandler?.notifyStart(msgId)  // 无条件发送
    // ... 其余不变
}
```

**时序考虑：** 当前工具未启用时，`notifyStart` 从 `sendMessage()` 发送（HTTP 请求之前）。移到 `startStreamingRound()` 意味着它稍后触发（请求构建之后）。这是可接受的，因为前端在响应 `start` 时创建 assistant 组，而 `sendMessage()` 和 `startStreamingRound()` 之间的延迟可忽略（无网络往返——仅对象构建）。不会出现可见的空气泡闪烁。

### 2.5 渲染

**新文件：** `components/AssistantGroup.tsx`

```tsx
function AssistantGroup({ group }: { group: AssistantGroup }) {
  return (
    <div className="assistant-group">
      {group.children.map(child => {
        if (child.kind === "execution") {
          return <ExecutionCard key={child.data.requestId} data={child.data} />
        }
        return (
          <div key={child.id} className="message-row message-row-assistant">
            <div className="message-bubble message-bubble-assistant">
              <div className="assistant-bubble-header">
                <span className="assistant-bubble-label">assistant</span>
                <CopyButton text={child.content} />
              </div>
              <AssistantMarkdown content={child.content} />
              {child.isStreaming && <span className="stream-cursor" />}
            </div>
          </div>
        )
      })}
    </div>
  )
}
```

`AssistantMarkdown` 从 `MessageBubble.tsx` 中提取当前的 markdown 渲染逻辑（`useEffect` 中的 `marked.parse` + `DOMPurify.sanitize` + 代码块复制按钮）。

**用户消息渲染** 变为更简单的组件，因为 `MessageBubble` 不再需要处理 `role === "execution"`：

```tsx
{groups.map(group => {
  if (group.type === "human") {
    return (
      <div key={group.id} className="message-row message-row-user">
        <div className="message-bubble message-bubble-user">
          <Typography.Text>{group.message.content}</Typography.Text>
        </div>
      </div>
    )
  }
  return <AssistantGroup key={group.id} group={group} />
})}
```

**执行卡片自动折叠：** 已在 `ExecutionCard.tsx` 中实现（`LogOutput` 在 `isStreaming` 从 true 变为 false 时自动折叠）。无需改动。

**加载指示器简化：**

```tsx
// 改动前：两个独立条件
{(continuationInfo) && <spinner />}
{(!continuationInfo && isLoading && !messages.some(m => m.isStreaming) && messages.some(m => m.role === "execution")) && <spinner />}

// 改动后：单一条件
{isLoading && <continuation-spinner-or-generic-spinner />}
```

由于 `isStreaming` 现在在组级别，前端始终知道当前回合是否仍在进行。

### 2.6 会话持久化

**后端：** `SessionStore.kt` 不变——仍存储 `Message[]` 扁平数组（仅用户 + 助手消息，无执行数据）。

**前端恢复：** `restoreToGroups()` 将扁平消息转换为分组结构：

```typescript
function restoreToGroups(flat: Array<{id: string; role: string; content: string}>): MessageGroup[] {
  const groups: MessageGroup[] = []
  let currentAssistant: AssistantGroup | null = null

  for (const msg of flat) {
    if (msg.role !== "user" && msg.role !== "assistant") continue
    if (msg.role === "assistant" && msg.content.trim().length === 0) continue

    if (msg.role === "user") {
      if (currentAssistant) { groups.push(currentAssistant); currentAssistant = null }
      groups.push({ type: "human", id: msg.id, message: { id: msg.id, content: msg.content } })
    } else {
      if (!currentAssistant) currentAssistant = { type: "assistant", id: msg.id, children: [], isStreaming: false }
      currentAssistant.children.push({ kind: "text", id: `text-${msg.id}`, content: msg.content, isStreaming: false })
    }
  }
  if (currentAssistant) groups.push(currentAssistant)
  return groups
}
```

### 2.7 Phase 2 改动总结

| 文件 | 改动 |
|---|---|
| `ChatService.kt` | 移除 `bridgeNotifiedStart`，移除 `notifyRemoveMessage`，新增 `notifyRoundEnd`，无条件发送 `notifyStart` |
| `BridgeHandler.kt` | 无新改动（Phase 1 已添加 `notifyRoundEnd`） |
| `App.tsx` | 状态从 `messages` → `groups`；用 `groupReducer` 替换 `eventReducer`；简化渲染循环和加载指示器 |
| 新增：`groupReducer.ts` | 替换 `eventReducer.ts`；将事件映射到组状态 |
| 新增：`components/AssistantGroup.tsx` | 渲染 assistant 组（执行卡片 + 文本） |
| 新增：`components/AssistantMarkdown.tsx` | 从 `MessageBubble.tsx` 提取的 markdown 渲染 |
| `MessageBubble.tsx` | 简化为仅用户使用（或移除，内联到 App.tsx 渲染中） |
| `types/bridge.d.ts` | 新增 `StreamEvent` 和 `MessageGroup` 类型 |

### 2.8 可移除内容

Phase 2 完成后，以下内容可以清理：

| 内容 | 原因 |
|---|---|
| BridgeHandler 中的 `notifyRemoveMessage` | 不再调用；组处理排序 |
| ChatService 中的 `bridgeNotifiedStart` | 不再需要；`notifyStart` 无条件发送 |
| ChatService `onToken` 中的延迟气泡创建逻辑 | 不再需要 |
| `remove_message` 事件类型 | 不再发送 |
| `Message` 类型中的 `role: "execution"` | 执行卡片存在于 `AssistantChild` 中 |

---

## 迁移策略

### Phase 1 部署清单：
1. 在 `BridgeHandler.kt` 中实现 `buildEventJS`
2. 重写所有 `notifyXxx` 内部实现
3. 新增 `notifyRoundEnd`（未使用）
4. 创建 `eventReducer.ts`
5. 更新 `useBridge.ts` 为单一 `onEvent`
6. 更新 `App.tsx` 使用 `eventReducer`
7. 更新 `types/bridge.d.ts`
8. 测试：所有现有流程（单轮对话、工具调用、审批、续写、恢复）行为必须完全一致

### Phase 2 部署清单：
1. 创建 `MessageGroup` 类型
2. 创建 `groupReducer.ts`
3. 创建 `AssistantGroup.tsx` 和 `AssistantMarkdown.tsx`
4. 更新 `App.tsx` 状态和渲染
5. 简化 `ChatService.kt`（移除 hack，新增 `notifyRoundEnd` 调用）
6. 测试：工具调用流程必须在无 `notifyRemoveMessage` 的情况下显示执行卡片在最终文本之前；多轮工具调用必须正确分组；自动折叠必须正常工作

### 回滚安全性：
- Phase 1 和 Phase 2 可以独立部署，因为 Phase 1 是透明重构。
- 如果 Phase 2 有问题，回滚前端改动即可恢复扁平渲染，同时保留统一事件系统。
