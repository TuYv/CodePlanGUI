package com.github.codeplangui.tools.bash

import com.github.codeplangui.execution.CommandExecutionService
import com.github.codeplangui.execution.ExecutionResult
import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.PreviewResult
import com.github.codeplangui.tools.Progress
import com.github.codeplangui.tools.Tool
import com.github.codeplangui.tools.ToolResult
import com.github.codeplangui.tools.ToolResultBlock
import com.github.codeplangui.tools.ValidationResult
import com.github.codeplangui.tools.tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class BashInput(
    val command: String,
    val description: String? = null,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 600
    }
}

data class BashOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val truncated: Boolean,
    val timedOut: Boolean,
)

private val json = Json { ignoreUnknownKeys = true }

// Heuristic — matches commands that typically cause data loss or irreversible
// side effects. Permission layer forces Ask when this returns true even if the
// user has an allow rule for the command prefix. See
// docs/phase2-mvp-tool-specs.md §1.3.
private val DESTRUCTIVE_PATTERNS = listOf(
    Regex("""\brm\s"""),
    Regex("""\brmdir\s"""),
    Regex("""\bmv\s"""),
    Regex("""\bdd\s"""),
    Regex("""\bDROP\s+TABLE\b""", RegexOption.IGNORE_CASE),
    Regex("""\bDROP\s+DATABASE\b""", RegexOption.IGNORE_CASE),
    Regex(""">\s*/"""),      // redirection into absolute path
    Regex("""\bgit\s+push\s+.*--force\b"""),
    Regex("""\bgit\s+reset\s+--hard\b"""),
)

private fun isDestructiveCommand(command: String): Boolean =
    DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(command) }

val BashTool: Tool<BashInput, BashOutput> = tool {
    name = "Bash"
    // Alias the legacy execution/ tool names so the LLM can call either naming scheme.
    aliases = listOf("run_command", "run_powershell")
    description = """
        Execute a shell command in the project's working directory. Supports piping,
        redirection, and subshells. Output over 20k chars is truncated. Use for git,
        build tools, package managers, and general shell workflows.
    """.trimIndent()

    inputSchema = buildJsonObject {
        put("type", "object")
        put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("command"))))
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "The shell command to execute.")
            })
            put("description", buildJsonObject {
                put("type", "string")
                put("description", "Short description of what the command does (optional).")
            })
            put("timeoutSeconds", buildJsonObject {
                put("type", "integer")
                put("description", "Timeout in seconds. Default 120, max 600.")
            })
        })
    }

    parse { raw: JsonElement -> json.decodeFromJsonElement(BashInput.serializer(), raw) }

    validate { input, _ ->
        when {
            input.command.isBlank() ->
                ValidationResult.Failed("command must not be blank", errorCode = 1)
            input.timeoutSeconds !in 1..BashInput.MAX_TIMEOUT_SECONDS ->
                ValidationResult.Failed(
                    "timeoutSeconds must be between 1 and ${BashInput.MAX_TIMEOUT_SECONDS}",
                    errorCode = 2,
                )
            else -> ValidationResult.Ok
        }
    }

    checkPermissions { input, ctx ->
        val basePath = ctx.project.basePath
        when {
            basePath == null ->
                PermissionResult.Deny("Project path unavailable")
            CommandExecutionService.hasPathsOutsideWorkspace(input.command, basePath) ->
                PermissionResult.Deny("Command references paths outside the workspace")
            isDestructiveCommand(input.command) ->
                PermissionResult.Ask(
                    reason = "Potentially destructive command",
                    preview = previewFor(input, basePath),
                )
            ctx.permissionContext.alwaysAllow.any { it == input.command || isPrefixMatch(it, input.command) } ->
                PermissionResult.Allow(Json.encodeToJsonElement(BashInput.serializer(), input))
            else ->
                PermissionResult.Ask(
                    reason = "New command — confirm before running",
                    preview = previewFor(input, basePath),
                )
        }
    }

    preview { input, ctx ->
        previewFor(input, ctx.project.basePath ?: "<no project>")
    }

    call { input, ctx, onProgress ->
        val service = CommandExecutionService.getInstance(ctx.project)
        val result = service.executeAsyncWithStream(
            command = input.command,
            timeoutSeconds = input.timeoutSeconds,
            onOutput = { line, isError ->
                onProgress(if (isError) Progress.Stderr(line) else Progress.Stdout(line))
            },
        )
        ToolResult(result.toBashOutput(input.command))
    }

    mapResult { output, toolUseId ->
        val content = buildString {
            if (output.timedOut) appendLine("[timed out]")
            if (output.exitCode != 0) appendLine("[exit ${output.exitCode}]")
            if (output.stdout.isNotEmpty()) appendLine(output.stdout)
            if (output.stderr.isNotEmpty()) {
                appendLine("--- stderr ---")
                appendLine(output.stderr)
            }
            if (output.truncated) appendLine("[output truncated]")
        }.trim()
        ToolResultBlock(
            toolUseId = toolUseId,
            content = content.ifEmpty { "(no output)" },
            isError = output.exitCode != 0 || output.timedOut,
        )
    }

    isConcurrencySafe { false }
    isReadOnly { false }
    isDestructive { isDestructiveCommand(it.command) }

    activityDescription { input -> input?.let { "Running: ${it.command.take(80)}" } }
}

private fun previewFor(input: BashInput, basePath: String): PreviewResult = PreviewResult(
    summary = "Run: ${input.command}",
    details = buildString {
        append("Working dir: ").appendLine(basePath)
        append("Timeout: ").append(input.timeoutSeconds).appendLine("s")
        input.description?.let { append("Intent: ").appendLine(it) }
    },
    risk = if (isDestructiveCommand(input.command)) PreviewResult.Risk.HIGH else PreviewResult.Risk.MEDIUM,
)

private fun isPrefixMatch(rule: String, command: String): Boolean =
    rule.endsWith(" *") && command.startsWith(rule.removeSuffix(" *"))

private fun ExecutionResult.toBashOutput(command: String): BashOutput = when (this) {
    is ExecutionResult.Success -> BashOutput(stdout, stderr, exitCode, durationMs, truncated, timedOut = false)
    is ExecutionResult.Failed -> BashOutput(stdout, stderr, exitCode, durationMs, truncated, timedOut = false)
    is ExecutionResult.TimedOut -> BashOutput(
        stdout = stdout,
        stderr = "",
        exitCode = -1,
        durationMs = timeoutSeconds * 1000L,
        truncated = false,
        timedOut = true,
    )
    is ExecutionResult.Blocked -> BashOutput(
        stdout = "",
        stderr = "Blocked: $reason (command: $command)",
        exitCode = -1,
        durationMs = 0,
        truncated = false,
        timedOut = false,
    )
    is ExecutionResult.Denied -> BashOutput(
        stdout = "",
        stderr = "Denied: $reason (command: $command)",
        exitCode = -1,
        durationMs = 0,
        truncated = false,
        timedOut = false,
    )
}
