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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private fun toolUseBlock(id: String, input: JsonElement) =
    ToolUseBlock(toolUseId = id, name = FileListTool.name, input = input)

class FileListToolTest {

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

    private fun mkFile(rel: String): java.io.File =
        tmp.resolve(rel).toFile().also { it.parentFile?.mkdirs(); it.createNewFile() }

    // ─── validate ────────────────────────────────────────────────────

    @Test
    fun `validate rejects blank path`() = runBlocking {
        val result = FileListTool.validateInput(FileListInput(""), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate accepts dot path`() = runBlocking {
        val result = FileListTool.validateInput(FileListInput("."), ctx())
        assertTrue(result is ValidationResult.Ok)
    }

    // ─── permissions ─────────────────────────────────────────────────

    @Test
    fun `permissions deny when project path unavailable`() = runBlocking {
        val project = mockk<Project>()
        every { project.basePath } returns null
        val ctx = ToolExecutionContext(project = project, toolUseId = "t", abortJob = Job())
        val result = FileListTool.checkPermissions(FileListInput("."), ctx)
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `permissions deny for path outside workspace`() = runBlocking {
        val result = FileListTool.checkPermissions(FileListInput("../../etc"), ctx())
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `permissions allow for workspace path`() = runBlocking {
        val result = FileListTool.checkPermissions(FileListInput("."), ctx())
        assertTrue(result is PermissionResult.Allow)
    }

    // ─── call ────────────────────────────────────────────────────────

    @Test
    fun `lists files in project root`() = runBlocking {
        mkFile("alpha.txt")
        mkFile("beta.txt")
        val input = buildJsonObject { put("path", ".") }
        val update = runToolUse(FileListTool, toolUseBlock("l1", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("alpha.txt"))
        assertTrue(update.block.content.contains("beta.txt"))
    }

    @Test
    fun `lists recursively when recursive=true`() = runBlocking {
        mkFile("sub/deep.kt")
        val input = buildJsonObject { put("path", "."); put("recursive", true) }
        val update = runToolUse(FileListTool, toolUseBlock("l2", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains("deep.kt"))
    }

    @Test
    fun `excludes hidden files by default`() = runBlocking {
        mkFile(".hidden")
        mkFile("visible.txt")
        val input = buildJsonObject { put("path", ".") }
        val update = runToolUse(FileListTool, toolUseBlock("l3", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(!update.block.content.contains(".hidden"))
        assertTrue(update.block.content.contains("visible.txt"))
    }

    @Test
    fun `includes hidden files when includeHidden=true`() = runBlocking {
        mkFile(".hidden")
        val input = buildJsonObject { put("path", "."); put("includeHidden", true) }
        val update = runToolUse(FileListTool, toolUseBlock("l4", input), ctx())
            .filterIsInstance<ToolUpdate.Completed>().first()
        assertTrue(update.block.content.contains(".hidden"))
    }

    @Test
    fun `fails for non-existent path`() = runBlocking {
        val input = buildJsonObject { put("path", "no_such_dir") }
        val updates = runToolUse(FileListTool, toolUseBlock("l5", input), ctx()).toList()
        assertTrue(updates.any { it is ToolUpdate.Failed })
    }

    @Test
    fun `fails for path outside workspace`() = runBlocking {
        val input = buildJsonObject { put("path", "../../") }
        val updates = runToolUse(FileListTool, toolUseBlock("l6", input), ctx()).toList()
        assertTrue(updates.any { it is ToolUpdate.Failed })
    }
}
