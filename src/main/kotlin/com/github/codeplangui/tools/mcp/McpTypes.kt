package com.github.codeplangui.tools.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Configuration for an external MCP server launched via stdio. */
data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

// ─── JSON-RPC 2.0 wire types ────────────────────────────────────────────────

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

// ─── MCP protocol types ──────────────────────────────────────────────────────

/** A single tool advertised by an MCP server. */
@Serializable
data class McpToolSpec(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class ToolListResult(val tools: List<McpToolSpec> = emptyList())

/** A single content item in a tools/call response. */
@Serializable
data class McpContentItem(
    val type: String,
    val text: String? = null,
)

/** The result block returned by tools/call. */
@Serializable
data class McpCallResult(
    val content: List<McpContentItem> = emptyList(),
    val isError: Boolean = false,
) {
    /** Flatten all text content items into a single string. */
    fun textContent(): String = content
        .filter { it.type == "text" }
        .joinToString("\n") { it.text ?: "" }
        .trimEnd()
}

/** Thrown when the MCP server returns a JSON-RPC error or the process dies. */
class McpException(message: String, val code: Int = -1) : RuntimeException(message)

internal const val MCP_CALL_TIMEOUT_MS: Long = 30_000
internal const val MCP_PROTOCOL_VERSION: String = "2024-11-05"
