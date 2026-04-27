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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class FileEditToolTest {

    @TempDir
    lateinit var tmp: Path

    private fun ctx(
        mode: ToolPermissionContext.Mode = ToolPermissionContext.Mode.DEFAULT,
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

    private fun writeFile(name: String, content: String): File =
        tmp.resolve(name).toFile().also { it.writeText(content, Charsets.UTF_8) }

    // ─── validate ────────────────────────────────────────────────────

    @Test
    fun `validate rejects blank path`() = runBlocking {
        val result = FileEditTool.validateInput(FileEditInput("", "old", "new"), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate rejects empty oldString`() = runBlocking {
        val result = FileEditTool.validateInput(FileEditInput("file.txt", "", "new"), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate rejects identical oldString and newString`() = runBlocking {
        val result = FileEditTool.validateInput(FileEditInput("file.txt", "same", "same"), ctx())
        assertTrue(result is ValidationResult.Failed)
    }

    @Test
    fun `validate passes for valid input`() = runBlocking {
        val result = FileEditTool.validateInput(FileEditInput("file.txt", "old", "new"), ctx())
        assertEquals(ValidationResult.Ok, result)
    }

    // ─── checkPermissions ────────────────────────────────────────────

    @Test
    fun `checkPermissions denies when file not found`() = runBlocking {
        val result = FileEditTool.checkPermissions(
            FileEditInput("nonexistent.txt", "old", "new"), ctx()
        )
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `checkPermissions denies when path escapes workspace`() = runBlocking {
        val result = FileEditTool.checkPermissions(
            FileEditInput("../../etc/passwd", "old", "new"), ctx()
        )
        assertTrue(result is PermissionResult.Deny)
    }

    @Test
    fun `checkPermissions denies when oldString not found in file`() = runBlocking {
        writeFile("target.txt", "hello world")
        val result = FileEditTool.checkPermissions(
            FileEditInput("target.txt", "MISSING", "new"), ctx()
        )
        val denied = result as PermissionResult.Deny
        assertTrue(denied.reason.contains("not found"))
    }

    @Test
    fun `checkPermissions returns Ask in DEFAULT mode`() = runBlocking {
        writeFile("target.txt", "foo bar baz")
        val result = FileEditTool.checkPermissions(
            FileEditInput("target.txt", "bar", "qux"), ctx()
        )
        assertTrue(result is PermissionResult.Ask)
        val ask = result as PermissionResult.Ask
        assertFalse(ask.preview?.details.isNullOrBlank())
    }

    @Test
    fun `checkPermissions returns Allow in ACCEPT_EDITS mode`() = runBlocking {
        writeFile("target.txt", "foo bar baz")
        val result = FileEditTool.checkPermissions(
            FileEditInput("target.txt", "bar", "qux"),
            ctx(mode = ToolPermissionContext.Mode.ACCEPT_EDITS),
        )
        assertTrue(result is PermissionResult.Allow)
    }

    @Test
    fun `checkPermissions returns Allow in BYPASS mode`() = runBlocking {
        writeFile("target.txt", "foo bar baz")
        val result = FileEditTool.checkPermissions(
            FileEditInput("target.txt", "bar", "qux"),
            ctx(mode = ToolPermissionContext.Mode.BYPASS),
        )
        assertTrue(result is PermissionResult.Allow)
    }

    // ─── preview ─────────────────────────────────────────────────────

    @Test
    fun `preview returns null when oldString absent`() = runBlocking {
        writeFile("target.txt", "no match here")
        val result = FileEditTool.preview(FileEditInput("target.txt", "MISSING", "x"), ctx())
        assertNull(result)
    }

    @Test
    fun `preview returns diff details when oldString found`() = runBlocking {
        writeFile("target.txt", "line1\nfoo\nline3")
        val result = FileEditTool.preview(FileEditInput("target.txt", "foo", "bar"), ctx())
        assertTrue(result != null)
        assertTrue(result!!.details!!.contains("-foo"))
        assertTrue(result.details!!.contains("+bar"))
    }

    // ─── call ────────────────────────────────────────────────────────

    @Test
    fun `call replaces first occurrence only by default`() = runBlocking {
        val file = writeFile("edit.txt", "aaa bbb aaa")
        val result = FileEditTool.call(
            FileEditInput("edit.txt", "aaa", "ZZZ"),
            ctx(mode = ToolPermissionContext.Mode.BYPASS),
        )
        assertEquals("ZZZ bbb aaa", file.readText())
        assertEquals(1, result.data.replacements)
    }

    @Test
    fun `call replaces all occurrences when replaceAll is true`() = runBlocking {
        val file = writeFile("edit.txt", "aaa bbb aaa")
        val result = FileEditTool.call(
            FileEditInput("edit.txt", "aaa", "ZZZ", replaceAll = true),
            ctx(mode = ToolPermissionContext.Mode.BYPASS),
        )
        assertEquals("ZZZ bbb ZZZ", file.readText())
        assertEquals(2, result.data.replacements)
    }

    @Test
    fun `call produces a unified diff in output`() = runBlocking {
        writeFile("edit.txt", "line1\nold\nline3")
        val result = FileEditTool.call(
            FileEditInput("edit.txt", "old", "new"),
            ctx(mode = ToolPermissionContext.Mode.BYPASS),
        )
        assertTrue(result.data.diff.contains("-old"))
        assertTrue(result.data.diff.contains("+new"))
    }

    // ─── metadata ────────────────────────────────────────────────────

    @Test
    fun `FileEditTool metadata flags are correct`() = runBlocking {
        val input = FileEditInput("f.txt", "a", "b")
        assertFalse(FileEditTool.isConcurrencySafe(input))
        assertFalse(FileEditTool.isReadOnly(input))
        assertTrue(FileEditTool.isDestructive(input))
    }

    // ─── permission denied via callback ──────────────────────────────

    @Test
    fun `runToolUse emits Failed(PERMISSION) when callback denies`() = runBlocking {
        writeFile("edit.txt", "hello world")
        val toolUse = com.github.codeplangui.tools.ToolUseBlock(
            toolUseId = "u1",
            name = "FileEdit",
            input = buildJsonObject {
                put("path", "edit.txt")
                put("oldString", "hello")
                put("newString", "bye")
            },
        )
        val updates = runToolUse(FileEditTool, toolUse, ctx(onPermission = { false })).toList()
        val failed = updates.filterIsInstance<ToolUpdate.Failed>().firstOrNull()
        assertTrue(failed != null, "Expected a Failed update")
        assertEquals(ToolUpdate.Failed.Stage.PERMISSION, failed!!.stage)
    }

    @Test
    fun `runToolUse emits PermissionAsked before invoking callback`() = runBlocking {
        writeFile("edit.txt", "hello world")
        val toolUse = com.github.codeplangui.tools.ToolUseBlock(
            toolUseId = "u2",
            name = "FileEdit",
            input = buildJsonObject {
                put("path", "edit.txt")
                put("oldString", "hello")
                put("newString", "bye")
            },
        )
        val asked = runToolUse(FileEditTool, toolUse, ctx())
            .filterIsInstance<ToolUpdate.PermissionAsked>()
            .first()
        assertEquals("FileEdit", asked.toolName)
    }

    // ─── buildUnifiedDiff unit tests ──────────────────────────────────

    @Test
    fun `buildUnifiedDiff shows changed lines with context`() {
        val original = "line1\nline2\nline3\nline4\nline5"
        val modified = "line1\nline2\nchanged3\nline4\nline5"
        val diff = buildUnifiedDiff(original, modified, "test.txt")
        assertTrue(diff.contains("-line3"))
        assertTrue(diff.contains("+changed3"))
        assertTrue(diff.contains(" line2"))
        assertTrue(diff.contains(" line4"))
    }

    @Test
    fun `buildUnifiedDiff returns no-change message for identical content`() {
        val text = "same\ncontent"
        val diff = buildUnifiedDiff(text, text, "test.txt")
        assertEquals("(no line changes)", diff)
    }

    @Test
    fun `buildUnifiedDiff handles insertion`() {
        val original = "a\nb"
        val modified = "a\ninserted\nb"
        val diff = buildUnifiedDiff(original, modified, "test.txt")
        assertTrue(diff.contains("+inserted"))
        assertFalse(diff.contains("-inserted"))
    }

    @Test
    fun `buildUnifiedDiff handles deletion`() {
        val original = "a\ndelete_me\nb"
        val modified = "a\nb"
        val diff = buildUnifiedDiff(original, modified, "test.txt")
        assertTrue(diff.contains("-delete_me"))
        assertFalse(diff.contains("+delete_me"))
    }
}
