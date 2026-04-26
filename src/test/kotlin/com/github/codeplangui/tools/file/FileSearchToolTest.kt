package com.github.codeplangui.tools.file

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.ToolExecutionContext
import com.github.codeplangui.tools.ToolPermissionContext
import com.github.codeplangui.tools.ToolUpdate
import com.github.codeplangui.tools.ValidationResult
import com.github.codeplangui.tools.runToolUse
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.github.codeplangui.tools.ToolUseBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private fun toolUseBlock(id: String, input: JsonElement) =
    ToolUseBlock(toolUseId = id, name = FileSearchTool.name, input = input)

class FileSearchToolTest {

    @TempDir
    lateinit var tmp: Path

    private fun ctx(): ToolExecutionContext {
        val project = mockk<Project>()
        every { project.basePath } returns tmp.toString()
        return ToolExecutionContext(
            project = project,
            toolUseId = "test-id",
            abortJob = Job(),
            permissionContext = ToolPermissionContext.default(),
        )
    }

    private fun mkFile(rel: String, content: String): java.io.File =
        tmp.resolve(rel).toFile().also { it.parentFile?.mkdirs(); it.writeText(content) }

    // ─── validate ────────────────────────────────────────────────────

    @Test
    fun `validate rejects blank pattern`() = runBlocking {
        val result = FileSearchTool.validateInput(FileSearchInput(""), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate rejects invalid regex`() = runBlocking {
        val result = FileSearchTool.validateInput(FileSearchInput("[invalid"), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate rejects maxResults out of range`() = runBlocking {
        val result = FileSearchTool.validateInput(FileSearchInput("ok", maxResults = 0), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate accepts valid pattern`() = runBlocking {
        val result = FileSearchTool.validateInput(FileSearchInput("hello"), ctx())
        assertTrue(result is ValidationResult.Ok)
    }

    // ─── permissions ─────────────────────────────────────────────────

    @Test
    fun `permissions deny when project path unavailable`() = runBlocking {
        val project = mockk<Project>()
        every { project.basePath } returns null
        val ctx = ToolExecutionContext(project = project, toolUseId = "t", abortJob = Job())
        val result = FileSearchTool.checkPermissions(FileSearchInput("x"), ctx)
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `permissions deny for path outside workspace`() = runBlocking {
        val result = FileSearchTool.checkPermissions(FileSearchInput("x", path = "../../etc"), ctx())
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `permissions allow for workspace path`() = runBlocking {
        val result = FileSearchTool.checkPermissions(FileSearchInput("x"), ctx())
        assertTrue(result is PermissionResult.Allow)
    }

    // ─── call ────────────────────────────────────────────────────────

    @Test
    fun `finds matching lines`() = runBlocking {
        mkFile("a.txt", "hello world\nfoo bar")
        val input = buildJsonObject { put("pattern", "hello") }
        val update = runToolUse(FileSearchTool, toolUseBlock("s1", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("hello world"))
    }

    @Test
    fun `returns no matches message when nothing found`() = runBlocking {
        mkFile("a.txt", "nothing here")
        val input = buildJsonObject { put("pattern", "xyzzy") }
        val update = runToolUse(FileSearchTool, toolUseBlock("s2", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("no matches"))
    }

    @Test
    fun `glob restricts to matching file types`() = runBlocking {
        mkFile("code.kt", "fun main() {}")
        mkFile("readme.md", "fun main() {}")
        val input = buildJsonObject { put("pattern", "fun main"); put("glob", "*.kt") }
        val update = runToolUse(FileSearchTool, toolUseBlock("s3", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("code.kt"))
        assertFalse(update.block.content.contains("readme.md"))
    }

    @Test
    fun `ignoreCase matches regardless of case`() = runBlocking {
        mkFile("b.txt", "Hello World")
        val input = buildJsonObject { put("pattern", "hello"); put("ignoreCase", true) }
        val update = runToolUse(FileSearchTool, toolUseBlock("s4", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("Hello World"))
    }

    @Test
    fun `case sensitive search does not match wrong case`() = runBlocking {
        mkFile("c.txt", "Hello World")
        val input = buildJsonObject { put("pattern", "hello"); put("ignoreCase", false) }
        val update = runToolUse(FileSearchTool, toolUseBlock("s5", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("no matches"))
    }

    @Test
    fun `maxResults caps the result count`() = runBlocking {
        mkFile("big.txt", (1..20).joinToString("\n") { "line $it match" })
        val input = buildJsonObject { put("pattern", "match"); put("maxResults", 5) }
        val update = runToolUse(FileSearchTool, toolUseBlock("s6", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        val lineCount = update.block.content.lines().count { it.contains("match") }
        assertTrue(lineCount <= 5)
        assertTrue(update.block.content.contains("truncated"))
    }

    @Test
    fun `fails for path outside workspace`() = runBlocking {
        val input = buildJsonObject { put("pattern", "x"); put("path", "../../") }
        val updates = runToolUse(FileSearchTool, toolUseBlock("s7", input), ctx()).toList()
        assertTrue(updates.any { it is ToolUpdate.Failed })
    }
}
