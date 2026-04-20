# 分层错误展示实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 前端按错误类型（配置/配额/临时/未知）分层展示，配置错误引导改设置，临时错误引导重试。

**Architecture:** OkHttpSseClient 已有错误分类逻辑，暴露 `ClassifiedError` data class；后端 BridgeHandler 改 `notifyError(type, message)` 签名；前端 ErrorBanner 按 type 渲染不同配色/icon/按钮。

**Tech Stack:** Kotlin (OkHttpSseClient, BridgeHandler, ChatService), TypeScript (ErrorBanner, App), JCEF bridge

---

## 文件变更总览

| 文件 | 变更 |
|------|------|
| `src/main/kotlin/.../api/OkHttpSseClient.kt` | 新增 `ClassifiedError` data class 和 `classifyErrorType()` public 方法 |
| `src/main/kotlin/.../BridgeHandler.kt` | `notifyError(message)` → `notifyError(errorType, message)` |
| `src/main/kotlin/.../ChatService.kt` | `abortStream` 传 errorType；`startStreamingRound` onError 回调返回 `ClassifiedError` |
| `src/main/kotlin/.../api/SseChunkParser.kt` | 辅助解析 SSE 错误 |
| `webview/src/types/bridge.d.ts` | `onError(type, message)`；`BridgeStatus.lastErrorType` |
| `webview/src/components/ErrorBanner.tsx` | 重构，按 type 渲染不同配色/icon/按钮 |
| `webview/src/App.tsx` | `onError(type, message)` 回调，`errorMessage` state |

---

## Task 1: OkHttpSseClient — 暴露 ClassifiedError 接口

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt:81-84`（TestResult 附近）

- [ ] **Step 1: 在 `TestResult` 下方添加 `ClassifiedError` data class**

在 `sealed class TestResult` 下方、`summarizeInterestingSseFrame` 之前插入：

```kotlin
/**
 * Classified API error with type tag for frontend differentiation.
 * type: "auth" | "quota" | "temp" | "generic"
 */
data class ClassifiedError(
    val type: String,
    val message: String
)
```

- [ ] **Step 2: 在 `testConnection` 方法下方添加 `classifyErrorType` public 方法**

在 `testConnection` 方法（line 273）结束后、`parseBodyErrorDetail` 之前添加：

```kotlin
/**
 * Classifies a raw error message into a typed error for frontend display.
 */
fun classifyErrorType(rawMessage: String): String {
    return when {
        QUOTA_PATTERNS.any { it in rawMessage.lowercase() } -> "quota"
        AUTH_PATTERNS.any { it in rawMessage.lowercase() } -> "auth"
        BUSY_PATTERNS.any { it in rawMessage.lowercase() } -> "temp"
        else -> "generic"
    }
}
```

- [ ] **Step 3: 确认 `BUSY_PATTERNS`、`AUTH_PATTERNS`、`QUOTA_PATTERNS` 在 companion object 中已存在**

这些在 line 478-490 已定义，无需修改。

- [ ] **Step 4: 运行测试确认未破坏现有功能**

```bash
./gradlew test --tests "com.github.codeplangui.OkHttpSseClientTest"
```

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt
git commit -m "feat(api): add ClassifiedError data class and classifyErrorType()"
```

---

## Task 2: OkHttpSseClient — streamChat onError 回调返回 ClassifiedError

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt:172-179`

- [ ] **Step 1: 修改 `streamChat` 方法的回调签名**

找到 `streamChat` 方法签名（line 172）：

```kotlin
fun streamChat(
    request: Request,
    onToken: (String) -> Unit,
    onEnd: () -> Unit,
    onError: (String) -> Unit,  // ← 旧
    onToolCallChunk: (ToolCallDelta) -> Unit = {},
    onFinishReason: (String) -> Unit = {}
): EventSource
```

改为：

```kotlin
    onError: (ClassifiedError) -> Unit,  // ← 新
```

- [ ] **Step 2: 修改 `EventSourceListener.onFailure` 中的 onError 调用**

在 `onFailure` 方法内（line 202-211），找到：

```kotlin
onError(msg)
```

替换为：

```kotlin
val errorType = classifyErrorType(msg)
onError(ClassifiedError(type = errorType, message = msg))
```

其中 `msg` 是 line 209 中的 `msg` 变量（已根据 HTTP 状态码和响应体构建）。

- [ ] **Step 3: 运行测试**

```bash
./gradlew test --tests "com.github.codeplangui.OkHttpSseClientTest"
```

Expected: PASS（测试可能需要更新回调签名）

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt
git commit -m "feat(api): streamChat onError callback returns ClassifiedError"
```

---

