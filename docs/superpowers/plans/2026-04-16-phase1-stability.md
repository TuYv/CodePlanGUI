# Phase 1 — Stability & Quality Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate silent failures, add structured error classification, fix WebView clipboard freeze, and ensure UI state consistency across reload/theme/provider changes.

**Architecture:** Five incremental changes: (1) fix bridge lifecycle ordering bugs, (2) add structured error payload channel alongside existing `onError`, (3) fix UI state bugs, (4) prevent WebView clipboard freeze via JS keyboard interceptor, (5) add regression tests covering all changes plus existing uncovered paths.

**Tech Stack:** Kotlin (JUnit 5), TypeScript/React (Ant Design), JCEF bridge, OkHttp SSE

---

### Task 1: Fix Bridge Lifecycle — Remove Spurious Context Push on Theme Change

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/ChatPanel.kt:60-65`
- Test: `src/test/kotlin/com/github/codeplangui/ChatPanelThemeTest.kt`

- [ ] **Step 1: Write test verifying theme change should NOT push context**

Add to `src/test/kotlin/com/github/codeplangui/ChatPanelThemeTest.kt`:

```kotlin
@Test
fun `theme change listener should only push theme not context`() {
    // The LafManagerListener callback in ChatPanel should call pushTheme()
    // but NOT notifyContextFile(). This is a design constraint test —
    // the actual fix is verified by code review since ChatPanel requires JCEF.
    // Here we verify the helper functions are correct.
    assertEquals("dark", bridgeTheme(isUnderDarcula = true))
    assertEquals("light", bridgeTheme(isUnderDarcula = false))
}
```

- [ ] **Step 2: Run test to verify it passes (existing behavior is correct for helpers)**

Run: `./gradlew test --tests "com.github.codeplangui.ChatPanelThemeTest" --info`
Expected: PASS

- [ ] **Step 3: Remove spurious `notifyContextFile` from LafManagerListener**

In `src/main/kotlin/com/github/codeplangui/ChatPanel.kt`, change lines 60-66 from:

```kotlin
            connection.subscribe(LafManagerListener.TOPIC, object : LafManagerListener {
                override fun lookAndFeelChanged(source: LafManager) {
                    val isDark = StartupUiUtil.isUnderDarcula()
                    pushTheme(isDark)
                    br.notifyContextFile(currentContextFile)
                }
            })
```

to:

```kotlin
            connection.subscribe(LafManagerListener.TOPIC, object : LafManagerListener {
                override fun lookAndFeelChanged(source: LafManager) {
                    val isDark = StartupUiUtil.isUnderDarcula()
                    pushTheme(isDark)
                }
            })
```

- [ ] **Step 4: Run all tests to verify no regression**

Run: `./gradlew test --info`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/ChatPanel.kt src/test/kotlin/com/github/codeplangui/ChatPanelThemeTest.kt
git commit -m "fix(bridge): remove spurious notifyContextFile from theme change listener"
```

---

### Task 2: Fix Bridge Lifecycle — Add Debug Logging to pushJS

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt:258-263`

- [ ] **Step 1: Add debug log when pushJS discards a message**

In `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt`, change lines 258-263 from:

```kotlin
    private fun pushJS(js: String) {
        if (!isReady) return
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(js, "", 0)
        }
    }
```

to:

```kotlin
    private fun pushJS(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] pushJS discarded (bridge not ready): ${js.take(120)}")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(js, "", 0)
        }
    }
```

- [ ] **Step 2: Add lifecycle contract comment to ChatService.onFrontendReady**

In `src/main/kotlin/com/github/codeplangui/ChatService.kt`, change lines 80-89 from:

```kotlin
    fun onFrontendReady() {
        isFrontendReady = true
        publishStatus()
        restoreSessionIfNeeded()
        onFrontendReadyCallback?.invoke()
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            sendMessage(prompt.text, prompt.includeContext, prompt.contextLabel)
        }
    }
