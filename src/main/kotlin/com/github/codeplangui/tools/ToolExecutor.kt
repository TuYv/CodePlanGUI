package com.github.codeplangui.tools

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
            val event = ToolUpdate.PermissionAsked(id, tool.name, p.reason, p.preview)
            send(event)
            val allowed = context.onPermissionAsked(event)
            if (!allowed) {
                send(ToolUpdate.Failed(id, ToolUpdate.Failed.Stage.PERMISSION, "Permission denied by user"))
                return@channelFlow
            }
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

/** Max number of concurrency-safe tools executed in parallel within one batch. */
const val MAX_BATCH_CONCURRENCY: Int = 10

/** Resolved or failed entry for a single tool_use in a batch. */
private sealed class BatchEntry {
    abstract val toolUse: ToolUseBlock
    data class Resolved(override val toolUse: ToolUseBlock, val tool: Tool<*, *>, val isSafe: Boolean) : BatchEntry()
    data class Failed(override val toolUse: ToolUseBlock, val reason: String) : BatchEntry()
}

/**
 * Multi-tool execution flow. Mirrors Claude Code's `partitionToolCalls()` +
 * `runToolsConcurrently()` (toolOrchestration.ts:91-177).
 *
 * The LLM may emit multiple `tool_use` blocks in one turn. This function:
 *   1. Resolves each block to a tool via the pool. Blocks whose tool is missing
 *      emit `Started` + `Failed(LOOKUP)` immediately.
 *   2. Partitions resolved blocks by `isConcurrencySafe(parsedInput)`:
 *        - safe   → run in parallel, bounded by [maxConcurrency]
 *        - unsafe → run serially after the safe group
 *   3. Merges all `ToolUpdate` events into a single [Flow]. Ordering across
 *      different tool_use_ids is not deterministic for the concurrent group;
 *      updates for the same id stay in order.
 *
 * Partition-time parsing is best-effort: if `parseInput` throws, the block is
 * classified as unsafe (so it's isolated) and the real PARSE failure is emitted
 * by the underlying [runToolUse] call. On the happy path, input is parsed twice
 * (once for classification, once for execution) — a deliberate trade-off to keep
 * `runToolUse` self-contained.
 *
 * MVP limitations:
 *   - `ToolResult.contextModifier` is not threaded through the serial unsafe
 *     group; tools that mutate context see the original. Add in M3 if needed.
 */
fun runToolUseBatch(
    toolUses: List<ToolUseBlock>,
    pool: List<Tool<*, *>>,
    context: ToolExecutionContext,
    maxConcurrency: Int = MAX_BATCH_CONCURRENCY,
): Flow<ToolUpdate> = channelFlow {
    require(maxConcurrency > 0) { "maxConcurrency must be > 0, was $maxConcurrency" }

    val poolMap = buildMap<String, Tool<*, *>> {
        for (tool in pool) {
            put(tool.name, tool)
            for (alias in tool.aliases) {
                put(alias, tool)
            }
        }
    }

    val entries = toolUses.map { tu ->
        val tool = poolMap[tu.name]
        if (tool == null) {
            BatchEntry.Failed(tu, "Tool not found: ${tu.name}")
        } else {
            BatchEntry.Resolved(tu, tool, isSafe = classifyConcurrencySafe(tool, tu))
        }
    }

    for (e in entries) {
        if (e is BatchEntry.Failed) {
            send(ToolUpdate.Started(e.toolUse.toolUseId, e.toolUse.name))
            send(ToolUpdate.Failed(e.toolUse.toolUseId, ToolUpdate.Failed.Stage.LOOKUP, e.reason))
        }
    }

    val resolved = entries.filterIsInstance<BatchEntry.Resolved>()
    val (safeGroup, unsafeGroup) = resolved.partition { it.isSafe }

    suspend fun executeEntry(e: BatchEntry.Resolved) {
        runToolUseErased(e.tool, e.toolUse, context).collect { send(it) }
    }

    if (safeGroup.isNotEmpty()) {
        // Semaphore limits concurrent entries into executeEntry, not thread count.
        // limitedParallelism(n) would only limit threads — a suspended tool releases
        // its thread, allowing the dispatcher to start another, bypassing the bound.
        val semaphore = Semaphore(maxConcurrency)
        coroutineScope {
            safeGroup.map { e -> launch { semaphore.withPermit { executeEntry(e) } } }.joinAll()
        }
    }

    for (e in unsafeGroup) {
        executeEntry(e)
    }
}

/**
 * Best-effort concurrency-safety probe. Parses input with the tool and asks it.
 * Parse failures classify as unsafe — they'll surface as PARSE errors when the
 * real runToolUse runs.
 */
private fun classifyConcurrencySafe(tool: Tool<*, *>, toolUse: ToolUseBlock): Boolean {
    return try {
        val t = tool.asErased()
        val input = t.parseInput(toolUse.input)
        t.isConcurrencySafe(input)
    } catch (_: Exception) {
        false
    }
}

@Suppress("UNCHECKED_CAST")
private fun Tool<*, *>.asErased(): Tool<Any, Any> = this as Tool<Any, Any>

private fun runToolUseErased(
    tool: Tool<*, *>,
    toolUse: ToolUseBlock,
    context: ToolExecutionContext,
): Flow<ToolUpdate> = runToolUse(tool.asErased(), toolUse, context)
