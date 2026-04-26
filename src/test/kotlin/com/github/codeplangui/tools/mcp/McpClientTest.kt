package com.github.codeplangui.tools.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Unit tests for [McpClient] using piped streams as a fake MCP server.
 *
 * Architecture: each test has two sides:
 *   - client side: the [McpClient] under test (writes to serverIn, reads from serverOut)
 *   - server side: the test writes responses to serverOut and reads requests from serverIn
 */
class McpClientTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Pipes: client reads from clientIn (server writes there), client writes to clientOut (server reads there)
    private val serverToClientOut = PipedOutputStream()
    private val clientIn = PipedInputStream(serverToClientOut)
    private val clientToServerOut = PipedOutputStream()
    private val serverIn = PipedInputStream(clientToServerOut)

    private lateinit var scope: CoroutineScope
    private lateinit var client: McpClient
    private val serverWriter = serverToClientOut.bufferedWriter(Charsets.UTF_8)
    private val serverReader = serverIn.bufferedReader(Charsets.UTF_8)

    @BeforeEach
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        client = McpClient.fromStreams("test-server", clientIn, clientToServerOut, scope)
    }

    @AfterEach
    fun tearDown() {
        client.close()
        scope.cancel()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun respond(id: Int, result: kotlinx.serialization.json.JsonElement) {
        val resp = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
        serverWriter.write(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), resp))
        serverWriter.newLine()
        serverWriter.flush()
    }

    private fun respondError(id: Int, code: Int, message: String) {
        val resp = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
        serverWriter.write(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), resp))
        serverWriter.newLine()
        serverWriter.flush()
    }

    private fun readRequest(): kotlinx.serialization.json.JsonObject {
        val line = serverReader.readLine() ?: error("Server stream closed unexpectedly")
        return json.decodeFromString(kotlinx.serialization.json.JsonObject.serializer(), line)
    }

    /** Drain initialize + notifications/initialized, then respond to them. */
    private fun handshake() {
        // 1. initialize request
        val init = readRequest()
        assertEquals("initialize", init["method"]?.let { (it as JsonPrimitive).content })
        val initId = (init["id"] as JsonPrimitive).content.toInt()
        respond(initId, buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("serverInfo", buildJsonObject { put("name", "test-server") })
        })

        // 2. notifications/initialized (no response needed — it's a notification)
        val notif = readRequest()
        assertEquals("notifications/initialized", notif["method"]?.let { (it as JsonPrimitive).content })
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `connect performs handshake and returns tool list`() = runBlocking {
        // Drive the server in background
        Thread {
            handshake()
            // tools/list request
            val listReq = readRequest()
            assertEquals("tools/list", (listReq["method"] as JsonPrimitive).content)
            val listId = (listReq["id"] as JsonPrimitive).content.toInt()
            respond(listId, buildJsonObject {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "echo")
                        put("description", "Echoes input")
                        put("inputSchema", buildJsonObject { put("type", "object") })
                    })
                })
            })
        }.also { it.isDaemon = true }.start()

        val tools = client.connect()

        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
        assertEquals("Echoes input", tools[0].description)
    }

    @Test
    fun `call sends tools-call request and returns parsed result`() = runBlocking {
        Thread {
            handshake()
            // tools/list
            val listReq = readRequest()
            respond((listReq["id"] as JsonPrimitive).content.toInt(), buildJsonObject {
                put("tools", buildJsonArray {})
            })
            // tools/call
            val callReq = readRequest()
            assertEquals("tools/call", (callReq["method"] as JsonPrimitive).content)
            val callId = (callReq["id"] as JsonPrimitive).content.toInt()
            respond(callId, buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "hello") })
                })
                put("isError", false)
            })
        }.also { it.isDaemon = true }.start()

        client.connect()
        val result = client.call("echo", buildJsonObject { put("msg", "hello") })

        assertFalse(result.isError)
        assertEquals("hello", result.textContent())
    }

    @Test
    fun `call propagates JSON-RPC error as McpException`() = runBlocking {
        Thread {
            handshake()
            val listReq = readRequest()
            respond((listReq["id"] as JsonPrimitive).content.toInt(), buildJsonObject { put("tools", buildJsonArray {}) })
            val callReq = readRequest()
            val callId = (callReq["id"] as JsonPrimitive).content.toInt()
            respondError(callId, -32600, "Invalid request")
        }.also { it.isDaemon = true }.start()

        client.connect()
        val ex = runCatching { client.call("bad", JsonNull) }.exceptionOrNull()
        assertTrue(ex is McpException, "Expected McpException, got $ex")
        assertTrue(ex!!.message!!.contains("Invalid request"))
    }

    @Test
    fun `malformed lines in server output are silently ignored`() = runBlocking {
        Thread {
            handshake()
            val listReq = readRequest()
            val listId = (listReq["id"] as JsonPrimitive).content.toInt()
            // Inject garbage before the real response
            serverWriter.write("NOT JSON AT ALL\n")
            serverWriter.flush()
            respond(listId, buildJsonObject { put("tools", buildJsonArray {}) })
        }.also { it.isDaemon = true }.start()

        val tools = client.connect()
        assertEquals(0, tools.size)  // garbage was ignored, real response arrived
    }

    @Test
    fun `textContent joins multiple text items`() {
        val result = McpCallResult(
            content = listOf(
                McpContentItem("text", "line1"),
                McpContentItem("image", null),  // non-text, skipped
                McpContentItem("text", "line2"),
            )
        )
        assertEquals("line1\nline2", result.textContent())
    }
}
