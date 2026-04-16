# Phase 1 — 稳定性与质量基线设计文档

> Date: 2026-04-16
> Branch: fix/commit-message-generation
> Status: Approved

---

## 目标

现有功能零静默失败，错误清晰可区分，IDE 重启后状态正常恢复。完成后即可提交 JetBrains Plugin Marketplace 上架审核。

## 验收标准

1. 主流程无静默失败
2. 错误信息能区分「配置问题」「网络问题」和「运行时失败」
3. UI 状态在 reload / 主题切换 / Provider 切换后保持一致
4. WebView 中 Ctrl+C/Cmd+C 不冻结 IDE

---

## 1. 统一 Bridge 生命周期事件

### 现状

生命周期流程：`onLoadEnd → bridge_ready(DOM) → frontendReady(JS→Kotlin) → [status, theme, context]`

已有事件：`ready`(DOM)、`status`(notifyStatus)、`theme`(notifyTheme)、`context`(notifyContextFile)

### 问题

- `ChatPanel.LafManagerListener` 在主题切换时多余调用 `notifyContextFile`，context 不应因 theme 变化而重推
- `pushJS()` 在 `isReady = false` 时静默丢弃消息，无日志

### 改动

| 文件 | 改动 |
|------|------|
| `ChatPanel.kt:61` | `LafManagerListener` 回调中删除 `br.notifyContextFile(currentContextFile)` |
| `BridgeHandler.kt:258` | `pushJS()` 在 `!isReady` 时添加 debug 日志 |
| `ChatService.kt:81-88` | `onFrontendReady()` 中保证 status → theme callback → context 的推送顺序（当前已正确，添加注释明确契约） |

### 生命周期契约（供前后端参考）

```
Kotlin onLoadEnd
  → inject window.__bridge (isReady=true)
  → dispatch bridge_ready DOM event
Frontend useBridge
  → bind callbacks
  → call window.__bridge.frontendReady()
Kotlin onFrontendReady
  → publishStatus()
  → restoreSessionIfNeeded()
  → onFrontendReadyCallback() → pushTheme() + notifyContextFile()
  → flush pendingPrompt (if any)
```

---

## 2. 结构化错误分层

### 错误类型定义

```kotlin
@Serializable
data class BridgeErrorPayload(
    val type: String,       // "config" | "network" | "runtime"
    val message: String,
    val action: String? = null  // "openSettings" | "retry" | null
)
```

### 分类规则

| 场景 | type | action | 示例消息 |
|------|------|--------|---------|
| 无 Provider | config | openSettings | 请先配置 API Provider |
| API Key 缺失 | config | openSettings | API Key 未设置 |
| SSE 连接失败 / timeout | network | retry | 连接超时，请检查网络 |
| HTTP 4xx (401/403) | config | openSettings | API Key 无效或已过期 |
| HTTP 5xx | network | retry | 服务端错误，请稍后重试 |
| Tool call 参数畸形 | runtime | null | AI 返回了无法解析的工具调用 |
| JSON 解析失败 | runtime | null | 响应格式异常 |

### Kotlin 端改动

| 文件 | 改动 |
|------|------|
| `BridgeHandler.kt` | 新增 `notifyStructuredError(BridgeErrorPayload)` 方法 |
| `ChatService.kt:107-116` | `sendMessage()` 的 Provider/Key 校验改用结构化错误 |
| `ChatService.kt:426-434` | `startStreamingRound.onError` 根据异常/HTTP 状态码分类 |
| `ChatService.kt:372-382` | `abortStream` 使用 `type = "runtime"` |
| `OkHttpSseClient.kt` | `onError` 回调传递更多上下文（HTTP status code 或异常类型） |

### 前端改动

| 文件 | 改动 |
|------|------|
| `bridge.d.ts` | 新增 `BridgeError` interface `{type, message, action?}` |
| `bridge.d.ts` | Bridge interface 新增 `onStructuredError` callback |
| `useBridge.ts` | 绑定 `onStructuredError` callback |
| `App.tsx` | 处理 `onStructuredError`，按 type 设置 error state |
| `ErrorBanner.tsx` | 根据 `type` 渲染不同按钮（config→打开设置，network→重试） |

### 向后兼容

保留 `onError(string)` 不变。新的 `onStructuredError` 作为独立通道，前端两者都监听。迁移完成后 `onError` 可在未来版本移除。

