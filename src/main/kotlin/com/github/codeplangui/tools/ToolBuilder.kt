package com.github.codeplangui.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * DSL for declaring a tool without writing a full `Tool` subclass. Corresponds to
 * Claude Code's `buildTool()` factory (Tool.ts:757-792), which fills sensible defaults
 * for commonly-stubbed methods.
 *
 * Usage:
 * ```
 * val MyTool: Tool<MyInput, MyOutput> = tool {
 *     name = "my_tool"
 *     description = "..."
 *     inputSchema = buildJsonObject { ... }
 *     parse { raw -> Json.decodeFromJsonElement<MyInput>(raw) }
 *     call { input, ctx, progress -> ToolResult(MyOutput(...)) }
 *     mapResult { output, id -> ToolResultBlock(id, content = "...") }
 * }
 * ```
 *
 * Everything not set falls back to defaults: `isEnabled = true`,
 * `isConcurrencySafe = false`, `checkPermissions = Allow`, etc.
 */
class ToolBuilder<Input : Any, Output : Any> {
    var name: String = ""
    var description: String = ""
    var aliases: List<String> = emptyList()
    var inputSchema: JsonSchema = JsonObject(emptyMap())
    var maxResultSizeChars: Int = Tool.DEFAULT_MAX_RESULT_SIZE

    private var parseFn: ((JsonElement) -> Input)? = null
    private var validateFn: (suspend (Input, ToolExecutionContext) -> ValidationResult)? = null
    private var permissionFn: (suspend (Input, ToolExecutionContext) -> PermissionResult)? = null
    private var previewFn: (suspend (Input, ToolExecutionContext) -> PreviewResult?)? = null
    private var callFn: (suspend (Input, ToolExecutionContext, (Progress) -> Unit) -> ToolResult<Output>)? = null
    private var mapResultFn: ((Output, String) -> ToolResultBlock)? = null

    private var isEnabledFn: () -> Boolean = { true }
    private var isConcurrencySafeFn: (Input) -> Boolean = { false }
    private var isReadOnlyFn: (Input) -> Boolean = { false }
    private var isDestructiveFn: (Input) -> Boolean = { false }
    private var userFacingNameFn: ((Input?) -> String)? = null
    private var activityDescriptionFn: (Input?) -> String? = { null }

    fun parse(block: (JsonElement) -> Input) { parseFn = block }
    fun validate(block: suspend (Input, ToolExecutionContext) -> ValidationResult) { validateFn = block }
    fun checkPermissions(block: suspend (Input, ToolExecutionContext) -> PermissionResult) { permissionFn = block }
    fun preview(block: suspend (Input, ToolExecutionContext) -> PreviewResult?) { previewFn = block }
    fun call(block: suspend (Input, ToolExecutionContext, (Progress) -> Unit) -> ToolResult<Output>) { callFn = block }
    fun mapResult(block: (Output, String) -> ToolResultBlock) { mapResultFn = block }

    fun isEnabled(block: () -> Boolean) { isEnabledFn = block }
    fun isConcurrencySafe(block: (Input) -> Boolean) { isConcurrencySafeFn = block }
    fun isReadOnly(block: (Input) -> Boolean) { isReadOnlyFn = block }
    fun isDestructive(block: (Input) -> Boolean) { isDestructiveFn = block }
    fun userFacingName(block: (Input?) -> String) { userFacingNameFn = block }
    fun activityDescription(block: (Input?) -> String?) { activityDescriptionFn = block }

    fun build(): Tool<Input, Output> {
        val finalName = name.ifEmpty { error("tool { } requires a non-empty name") }
        val finalParse = parseFn ?: error("tool { } requires parse { } for '$finalName'")
        val finalCall = callFn ?: error("tool { } requires call { } for '$finalName'")
        val finalMapResult = mapResultFn ?: error("tool { } requires mapResult { } for '$finalName'")
        val userFacing = userFacingNameFn ?: { _ -> finalName }

        return object : Tool<Input, Output> {
            override val name = finalName
            override val description = this@ToolBuilder.description
            override val aliases = this@ToolBuilder.aliases
            override val inputSchema = this@ToolBuilder.inputSchema
            override val maxResultSizeChars = this@ToolBuilder.maxResultSizeChars

            override fun parseInput(raw: JsonElement): Input = finalParse(raw)

            override suspend fun validateInput(input: Input, context: ToolExecutionContext): ValidationResult =
                validateFn?.invoke(input, context) ?: ValidationResult.Ok

            override suspend fun checkPermissions(input: Input, context: ToolExecutionContext): PermissionResult =
                permissionFn?.invoke(input, context) ?: PermissionResult.Allow(JsonObject(emptyMap()))

            override suspend fun preview(input: Input, context: ToolExecutionContext): PreviewResult? =
                previewFn?.invoke(input, context)

            override suspend fun call(
                input: Input,
                context: ToolExecutionContext,
                onProgress: (Progress) -> Unit,
            ): ToolResult<Output> = finalCall(input, context, onProgress)

            override fun mapResultToApiBlock(output: Output, toolUseId: String): ToolResultBlock =
                finalMapResult(output, toolUseId)

            override fun isEnabled(): Boolean = isEnabledFn()
            override fun isConcurrencySafe(input: Input) = isConcurrencySafeFn(input)
            override fun isReadOnly(input: Input) = isReadOnlyFn(input)
            override fun isDestructive(input: Input) = isDestructiveFn(input)
            override fun userFacingName(input: Input?): String = userFacing(input)
            override fun getActivityDescription(input: Input?): String? = activityDescriptionFn(input)
        }
    }
}

fun <Input : Any, Output : Any> tool(block: ToolBuilder<Input, Output>.() -> Unit): Tool<Input, Output> =
    ToolBuilder<Input, Output>().apply(block).build()
