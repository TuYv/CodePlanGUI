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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

private fun toolUseBlock(id: String, input: JsonElement) =
    ToolUseBlock(toolUseId = id, name = WriteFileTool.name, input = input)

class WriteFileToolTest {

    @TempDir
    lateinit var tmp: Path

    private fun ctx(
        mode: ToolPermissionContext.Mode = ToolPermissionContext.Mode.ACCEPT_EDITS,
        onPermission: suspend (ToolUpdate.PermissionAsked) -> Boolean = { true },
    ): ToolExecutionContext {
        val project = mockk<Project>()
        every { project.basePath } returns tmp.toString()
        return ToolExecutionContext(
            project = project,
            toolUseId = "test-id",
            abortJob = Job(),
            permissionContext = ToolPermissionContext.default().copy(mode = mode),
            onPermissionAsked = onPermission,
        )
    }

    // ─── validate ────────────────────────────────────────────────────

    @Test
    fun `validate rejects blank path`() = runBlocking {
        val result = WriteFileTool.validateInput(WriteFileInput("", "content"), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate accepts valid input`() = runBlocking {
        val result = WriteFileTool.validateInput(WriteFileInput("file.txt", "hello"), ctx())
        assertEquals(ValidationResult.Ok, result)
    }

    // ─── permissions ─────────────────────────────────────────────────

    @Test
    fun `permissions deny when project path is unavailable`() = runBlocking {
        val project = mockk<Project>()
        every { project.basePath } returns null
        val ctx = ToolExecutionContext(project = project, toolUseId = "t", abortJob = Job())
        val result = WriteFileTool.checkPermissions(WriteFileInput("a.txt", "x"), ctx)
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `permissions deny for path outside workspace`() = runBlocking {
        val result = WriteFileTool.checkPermissions(WriteFileInput("../../etc/passwd", "bad"), ctx())
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `permissions allow in ACCEPT_EDITS mode`() = runBlocking {
        val result = WriteFileTool.checkPermissions(WriteFileInput("new.txt", "hi"), ctx(ToolPermissionContext.Mode.ACCEPT_EDITS))
        assertTrue(result is PermissionResult.Allow)
    }

    @Test
    fun `permissions allow in BYPASS mode`() = runBlocking {
        val result = WriteFileTool.checkPermissions(WriteFileInput("new.txt", "hi"), ctx(ToolPermissionContext.Mode.BYPASS))
        assertTrue(result is PermissionResult.Allow)
    }

    @Test
    fun `permissions ask in DEFAULT mode`() = runBlocking {
        val result = WriteFileTool.checkPermissions(WriteFileInput("new.txt", "hi"), ctx(ToolPermissionContext.Mode.DEFAULT))
        assertTrue(result is PermissionResult.Ask)
    }

    // ─── call ────────────────────────────────────────────────────────

    @Test
    fun `creates new file with correct content`() = runBlocking {
        val input = buildJsonObject { put("path", "hello.txt"); put("content", "world") }
        val update = runToolUse(WriteFileTool, toolUseBlock("w1", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        val file = tmp.resolve("hello.txt").toFile()
        assertTrue(file.exists())
        assertEquals("world", file.readText())
        assertTrue(update.block.content.contains("Created"))
    }

    @Test
    fun `overwrites existing file`() = runBlocking {
        val file = tmp.resolve("existing.txt").toFile().also { it.writeText("old") }
        val input = buildJsonObject { put("path", "existing.txt"); put("content", "new") }
        val update = runToolUse(WriteFileTool, toolUseBlock("w2", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertEquals("new", file.readText())
        assertTrue(update.block.content.contains("Wrote"))
    }

    @Test
    fun `creates parent directories`() = runBlocking {
        val input = buildJsonObject { put("path", "a/b/c/deep.txt"); put("content", "deep") }
        runToolUse(WriteFileTool, toolUseBlock("w3", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(tmp.resolve("a/b/c/deep.txt").toFile().exists())
    }

    @Test
    fun `reports bytes written`() = runBlocking {
        val input = buildJsonObject { put("path", "bytes.txt"); put("content", "abc") }
        val update = runToolUse(WriteFileTool, toolUseBlock("w4", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("3 bytes"))
    }

    @Test
    fun `fails for path outside workspace`() = runBlocking {
        val input = buildJsonObject { put("path", "../../evil.txt"); put("content", "x") }
        val updates = runToolUse(WriteFileTool, toolUseBlock("w5", input), ctx()).toList()
        assertTrue(updates.any { it is ToolUpdate.Failed })
    }
}
