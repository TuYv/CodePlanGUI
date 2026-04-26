package com.github.codeplangui.tools.mcp

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.ToolExecutionContext
import com.github.codeplangui.tools.ToolPermissionContext
import com.github.codeplangui.tools.ValidationResult
import com.intellij.openapi.project.Project
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpProxyToolTest {

    private lateinit var mockClient: McpClient
    private lateinit var ctx: ToolExecutionContext

    @BeforeEach
    fun setup() {
        mockClient = mockk()
        ctx = ToolExecutionContext(
            project = mockk<Project>().also { every { it.basePath } returns "/tmp" },
            toolUseId = "test-id",
            abortJob = Job(),
            permissionContext = ToolPermissionContext.default(),
        )
    }

    private fun spec(name: String = "echo", description: String = "Echoes text") = McpToolSpec(
        name = name,
        description = description,
        inputSchema = buildJsonObject { put("type", "object") },
    )

    // ─── Name and schema ─────────────────────────────────────────────────────

    @Test
    fun `tool name follows mcp__server__tool convention`() {
        val tool = mcpProxyTool("my_server", spec("do_thing"), mockClient)
        assertEquals("mcp__my_server__do_thing", tool.name)
    }

    @Test
    fun `tool description inherits from spec`() {
        val tool = mcpProxyTool("srv", spec(description = "Does stuff"), mockClient)
        assertTrue(tool.description.contains("Does stuff"))
    }

    @Test
    fun `tool uses blank fallback description when spec description is empty`() {
        val tool = mcpProxyTool("srv", spec(description = ""), mockClient)
        assertTrue(tool.description.isNotBlank())
    }

    @Test
    fun `inputSchema is forwarded from spec`() {
        val schema = buildJsonObject { put("type", "object"); put("required", buildJsonObject {}) }
        val tool = mcpProxyTool("srv", McpToolSpec("t", "d", schema as JsonObject), mockClient)
        assertEquals(schema, tool.inputSchema)
    }

    // ─── Metadata ────────────────────────────────────────────────────────────

    @Test
    fun `metadata flags are conservative`() = runBlocking {
        val tool = mcpProxyTool("srv", spec(), mockClient)
        val input = JsonNull
        assertFalse(tool.isConcurrencySafe(input))
        assertFalse(tool.isReadOnly(input))
        assertTrue(tool.isDestructive(input))
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    fun `validate rejects non-object input`() = runBlocking {
        val tool = mcpProxyTool("srv", spec(), mockClient)
        val result = tool.validateInput(JsonPrimitive("not an object"), ctx)
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate accepts object input`() = runBlocking {
        val tool = mcpProxyTool("srv", spec(), mockClient)
        val result = tool.validateInput(buildJsonObject { put("text", "hi") }, ctx)
        assertEquals(ValidationResult.Ok, result)
    }

    // ─── checkPermissions ────────────────────────────────────────────────────

    @Test
    fun `checkPermissions always returns Ask`() = runBlocking {
        val tool = mcpProxyTool("srv", spec(), mockClient)
        val perm = tool.checkPermissions(JsonNull, ctx)
        assertTrue(perm is PermissionResult.Ask)
    }

    @Test
    fun `Ask preview contains tool name and server name`() = runBlocking {
        val tool = mcpProxyTool("my_srv", spec("my_tool"), mockClient)
        val ask = tool.checkPermissions(buildJsonObject {}, ctx) as PermissionResult.Ask
        assertTrue(ask.preview?.summary?.contains("my_srv") == true)
        assertTrue(ask.reason.contains("mcp__my_srv__my_tool"))
    }

    // ─── call ────────────────────────────────────────────────────────────────

    @Test
    fun `call delegates to client and returns result`() = runBlocking {
        val tool = mcpProxyTool("srv", spec("echo"), mockClient)
        val args = buildJsonObject { put("text", "hello") }
        val mcpResult = McpCallResult(content = listOf(McpContentItem("text", "hello")), isError = false)

        coEvery { mockClient.call("echo", args) } returns mcpResult

        val result = tool.call(args, ctx)
        assertEquals(mcpResult, result.data)
    }

    // ─── mapResult ───────────────────────────────────────────────────────────

    @Test
    fun `mapResult sets isError from call result`() = runBlocking {
        val tool = mcpProxyTool("srv", spec(), mockClient)
        val errResult = McpCallResult(content = listOf(McpContentItem("text", "boom")), isError = true)
        val block = tool.mapResultToApiBlock(errResult, "u1")
        assertTrue(block.isError)
        assertTrue(block.content.contains("boom"))
    }

    @Test
    fun `mapResult includes error flag in content for error results`() = runBlocking {
        val tool = mcpProxyTool("srv", spec(), mockClient)
        val errResult = McpCallResult(content = listOf(McpContentItem("text", "fail")), isError = true)
        val block = tool.mapResultToApiBlock(errResult, "u1")
        assertTrue(block.content.contains("error: true"))
    }
}
