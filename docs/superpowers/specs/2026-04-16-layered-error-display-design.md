# 分层错误展示设计

## 背景

后端 `OkHttpSseClient` 已将 API 错误分为 4 类（Auth/Quota/Temp/Generic），但统一通过 `notifyError(message: String)` 传给前端，前端只用一个红色 ErrorBanner 展示。用户无法判断该「改设置」还是「等一下」。

## 错误分类

| 类型 | 判断依据 | 标签 | 图标 | 按钮 |
|------|----------|------|------|------|
| `auth` 配置错误 | HTTP 401/403 | 配置错误 | 🔐 | 打开设置 |
| `quota` 配额错误 | QUOTA 语义关键词 | 配额不足 | 💰 | 打开设置 |
| `temp` 临时错误 | HTTP 429/503/529 + BUSY 语义 | 临时错误 | ⏳ | 重试 |
| `generic` 未知错误 | 其他所有情况 | 未知错误 | ❓ | 无 |

## 架构变更

### Kotlin 后端

**1. `BridgeHandler.kt` — 修改 `notifyError` 签名**

```kotlin
// 旧
fun notifyError(message: String) = pushJS("window.__bridge.onError(${json.encodeToString(message)})")

// 新
fun notifyError(errorType: String, message: String) =
    pushJS("window.__bridge.onError(${json.encodeToString(errorType)}, ${json.encodeToString(message)})")
```

同时新增 `notifyStructuredError` 支持结构化错误：

```kotlin
@Serializable
data class BridgeErrorPayload(
    val type: String,
    val message: String,
    val action: String? = null
)

fun notifyStructuredError(error: BridgeErrorPayload) =
    pushJS("window.__bridge.onStructuredError(${json.encodeToString(error)})")
```

**2. `ChatService.kt` — `abortStream` 传递错误类型**

`abortStream` 调用 `bridgeHandler?.notifyError(errorType, message)`，由调用处传入错误类型。

现有调用处（需要传递错误类型）：
- `sendMessage` 中 provider/apiKey 检查 → `"auth"`
- `startStreamingRound` 中 `onError` 回调 → 从 SSE 错误解析得到类型
- `prepareToolCallsForExecution` 中的 abort → `"generic"`

**3. `OkHttpSseClient.kt` — 新增 ClassifiedError 和 classifyErrorType**

```kotlin
data class ClassifiedError(
    val type: String,  // "auth" | "quota" | "temp" | "generic"
    val message: String
)

fun classifyErrorType(rawMessage: String): String {
    return when {
        QUOTA_PATTERNS.any { it in rawMessage.lowercase() } -> "quota"
        AUTH_PATTERNS.any { it in rawMessage.lowercase() } -> "auth"
        BUSY_PATTERNS.any { it in rawMessage.lowercase() } -> "temp"
        else -> "generic"
    }
}

// streamChat 的 onError 回调改为 onError: (ClassifiedError) -> Unit
```

### TypeScript 前端

**1. `bridge.d.ts` — 更新类型**

```typescript
export interface Bridge {
  // ...
  onError: (type: string, message: string) => void  // 旧: onError: (message: string) => void
  onStructuredError: (error: BridgeError) => void  // 新增
  // ...
}

export interface BridgeError {
  type: string
  message: string
  action?: string
}

export interface BridgeStatus {
  // ...
  lastErrorType?: string  // 新增
}
```

**2. `ErrorBanner.tsx` — 按 type 渲染不同样式**

```typescript
interface Props {
  errorType: 'auth' | 'quota' | 'temp' | 'generic'
  message: string
  onClose: () => void
  onAction?: () => void  // "打开设置" | "重试"
}

const ERROR_CONFIG = {
  auth: { label: '配置错误', icon: '🔐', actionLabel: '打开设置' },
  quota: { label: '配额不足', icon: '💰', actionLabel: '打开设置' },
  temp: { label: '临时错误', icon: '⏳', actionLabel: '重试' },
  generic: { label: '未知错误', icon: '❓', actionLabel: null },
}
```

**样式实现**：使用 CSS class 区分类型，配合 CSS 变量实现主题适配：

```css
.error-banner-auth {
  background: rgba(210, 161, 94, 0.12);
  border-color: rgba(210, 161, 94, 0.35);
}
.error-banner-quota {
  background: rgba(212, 160, 23, 0.12);
  border-color: rgba(212, 160, 23, 0.35);
}
/* ... */
```

**3. `App.tsx` — 更新 onError 回调和重试逻辑**

```typescript
const onError = useCallback((type: string, message: string) => {
  setIsLoading(false)
  setMessages(prev =>
    prev.map(item => item.isStreaming ? { ...item, isStreaming: false } : item)
  )
  setErrorType(type)
  setErrorMessage(message)
}, [])

const onStructuredError = useCallback((bridgeError: BridgeError) => {
  setIsLoading(false)
  setErrorType(bridgeError.type)
  setErrorMessage(bridgeError.message)
}, [])

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

**4. `composerState.ts` — connectionState 为 'error' 时显示提示**

当 API Key 未设置时，`connectionState` 为 `'error'`，composer 区域显示"API Key 未设置或未保存，请在 Settings 中重新配置并应用"，点击发送按钮会触发 `errorType='auth'` 的 ErrorBanner，显示"打开设置"按钮。

## 数据流

```
OkHttpSseClient.classifyErrorType(msg)
    ↓ ClassifiedError(type, message)
ChatService.abortStream(msgId, errorType, errorMessage)
    ↓
BridgeHandler.notifyError(errorType, message)
    ↓ pushJS
window.__bridge.onError(type, message)
    ↓
App.tsx onError(type, message)
    ↓
ErrorBanner errorType={type} message={message}
```

**结构化错误流**（SSE 流中分类错误）：

```
OkHttpSseClient 分类错误
    ↓ BridgeErrorPayload(type, message, action)
BridgeHandler.notifyStructuredError(error)
    ↓ pushJS
window.__bridge.onStructuredError(error)
    ↓
App.tsx onStructuredError(bridgeError)
    ↓
ErrorBanner（action 可触发 openSettings 或 retry）
```

## 测试要点

- HTTP 401/403 → 显示"配置错误" 🔐，点击"打开设置"跳转 Settings
- 配额错误（insufficient_quota）→ 显示"配额不足" 💰，点击"打开设置"
- HTTP 429/503/529 → 显示"临时错误" ⏳，点击"重试"重新发送上一条消息
- 网络超时/ConnectException → 显示"临时错误"（属于 BUSY 类型）
- API Key 未设置时点击发送 → 显示"配置错误"，点击"打开设置"
- 未知错误 → 显示"未知错误" ❓，无按钮

## 未纳入

- ProviderBar 错误 tag（可作为后续增强）
- 错误自动重试逻辑（重试按钮仅重新触发发送，用户主动操作）
