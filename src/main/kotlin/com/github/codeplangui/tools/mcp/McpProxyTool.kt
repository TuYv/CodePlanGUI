package com.github.codeplangui.tools.mcp

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.PreviewResult
import com.github.codeplangui.tools.Tool
import com.github.codeplangui.tools.ToolResult
import com.github.codeplangui.tools.ToolResultBlock
import com.github.codeplangui.tools.ValidationResult
import com.github.codeplangui.tools.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Wrap a remote MCP tool as a local [Tool] instance.
 *
 * Naming convention: `mcp__{serverName}__{toolName}` — matches Claude Code's
 * `__` separator so the LLM can distinguish MCP tools from built-ins.
 *
 * Design decisions:
 * - Input/Output are both [JsonElement]: the remote schema is forwarded as-is so
 *   the LLM sees the exact schema the MCP server advertises.
 * - `parseInput` is the identity — input arrives from the LLM as JSON and is
 *   forwarded without re-serialization.
 * - `checkPermissions` always returns Ask (MCP tools are opaque; we don't know
 *   whether they mutate state). M5 may add per-server permission rules.
 * - `isConcurrencySafe` is false (conservative default for remote tools).
 * - `isDestructive` is true (conservative default).
 */
fun mcpProxyTool(
    serverName: String,
    spec: McpToolSpec,
    client: McpClient,
): Tool<JsonElement, McpCallResult> {
    val toolId = "mcp__${serverName}__${spec.name}"
    val json = Json { ignoreUnknownKeys = true }

    return tool {
        name = toolId
        description = spec.description.ifBlank { "MCP tool '$toolId'" }
        inputSchema = spec.inputSchema

        parse { raw: JsonElement -> raw }

        validate { input, _ ->
            val obj = runCatching { input.jsonObject }.getOrNull()
                ?: return@validate ValidationResult.Failed("MCP tool input must be a JSON object", errorCode = 1)
            val totalBytes = json.encodeToString(JsonElement.serializer(), input).length
            if (totalBytes > MAX_INPUT_BYTES) {
                ValidationResult.Failed("Input exceeds ${MAX_INPUT_BYTES / 1024}KB limit", errorCode = 2)
            } else {
                ValidationResult.Ok
            }
        }

        checkPermissions { input, ctx ->
            PermissionResult.Ask(
                reason = "Call MCP tool $toolId",
                preview = PreviewResult(
                    summary = "Invoke $toolId on server '$serverName'",
                    details = "Arguments:\n${json.encodeToString(JsonElement.serializer(), input)}",
                    risk = PreviewResult.Risk.MEDIUM,
                ),
            )
        }

        // No preview() — MCP servers don't expose a dry-run mechanism in the MVP.

        call { input, _, onProgress ->
            onProgress(com.github.codeplangui.tools.Progress.Status("Calling $toolId…"))
            val result = client.call(spec.name, input)
            ToolResult(result)
        }

        mapResult { result, toolUseId ->
            val content = buildString {
                if (result.isError) appendLine("error: true")
                append(result.textContent())
            }
            ToolResultBlock(
                toolUseId = toolUseId,
                content = content,
                isError = result.isError,
            )
        }

        isConcurrencySafe { false }
        isReadOnly { false }
        isDestructive { true }

        activityDescription { _ -> "Calling $toolId" }
    }
}

private const val MAX_INPUT_BYTES = 1024 * 1024  // 1MB
