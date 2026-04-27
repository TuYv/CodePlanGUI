package com.github.codeplangui.tools

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    private data class Dummy(val x: String)

    private fun dummyTool(n: String, vararg extraAliases: String): Tool<Dummy, Dummy> = tool {
        name = n
        description = "dummy"
        aliases = extraAliases.toList()
        parse { _ -> Dummy("") }
        call { d, _, _ -> ToolResult(d) }
        mapResult { _, id -> ToolResultBlock(id, "") }
    }

    private fun ctx(deny: Set<String> = emptySet()): ToolExecutionContext = ToolExecutionContext(
        project = mockk<Project>().also { every { it.basePath } returns "/tmp" },
        toolUseId = "t",
        abortJob = Job(),
        permissionContext = ToolPermissionContext.default().copy(alwaysDeny = deny),
    )

    @Test
    fun `getAllBaseTools returns non-empty registry including Bash FileEdit and FileRead`() {
        val tools = ToolRegistry.getAllBaseTools()
        val names = tools.map { it.name }.toSet()
        assertTrue("Bash" in names, "Bash must be registered")
        assertTrue("FileEdit" in names, "FileEdit must be registered")
        assertTrue("FileRead" in names, "FileRead must be registered")
    }

    @Test
    fun `assembleToolPool sorts by name and dedupes built-in over mcp on name collision`() {
        val builtin = listOf(dummyTool("Bash"), dummyTool("FileRead"))
        val mcp = listOf(dummyTool("FileRead"), dummyTool("MCPExtra"))  // duplicate name
        val pool = ToolRegistry.assembleToolPool(baseTools = builtin, mcpTools = mcp)

        assertEquals(listOf("Bash", "FileRead", "MCPExtra"), pool.map { it.name })
        // The FileRead in the pool must be the built-in, not the MCP one.
        // Identity check is fine here — dedupe should keep the first occurrence.
        assertTrue(pool[1] === builtin[1])
    }

    @Test
    fun `filterToolsByDenyRules removes exact-name matches`() {
        val tools = listOf(dummyTool("Bash"), dummyTool("FileRead"))
        val filtered = ToolRegistry.filterToolsByDenyRules(tools, ctx(deny = setOf("Bash")))
        assertEquals(listOf("FileRead"), filtered.map { it.name })
    }

    @Test
    fun `filterToolsByDenyRules removes parametrized rule matches like Bash(git push)`() {
        val tools = listOf(dummyTool("Bash"), dummyTool("FileRead"))
        val filtered = ToolRegistry.filterToolsByDenyRules(tools, ctx(deny = setOf("Bash(git push *)")))
        assertEquals(listOf("FileRead"), filtered.map { it.name })
    }

    @Test
    fun `filterToolsByDenyRules noop when rules empty`() {
        val tools = ToolRegistry.getAllBaseTools()
        val filtered = ToolRegistry.filterToolsByDenyRules(tools, ctx())
        assertEquals(tools.size, filtered.size)
    }

    @Test
    fun `findByName returns tool by primary name`() {
        val pool: List<Tool<*, *>> = listOf(dummyTool("Foo"), dummyTool("Bar"))
        assertNotNull(ToolRegistry.findByName(pool, "Foo"))
        assertNotNull(ToolRegistry.findByName(pool, "Bar"))
        assertNull(ToolRegistry.findByName(pool, "Missing"))
    }

    @Test
    fun `findByName returns tool by alias`() {
        val pool: List<Tool<*, *>> = listOf(dummyTool("Canonical", "LegacyName", "OldName"))
        assertEquals("Canonical", ToolRegistry.findByName(pool, "LegacyName")?.name)
        assertEquals("Canonical", ToolRegistry.findByName(pool, "OldName")?.name)
        assertNull(ToolRegistry.findByName(pool, "Nope"))
    }

    @Test
    fun `assembleToolPool with default args includes real built-in tools`() {
        val pool = ToolRegistry.assembleToolPool()
        val names = pool.map { it.name }
        assertEquals(names.sorted(), names, "pool must be sorted for prompt-cache stability")
        assertTrue("Bash" in names && "FileRead" in names)
    }

    // Ensure Dummy's schema compiles — dummyTool uses JsonObject default
    @Suppress("unused")
    private val schemaSanity: JsonObject = JsonObject(emptyMap())
}
