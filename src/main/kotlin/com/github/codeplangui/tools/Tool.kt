package com.github.codeplangui.tools

import kotlinx.serialization.json.JsonElement

/**
 * The core Tool abstraction. A `Tool` is the unit the LLM can invoke via the
 * Anthropic tool_use protocol, wrapping either a shell command, an IDE action, or
 * an MCP proxy call.
 *
 * Modeled after Claude Code's `Tool<Input, Output, P>` (Tool.ts:362-695), pared down
 * to the fields an MVP actually needs. See docs/phase2-tools-design-notes.md §3 for
 * the full mapping.
 *
 * Each concrete tool is a singleton (top-level `val` or `object`), constructed via
 * the [tool] DSL in ToolBuilder.kt.
 */
interface Tool<Input : Any, Output : Any> {

    val name: String
    val description: String
    val inputSchema: JsonSchema
    val aliases: List<String> get() = emptyList()

    /** Maximum characters before result is truncated or persisted. Tools that
     * self-bound their output (e.g. FileRead with offset/limit) may return
     * `Int.MAX_VALUE`. */
    val maxResultSizeChars: Int get() = DEFAULT_MAX_RESULT_SIZE

    /** Parse the raw JSON input the LLM emitted into this tool's typed input shape. */
    fun parseInput(raw: JsonElement): Input

    /** Pre-permission syntactic / semantic check. Default: no-op. */
    suspend fun validateInput(input: Input, context: ToolExecutionContext): ValidationResult =
        ValidationResult.Ok

    /** Tool-specific permission decision. Default: Allow (defer to framework-wide rules). */
    suspend fun checkPermissions(input: Input, context: ToolExecutionContext): PermissionResult =
        PermissionResult.Allow(updatedInput = emptyJsonObject())

    /** Optional dry-run preview. Null ⇒ this tool doesn't support preview
     * (e.g. read operations). See docs/phase2-mvp-tool-specs.md §0.3. */
    suspend fun preview(input: Input, context: ToolExecutionContext): PreviewResult? = null

    /** Main executor. Implementations emit progress via [onProgress] while running. */
    suspend fun call(
        input: Input,
        context: ToolExecutionContext,
        onProgress: (Progress) -> Unit = {},
    ): ToolResult<Output>

    /** Serialize the typed output into the Anthropic tool_result shape. */
    fun mapResultToApiBlock(output: Output, toolUseId: String): ToolResultBlock

    // --- Metadata predicates (default values let tools omit these) ---

    fun isEnabled(): Boolean = true
    fun isConcurrencySafe(input: Input): Boolean = false
    fun isReadOnly(input: Input): Boolean = false
    fun isDestructive(input: Input): Boolean = false

    // --- Display helpers ---

    fun userFacingName(input: Input?): String = name
    fun getActivityDescription(input: Input?): String? = null

    companion object {
        const val DEFAULT_MAX_RESULT_SIZE: Int = 40_000
    }
}

private fun emptyJsonObject(): JsonElement =
    kotlinx.serialization.json.JsonObject(emptyMap())