---

## 3. 补充回归测试

### 已有覆盖（19 个测试文件）

- Bridge dispatch、payload handling、status 推导
- SSE 解析（含 tool call）、OkHttpSseClient
- Provider dialog、API key、settings 持久化
- ChatSession、context snapshot、commit prompt builder
- DiffAnalyzer、CommandExecutionService、ShellPlatform

### 新增测试

| 测试文件 | 覆盖目标 | 测试方法 |
|---------|---------|---------|
| `ChatServiceSelectionTest.kt` | `askAboutSelection` 流程：bridge ready 时直接发送，bridge 未 ready 时排队等 `onFrontendReady` | Mock BridgeHandler，验证 `sendMessage` 调用时机 |
| `ChatServiceSessionRestoreTest.kt` | session 恢复：有消息时恢复、空 session 时跳过、恢复后 system 消息被过滤 | Mock SessionStore，验证 `notifyRestoreMessages` 内容 |
| `TwoStageCommitGeneratorTest.kt` | 两阶段 commit message 生成：正常流、空 diff、API 错误 | Mock OkHttpSseClient |
| `BridgeErrorPayloadTest.kt` | `BridgeErrorPayload` 序列化、类型校验 | 纯单元测试 |
| `ChatPanelThemeLifecycleTest.kt` | 主题切换只推 theme 不推 context | 扩展已有 `ChatPanelThemeTest` |

### 测试约束

- 不依赖 JCEF 运行时（纯逻辑测试）
- 不依赖网络（Mock HTTP 层）
- 与已有测试风格一致（JUnit 5 + Assertions）

---

## 4. WebView 冻结修复（预防性）

### 问题根因

JCEF 中 `Ctrl+C / Cmd+C` 触发原生键盘事件时，可能同步等待系统剪贴板访问，导致 WebView 线程阻塞。参考 `jetbrains-cc-gui #846`（commit `3718f46`）。

### 方案

在 `BridgeHandler.register()` 注入的 JS 中添加键盘事件拦截器：

```javascript
document.addEventListener('keydown', function(e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'c') {
        var selection = window.getSelection();
        if (selection && selection.toString().length > 0) {
            e.preventDefault();
            var text = selection.toString();
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).catch(function() {});
            }
        }
    }
}, true);
```

### 改动

| 文件 | 改动 |
|------|------|
| `BridgeHandler.kt:151-189` | 在 `window.__bridge` 注入后追加键盘事件拦截 JS |

### 已有的安全保障

`MessageBubble.tsx` 的 `copyText` 函数已使用异步 clipboard API + fallback textarea 方案，无需修改。

---

## 5. UI 状态一致性修复

### 问题清单

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 5.1 | 主题切换后 context file 被无故重推 | `LafManagerListener` 多余调用 `notifyContextFile` | 同任务 1，删除多余调用 |
| 5.2 | Provider 切换后 status bar 不更新 | `refreshBridgeStatus()` 在 bridge 未 ready 时被 `pushJS` 丢弃 | `publishStatus()` 在 `!isReady` 时缓存最新状态，`onFrontendReady` 时自动推送（当前 `onFrontendReady` 已调 `publishStatus()`，问题已被覆盖） |
| 5.3 | 重载后 ErrorBanner 残留 | 前端 state 在 bridge 重建时未清除 | `useBridge` 的 `bridge_ready` handler 中清除 error state |

### 改动

| 文件 | 改动 |
|------|------|
| `ChatPanel.kt:61` | 同任务 1 |
| `App.tsx` | `bridge_ready` 事件中调 `setError(null)` |
| `BridgeHandler.kt:258` | 同任务 1（`pushJS` 加 debug 日志） |

---

## 执行顺序

1. **Bridge 生命周期** — 基础设施，其他改动依赖此
2. **结构化错误分层** — 新增通道和类型
3. **UI 状态一致性** — 利用新的错误通道
4. **WebView 冻结修复** — 独立改动
5. **回归测试** — 验证以上所有改动

---

## 不做的事

- 不引入新依赖
- 不改前端构建工具链
- 不做 i18n（Phase 5）
- 不做 token 统计（Phase 3）
- 不修改 SSE 协议格式
- `onError(string)` 暂不移除，保持向后兼容