## Task 3: BridgeHandler — notifyError 签名改为双参数

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt:199`

- [ ] **Step 1: 修改 `notifyError` 方法签名和实现**

找到 line 199：

```kotlin
fun notifyError(message: String) = pushJS("window.__bridge.onError(${json.encodeToString(message)})")
```

替换为：

```kotlin
fun notifyError(errorType: String, message: String) =
    pushJS("window.__bridge.onError(${json.encodeToString(errorType)}, ${json.encodeToString(message)})")
```

- [ ] **Step 2: 确认 JS bridge 注入的 `onError` 接受两个参数**

找到 JS 注入部分（line 176），`onError: function(message) {}` 保持为空实现（前端会替换），无需修改。但前端调用时会传两个参数，这是兼容的。

- [ ] **Step 3: 确认 `BridgeStatusPayload` 无需修改（spec 中计划加 lastErrorType，但暂不实现，作为后续增强）**

跳过此步。

- [ ] **Step 4: 运行 Kotlin 编译确认无错误**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/codeplangui/BridgeHandler.kt
git commit -m "feat(bridge): notifyError(type, message) dual-arg signature"
```

---

## Task 4: ChatService — abortStream 传 errorType，onError 回调更新

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/ChatService.kt:359-369`, `371-443`

- [ ] **Step 1: 修改 `abortStream` 方法签名**

找到 line 359-369：

```kotlin
private fun abortStream(msgId: String, errorMessage: String) {
    if (activeMessageId != msgId) return
    logger.warn("[CodePlanGUI Approval] aborting stream msgId=$msgId error=${errorMessage.summarizeForLog(240)}")
    activeStream?.cancel()
    activeStream = null
    activeMessageId = null
    bridgeNotifiedStart.remove(msgId)
    resetToolCallState()
    publishStatus()
    bridgeHandler?.notifyError(errorMessage)  // ← 旧
}
```

改为：

```kotlin
private fun abortStream(msgId: String, errorType: String, errorMessage: String) {
    if (activeMessageId != msgId) return
    logger.warn("[CodePlanGUI Approval] aborting stream msgId=$msgId type=$errorType error=${errorMessage.summarizeForLog(240)}")
    activeStream?.cancel()
    activeStream = null
    activeMessageId = null
    bridgeNotifiedStart.remove(msgId)
    resetToolCallState()
    publishStatus()
    bridgeHandler?.notifyError(errorType, errorMessage)
}
```

- [ ] **Step 2: 更新所有 abortStream 调用处**

找到所有 `abortStream(msgId, "...")` 调用，改为三参数 `abortStream(msgId, errorType, message)`：

**调用处 1** — `prepareToolCallsForExecution` 中（line 459-460）：
```kotlin
abortStream(msgId, "AI sent a tool_calls finish_reason but no tool call deltas were captured")
```
改为：
```kotlin
abortStream(msgId, "generic", "AI sent a tool_calls finish_reason but no tool call deltas were captured")
```

**调用处 2** — 同文件 line 465-468（tool call id missing）：
```kotlin
abortStream(msgId, "AI sent a tool_calls finish_reason but tool call index ${accumulated.index} had no id")
```
改为传入 `"generic"`。

**调用处 3** — line 475（malformed args）：
```kotlin
abortStream(msgId, "AI returned malformed tool call arguments for index ${accumulated.index}: '$argsJson'")
```
改为传入 `"generic"`。

**调用处 4** — line 479（missing command field）：
```kotlin
abortStream(msgId, "AI tool call index ${accumulated.index} is missing required 'command' field")
```
改为传入 `"generic"`。

- [ ] **Step 3: 更新 `sendMessage` 中两处配置错误调用**

找到 `sendMessage` 中 line 107-108：
```kotlin
bridgeHandler?.notifyError("请先在 Settings > Tools > CodePlanGUI 中配置 API Provider")
```
改为：
```kotlin
bridgeHandler?.notifyError("auth", "请先在 Settings > Tools > CodePlanGUI 中配置 API Provider")
```

找到 line 114-115：
```kotlin
bridgeHandler?.notifyError("API Key 未设置或未保存，请在 Settings 中重新配置并点 Apply/OK")
```
改为：
```kotlin
bridgeHandler?.notifyError("auth", "API Key 未设置或未保存，请在 Settings 中重新配置并点 Apply/OK")
```

- [ ] **Step 4: 更新 `startStreamingRound` 中 `onError` 回调**

找到 `startStreamingRound` 中的 onError 回调（line 413-421）：

```kotlin
onError = { message ->
    if (activeMessageId == msgId) {
        logger.warn("[CodePlanGUI Approval] model round failed msgId=$msgId error=$message")
        activeStream = null
        activeMessageId = null
        bridgeNotifiedStart.remove(msgId)
        publishStatus()
        bridgeHandler?.notifyError(message)
    }
},
```

改为：

```kotlin
onError = { classifiedError ->
    if (activeMessageId == msgId) {
        logger.warn("[CodePlanGUI Approval] model round failed msgId=$msgId type=${classifiedError.type} error=${classifiedError.message}")
        activeStream = null
        activeMessageId = null
        bridgeNotifiedStart.remove(msgId)
        publishStatus()
        bridgeHandler?.notifyError(classifiedError.type, classifiedError.message)
    }
},
```

- [ ] **Step 5: 更新 `streamChat` 调用处传入回调**

找到 `startStreamingRound` 中 `client.streamChat` 调用（line 374），确认传入的 `onError` 参数已被上一步更新。确认无误。

- [ ] **Step 6: 运行 Kotlin 编译确认无错误**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 运行测试**

```bash
./gradlew test --tests "com.github.codeplangui.ChatServiceStatusTest"
```

Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/com/github/codeplangui/ChatService.kt
git commit -m "feat(service): pass errorType through abortStream and onError callback"
```