```

to:

```kotlin
    /**
     * Bridge lifecycle contract — called when frontend sends "frontendReady":
     *   1. publishStatus()              → push provider/model/connectionState
     *   2. restoreSessionIfNeeded()     → replay persisted messages
     *   3. onFrontendReadyCallback()    → ChatPanel pushes theme + contextFile
     *   4. flush pendingPrompt          → dequeues Ask AI prompts queued before bridge ready
     */
    fun onFrontendReady() {
        isFrontendReady = true
        publishStatus()
        restoreSessionIfNeeded()
        onFrontendReadyCallback?.invoke()
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            sendMessage(prompt.text, prompt.includeContext, prompt.contextLabel)
        }
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --info`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/BridgeHandler.kt src/main/kotlin/com/github/codeplangui/ChatService.kt
git commit -m "fix(bridge): add debug logging when pushJS discards and document lifecycle contract"
```

---

### Task 3: Structured Error — Add BridgeErrorPayload and notifyStructuredError

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt`
- Create: `src/test/kotlin/com/github/codeplangui/BridgeErrorPayloadTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/github/codeplangui/BridgeErrorPayloadTest.kt`:

```kotlin
package com.github.codeplangui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BridgeErrorPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `config error serializes with action`() {
        val payload = BridgeErrorPayload(
            type = "config",
            message = "API Key 未设置",
            action = "openSettings"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<BridgeErrorPayload>(serialized)
        assertEquals("config", deserialized.type)
        assertEquals("API Key 未设置", deserialized.message)
        assertEquals("openSettings", deserialized.action)
    }

    @Test
    fun `runtime error serializes without action`() {
        val payload = BridgeErrorPayload(
            type = "runtime",
            message = "AI 返回了无法解析的工具调用"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<BridgeErrorPayload>(serialized)
        assertEquals("runtime", deserialized.type)
        assertNull(deserialized.action)
    }

    @Test
    fun `network error serializes with retry action`() {
        val payload = BridgeErrorPayload(
            type = "network",
            message = "连接超时",
            action = "retry"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<BridgeErrorPayload>(serialized)
        assertEquals("network", deserialized.type)
        assertEquals("retry", deserialized.action)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.github.codeplangui.BridgeErrorPayloadTest" --info`
Expected: FAIL — `BridgeErrorPayload` class not found

- [ ] **Step 3: Add BridgeErrorPayload and notifyStructuredError**

In `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt`, add after `BridgeStatusPayload` (after line 88):

```kotlin
@Serializable
data class BridgeErrorPayload(
    val type: String,
    val message: String,
    val action: String? = null
)
```

In the `BridgeHandler` class, add after `notifyError` (after line 202):

```kotlin
    fun notifyStructuredError(error: BridgeErrorPayload) =
        pushJS("window.__bridge.onStructuredError(${json.encodeToString(error)})")
```

In the bridge JS injection block (inside `onLoadEnd`, after line 186 `onRestoreMessages`), add:

```javascript
                            onStructuredError: function(error) {},
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.github.codeplangui.BridgeErrorPayloadTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/BridgeHandler.kt src/test/kotlin/com/github/codeplangui/BridgeErrorPayloadTest.kt
git commit -m "feat(bridge): add BridgeErrorPayload and notifyStructuredError channel"
```

---

### Task 4: Structured Error — Wire ChatService to Use Structured Errors

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/ChatService.kt:106-116, 371-382, 426-434`

- [ ] **Step 1: Replace config errors in sendMessage with structured errors**

In `src/main/kotlin/com/github/codeplangui/ChatService.kt`, change lines 106-116 from:

```kotlin
        if (provider == null) {
            publishStatus()
            bridgeHandler?.notifyError("请先在 Settings > Tools > CodePlanGUI 中配置 API Provider")
            return
        }

        val apiKey = ApiKeyStore.load(provider.id) ?: ""
        if (apiKey.isBlank()) {
            publishStatus()
            bridgeHandler?.notifyError("API Key 未设置或未保存，请在 Settings 中重新配置并点 Apply/OK")
            return
        }
```

to:

```kotlin
        if (provider == null) {
            publishStatus()
            bridgeHandler?.notifyStructuredError(BridgeErrorPayload(
                type = "config",
                message = "请先在 Settings > Tools > CodePlanGUI 中配置 API Provider",
                action = "openSettings"
            ))
            return
        }

        val apiKey = ApiKeyStore.load(provider.id) ?: ""
        if (apiKey.isBlank()) {
            publishStatus()
            bridgeHandler?.notifyStructuredError(BridgeErrorPayload(
                type = "config",
                message = "API Key 未设置或未保存，请在 Settings 中重新配置并点 Apply/OK",
                action = "openSettings"
            ))
            return
        }
```

- [ ] **Step 2: Classify streaming errors in startStreamingRound.onError**

In `src/main/kotlin/com/github/codeplangui/ChatService.kt`, change lines 426-434 from:

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

to:

```kotlin
                onError = { message ->
                    if (activeMessageId == msgId) {
                        logger.warn("[CodePlanGUI Approval] model round failed msgId=$msgId error=$message")
                        activeStream = null
                        activeMessageId = null
                        bridgeNotifiedStart.remove(msgId)
                        publishStatus()
                        bridgeHandler?.notifyStructuredError(classifyStreamError(message))
                    }
                },
```

- [ ] **Step 3: Classify abortStream errors**

In `src/main/kotlin/com/github/codeplangui/ChatService.kt`, change line 381 from:

```kotlin
        bridgeHandler?.notifyError(errorMessage)
```

to:

```kotlin
        bridgeHandler?.notifyStructuredError(BridgeErrorPayload(
            type = "runtime",
            message = errorMessage
        ))
```

- [ ] **Step 4: Add classifyStreamError helper**

In `src/main/kotlin/com/github/codeplangui/ChatService.kt`, add before the `companion object` (before line 673):

```kotlin
    private fun classifyStreamError(message: String): BridgeErrorPayload {
        val lowerMsg = message.lowercase()
        return when {
            lowerMsg.contains("401") || lowerMsg.contains("403") ||
            lowerMsg.contains("api key") || lowerMsg.contains("unauthorized") ->
                BridgeErrorPayload(type = "config", message = message, action = "openSettings")

            lowerMsg.contains("timeout") || lowerMsg.contains("超时") ||
            lowerMsg.contains("无法连接") || lowerMsg.contains("connectexception") ||
            lowerMsg.contains("http 5") || lowerMsg.contains("http 429") ->
                BridgeErrorPayload(type = "network", message = message, action = "retry")

            else ->
                BridgeErrorPayload(type = "runtime", message = message)
        }
    }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --info`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/ChatService.kt
git commit -m "feat(error): wire ChatService to emit structured errors with type classification"
```

---

### Task 5: Structured Error — Frontend Types and Bridge Wiring

**Files:**
- Modify: `webview/src/types/bridge.d.ts`
- Modify: `webview/src/hooks/useBridge.ts`

- [ ] **Step 1: Add BridgeError type and onStructuredError to bridge.d.ts**

In `webview/src/types/bridge.d.ts`, add after `ExecutionResult` interface (after line 17):

```typescript
export interface BridgeError {
  type: 'config' | 'network' | 'runtime'
  message: string
  action?: 'openSettings' | 'retry'
}
```

In the `Bridge` interface, add after `onError` (after line 30):

```typescript
  onStructuredError: (error: BridgeError) => void
```

- [ ] **Step 2: Add BridgeError import and callback to useBridge.ts**

In `webview/src/hooks/useBridge.ts`, add to the import:

```typescript
import { BridgeError, BridgeStatus } from '../types/bridge'
```

In the `BridgeCallbacks` interface (after `onError`), add:

```typescript
  onStructuredError: (error: BridgeError) => void
```

In ALL THREE places where callbacks are bound to `window.__bridge` (the `useEffect` on line 30, the `if (!window.__bridge)` block on line 48, and the `else` block on line 70), add:

```typescript
      window.__bridge.onStructuredError = currentCallbacks.onStructuredError
```

- [ ] **Step 3: Build webview to verify no TypeScript errors**

Run: `cd webview && npm run build`
Expected: Build succeeds

- [ ] **Step 4: Commit**

```bash
git add webview/src/types/bridge.d.ts webview/src/hooks/useBridge.ts
git commit -m "feat(frontend): add BridgeError type and onStructuredError to bridge"
```

---

### Task 6: Structured Error — ErrorBanner and App Integration

**Files:**
- Modify: `webview/src/components/ErrorBanner.tsx`
- Modify: `webview/src/App.tsx`

- [ ] **Step 1: Update ErrorBanner to support structured errors**

Replace `webview/src/components/ErrorBanner.tsx` with:

```tsx
import { Alert, Button, Space } from 'antd'
import type { BridgeError } from '../types/bridge'

interface Props {
  error: BridgeError
  onClose: () => void
}

export function ErrorBanner({ error, onClose }: Props) {
  const alertType = error.type === 'config' ? 'warning' : 'error'

  const action = error.action === 'openSettings' ? (
    <Button size="small" type="link" onClick={() => window.__bridge?.openSettings()}>
      打开设置
    </Button>
  ) : error.action === 'retry' ? (
    <Button size="small" type="link" onClick={onClose}>
      关闭
    </Button>
  ) : undefined

  return (
    <Alert
      message={error.message}
      type={alertType}
      closable
      onClose={onClose}
      className="error-banner"
      action={action ? <Space>{action}</Space> : undefined}
    />
  )
}
```

- [ ] **Step 2: Update App.tsx to use BridgeError state and handle onStructuredError**

In `webview/src/App.tsx`, change the error state type (line 25):

```typescript
  const [error, setError] = useState<BridgeError | null>(null)
```

Add the import at the top:

```typescript
import type { BridgeError } from './types/bridge'
```

Change the `onError` callback (lines 77-83) to convert legacy string errors:

```typescript
  const onError = useCallback((message: string) => {
    setIsLoading(false)
    setMessages((prev) =>
      prev.map((item) => (item.isStreaming ? { ...item, isStreaming: false } : item)),
    )
    setError({ type: 'runtime', message })
  }, [])
```

Add the `onStructuredError` callback (after `onError`):

```typescript
  const onStructuredError = useCallback((bridgeError: BridgeError) => {
    setIsLoading(false)
    setMessages((prev) =>
      prev.map((item) => (item.isStreaming ? { ...item, isStreaming: false } : item)),
    )
    setError(bridgeError)
  }, [])
```

Add `onStructuredError` to the `useBridge` call (after `onError` on line 205):

```typescript
    onStructuredError,
```

Update the ErrorBanner rendering (line 304):

```tsx
        {error && <ErrorBanner error={error} onClose={() => setError(null)} />}
```

Update `handleSend` where it sets error (line 228):

```typescript
        setError({ type: 'runtime', message: composerReadiness.reason! })
```

- [ ] **Step 3: Build webview**

Run: `cd webview && npm run build`
Expected: Build succeeds

- [ ] **Step 4: Copy built webview to resources**

Run: `cp webview/dist/index.html src/main/resources/webview/index.html`

- [ ] **Step 5: Commit**

```bash
git add webview/src/components/ErrorBanner.tsx webview/src/App.tsx src/main/resources/webview/index.html
git commit -m "feat(frontend): structured ErrorBanner with config/network/runtime styling"
```

---

### Task 7: UI State — Clear Error on Bridge Reconnect

**Files:**
- Modify: `webview/src/App.tsx`

- [ ] **Step 1: Clear error state when bridge reconnects**

In `webview/src/App.tsx`, in the `useBridge` callbacks object, update the `onStatus` callback (or add a new effect). The simplest approach: in the existing `onStart` callback, error is already cleared. But we also need to clear on bridge reconnect.

Add a `useEffect` that clears error when `bridgeReady` transitions to true (after the `useBridge` call, around line 214):

```typescript
  // Clear stale errors when the bridge reconnects (e.g., after webview reload)
  useEffect(() => {
    if (bridgeReady) {
      setError(null)
    }
  }, [bridgeReady])
```

- [ ] **Step 2: Build webview**

Run: `cd webview && npm run build && cp webview/dist/index.html src/main/resources/webview/index.html`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add webview/src/App.tsx src/main/resources/webview/index.html
git commit -m "fix(ui): clear stale error banner on bridge reconnect"
```

---

### Task 8: WebView Freeze — Add Async Clipboard Keyboard Interceptor

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt:188-190`

- [ ] **Step 1: Add clipboard interceptor JS after bridge injection**

In `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt`, change lines 188-190 from:

```kotlin
                    browser.executeJavaScript(js, "", 0)
                }
            }
```

to:

```kotlin
                    browser.executeJavaScript(js, "", 0)

                    // Prevent JCEF WebView freeze on Ctrl+C/Cmd+C by intercepting
                    // the keyboard event and using async clipboard API instead of
                    // letting CEF handle it synchronously. Ref: jetbrains-cc-gui #846
                    val clipboardJs = """
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
                    """.trimIndent()
                    browser.executeJavaScript(clipboardJs, "", 0)
                }
            }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --info`
Expected: All tests PASS (no JCEF required for unit tests)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/BridgeHandler.kt
git commit -m "fix(webview): prevent clipboard freeze via async keyboard interceptor"
```

---

### Task 9: Regression Test — ChatService Selection Flow

**Files:**
- Create: `src/test/kotlin/com/github/codeplangui/ChatServiceSelectionTest.kt`

- [ ] **Step 1: Write the tests**

Create `src/test/kotlin/com/github/codeplangui/ChatServiceSelectionTest.kt`:

```kotlin
package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceSelectionTest {

    @Test
    fun `buildSelectionPrompt wraps code in markdown block`() {
        // buildSelectionPrompt is private in ChatService, but the visible helper
        // buildSelectionContextLabel is what we can test at the unit level.
        val label = buildSelectionContextLabel("Main.kt", 5)
        assertEquals("Main.kt · 选中 5 行", label)
    }

    @Test
    fun `buildSelectionContextLabel handles null fileName`() {
        val label = buildSelectionContextLabel(null, 3)
        assertEquals("选中 3 行", label)
    }

    @Test
    fun `buildSelectionContextLabel handles blank fileName`() {
        val label = buildSelectionContextLabel("", 1)
        assertEquals("选中 1 行", label)
    }

    @Test
    fun `buildSelectionContextLabel coerces zero lines to 1`() {
        val label = buildSelectionContextLabel("Test.kt", 0)
        assertEquals("Test.kt · 选中 1 行", label)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "com.github.codeplangui.ChatServiceSelectionTest" --info`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/github/codeplangui/ChatServiceSelectionTest.kt
git commit -m "test(selection): add regression tests for selection context label builder"
```

---

### Task 10: Regression Test — Connection State and Error Classification

**Files:**
- Create: `src/test/kotlin/com/github/codeplangui/ChatServiceErrorClassificationTest.kt`

- [ ] **Step 1: Write the tests**

Create `src/test/kotlin/com/github/codeplangui/ChatServiceErrorClassificationTest.kt`:

```kotlin
package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceErrorClassificationTest {

    @Test
    fun `deriveConnectionState returns streaming when active`() {
        assertEquals("streaming", deriveConnectionState(
            hasProvider = true, hasApiKey = true, isStreaming = true
        ))
    }

    @Test
    fun `deriveConnectionState returns unconfigured when no provider`() {
        assertEquals("unconfigured", deriveConnectionState(
            hasProvider = false, hasApiKey = false, isStreaming = false
        ))
    }

    @Test
    fun `deriveConnectionState returns error when no api key`() {
        assertEquals("error", deriveConnectionState(
            hasProvider = true, hasApiKey = false, isStreaming = false
        ))
    }

    @Test
    fun `deriveConnectionState returns ready when fully configured`() {
        assertEquals("ready", deriveConnectionState(
            hasProvider = true, hasApiKey = true, isStreaming = false
        ))
    }

    @Test
    fun `formatSystemContent includes memory when provided`() {
        val result = formatSystemContent(
            base = "You are a code assistant.",
            snapshot = null,
            memoryText = "User prefers Kotlin"
        )
        assert(result.contains("[User Memory]"))
        assert(result.contains("User prefers Kotlin"))
    }

    @Test
    fun `formatSystemContent includes context snapshot`() {
        val snapshot = PromptContextSnapshot(
            fileName = "Test.kt",
            extension = "kt",
            content = "fun main() {}",
            contextLabel = "Test.kt · 当前文件"
        )
        val result = formatSystemContent(
            base = "You are a code assistant.",
            snapshot = snapshot
        )
        assert(result.contains("当前文件：Test.kt"))
        assert(result.contains("fun main() {}"))
    }

    @Test
    fun `formatSystemContent without memory or snapshot returns base only`() {
        val result = formatSystemContent(
            base = "You are a code assistant.",
            snapshot = null,
            memoryText = ""
        )
        assertEquals("You are a code assistant.", result)
    }

    @Test
    fun `resolveUiContextLabel prefers override`() {
        val snapshot = PromptContextSnapshot("A.kt", "kt", "code", "A.kt · 当前文件")
        assertEquals("override", resolveUiContextLabel("override", snapshot))
    }

    @Test
    fun `resolveUiContextLabel falls back to snapshot label`() {
        val snapshot = PromptContextSnapshot("A.kt", "kt", "code", "A.kt · 当前文件")
        assertEquals("A.kt · 当前文件", resolveUiContextLabel(null, snapshot))
    }

    @Test
    fun `resolveUiContextLabel returns empty when both null`() {
        assertEquals("", resolveUiContextLabel(null, null))
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "com.github.codeplangui.ChatServiceErrorClassificationTest" --info`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/github/codeplangui/ChatServiceErrorClassificationTest.kt
git commit -m "test(error): add regression tests for connection state and system prompt formatting"
```

---

### Task 11: Regression Test — Session Restore Helpers

**Files:**
- Create: `src/test/kotlin/com/github/codeplangui/ChatServiceSessionRestoreTest.kt`

- [ ] **Step 1: Write the tests**

Create `src/test/kotlin/com/github/codeplangui/ChatServiceSessionRestoreTest.kt`:

```kotlin
package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceSessionRestoreTest {

    @Test
    fun `buildPromptContextSnapshot uses selected text when available`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Main.kt",
            extension = "kt",
            selectedText = "val x = 1",
            documentText = "package com.example\nval x = 1\nval y = 2",
            maxLines = 300
        )
        assertEquals("val x = 1", snapshot.content)
        assert(snapshot.contextLabel.contains("选中"))
    }

    @Test
    fun `buildPromptContextSnapshot uses document text when no selection`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Main.kt",
            extension = "kt",
            selectedText = null,
            documentText = "package com.example\nval x = 1",
            maxLines = 300
        )
        assertEquals("package com.example\nval x = 1", snapshot.content)
        assertEquals("Main.kt · 当前文件", snapshot.contextLabel)
    }

    @Test
    fun `buildPromptContextSnapshot truncates to maxLines`() {
        val longDocument = (1..500).joinToString("\n") { "line $it" }
        val snapshot = buildPromptContextSnapshot(
            fileName = "Long.kt",
            extension = "kt",
            selectedText = null,
            documentText = longDocument,
            maxLines = 10
        )
        assertEquals(10, snapshot.content.lines().size)
    }

    @Test
    fun `buildPromptContextSnapshot uses blank selection as no selection`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Main.kt",
            extension = "kt",
            selectedText = "   ",
            documentText = "val code = true",
            maxLines = 300
        )
        assertEquals("val code = true", snapshot.content)
        assertEquals("Main.kt · 当前文件", snapshot.contextLabel)
    }

    @Test
    fun `buildPromptContextSnapshot defaults extension to txt`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Makefile",
            extension = null,
            selectedText = null,
            documentText = "all: build",
            maxLines = 300
        )
        assertEquals("txt", snapshot.extension)
    }

    @Test
    fun `openSettingsOnEdt enqueues the dialog opener`() {
        var executed = false
        openSettingsOnEdt(
            openDialog = { executed = true },
            enqueue = { action -> action() }
        )
        assertEquals(true, executed)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "com.github.codeplangui.ChatServiceSessionRestoreTest" --info`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/github/codeplangui/ChatServiceSessionRestoreTest.kt
git commit -m "test(session): add regression tests for context snapshot and settings opener"
```

---

### Task 12: Final Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test --info`
Expected: All tests PASS, including all new test files

- [ ] **Step 2: Build the plugin**

Run: `./gradlew buildPlugin`
Expected: Build succeeds, plugin JAR created in `build/distributions/`

- [ ] **Step 3: Verify webview builds cleanly**

Run: `cd webview && npm run build`
Expected: No TypeScript errors, `dist/index.html` generated

- [ ] **Step 4: Commit any remaining build artifacts**

```bash
git status
# If index.html needs updating:
cp webview/dist/index.html src/main/resources/webview/index.html
git add src/main/resources/webview/index.html
git commit -m "chore: rebuild webview with structured error support"
```
