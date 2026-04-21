package com.github.codeplangui.tools

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Single-tool execution flow. Mirrors Claude Code's `runToolUse()`
 * (src/services/tools/toolExecution.ts:337), which is an `AsyncGenerator` yielding
 * message updates. Kotlin's analog is a [Flow] emitting [ToolUpdate] events.
 *
 * Lifecycle (matches docs/phase2-tools-design-notes.md §3.5.2):
 *   1. Look up tool by name → `ToolUpdate.Failed(LOOKUP)` on miss
 *   2. Parse input JSON → `Failed(PARSE)` on throw
 *   3. `validateInput` → `Failed(VALIDATE)` on Failed result
 *   4. `checkPermissions` → on Deny emit `Failed(PERMISSION)`; on Ask emit
 *      `PermissionAsked` and (MVP) auto-approve
 *   5. `call` with progress callback → `ProgressEmitted` for each progress event
 *   6. `mapResultToApiBlock` → `Completed`
 *
 * On any exception from `call()` → `Failed(EXECUTE)`.
 *
 * MVP notes:
 * - `Ask` is currently auto-approved (emit PermissionAsked then proceed). M3 will
 *   route this to a real user decision callback.
 * - No parallel-batch execution; one tool_use at a time. M2 adds
 *   `runToolUseBatch` with the concurrency-safe partition logic.
 */
fun <Input : Any, Output : Any> runToolUse(
    tool: Tool<Input, Output>,
    toolUse: ToolUseBlock,
    context: ToolExecutionContext,
): Flow<ToolUpdate> = channelFlow {
    val id = toolUse.toolUseId
    send(ToolUpdate.Started(id, tool.name))

    val input: Input = try {
        tool.parseInput(toolUse.input)
    } catch (t: Throwable) {
        send(ToolUpdate.Failed(id, ToolUpdate.Failed.Stage.PARSE, t.message ?: "input parse error"))
        return@channelFlow
    }

    when (val v = tool.validateInput(input, context)) {
        is ValidationResult.Ok -> Unit
        is ValidationResult.Failed -> {
            send(ToolUpdate.Failed(id, ToolUpdate.Failed.Stage.VALIDATE, v.message))
            return@channelFlow
        }
    }

    when (val p = tool.checkPermissions(input, context)) {
        is PermissionResult.Allow -> Unit
        is PermissionResult.Deny -> {
            send(ToolUpdate.Failed(id, ToolUpdate.Failed.Stage.PERMISSION, p.reason))
            return@channelFlow
        }
        is PermissionResult.Ask -> {
            send(ToolUpdate.PermissionAsked(id, tool.name, p.reason, p.preview))
            // MVP: auto-approve. M3 replaces this with a real user-decision callback.
        }
    }

    // Bridge the sync onProgress callback to the channelFlow.
    val progressChannel = Channel<Progress>(Channel.UNLIMITED)
    val pump = launch {
        for (progress in progressChannel) {
            send(ToolUpdate.ProgressEmitted(id, progress))
        }
    }

    val result: ToolResult<Output> = try {
        tool.call(input, context) { progressChannel.trySend(it) }
    } catch (t: Throwable) {
        progressChannel.close()
        pump.join()
        send(ToolUpdate.Failed(id, ToolUpdate.Failed.Stage.EXECUTE, t.message ?: t.javaClass.simpleName))
        return@channelFlow
    }
    progressChannel.close()
    pump.join()

    val block: ToolResultBlock = try {
        tool.mapResultToApiBlock(result.data, id)
    } catch (t: Throwable) {
        send(ToolUpdate.Failed(id, ToolUpdate.Failed.Stage.SERIALIZE, t.message ?: "serialize error"))
        return@channelFlow
    }

    send(ToolUpdate.Completed(id, block))
}