---

## Task 5: TypeScript — bridge.d.ts 类型更新

**Files:**
- Modify: `webview/src/types/bridge.d.ts:30`, `webview/src/types/bridge.d.ts:1-6`

- [ ] **Step 1: 更新 `onError` 签名**

找到 line 30：
```typescript
onError: (message: string) => void
```

改为：
```typescript
onError: (type: string, message: string) => void
```

- [ ] **Step 2: 更新 `BridgeStatus` 接口**

找到 line 1-6：
```typescript
export interface BridgeStatus {
  providerName: string
  model: string
  connectionState: 'unconfigured' | 'ready' | 'streaming' | 'error'
  contextFile?: string
}
```

改为：
```typescript
export interface BridgeStatus {
  providerName: string
  model: string
  connectionState: 'unconfigured' | 'ready' | 'streaming' | 'error'
  contextFile?: string
  lastErrorType?: string
}
```

- [ ] **Step 3: 提交**

```bash
git add webview/src/types/bridge.d.ts
git commit -m "feat(types): Bridge.onError(type, message), BridgeStatus.lastErrorType"
```

---

## Task 6: ErrorBanner — 按 type 渲染不同样式

**Files:**
- Modify: `webview/src/components/ErrorBanner.tsx`

- [ ] **Step 1: 重写 ErrorBanner 组件**

```typescript
import { Alert } from 'antd'

interface Props {
  errorType: 'auth' | 'quota' | 'temp' | 'generic'
  message: string
  onClose: () => void
  onAction?: () => void
}

const ERROR_STYLES = {
  auth: {
    bg: '#2d1a1a',
    border: '#c0392b',
    icon: '🔐',
    label: '配置错误',
    actionLabel: '打开设置',
  },
  quota: {
    bg: '#2d2416',
    border: '#d4a017',
    icon: '💰',
    label: '配额不足',
    actionLabel: '打开设置',
  },
  temp: {
    bg: '#1a2a3a',
    border: '#2980b9',
    icon: '⏳',
    label: '临时错误',
    actionLabel: '重试',
  },
  generic: {
    bg: '#2a2a2a',
    border: '#7f8c8d',
    icon: '❓',
    label: '未知错误',
    actionLabel: null,
  },
} as const

export function ErrorBanner({ errorType, message, onClose, onAction }: Props) {
  const style = ERROR_STYLES[errorType] ?? ERROR_STYLES.generic

  return (
    <div
      style={{
        margin: '8px 12px',
        background: style.bg,
        border: `1px solid ${style.border}`,
        borderRadius: 8,
        padding: '10px 14px',
        display: 'flex',
        alignItems: 'flex-start',
        gap: 10,
      }}
    >
      <span style={{ fontSize: 18, lineHeight: 1.4 }}>{style.icon}</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ color: style.border, fontSize: 12, fontWeight: 600, marginBottom: 2 }}>
          {style.label}
        </div>
        <div style={{ color: '#ccc', fontSize: 12 }}>{message}</div>
      </div>
      {style.actionLabel && onAction && (
        <button
          onClick={onAction}
          style={{
            background: style.border,
            border: 'none',
            color: errorType === 'temp' ? '#fff' : '#000',
            padding: '4px 12px',
            borderRadius: 6,
            fontSize: 11,
            fontWeight: 600,
            cursor: 'pointer',
            whiteSpace: 'nowrap',
          }}
        >
          {style.actionLabel}
        </button>
      )}
      <button
        onClick={onClose}
        style={{
          background: 'none',
          border: 'none',
          color: '#666',
          cursor: 'pointer',
          fontSize: 14,
          padding: '2px 4px',
        }}
      >
        ✕
      </button>
    </div>
  )
}
```

- [ ] **Step 2: 确认 Alert 组件已无用，移除 import（如果只用了 Alert 的样式，可以整个替换）**

上面代码已完全重写，不再使用 Ant Design Alert。

- [ ] **Step 3: 提交**

