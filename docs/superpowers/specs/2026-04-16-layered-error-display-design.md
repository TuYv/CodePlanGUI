# 分层错误展示设计

## 背景

后端 `OkHttpSseClient` 已将 API 错误分为 4 类（Auth/Quota/Temp/Generic），但统一通过 `notifyError(message: String)` 传给前端，前端只用一个红色 ErrorBanner 展示。用户无法判断该「改设置」还是「等一下」。

## 错误分类

| 类型 | 判断依据 | 标签 | 配色 | 按钮 |
|------|----------|------|------|------|
| `auth` 配置错误 | HTTP 401/403 | 🔐 配置错误 | 红色 #c0392b | 打开设置 |
| `quota` 配额错误 | QUOTA 语义关键词 | 💰 配额不足 | 橙色 #d4a017 | 打开设置 |
| `temp` 临时错误 | HTTP 429/503/529 + BUSY 语义 | ⏳ 临时错误 | 蓝色 #2980b9 | 重试 |
| `generic` 未知错误 | 其他所有情况 | ❓ 未知错误 | 灰色 #7f8c8d | 无 |

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

**2. `BridgeStatusPayload` 增加字段**

```kotlin
@Serializable
data class BridgeStatusPayload(
    val providerName: String = "",
    val model: String = "",
    val connectionState: String = "unconfigured",
    val lastErrorType: String? = null  // 新增
)
```

**3. `ChatService.kt` — `abortStream` 传递错误类型**

`abortStream` 调用 `bridgeHandler?.notifyError(errorType, message)`，由调用处传入错误类型。

现有调用处（需要传递错误类型）：
- `sendMessage` 中 provider/apiKey 检查 → `"auth"`
- `startStreamingRound` 中 `onError` 回调 → 从 SSE 错误解析得到类型
- `prepareToolCallsForExecution` 中的 abort → `"generic"`

**4. `OkHttpSseClient.kt` — 已有分类逻辑，暴露接口**

`ErrorType` enum 和 `classifyError` 已是 private，需提升可见性或新增 public 方法：

```kotlin
fun classifyErrorType(msg: String): String {
    return when (classifyError(msg)) {
        ErrorType.AUTH -> "auth"
        ErrorType.QUOTA -> "quota"
        ErrorType.RETRIABLE_BUSY -> "temp"
        ErrorType.GENERIC -> "generic"
    }
}
```

`buildErrorMessage` 返回值需同步带上类型信息，建议封装为 data class：

```kotlin
data class ClassifiedError(
    val type: String,  // "auth" | "quota" | "temp" | "generic"
    val message: String
)

// streamChat 的 onError 回调改为 onError: (ClassifiedError) -> Unit
```

### TypeScript 前端

**1. `bridge.d.ts` — 更新类型**

```typescript
export interface Bridge {
  // ...
  onError: (type: string, message: string) => void  // 旧: onError: (message: string) => void
  // ...
}

export interface BridgeStatus {
  // ...
  lastErrorType?: string  // 新增
}
```

**2. `ErrorBanner.tsx` — 按 type 渲染不同样式**

```typescript
interface ErrorBannerProps {
  errorType: string  // "auth" | "quota" | "temp" | "generic"
  message: string
  onClose: () => void
  onAction?: () => void  // "打开设置" | "重试"
}

// 配色映射
const ERROR_STYLES = {
  auth:    { bg: "#2d1a1a", border: "#c0392b", icon: "🔐", label: "配置错误" },
  quota:   { bg: "#2d2416", border: "#d4a017", icon: "💰", label: "配额不足" },
  temp:    { bg: "#1a2a3a", border: "#2980b9", icon: "⏳", label: "临时错误" },
  generic: { bg: "#2a2a2a", border: "#7f8c8d", icon: "❓", label: "未知错误" },
}
```

**3. `App.tsx` — 更新 onError 回调**

```typescript
const onError = useCallback((type: string, message: string) => {
  setIsLoading(false)
  setMessages(prev =>
    prev.map(item => item.isStreaming ? { ...item, isStreaming: false } : item)
  )
  setError(type)  // 存 type，message 通过其他方式传递
}, [])

// ErrorBanner 接收 errorType
{error && <ErrorBanner errorType={error} message={errorMessage} onClose={() => setError(null)} />}

// 需要新增 state: errorMessage: string | null
```

**4. ProviderBar 内联状态（可选增强）**

当 `status.lastErrorType` 存在时，ProviderBar 右侧显示错误类型 tag：

```typescript
{status.lastErrorType && (
  <span className={`error-tag error-tag-${status.lastErrorType}`}>
    {ERROR_LABELS[status.lastErrorType]}
  </span>
)}
```

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

## 测试要点

- HTTP 401/403 → 显示红色"配置错误"，点击"打开设置"跳转 Settings
- 配额错误（insufficient_quota）→ 显示橙色"配额不足"，点击"打开设置"
- HTTP 429/503/529 → 显示蓝色"临时错误"，点击"重试"重新发送上一条消息
- 网络超时/ConnectException → 显示蓝色"临时错误"（属于 BUSY 类型）
- 未知错误 → 显示灰色"未知错误"，无按钮

## 未纳入

- ProviderBar 错误 tag（可作为后续增强）
- 错误自动重试逻辑（重试按钮仅重新触发发送，用户主动操作）
