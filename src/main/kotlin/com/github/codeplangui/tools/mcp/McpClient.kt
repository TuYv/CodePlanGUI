package com.github.codeplangui.tools.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stdio JSON-RPC 2.0 client for an MCP server process.
 *
 * Lifecycle:
 *   1. [connect] — sends `initialize` + `notifications/initialized`, then calls `tools/list`.
 *   2. [call]    — sends `tools/call` and awaits the response.
 *   3. [close]   — cancels the reader and destroys the process.
 *
 * Inject [input]/[output] directly for unit tests (see [fromStreams]).
 * Production callers use [fromConfig] which launches the child process.
 */
class McpClient internal constructor(
    val serverName: String,
    private val process: Process?,
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
) : Closeable {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val idSeq = AtomicInteger(0)
    private val writer = output.bufferedWriter(Charsets.UTF_8)
    private var readerJob: Job? = null

    /** Start the reader loop and perform the MCP handshake. Returns the server's tool list. */
    suspend fun connect(): List<McpToolSpec> {
        startReader()

        sendRequest(
            "initialize",
            buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "CodePlanGUI")
                    put("version", "1.0")
                })
            },
        )
        sendNotification("notifications/initialized")

        val result = sendRequest("tools/list")
        return json.decodeFromJsonElement(ToolListResult.serializer(), result).tools
    }

    /** Execute a tool on the MCP server and return the call result. */
    suspend fun call(toolName: String, arguments: JsonElement): McpCallResult {
        val result = sendRequest(
            "tools/call",
            buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            },
        )
        return json.decodeFromJsonElement(McpCallResult.serializer(), result)
    }

    override fun close() {
        readerJob?.cancel()
        runCatching { writer.close() }
        process?.destroyForcibly()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private fun startReader() {
        readerJob = scope.launch(Dispatchers.IO) {
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        val resp = json.decodeFromString(JsonRpcResponse.serializer(), line)
                        val id = resp.id ?: return@forEachLine
                        val deferred = pending.remove(id) ?: return@forEachLine
                        when {
                            resp.error != null ->
                                deferred.completeExceptionally(McpException(resp.error.message, resp.error.code))
                            else ->
                                deferred.complete(resp.result ?: JsonNull)
                        }
                    } catch (_: Exception) {
                        // Ignore malformed lines (stderr leakage, debug output, etc.)
                    }
                }
            }
            // Process closed — fail any requests that never got a response.
            val err = McpException("MCP server '$serverName' process ended unexpectedly", -32000)
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
        }
    }

    private suspend fun sendRequest(method: String, params: JsonElement? = null): JsonElement {
        val id = idSeq.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred

        val req = JsonRpcRequest(id = id, method = method, params = params)
        withContext(Dispatchers.IO) {
            writer.write(json.encodeToString(JsonRpcRequest.serializer(), req))
            writer.newLine()
            writer.flush()
        }

        return withTimeout(MCP_CALL_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun sendNotification(method: String, params: JsonElement? = null) {
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                params?.let { put("params", it) }
            }
            writer.write(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
            writer.newLine()
            writer.flush()
        }
    }

    companion object {
        /**
         * Spawn a child process from [config] and wire its stdio to a new client.
         * The caller is responsible for eventually calling [close].
         */
        fun fromConfig(config: McpServerConfig, scope: CoroutineScope): McpClient {
            val proc = ProcessBuilder(buildList { add(config.command); addAll(config.args) })
                .apply { config.env.forEach { (k, v) -> environment()[k] = v } }
                .redirectErrorStream(false)
                .start()
            return McpClient(config.name, proc, proc.inputStream, proc.outputStream, scope)
        }

        /** For unit tests: inject arbitrary streams instead of a real process. */
        fun fromStreams(
            serverName: String,
            input: InputStream,
            output: OutputStream,
            scope: CoroutineScope,
        ): McpClient = McpClient(serverName, process = null, input, output, scope)
    }
}
