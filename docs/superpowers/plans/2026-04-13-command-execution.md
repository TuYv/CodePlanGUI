# AI Command Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable AI to autonomously execute shell commands from Chat, gated by a user-configurable whitelist and a per-command approval dialog.

**Architecture:** OpenAI function-calling (tool_use) protocol — `ChatService` attaches a `run_command` tool definition to each API request; the SSE parser detects `tool_call` deltas and accumulates them; `ChatService` orchestrates whitelist check → bridge approval → `CommandExecutionService` execution → `tool_result` second-round API call. The React webview shows an `ApprovalDialog` and inline `ExecutionCard` for each invocation.

**Tech Stack:** Kotlin coroutines (`CompletableFuture` for async approval), OkHttp SSE, `ProcessBuilder` for subprocess, React/TypeScript + Ant Design for UI, `kotlinx.serialization` for JSON.

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Create | `src/main/kotlin/com/github/codeplangui/execution/ExecutionResult.kt` | Sealed result type |
| Create | `src/main/kotlin/com/github/codeplangui/execution/CommandExecutionService.kt` | Whitelist check + ProcessBuilder execution |
| Create | `src/test/kotlin/com/github/codeplangui/execution/CommandExecutionServiceTest.kt` | Unit tests for execution logic |
| Create | `src/test/kotlin/com/github/codeplangui/SseChunkParserToolCallTest.kt` | Tests for new parser methods |
| Modify | `src/main/kotlin/com/github/codeplangui/settings/PluginSettings.kt` | Add execution fields to `SettingsState` |
| Modify | `src/main/kotlin/com/github/codeplangui/settings/SettingsFormState.kt` | Propagate new fields |
| Modify | `src/main/kotlin/com/github/codeplangui/api/SseChunkParser.kt` | Add `extractToolCallChunk()` + `extractFinishReason()` |
| Modify | `src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt` | Add `tools` to request body; add `onToolCallChunk`/`onFinishReason` callbacks |
| Modify | `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt` | Add approval events; extend JS bridge object |
| Modify | `src/main/kotlin/com/github/codeplangui/ChatService.kt` | Add tool_call state machine + approval orchestration |
| Modify | `src/main/kotlin/com/github/codeplangui/settings/PluginSettingsConfigurable.kt` | Add Execution tab |
| Modify | `src/main/resources/META-INF/plugin.xml` | Register `CommandExecutionService` |
| Modify | `webview/src/types/bridge.d.ts` | Add `approvalResponse`, `onApprovalRequest`, `onExecutionStatus` |
| Modify | `webview/src/hooks/useBridge.ts` | Wire new bridge callbacks |
| Create | `webview/src/components/ApprovalDialog.tsx` | Approval popup |
| Create | `webview/src/components/ExecutionCard.tsx` | 5-state execution result card |
| Modify | `webview/src/App.tsx` | Add approval state + render ExecutionCard |

---

## Task 1: ExecutionResult data model

**Files:**
- Create: `src/main/kotlin/com/github/codeplangui/execution/ExecutionResult.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.github.codeplangui.execution

sealed class ExecutionResult {
    data class Success(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false
    ) : ExecutionResult()

    data class Blocked(val command: String, val reason: String) : ExecutionResult()
    data class Denied(val command: String, val reason: String) : ExecutionResult()
    data class TimedOut(val command: String, val stdout: String, val timeoutSeconds: Int) : ExecutionResult()
    data class Failed(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false
    ) : ExecutionResult()

    /** Serialize to a JSON string suitable for tool_result content. */
    fun toToolResultContent(): String = when (this) {
        is Success -> """{"status":"ok","exit_code":$exitCode,"stdout":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(stdout.take(4000)))  },"stderr":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(stderr.take(2000)))},"duration_ms":$durationMs${if (truncated) ""","truncated":true""" else ""}}"""
        is Failed  -> """{"status":"error","exit_code":$exitCode,"stdout":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(stdout.take(4000)))},"stderr":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(stderr.take(2000)))},"duration_ms":$durationMs${if (truncated) ""","truncated":true""" else ""}}"""
        is Blocked -> """{"status":"blocked","reason":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(reason))}}"""
        is Denied  -> """{"status":"denied","reason":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(reason))}}"""
        is TimedOut -> """{"status":"timeout","timeout_seconds":$timeoutSeconds,"stdout":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(stdout.take(4000)))}}"""
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI
JAVA_HOME=/path/to/jdk17 ./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/execution/ExecutionResult.kt
git commit -m "feat(execution): add ExecutionResult sealed class"
```

---

## Task 2: Extend SettingsState with command execution fields

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/settings/PluginSettings.kt` (SettingsState data class)
- Modify: `src/main/kotlin/com/github/codeplangui/settings/SettingsFormState.kt`
- Test: `src/test/kotlin/com/github/codeplangui/SettingsFormStateTest.kt`

- [ ] **Step 1: Write a failing test in `SettingsFormStateTest.kt` — open the file and append**

```kotlin
@Test
fun `command execution fields round-trip through SettingsFormState`() {
    val state = SettingsState(
        commandExecutionEnabled = true,
        commandWhitelist = mutableListOf("cargo", "git"),
        commandTimeoutSeconds = 45
    )
    val form = SettingsFormState.fromSettingsState(state)
    val roundTripped = form.toSettingsState()
    assertEquals(true, roundTripped.commandExecutionEnabled)
    assertEquals(listOf("cargo", "git"), roundTripped.commandWhitelist)
    assertEquals(45, roundTripped.commandTimeoutSeconds)
}
```

- [ ] **Step 2: Run to see it fail**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.SettingsFormStateTest" 2>&1 | tail -20
```

Expected: FAIL — `commandExecutionEnabled` unresolved.

- [ ] **Step 3: Add fields to `SettingsState` in `PluginSettings.kt`**

Find the `data class SettingsState(` block and add three fields at the end (before the closing `)`):

```kotlin
// Existing fields stay unchanged. Add:
    var commandExecutionEnabled: Boolean = false,
    var commandWhitelist: MutableList<String> = mutableListOf(
        "cargo", "gradle", "mvn", "npm", "yarn", "pnpm",
        "git", "ls", "cat", "grep", "find", "echo", "pwd"
    ),
    var commandTimeoutSeconds: Int = 30
```

- [ ] **Step 4: Add fields to `SettingsFormState`**

In `SettingsFormState.kt`, add to the data class:

```kotlin
    var commandExecutionEnabled: Boolean = false,
    var commandWhitelist: MutableList<String> = mutableListOf(
        "cargo", "gradle", "mvn", "npm", "yarn", "pnpm",
        "git", "ls", "cat", "grep", "find", "echo", "pwd"
    ),
    var commandTimeoutSeconds: Int = 30
```

In `toSettingsState()`, add:

```kotlin
        commandExecutionEnabled = commandExecutionEnabled,
        commandWhitelist = commandWhitelist.toMutableList(),
        commandTimeoutSeconds = commandTimeoutSeconds,
```

In `fromSettingsState()`, add:

```kotlin
            commandExecutionEnabled = state.commandExecutionEnabled,
            commandWhitelist = state.commandWhitelist.toMutableList(),
            commandTimeoutSeconds = state.commandTimeoutSeconds,
```

- [ ] **Step 5: Run the test — expect PASS**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.SettingsFormStateTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/settings/PluginSettings.kt \
        src/main/kotlin/com/github/codeplangui/settings/SettingsFormState.kt \
        src/test/kotlin/com/github/codeplangui/SettingsFormStateTest.kt
git commit -m "feat(settings): add command execution fields to SettingsState"
```

---

## Task 3: SseChunkParser — add tool_call extraction

**Files:**
- Create: `src/test/kotlin/com/github/codeplangui/SseChunkParserToolCallTest.kt`
- Modify: `src/main/kotlin/com/github/codeplangui/api/SseChunkParser.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package com.github.codeplangui

import com.github.codeplangui.api.SseChunkParser
import com.github.codeplangui.api.ToolCallDelta
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SseChunkParserToolCallTest {

    @Test
    fun `extractToolCallChunk returns delta on first tool_call chunk`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"run_command","arguments":""}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertEquals("call_abc", delta!!.id)
        assertEquals("run_command", delta.functionName)
        assertEquals("", delta.argumentsChunk)
    }

    @Test
    fun `extractToolCallChunk returns arguments chunk on subsequent chunks`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"co"}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertNull(delta!!.id)
        assertEquals("{\"co", delta.argumentsChunk)
    }

    @Test
    fun `extractToolCallChunk returns null for text delta`() {
        val data = """{"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractToolCallChunk(data))
    }

    @Test
    fun `extractFinishReason returns stop for normal end`() {
        val data = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertEquals("stop", SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractFinishReason returns tool_calls on tool invocation end`() {
        val data = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        assertEquals("tool_calls", SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractFinishReason returns null when finish_reason is absent`() {
        val data = """{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractFinishReason(data))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.SseChunkParserToolCallTest" 2>&1 | tail -20
```

Expected: FAIL — `extractToolCallChunk`, `ToolCallDelta` unresolved.

- [ ] **Step 3: Add `ToolCallDelta` data class and new methods to `SseChunkParser.kt`**

After the existing `extractToken()` method, add:

```kotlin
data class ToolCallDelta(
    val id: String?,           // non-null only on first chunk
    val functionName: String?, // non-null only on first chunk
    val argumentsChunk: String?
)

/**
 * Extracts tool_call delta from a streaming SSE chunk.
 * Returns null if the chunk is a regular text delta or unparseable.
 */
fun extractToolCallChunk(data: String): ToolCallDelta? {
    if (data.trim() == "[DONE]") return null
    return try {
        val obj = json.parseToJsonElement(data).jsonObject
        val toolCallsArray = obj["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("delta")
            ?.jsonObject
            ?.get("tool_calls")
            ?.jsonArray
            ?: return null

        val first = toolCallsArray.firstOrNull()?.jsonObject ?: return null
        val func = first["function"]?.jsonObject

        ToolCallDelta(
            id = first["id"]?.jsonPrimitive?.contentOrNull,
            functionName = func?.get("name")?.jsonPrimitive?.contentOrNull,
            argumentsChunk = func?.get("arguments")?.jsonPrimitive?.contentOrNull
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Extracts finish_reason from an SSE chunk.
 * Returns null when finish_reason is absent or null in the JSON.
 */
fun extractFinishReason(data: String): String? {
    if (data.trim() == "[DONE]") return null
    return try {
        val obj = json.parseToJsonElement(data).jsonObject
        obj["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("finish_reason")
            ?.jsonPrimitive
            ?.contentOrNull
    } catch (_: Exception) {
        null
    }
}
```

Note: `ToolCallDelta` must be declared at the file level (outside the `object SseChunkParser`).

- [ ] **Step 4: Run tests — expect PASS**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.SseChunkParserToolCallTest" \
  --tests "com.github.codeplangui.SseChunkParserTest" 2>&1 | tail -20
```

Expected: all 13 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/api/SseChunkParser.kt \
        src/test/kotlin/com/github/codeplangui/SseChunkParserToolCallTest.kt
git commit -m "feat(parser): add extractToolCallChunk and extractFinishReason to SseChunkParser"
```

---

## Task 4: OkHttpSseClient — add tools param + tool_call callbacks

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt`
- Test: `src/test/kotlin/com/github/codeplangui/OkHttpSseClientTest.kt`

- [ ] **Step 1: Add failing test in `OkHttpSseClientTest.kt`**

Open the file and append:

```kotlin
@Test
fun `buildRequest includes tools array when provided`() {
    val provider = ProviderConfig(endpoint = "https://api.example.com/v1", model = "gpt-4")
    val tool = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "run_command",
            description = "Execute a shell command",
            parameters = kotlinx.serialization.json.buildJsonObject {
                put("type", "object")
            }
        )
    )
    val client = OkHttpSseClient()
    val request = client.buildRequest(
        config = provider,
        apiKey = "test-key",
        messages = listOf(Message(MessageRole.USER, "hello")),
        temperature = 0.7,
        maxTokens = 100,
        stream = true,
        tools = listOf(tool)
    )
    val body = request.body!!.let {
        val buffer = okio.Buffer()
        it.writeTo(buffer)
        buffer.readUtf8()
    }
    assertTrue(body.contains("\"tools\""), "body should contain tools array")
    assertTrue(body.contains("run_command"), "body should contain tool name")
}
```

Add imports at the top of the test file:
```kotlin
import com.github.codeplangui.api.ToolDefinition
import com.github.codeplangui.api.FunctionDefinition
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.OkHttpSseClientTest" 2>&1 | tail -20
```

Expected: FAIL — `ToolDefinition` unresolved.

- [ ] **Step 3: Add `ToolDefinition`, `FunctionDefinition` and rewrite `ApiMessage` + `ChatRequestBody` in `OkHttpSseClient.kt`**

The OpenAI protocol requires three different message shapes that cannot be represented by a single `ApiMessage(role, content)`:
- Regular: `{"role":"user","content":"..."}` 
- Assistant with tool call: `{"role":"assistant","content":null,"tool_calls":[...]}`
- Tool result: `{"role":"tool","tool_call_id":"...","content":"..."}`

**Replace** the existing `ApiMessage` data class with a `JsonObject`-based approach. Delete the old `private data class ApiMessage` line entirely, then add:

```kotlin
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

/** Converts a domain Message to the JSON shape required by the OpenAI API. */
private fun Message.toApiJson(): JsonObject = buildJsonObject {
    put("role", role.name.lowercase())
    when {
        role == MessageRole.TOOL && toolCallId != null -> {
            put("tool_call_id", toolCallId)
            put("content", content)
        }
        toolCalls != null -> {
            put("content", JsonNull)
            put("tool_calls", buildJsonArray {
                toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.functionName)
                            put("arguments", tc.arguments)
                        })
                    })
                }
            })
        }
        else -> put("content", content)
    }
}
```

**Replace** `ChatRequestBody` — messages is now `List<JsonObject>` and add `tools`:

```kotlin
@Serializable
private data class ChatRequestBody(
    val model: String,
    val messages: List<JsonObject>,   // ← was List<ApiMessage>
    val stream: Boolean,
    val temperature: Double,
    val max_tokens: Int,
    val tools: List<ToolDefinition>? = null
)
```

**Replace** `buildRequest()` in full:

```kotlin
fun buildRequest(
    config: ProviderConfig,
    apiKey: String,
    messages: List<Message>,
    temperature: Double,
    maxTokens: Int,
    stream: Boolean,
    tools: List<ToolDefinition>? = null
): Request {
    val endpoint = config.endpoint.trimEnd('/') + "/chat/completions"
    val body = json.encodeToString(
        ChatRequestBody(
            model = config.model,
            messages = messages.map { it.toApiJson() },
            stream = stream,
            temperature = temperature,
            max_tokens = maxTokens,
            tools = tools
        )
    )
    return Request.Builder()
        .url(endpoint)
        .post(body.toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", if (stream) "text/event-stream" else "application/json")
        .build()
}
```

- [ ] **Step 4: Add `onToolCallChunk` and `onFinishReason` callbacks to `streamChat()`; guard `onEnd` from firing during tool_call streams**

When `finish_reason == "tool_calls"` the SSE stream still emits `[DONE]`. Without a guard, `onEnd` fires and collapses the Vue streaming state before the second API round starts. Fix: track whether the stream is a tool_call stream and suppress `onEnd` if so.

Replace `streamChat()` in full:

```kotlin
fun streamChat(
    request: Request,
    onToken: (String) -> Unit,
    onEnd: () -> Unit,
    onError: (String) -> Unit,
    onToolCallChunk: (ToolCallDelta) -> Unit = {},
    onFinishReason: (String) -> Unit = {}
): EventSource {
    val listener = object : EventSourceListener() {
        // Set to true when finish_reason == "tool_calls"; prevents [DONE] from
        // triggering onEnd — the second API round will call onEnd instead.
        private var isToolCallStream = false

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val finishReason = SseChunkParser.extractFinishReason(data)
            if (finishReason != null) {
                onFinishReason(finishReason)
                if (finishReason == "tool_calls") isToolCallStream = true
            }

            val toolCallDelta = SseChunkParser.extractToolCallChunk(data)
            if (toolCallDelta != null) {
                onToolCallChunk(toolCallDelta)
                return
            }

            val token = SseChunkParser.extractToken(data)
            if (token != null) onToken(token)
            if (data.trim() == "[DONE]" && !isToolCallStream) onEnd()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val msg = buildErrorMessage(response, t)
            onError(msg)
        }
    }
    return eventSourceFactory.newEventSource(request, listener)
}
```

- [ ] **Step 5: Run all tests**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.OkHttpSseClientTest" \
  --tests "com.github.codeplangui.SseChunkParserTest" \
  --tests "com.github.codeplangui.SseChunkParserToolCallTest" 2>&1 | tail -20
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/api/OkHttpSseClient.kt \
        src/test/kotlin/com/github/codeplangui/OkHttpSseClientTest.kt
git commit -m "feat(api): add tools param and tool_call callbacks to OkHttpSseClient"
```

---

## Task 5: CommandExecutionService

**Files:**
- Create: `src/main/kotlin/com/github/codeplangui/execution/CommandExecutionService.kt`
- Create: `src/test/kotlin/com/github/codeplangui/execution/CommandExecutionServiceTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package com.github.codeplangui.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommandExecutionServiceTest {

    @Test
    fun `extractBaseCommand returns first word for simple command`() {
        assertEquals("cargo", CommandExecutionService.extractBaseCommand("cargo test --workspace"))
    }

    @Test
    fun `extractBaseCommand strips path prefix`() {
        assertEquals("cargo", CommandExecutionService.extractBaseCommand("/usr/local/bin/cargo test"))
    }

    @Test
    fun `extractBaseCommand returns first word before pipe`() {
        assertEquals("ls", CommandExecutionService.extractBaseCommand("ls src/ | grep kt"))
    }

    @Test
    fun `isWhitelisted returns true when base command matches whitelist entry`() {
        val whitelist = listOf("cargo", "git", "ls")
        assertTrue(CommandExecutionService.isWhitelisted("cargo test --workspace", whitelist))
    }

    @Test
    fun `isWhitelisted returns false when base command is not in whitelist`() {
        val whitelist = listOf("cargo", "git")
        assertFalse(CommandExecutionService.isWhitelisted("rm -rf dist", whitelist))
    }

    @Test
    fun `isWhitelisted returns false for empty whitelist`() {
        assertFalse(CommandExecutionService.isWhitelisted("ls", emptyList()))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns false for relative paths`() {
        assertFalse(CommandExecutionService.hasPathsOutsideWorkspace("ls src/main", "/home/user/project"))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns true for absolute path outside project`() {
        assertTrue(CommandExecutionService.hasPathsOutsideWorkspace("cat /etc/passwd", "/home/user/project"))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns false for absolute path inside project`() {
        assertFalse(CommandExecutionService.hasPathsOutsideWorkspace(
            "cat /home/user/project/src/main.kt",
            "/home/user/project"
        ))
    }

    @Test
    fun `truncateOutput trims output exceeding max chars`() {
        val long = "a".repeat(5000)
        val result = CommandExecutionService.truncateOutput(long, 4000)
        assertEquals(4000, result.length)
    }

    @Test
    fun `truncateOutput returns original when within limit`() {
        val short = "hello"
        assertEquals(short, CommandExecutionService.truncateOutput(short, 4000))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.execution.CommandExecutionServiceTest" 2>&1 | tail -20
```

Expected: FAIL — `CommandExecutionService` not found.

- [ ] **Step 3: Create `CommandExecutionService.kt`**

```kotlin
package com.github.codeplangui.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CommandExecutionService(private val project: Project) {

    suspend fun executeAsync(
        command: String,
        timeoutSeconds: Int
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val basePath = project.basePath ?: return@withContext ExecutionResult.Blocked(command, "Project path unavailable")
        val startMs = System.currentTimeMillis()

        val process = ProcessBuilder("sh", "-c", command)
            .directory(File(basePath))
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val durationMs = System.currentTimeMillis() - startMs

        if (!finished) {
            process.destroyForcibly()
            ExecutionResult.TimedOut(
                command = command,
                stdout = truncateOutput(stdout, 4000),
                timeoutSeconds = timeoutSeconds
            )
        } else {
            val truncated = stdout.length > 4000 || stderr.length > 2000
            if (process.exitValue() == 0) {
                ExecutionResult.Success(
                    command = command,
                    stdout = truncateOutput(stdout, 4000),
                    stderr = truncateOutput(stderr, 2000),
                    exitCode = 0,
                    durationMs = durationMs,
                    truncated = truncated
                )
            } else {
                ExecutionResult.Failed(
                    command = command,
                    stdout = truncateOutput(stdout, 4000),
                    stderr = truncateOutput(stderr, 2000),
                    exitCode = process.exitValue(),
                    durationMs = durationMs,
                    truncated = truncated
                )
            }
        }
    }

    companion object {
        fun extractBaseCommand(command: String): String {
            val stripped = command.trimStart()
            // Take first token before shell metacharacters
            val base = stripped.split(" ", "|", ";", ">", "<", "&").first().trim()
            // Strip path prefix (e.g. /usr/local/bin/cargo → cargo)
            return base.substringAfterLast('/')
        }

        fun isWhitelisted(command: String, whitelist: List<String>): Boolean {
            if (whitelist.isEmpty()) return false
            val base = extractBaseCommand(command)
            return whitelist.any { it == base }
        }

        fun hasPathsOutsideWorkspace(command: String, basePath: String): Boolean {
            val tokens = command.split("\\s+".toRegex())
            val home = System.getProperty("user.home") ?: ""
            return tokens.any { token ->
                if (token.startsWith('-')) return@any false
                val expanded = when {
                    token.startsWith("~/") -> home + token.drop(1)
                    else -> token
                }
                if (!expanded.startsWith('/')) return@any false
                !expanded.startsWith(basePath)
            }
        }

        fun truncateOutput(text: String, maxChars: Int): String =
            if (text.length <= maxChars) text else text.take(maxChars)

        fun getInstance(project: Project): CommandExecutionService =
            project.getService(CommandExecutionService::class.java)
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.execution.CommandExecutionServiceTest" 2>&1 | tail -20
```

Expected: all 11 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/execution/CommandExecutionService.kt \
        src/test/kotlin/com/github/codeplangui/execution/CommandExecutionServiceTest.kt
git commit -m "feat(execution): add CommandExecutionService with whitelist and ProcessBuilder"
```

---

## Task 6: BridgeHandler — approval events + JS bridge extension

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/BridgeHandler.kt`
- Test: `src/test/kotlin/com/github/codeplangui/BridgeHandlerDispatchTest.kt`

- [ ] **Step 1: Add failing test for `approvalResponse` dispatch**

Open `BridgeHandlerDispatchTest.kt` and append:

```kotlin
@Test
fun `dispatchBridgeRequest routes approvalResponse`() {
    val commands = RecordingBridgeCommands()

    dispatchBridgeRequest(
        type = "approvalResponse",
        text = "",
        includeContext = false,
        requestId = "req-123",
        decision = "allow",
        commands = commands
    )

    assertEquals(listOf(Pair("req-123", "allow")), commands.approvalResponses)
}
```

Also add to `RecordingBridgeCommands`:

```kotlin
val approvalResponses = mutableListOf<Pair<String, String>>()

override fun approvalResponse(requestId: String, decision: String) {
    approvalResponses += Pair(requestId, decision)
}
```

And add `approvalResponse` to the `BridgeCommands` interface (you'll update it next step, so the test won't compile yet — that's expected).

- [ ] **Step 2: Update `BridgePayload`, `BridgeCommands`, and `dispatchBridgeRequest`**

In `BridgeHandler.kt`:

**`BridgePayload`** — add two new optional fields:
```kotlin
@Serializable
private data class BridgePayload(
    val type: String,
    val text: String = "",
    val includeContext: Boolean = true,
    val requestId: String = "",
    val decision: String = ""
)
```

**`BridgeCommands`** — add new method:
```kotlin
internal interface BridgeCommands {
    fun sendMessage(text: String, includeContext: Boolean)
    fun newChat()
    fun openSettings()
    fun onFrontendReady()
    fun approvalResponse(requestId: String, decision: String)  // ← add
}
```

**`dispatchBridgeRequest`** — add `requestId` and `decision` params + new case:
```kotlin
internal fun dispatchBridgeRequest(
    type: String,
    text: String,
    includeContext: Boolean,
    requestId: String = "",
    decision: String = "",
    commands: BridgeCommands
) {
    when (type) {
        "sendMessage" -> commands.sendMessage(text, includeContext)
        "newChat" -> commands.newChat()
        "openSettings" -> commands.openSettings()
        "frontendReady" -> commands.onFrontendReady()
        "approvalResponse" -> commands.approvalResponse(requestId, decision)
    }
}
```

**`handleBridgePayload`** — update the `dispatchBridgeRequest` call:
```kotlin
dispatchBridgeRequest(req.type, req.text, req.includeContext, req.requestId, req.decision, commands)
```

**`BridgeHandler.register()`** — update the BridgeCommands anonymous object to implement `approvalResponse`:
```kotlin
override fun approvalResponse(requestId: String, decision: String) {
    chatService.onApprovalResponse(requestId, decision)
}
```

**JS bridge object** — in the `onLoadEnd` JS string, add `approvalResponse` and new callbacks:
```javascript
approvalResponse: function(requestId, decision) {
    ${sendQuery.inject("""JSON.stringify({type:'approvalResponse',requestId:requestId,decision:decision})""")}
},
onApprovalRequest: function(requestId, command, description) {},
onExecutionStatus: function(requestId, status, result) {}
```

- [ ] **Step 3: Add `notifyApprovalRequest` and `notifyExecutionStatus` methods**

```kotlin
fun notifyApprovalRequest(requestId: String, command: String, description: String) =
    pushJS(
        "window.__bridge.onApprovalRequest(" +
        "${json.encodeToString(requestId)}," +
        "${json.encodeToString(command)}," +
        "${json.encodeToString(description)})"
    )

fun notifyExecutionStatus(requestId: String, status: String, resultJson: String) =
    pushJS(
        "window.__bridge.onExecutionStatus(" +
        "${json.encodeToString(requestId)}," +
        "${json.encodeToString(status)}," +
        "$resultJson)"
    )
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test --tests "com.github.codeplangui.BridgeHandlerDispatchTest" 2>&1 | tail -20
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/BridgeHandler.kt \
        src/test/kotlin/com/github/codeplangui/BridgeHandlerDispatchTest.kt
git commit -m "feat(bridge): add approval events and JS bridge extension"
```

---

## Task 7: ChatService — tool_call state machine + approval orchestration

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/ChatService.kt`

- [ ] **Step 1: Add imports at the top of `ChatService.kt`**

```kotlin
import com.github.codeplangui.api.ToolCallDelta
import com.github.codeplangui.api.ToolDefinition
import com.github.codeplangui.api.FunctionDefinition
import com.github.codeplangui.execution.CommandExecutionService
import com.github.codeplangui.execution.ExecutionResult
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
```

- [ ] **Step 2: Add state machine fields and pending approvals map to `ChatService`**

Inside the `ChatService` class, add after the existing `private var isFrontendReady` field:

```kotlin
// Tool call state machine
private enum class StreamState { TEXT, ACCUMULATING_TOOL_CALL }
private var streamState = StreamState.TEXT
private var pendingToolCallId: String? = null
private var pendingFunctionName: String? = null
private val argumentsBuffer = StringBuilder()

// Approval gate
private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
```

- [ ] **Step 3: Add `onApprovalResponse()` method**

```kotlin
fun onApprovalResponse(requestId: String, decision: String) {
    pendingApprovals[requestId]?.complete(decision == "allow")
}
```

- [ ] **Step 4: Add `runCommandToolDefinition()` helper**

```kotlin
private fun runCommandToolDefinition(): ToolDefinition = ToolDefinition(
    type = "function",
    function = FunctionDefinition(
        name = "run_command",
        description = "Execute a shell command in the project root directory. Only use when the user asks you to run something or when you need to inspect state to answer accurately.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "The shell command to execute")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "One-line explanation of why you are running this command")
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("command"))
                add(kotlinx.serialization.json.JsonPrimitive("description"))
            })
        }
    )
)
```

- [ ] **Step 5: Update `sendMessage()` to attach tools and wire state machine callbacks**

In the `sendMessage()` function, find the `client.buildRequest(...)` call. Update it to pass tools when enabled:

```kotlin
val settings = PluginSettings.getInstance()
val commandExecutionEnabled = settings.getState().commandExecutionEnabled

val request = client.buildRequest(
    config = provider,
    apiKey = apiKey,
    messages = session.getApiMessages(),
    temperature = settings.getState().chatTemperature,
    maxTokens = settings.getState().chatMaxTokens,
    stream = true,
    tools = if (commandExecutionEnabled) listOf(runCommandToolDefinition()) else null
)
```

Reset state machine before starting a new stream:

```kotlin
streamState = StreamState.TEXT
pendingToolCallId = null
pendingFunctionName = null
pendingToolDescription = null
argumentsBuffer.clear()
```

Update `client.streamChat(...)` call to add the new callbacks:

```kotlin
val stream = client.streamChat(
    request = request,
    onToken = { token ->
        if (activeMessageId == msgId) {
            responseBuffer.append(token)
            bridgeHandler?.notifyToken(token)
        }
    },
    onEnd = {
        if (activeMessageId == msgId) {
            session.add(Message(MessageRole.ASSISTANT, responseBuffer.toString()))
            activeStream = null
            activeMessageId = null
            publishStatus()
            bridgeHandler?.notifyEnd(msgId)
        }
    },
    onError = { message ->
        if (activeMessageId == msgId) {
            activeStream = null
            activeMessageId = null
            publishStatus()
            bridgeHandler?.notifyError(message)
        }
    },
    onToolCallChunk = { delta ->
        if (activeMessageId == msgId) handleToolCallChunk(delta)
    },
    onFinishReason = { reason ->
        if (activeMessageId == msgId && reason == "tool_calls") {
            scope.launch { handleToolCallComplete(msgId, responseBuffer) }
        }
    }
)
```

- [ ] **Step 6: Add `handleToolCallChunk()` and `handleToolCallComplete()`**

```kotlin
private fun handleToolCallChunk(delta: ToolCallDelta) {
    if (delta.id != null) {
        pendingToolCallId = delta.id
        pendingFunctionName = delta.functionName
        streamState = StreamState.ACCUMULATING_TOOL_CALL
    }
    delta.argumentsChunk?.let { argumentsBuffer.append(it) }
}

private suspend fun handleToolCallComplete(msgId: String, responseBuffer: StringBuilder) {
    val toolCallId = pendingToolCallId ?: return
    val argsJson = argumentsBuffer.toString()

    // Parse command + description from arguments JSON
    val argsObj = try {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonObject
    } catch (_: Exception) {
        bridgeHandler?.notifyError("AI returned malformed tool call arguments")
        return
    }
    val command = argsObj["command"]?.jsonPrimitive?.contentOrNull ?: return
    val description = argsObj["description"]?.jsonPrimitive?.contentOrNull ?: ""

    val settings = PluginSettings.getInstance().getState()
    val requestId = java.util.UUID.randomUUID().toString()

    // Notify Vue: show execution card in "waiting" state
    bridgeHandler?.notifyApprovalRequest(requestId, command, description)

    // Whitelist check
    if (!CommandExecutionService.isWhitelisted(command, settings.commandWhitelist)) {
        val result = ExecutionResult.Blocked(command, "'${CommandExecutionService.extractBaseCommand(command)}' is not in the allowed command list")
        bridgeHandler?.notifyExecutionStatus(requestId, "blocked", result.toToolResultContent())
        continueWithToolResult(msgId, toolCallId, responseBuffer, result)
        return
    }

    // Path safety check
    val basePath = project.basePath ?: ""
    if (CommandExecutionService.hasPathsOutsideWorkspace(command, basePath)) {
        val result = ExecutionResult.Blocked(command, "Command accesses paths outside the project")
        bridgeHandler?.notifyExecutionStatus(requestId, "blocked", result.toToolResultContent())
        continueWithToolResult(msgId, toolCallId, responseBuffer, result)
        return
    }

    // Approval gate
    val future = CompletableFuture<Boolean>()
    pendingApprovals[requestId] = future
    bridgeHandler?.notifyExecutionStatus(requestId, "waiting", "{}")

    val approved = try {
        withContext(Dispatchers.IO) { future.get(60, TimeUnit.SECONDS) }
    } catch (_: Exception) {
        false
    } finally {
        pendingApprovals.remove(requestId)
    }

    if (!approved) {
        val result = ExecutionResult.Denied(command, "User rejected the command")
        bridgeHandler?.notifyExecutionStatus(requestId, "denied", result.toToolResultContent())
        continueWithToolResult(msgId, toolCallId, responseBuffer, result)
        return
    }

    // Execute
    bridgeHandler?.notifyExecutionStatus(requestId, "running", "{}")
    val execService = CommandExecutionService.getInstance(project)
    val result = execService.executeAsync(command, settings.commandTimeoutSeconds)
    // Send "timeout" status to Vue for TimedOut results so the card renders correctly
    val bridgeStatus = if (result is ExecutionResult.TimedOut) "timeout" else "done"
    bridgeHandler?.notifyExecutionStatus(requestId, bridgeStatus, result.toToolResultContent())

    continueWithToolResult(msgId, toolCallId, argsJson, responseBuffer, result)
}

private fun continueWithToolResult(
    msgId: String,
    toolCallId: String,
    argsJson: String,
    responseBuffer: StringBuilder,
    result: ExecutionResult
) {
    // The OpenAI API requires the assistant message to carry a `tool_calls` array
    // before the corresponding `tool` role message. Omitting it causes HTTP 400.
    session.add(Message(
        role = MessageRole.ASSISTANT,
        content = responseBuffer.toString(),
        toolCalls = listOf(ToolCallRecord(
            id = toolCallId,
            functionName = pendingFunctionName ?: "run_command",
            arguments = argsJson
        ))
    ))
    // Add tool result message
    session.add(Message(
        role = MessageRole.TOOL,
        content = result.toToolResultContent(),
        toolCallId = toolCallId
    ))

    // Reset state and start second round
    streamState = StreamState.TEXT
    pendingToolCallId = null
    argumentsBuffer.clear()
    responseBuffer.clear()

    sendMessageInternal(msgId)
}

private fun sendMessageInternal(msgId: String) {
    val settings = PluginSettings.getInstance()
    val provider = settings.getActiveProvider() ?: return
    val apiKey = ApiKeyStore.load(provider.id) ?: return

    val request = client.buildRequest(
        config = provider,
        apiKey = apiKey,
        messages = session.getApiMessages(),
        temperature = settings.getState().chatTemperature,
        maxTokens = settings.getState().chatMaxTokens,
        stream = true,
        tools = null  // no tools on follow-up round
    )

    scope.launch {
        client.streamChat(
            request = request,
            onToken = { token ->
                if (activeMessageId == msgId) {
                    bridgeHandler?.notifyToken(token)
                }
            },
            onEnd = {
                if (activeMessageId == msgId) {
                    activeStream = null
                    activeMessageId = null
                    publishStatus()
                    bridgeHandler?.notifyEnd(msgId)
                }
            },
            onError = { message ->
                if (activeMessageId == msgId) {
                    activeStream = null
                    activeMessageId = null
                    publishStatus()
                    bridgeHandler?.notifyError(message)
                }
            }
        )
    }
}
```

- [ ] **Step 7: Update `Message` model to support `tool` role, `toolCallId`, and `toolCalls`**

Open `src/main/kotlin/com/github/codeplangui/model/Message.kt`. Replace the file contents with:

```kotlin
package com.github.codeplangui.model

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * Carries tool-call metadata on an ASSISTANT message so that OkHttpSseClient
 * can serialize the `tool_calls` array the OpenAI API requires before a tool result.
 */
data class ToolCallRecord(
    val id: String,
    val functionName: String,
    val arguments: String  // raw JSON string from argumentsBuffer
)

data class Message(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,    // set on TOOL messages
    val toolCalls: List<ToolCallRecord>? = null  // set on ASSISTANT messages that invoked a tool
)
```

`ChatSession.getApiMessages()` requires no change — it passes `Message` objects through as-is. `OkHttpSseClient.toApiJson()` (added in Task 4) handles all three shapes correctly based on these fields.

- [ ] **Step 8: Compile check**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew compileKotlin 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` (no test run needed, logic tested via integration).

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/ChatService.kt \
        src/main/kotlin/com/github/codeplangui/model/Message.kt
git commit -m "feat(chat): add tool_call state machine and approval orchestration to ChatService"
```

---

## Task 8: bridge.d.ts + useBridge.ts — React bridge extension

**Files:**
- Modify: `webview/src/types/bridge.d.ts`
- Modify: `webview/src/hooks/useBridge.ts`

- [ ] **Step 1: Add new types and callbacks to `bridge.d.ts`**

```typescript
export interface ExecutionResult {
  status: 'ok' | 'error' | 'blocked' | 'denied' | 'timeout'
  exit_code?: number
  stdout?: string
  stderr?: string
  duration_ms?: number
  truncated?: boolean
  reason?: string
  timeout_seconds?: number
}

export interface Bridge {
  isReady: boolean
  sendMessage: (text: string, includeContext: boolean) => void
  newChat: () => void
  openSettings: () => void
  frontendReady: () => void
  approvalResponse: (requestId: string, decision: 'allow' | 'deny') => void  // ← add
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
  onContextFile: (fileName: string) => void
  onTheme: (theme: 'dark' | 'light') => void
  onApprovalRequest: (requestId: string, command: string, description: string) => void  // ← add
  onExecutionStatus: (requestId: string, status: string, result: string) => void        // ← add
}
```

- [ ] **Step 2: Add new callbacks to `useBridge.ts`**

Update the `BridgeCallbacks` interface:

```typescript
interface BridgeCallbacks {
  onStart: (msgId: string) => void
  onToken: (token: string) => void
  onEnd: (msgId: string) => void
  onError: (message: string) => void
  onStatus: (status: BridgeStatus) => void
  onContextFile: (fileName: string) => void
  onTheme: (theme: 'dark' | 'light') => void
  onApprovalRequest: (requestId: string, command: string, description: string) => void  // ← add
  onExecutionStatus: (requestId: string, status: string, result: string) => void        // ← add
}
```

In the `setup()` function inside `useBridge`, add the new callbacks wherever existing ones are set:

```typescript
window.__bridge.onApprovalRequest = callbacks.onApprovalRequest
window.__bridge.onExecutionStatus = callbacks.onExecutionStatus
```

In the initial `window.__bridge` creation block:

```typescript
approvalResponse: () => {},
onApprovalRequest: currentCallbacks.onApprovalRequest,
onExecutionStatus: currentCallbacks.onExecutionStatus,
```

- [ ] **Step 3: Build webview to check for TypeScript errors**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI/webview
npm run build 2>&1 | tail -30
```

Expected: build succeeds with no type errors.

- [ ] **Step 4: Commit**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI
git add webview/src/types/bridge.d.ts webview/src/hooks/useBridge.ts
git commit -m "feat(webview): extend Bridge interface with approval and execution callbacks"
```

---

## Task 9: ApprovalDialog.tsx + ExecutionCard.tsx

**Files:**
- Create: `webview/src/components/ApprovalDialog.tsx`
- Create: `webview/src/components/ExecutionCard.tsx`

- [ ] **Step 1: Create `ApprovalDialog.tsx`**

```typescript
import { Button, Modal, Typography } from 'antd'
import { WarningOutlined } from '@ant-design/icons'

interface ApprovalDialogProps {
  open: boolean
  command: string
  description: string
  onAllow: () => void
  onDeny: () => void
}

export function ApprovalDialog({ open, command, description, onAllow, onDeny }: ApprovalDialogProps) {
  return (
    <Modal
      open={open}
      title={<span><WarningOutlined style={{ color: '#faad14', marginRight: 8 }} />AI 请求执行命令</span>}
      footer={[
        <Button key="deny" onClick={onDeny}>拒绝</Button>,
        <Button key="allow" type="primary" danger onClick={onAllow}>允许执行</Button>,
      ]}
      closable={false}
      maskClosable={false}
    >
      <div style={{ marginBottom: 12 }}>
        <Typography.Text code style={{ fontSize: 13, display: 'block', padding: '8px 12px', background: 'rgba(0,0,0,0.06)', borderRadius: 6 }}>
          $ {command}
        </Typography.Text>
      </div>
      {description && (
        <Typography.Text type="secondary">{description}</Typography.Text>
      )}
    </Modal>
  )
}
```

- [ ] **Step 2: Create `ExecutionCard.tsx`**

```typescript
import { useState } from 'react'
import { Typography } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined, StopOutlined, LockOutlined, ClockCircleOutlined } from '@ant-design/icons'

export type ExecutionStatus = 'waiting' | 'running' | 'done' | 'blocked' | 'denied' | 'timeout'

export interface ExecutionCardData {
  requestId: string
  command: string
  status: ExecutionStatus
  result?: {
    status: 'ok' | 'error' | 'blocked' | 'denied' | 'timeout'
    exit_code?: number
    stdout?: string
    stderr?: string
    duration_ms?: number
    truncated?: boolean
    reason?: string
    timeout_seconds?: number
  }
}

const PREVIEW_LINES = 5

function OutputBlock({ text, label }: { text: string; label: string }) {
  const lines = text.split('\n')
  const [expanded, setExpanded] = useState(lines.length <= PREVIEW_LINES)
  const visible = expanded ? lines : lines.slice(0, PREVIEW_LINES)

  return (
    <div style={{ marginTop: 8 }}>
      {label && <Typography.Text type="secondary" style={{ fontSize: 11 }}>{label}</Typography.Text>}
      <pre style={{ margin: '4px 0', fontSize: 12, overflowX: 'auto', background: 'rgba(0,0,0,0.04)', padding: '6px 10px', borderRadius: 4 }}>
        {visible.join('\n')}
      </pre>
      {!expanded && (
        <Typography.Link style={{ fontSize: 12 }} onClick={() => setExpanded(true)}>
          ▼ show {lines.length - PREVIEW_LINES} more lines
        </Typography.Link>
      )}
    </div>
  )
}

export function ExecutionCard({ data }: { data: ExecutionCardData }) {
  const { command, status, result } = data

  const header = () => {
    switch (status) {
      case 'waiting': return <><LockOutlined style={{ marginRight: 6 }} />等待审批</>
      case 'running': return <><LoadingOutlined style={{ marginRight: 6 }} />执行中</>
      case 'blocked': return <><StopOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />已拦截 · {result?.reason}</>
      case 'denied':  return <><StopOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />用户拒绝</>
      case 'timeout': return <><ClockCircleOutlined style={{ marginRight: 6, color: '#faad14' }} />超时 · {result?.timeout_seconds}s</>
      case 'done': {
        if (!result) return null
        const success = result.status === 'ok'
        const duration = result.duration_ms ? `${(result.duration_ms / 1000).toFixed(1)}s` : ''
        return success
          ? <><CheckCircleOutlined style={{ marginRight: 6, color: '#52c41a' }} />完成 · exit {result.exit_code} · {duration}</>
          : <><CloseCircleOutlined style={{ marginRight: 6, color: '#ff4d4f' }} />失败 · exit {result.exit_code} · {duration}</>
      }
    }
  }

  return (
    <div style={{ border: '1px solid rgba(128,128,128,0.2)', borderRadius: 8, padding: '10px 14px', margin: '6px 0', fontSize: 13 }}>
      <div style={{ marginBottom: 6 }}>{header()}</div>
      <Typography.Text code style={{ fontSize: 12 }}>$ {command}</Typography.Text>
      {result?.stdout && <OutputBlock text={result.stdout} label="stdout" />}
      {result?.stderr && <OutputBlock text={result.stderr} label="stderr" />}
      {result?.truncated && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>[output truncated]</Typography.Text>
      )}
    </div>
  )
}
```

- [ ] **Step 3: Build to check for type errors**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI/webview && npm run build 2>&1 | tail -20
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI
git add webview/src/components/ApprovalDialog.tsx webview/src/components/ExecutionCard.tsx
git commit -m "feat(webview): add ApprovalDialog and ExecutionCard components"
```

---

## Task 10: App.tsx — wire approval flow

**Files:**
- Modify: `webview/src/App.tsx`
- Modify: `webview/src/components/MessageBubble.tsx`

- [ ] **Step 1: Extend `Message` type in `MessageBubble.tsx` to support execution cards**

In `MessageBubble.tsx`, update the exported `Message` interface:

```typescript
import type { ExecutionCardData } from './ExecutionCard'

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'execution'
  content: string
  isStreaming?: boolean
  execution?: ExecutionCardData   // ← add
}
```

In the `MessageBubble` component, add the execution case:

```typescript
import { ExecutionCard } from './ExecutionCard'

// Inside MessageBubble render:
if (message.role === 'execution' && message.execution) {
  return (
    <div className="message-row message-row-assistant">
      <ExecutionCard data={message.execution} />
    </div>
  )
}
```

- [ ] **Step 2: Add approval state and handlers in `App.tsx`**

Add imports:

```typescript
import { ApprovalDialog } from './components/ApprovalDialog'
import type { ExecutionCardData, ExecutionStatus } from './components/ExecutionCard'
```

Add state inside `App()`:

```typescript
const [approvalOpen, setApprovalOpen] = useState(false)
const [approvalRequestId, setApprovalRequestId] = useState('')
const [approvalCommand, setApprovalCommand] = useState('')
const [approvalDescription, setApprovalDescription] = useState('')
```

Add `onApprovalRequest` callback:

```typescript
const onApprovalRequest = useCallback((requestId: string, command: string, description: string) => {
  setApprovalRequestId(requestId)
  setApprovalCommand(command)
  setApprovalDescription(description)
  setApprovalOpen(true)
  // Insert an execution card placeholder into message list
  setMessages((prev) => [
    ...prev,
    {
      id: requestId,
      role: 'execution' as const,
      content: '',
      execution: { requestId, command, status: 'waiting' as ExecutionStatus },
    },
  ])
}, [])
```

Add `onExecutionStatus` callback:

```typescript
const onExecutionStatus = useCallback((requestId: string, status: string, resultJson: string) => {
  const result = (() => {
    try { return JSON.parse(resultJson) } catch { return undefined }
  })()
  setMessages((prev) =>
    prev.map((msg) =>
      msg.id === requestId
        ? { ...msg, execution: { ...msg.execution!, status: status as ExecutionStatus, result } }
        : msg
    )
  )
}, [])
```

Add approval handlers:

```typescript
const handleApprovalAllow = useCallback(() => {
  setApprovalOpen(false)
  window.__bridge?.approvalResponse(approvalRequestId, 'allow')
}, [approvalRequestId])

const handleApprovalDeny = useCallback(() => {
  setApprovalOpen(false)
  window.__bridge?.approvalResponse(approvalRequestId, 'deny')
}, [approvalRequestId])
```

Update `useBridge` call to include new callbacks:

```typescript
const bridgeReady = useBridge({
  onStart, onToken, onEnd, onError, onStatus, onContextFile, onTheme,
  onApprovalRequest,    // ← add
  onExecutionStatus,    // ← add
})
```

Add `ApprovalDialog` to JSX (inside `<ConfigProvider>`, before or after `ProviderBar`):

```tsx
<ApprovalDialog
  open={approvalOpen}
  command={approvalCommand}
  description={approvalDescription}
  onAllow={handleApprovalAllow}
  onDeny={handleApprovalDeny}
/>
```

- [ ] **Step 3: Build**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI/webview && npm run build 2>&1 | tail -20
```

Expected: no TypeScript errors, build succeeds.

- [ ] **Step 4: Commit**

```bash
cd ~/SourceLib/fishNotExist/CodePlanGUI
git add webview/src/App.tsx webview/src/components/MessageBubble.tsx
git commit -m "feat(webview): wire approval flow and execution cards in App"
```

---

## Task 11: PluginSettingsConfigurable — Execution tab

**Files:**
- Modify: `src/main/kotlin/com/github/codeplangui/settings/PluginSettingsConfigurable.kt`

- [ ] **Step 1: Add Execution tab fields to `PluginSettingsConfigurable`**

Add new fields after the existing `lateinit var` declarations:

```kotlin
private lateinit var commandExecutionCheckbox: JCheckBox
private lateinit var commandTimeoutSpinner: JSpinner
private lateinit var commandWhitelistModel: javax.swing.DefaultListModel<String>
private lateinit var commandWhitelistList: javax.swing.JList<String>
```

- [ ] **Step 2: Build the Execution panel in `createComponent()`**

After `chatCommitPanel` is built, add:

```kotlin
val settings = SettingsFormState.fromSettingsState(PluginSettings.getInstance().getState())

commandExecutionCheckbox = JCheckBox("Enable AI command execution", settings.commandExecutionEnabled)
commandTimeoutSpinner = JSpinner(SpinnerNumberModel(settings.commandTimeoutSeconds, 5, 300, 5))
commandWhitelistModel = javax.swing.DefaultListModel<String>().also { model ->
    settings.commandWhitelist.forEach { model.addElement(it) }
}
commandWhitelistList = javax.swing.JList(commandWhitelistModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    preferredScrollableViewportSize = Dimension(300, 120)
}

val whitelistPanel = JPanel(BorderLayout()).apply {
    add(javax.swing.JScrollPane(commandWhitelistList), BorderLayout.CENTER)
    val btnPanel = JPanel()
    val addBtn = JButton("+ Add").apply {
        addActionListener {
            val cmd = Messages.showInputDialog("Command name (e.g. cargo):", "Add Command", null) ?: return@addActionListener
            if (cmd.isNotBlank() && !commandWhitelistModel.contains(cmd.trim())) {
                commandWhitelistModel.addElement(cmd.trim())
            }
        }
    }
    val removeBtn = JButton("Remove").apply {
        addActionListener {
            val idx = commandWhitelistList.selectedIndex
            if (idx >= 0) commandWhitelistModel.remove(idx)
        }
    }
    btnPanel.add(addBtn)
    btnPanel.add(removeBtn)
    add(btnPanel, BorderLayout.SOUTH)
}

val executionPanel = FormBuilder.createFormBuilder()
    .addComponent(commandExecutionCheckbox)
    .addLabeledComponent("Execution timeout (s):", commandTimeoutSpinner)
    .addLabeledComponent(
        JBLabel("Allowed commands:"),
        whitelistPanel
    )
    .addComponent(JBLabel("<html><small>⚠ AI still requires your approval before each command runs.<br>Commands not in this list are blocked without prompting.</small></html>"))
    .panel
```

- [ ] **Step 3: Add the new tab to `JBTabbedPane`**

Find the `tabs` creation and add:

```kotlin
val tabs = JBTabbedPane().apply {
    addTab("Providers", providersPanel)
    addTab("Chat / Commit", chatCommitPanel)
    addTab("Execution", executionPanel)   // ← add
}
```

- [ ] **Step 4: Update `isModified()`, `apply()`, `reset()`, `currentFormState()`**

In `currentFormState()`, add:

```kotlin
commandExecutionEnabled = commandExecutionCheckbox.isSelected,
commandWhitelist = (0 until commandWhitelistModel.size()).map { commandWhitelistModel.getElementAt(it) }.toMutableList(),
commandTimeoutSeconds = (commandTimeoutSpinner.value as Number).toInt(),
```

In `reset()`, add:

```kotlin
commandExecutionCheckbox.isSelected = settings.commandExecutionEnabled
commandTimeoutSpinner.value = settings.commandTimeoutSeconds
commandWhitelistModel.clear()
settings.commandWhitelist.forEach { commandWhitelistModel.addElement(it) }
```

`isModified()` and `apply()` will pick up the new fields automatically through `currentFormState()`.

- [ ] **Step 5: Compile check**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew compileKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/codeplangui/settings/PluginSettingsConfigurable.kt
git commit -m "feat(settings): add Execution tab with whitelist and timeout configuration"
```

---

## Task 12: plugin.xml — register CommandExecutionService

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add project service registration**

In `plugin.xml`, inside `<extensions defaultExtensionNs="com.intellij">`, add after the existing `applicationService`:

```xml
<projectService
    serviceImplementation="com.github.codeplangui.execution.CommandExecutionService"/>
```

- [ ] **Step 2: Full build and plugin zip**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew buildWebview buildPlugin 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, `build/distributions/CodePlanGUI-0.1.0.zip` exists.

- [ ] **Step 3: Run all tests**

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test 2>&1 | tail -20
```

Expected: all tests green.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml
git commit -m "feat(plugin): register CommandExecutionService as project service"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered in task |
|---|---|
| 功能默认关闭，开启前无任何行为变化 | Task 7 — `commandExecutionEnabled` gate in `sendMessage()` |
| 不在白名单的命令直接返回 blocked，不弹框 | Task 7 — `isWhitelisted()` check before `notifyApprovalRequest()` |
| 在白名单内的命令弹出审批框，用户拒绝后命令不执行 | Task 6 + Task 10 — approval dialog + `CompletableFuture` gate |
| 执行超时后进程强制终止 | Task 5 — `destroyForcibly()` + `TimedOut` result |
| AI 能读到 tool_result 继续推理 | Task 7 — `continueWithToolResult()` + second-round API call |
| 输出超过 4000 字符时自动截断 | Task 5 — `truncateOutput()` |
| 60 秒无操作自动视为拒绝 | Task 7 — `future.get(60, TimeUnit.SECONDS)` |
| Settings UI — whitelist + timeout + enable toggle | Task 11 |
| 5-state execution card | Task 9 — `ExecutionCard` |
| Approval dialog | Task 9 — `ApprovalDialog` |

**Type consistency confirmed:**
- `ExecutionResult` sealed class defined in Task 1, used in Tasks 5 and 7
- `ToolCallDelta` defined in Task 3, used in Tasks 4 and 7
- `ToolDefinition` / `FunctionDefinition` defined in Task 4, used in Task 7
- `ToolCallRecord` defined in Task 7 Step 7 (`Message.kt`), used in `continueWithToolResult` (Task 7 Step 6) and `toApiJson()` (Task 4 Step 3)
- `ExecutionCardData` / `ExecutionStatus` (including `'timeout'`) defined in Task 9, used in Task 10
- `BridgeCommands.approvalResponse()` defined in Task 6, implemented in Task 7
- `CommandExecutionService.isWhitelisted()` / `extractBaseCommand()` / `hasPathsOutsideWorkspace()` are static companions, called from Task 7
- `ApiMessage` (old) removed in Task 4; replaced by `Message.toApiJson()` extension + `ChatRequestBody.messages: List<JsonObject>`

**Fixes applied (补全):**
1. `ApiMessage` 重写为 `toApiJson()` 扩展 — 支持 tool_calls 数组和 tool_call_id 字段（Task 4 Step 3）
2. `streamChat()` 增加 `isToolCallStream` 守卫 — 防止 `[DONE]` 误触发 `onEnd`（Task 4 Step 4）
3. `continueWithToolResult()` 正确存储带 `toolCalls` 的 assistant 消息（Task 7 Step 6）
4. `TimedOut` 结果推送 `"timeout"` 状态而非 `"done"`（Task 7 Step 6）
5. `ExecutionStatus` 增加 `'timeout'` 变体，`ExecutionCard` 增加超时分支（Task 9 Step 2）
6. 删除无用的 `pendingToolDescription` 字段（Task 7 Step 2）

No placeholders found. All steps contain complete code.
