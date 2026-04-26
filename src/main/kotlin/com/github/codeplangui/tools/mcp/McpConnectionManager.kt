package com.github.codeplangui.tools.mcp

import com.github.codeplangui.tools.Tool
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of external MCP server connections for a single project.
 *
 * Responsibilities:
 *   - Spawn child processes from [McpServerConfig]
 *   - Run the MCP handshake and collect the server's tool list
 *   - Expose all active MCP tools as [Tool] instances (for use by [ToolRegistry.assembleToolPool])
 *   - Tear down connections when the project closes (via [Disposable])
 *
 * M5 will extend this with:
 *   - Reconnection with exponential backoff on process death
 *   - Heartbeat / ping monitoring
 *   - Config reload without restarting the plugin
 */
class McpConnectionManager(
    @Suppress("unused") private val project: Project,
) : Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val _tools = ConcurrentHashMap<String, List<Tool<JsonElement, McpCallResult>>>()

    /** All currently active MCP proxy tools, flat across all connected servers. */
    val tools: List<Tool<*, *>>
        get() = _tools.values.flatten()

    /**
     * Connect to an MCP server: spawns the process, performs the handshake, and
     * registers the remote tools. Safe to call from a coroutine.
     * Replaces any existing connection for [config.name] without error.
     */
    suspend fun addServer(config: McpServerConfig) {
        removeServer(config.name)

        val client = McpClient.fromConfig(config, scope)
        val specs = try {
            client.connect()
        } catch (t: Throwable) {
            client.close()
            throw McpException("Failed to connect to MCP server '${config.name}': ${t.message}")
        }

        clients[config.name] = client
        _tools[config.name] = specs.map { spec -> mcpProxyTool(config.name, spec, client) }
    }

    /**
     * Disconnect and remove all tools from [serverName].
     * No-op if the server was not registered.
     */
    fun removeServer(serverName: String) {
        clients.remove(serverName)?.close()
        _tools.remove(serverName)
    }

    /** Tear down all connections — called by the IntelliJ plugin lifecycle on project close. */
    override fun dispose() {
        clients.keys.toList().forEach { removeServer(it) }
        scope.cancel()
    }
}