```bash
git add webview/src/components/ErrorBanner.tsx
git commit -m "feat(ui): ErrorBanner renders different styles per error type"
```

---

## Task 7: App.tsx — onError 回调和重试逻辑

**Files:**
- Modify: `webview/src/App.tsx:25`, `webview/src/App.tsx:77-83`, `webview/src/App.tsx:209-214`, `webview/src/App.tsx:291`

- [ ] **Step 1: 新增 `errorMessage` state**

找到 line 25 附近：
```typescript
const [error, setError] = useState<string | null>(null)
```

改为：
```typescript
const [errorType, setErrorType] = useState<string | null>(null)
const [errorMessage, setErrorMessage] = useState<string | null>(null)
```

- [ ] **Step 2: 新增 `lastUserMessage` ref 记录上一条用户消息（用于重试）**

在 `messagesEndRef` 附近（line 33）添加：
```typescript
const lastUserMessageRef = useRef<{ text: string; includeContext: boolean } | null>(null)
```

在 `handleSend` 中，更新消息后记录：
```typescript
lastUserMessageRef.current = { text: payload.text, includeContext }
```

- [ ] **Step 3: 更新 `onError` 回调**

找到 line 77-83：
```typescript
const onError = useCallback((message: string) => {
    setIsLoading(false)
    setMessages(prev =>
      prev.map(item => (item.isStreaming ? { ...item, isStreaming: false } : item)),
    )
    setError(message)
}, [])
```

改为：
```typescript
const onError = useCallback((type: string, message: string) => {
    setIsLoading(false)
    setMessages(prev =>
      prev.map(item => (item.isStreaming ? { ...item, isStreaming: false } : item)),
    )
    setErrorType(type)
    setErrorMessage(message)
}, [])
```

- [ ] **Step 4: 添加 `handleErrorAction` 函数**

在 `handleCancel` 函数附近（line 241）添加：
```typescript
const handleErrorAction = useCallback(() => {
    const type = errorType
    if (type === 'auth' || type === 'quota') {
      window.__bridge?.openSettings()
    } else if (type === 'temp' && lastUserMessageRef.current) {
      // Retry: re-send last user message
      const msgToRetry = lastUserMessageRef.current
      window.__bridge?.sendMessage(msgToRetry.text, msgToRetry.includeContext)
    }
    setErrorType(null)
    setErrorMessage(null)
  }, [errorType])
```

- [ ] **Step 5: 更新 ErrorBanner 渲染**

找到 line 291：
```typescript
{error && <ErrorBanner message={error} onClose={() => setError(null)} />}
```

改为：
```typescript
{errorType && errorMessage && (
  <ErrorBanner
    errorType={errorType as 'auth' | 'quota' | 'temp' | 'generic'}
    message={errorMessage}
    onClose={() => { setErrorType(null); setErrorMessage(null) }}
    onAction={handleErrorAction}
  />
)}
```

- [ ] **Step 6: 更新 `handleSend` 记录 lastUserMessageRef**

在 `handleSend` 函数（line 209）末尾，在 `window.__bridge?.sendMessage` 调用之前或之后添加：
```typescript
lastUserMessageRef.current = { text: payload.text, includeContext }
```

（`payload.text` 即用户发送的文本，`includeContext` 为当前 toggle 状态）

- [ ] **Step 7: 提交**

```bash
git add webview/src/App.tsx
git commit -m "feat(ui): App routes error actions (openSettings/retry) by errorType"
```

---

## Task 8: 端到端验证

**Files:**
- Test: All changed files

- [ ] **Step 1: 构建 webview**

```bash
cd webview && npm run build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 构建插件**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview buildPlugin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交最终变更**

```bash
git add -A && git commit -m "feat: layered error display with typed banners

- OkHttpSseClient: ClassifiedError data class + classifyErrorType()
- BridgeHandler: notifyError(type, message) dual-arg
- ChatService: errorType propagated through abortStream and onError
- ErrorBanner: per-type styling (auth/quota/temp/generic)
- App.tsx: error action routing (openSettings for auth/quota, retry for temp)

Closes #IMPLEMENTATION"
```

---

## 自检清单

- [ ] 所有 `abortStream` 调用处都传了 3 个参数（msgId, errorType, message）
- [ ] `ChatService` 中两处 `notifyError("...")` 改为 `notifyError("auth", "...")`
- [ ] `streamChat` 的 `onError` 回调参数从 `String` 改为 `ClassifiedError`
- [ ] `ErrorBanner` 不再使用 Ant Design Alert，纯 CSS 自渲染
- [ ] `errorType` 和 `errorMessage` 两个 state 正确分离
- [ ] 重试逻辑使用 `lastUserMessageRef` 记录并重发
- [ ] `bridge.d.ts` 中 `onError` 签名为 `(type: string, message: string) => void`
