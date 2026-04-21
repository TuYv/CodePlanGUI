package com.github.codeplangui.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Outcome of `Tool.validateInput`. Validation runs before permission checks.
 */
sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Failed(val message: String, val errorCode: Int = -1) : ValidationResult()
}

/**
 * Outcome of `Tool.checkPermissions`. Matches the three-state permission model from
 * Claude Code's Tool.ts (PermissionResult). `Allow` carries an `updatedInput` so the
 * permission layer can mutate input (e.g. normalize paths) before execution.
 */
sealed class PermissionResult {
    data class Allow(val updatedInput: JsonElement) : PermissionResult()
    data class Ask(val reason: String, val preview: PreviewResult? = null) : PermissionResult()
    data class Deny(val reason: String) : PermissionResult()
}

/**
 * Structured preview of what `call()` would do. Used when `checkPermissions` returns `Ask`
 * so the UI can show the user a dry-run summary before they approve.
 */
data class PreviewResult(
    val summary: String,
    val details: String? = null,
    val risk: Risk = Risk.MEDIUM,
) {
    enum class Risk { LOW, MEDIUM, HIGH }
}

/**
 * The value returned from `Tool.call()`. `data` is the typed output; optional fields let
 * tools inject new messages or mutate context (rare — used by non-concurrency-safe tools).
 */
data class ToolResult<T>(
    val data: T,
    val newMessages: List<String> = emptyList(),
    val contextModifier: ((ToolExecutionContext) -> ToolExecutionContext)? = null,
)

/**
 * Tool-facing progress payload. Tools emit these via the `onProgress` callback during `call()`.
 * Kept as a sealed class so specific tools (Bash streaming, etc.) can carry typed data.
 */
sealed class Progress {
    data class Stdout(val line: String) : Progress()
    data class Stderr(val line: String) : Progress()
    data class Status(val message: String) : Progress()
}

/**
 * Serializable shape sent back to the LLM as a `tool_result` block.
 * Mirrors Anthropic's tool_result structure (simplified for MVP — only text content).
 */
@Serializable
data class ToolResultBlock(
    val toolUseId: String,
    val content: String,
    val isError: Boolean = false,
)

/**
 * A tool_use request from the LLM. Comes from the Anthropic API response.
 */
@Serializable
data class ToolUseBlock(
    val toolUseId: String,
    val name: String,
    val input: JsonElement,
)

/**
 * Events emitted by `runToolUse`. One `tool_use` becomes a Flow of these.
 */
sealed class ToolUpdate {
    abstract val toolUseId: String

    data class Started(override val toolUseId: String, val toolName: String) : ToolUpdate()
    data class ProgressEmitted(override val toolUseId: String, val progress: Progress) : ToolUpdate()
    data class PermissionAsked(
        override val toolUseId: String,
        val toolName: String,
        val reason: String,
        val preview: PreviewResult?,
    ) : ToolUpdate()
    data class Completed(override val toolUseId: String, val block: ToolResultBlock) : ToolUpdate()
    data class Failed(
        override val toolUseId: String,
        val stage: Stage,
        val message: String,
    ) : ToolUpdate() {
        enum class Stage { LOOKUP, PARSE, VALIDATE, PERMISSION, EXECUTE, SERIALIZE }
    }
}

/**
 * Convenience alias for the schema representation. Raw JSON Schema (draft-07 style) kept as a
 * `JsonObject` — we don't introduce a schema DSL for MVP.
 */
typealias JsonSchema = JsonObject
