package com.github.codeplangui.tools.file

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.Progress
import com.github.codeplangui.tools.ToolExecutionContext
import com.github.codeplangui.tools.ToolPermissionContext
import com.github.codeplangui.tools.ValidationResult
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class FileReadToolTest {

    private fun contextFor(
        @TempDir base: Path,
        additionalDirs: Set<String> = emptySet(),
    ): ToolExecutionContext {
        val permissionCtx = ToolPermissionContext.default().copy(additionalWorkingDirectories = additionalDirs)
        return ToolExecutionContext(
            project = mockk<Project>().also { every { it.basePath } returns base.toString() },
            toolUseId = "t1",
            abortJob = Job(),
            permissionContext = permissionCtx,
        )
    }

    private fun input(path: String, offset: Int? = null, limit: Int? = null) = buildJsonObject {
        put("path", JsonPrimitive(path))
        offset?.let { put("offset", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
    }

    @Test
    fun `happy path returns line-numbered content with correct totals`(@TempDir base: Path) = runBlocking {
        val file = base.resolve("hello.txt")
        file.writeText("alpha\nbeta\ngamma\n")
        val ctx = contextFor(base)

        val parsed = FileReadTool.parseInput(input("hello.txt"))
        val result = FileReadTool.call(parsed, ctx) { _: Progress -> }
        val out = result.data

        assertEquals(3, out.totalLines)
        assertEquals(3, out.returnedLines)
        assertFalse(out.truncated)
        // Line format: right-padded width, arrow, content
        assertTrue(out.content.contains("1→alpha"))
        assertTrue(out.content.contains("3→gamma"))
    }

    @Test
    fun `offset and limit page through the file correctly`(@TempDir base: Path) = runBlocking {
        val file = base.resolve("big.txt")
        file.writeText((1..20).joinToString("\n") { "line$it" })
        val ctx = contextFor(base)

        val parsed = FileReadTool.parseInput(input("big.txt", offset = 5, limit = 3))
        val out = FileReadTool.call(parsed, ctx) { _: Progress -> }.data

        assertEquals(3, out.returnedLines)
        assertEquals(20, out.totalLines)
        assertTrue(out.truncated)  // endIdx = 8 < 20
        assertTrue(out.content.contains("5→line5"))
        assertTrue(out.content.contains("7→line7"))
        assertFalse(out.content.contains("line4"))
        assertFalse(out.content.contains("line8"))
    }

    @Test
    fun `validation rejects blank path and out-of-range offset-limit`(@TempDir base: Path) = runBlocking {
        val ctx = contextFor(base)

        val blank = FileReadTool.validateInput(FileReadInput(path = ""), ctx)
        assertTrue(blank is ValidationResult.Failed)

        val badOffset = FileReadTool.validateInput(FileReadInput(path = "x", offset = 0), ctx)
        assertTrue(badOffset is ValidationResult.Failed)

        val badLimit = FileReadTool.validateInput(FileReadInput(path = "x", limit = FILE_READ_MAX_LINES + 1), ctx)
        assertTrue(badLimit is ValidationResult.Failed)

        val good = FileReadTool.validateInput(FileReadInput(path = "x", offset = 1, limit = 500), ctx)
        assertEquals(ValidationResult.Ok, good)
    }

    @Test
    fun `permission denies path outside workspace`(@TempDir base: Path) = runBlocking {
        val ctx = contextFor(base)
        val outsideInput = FileReadInput(path = "../escape.txt")
        val perm = FileReadTool.checkPermissions(outsideInput, ctx)
        assertTrue(perm is PermissionResult.Deny, "expected Deny, got $perm")
    }

    @Test
    fun `permission allows path inside workspace`(@TempDir base: Path) = runBlocking {
        val ctx = contextFor(base)
        val perm = FileReadTool.checkPermissions(FileReadInput(path = "sub/file.txt"), ctx)
        assertTrue(perm is PermissionResult.Allow, "expected Allow, got $perm")
    }

    @Test
    fun `permission allows path inside additional working directory`(@TempDir base: Path, @TempDir extra: Path) = runBlocking {
        val ctx = contextFor(base, additionalDirs = setOf(extra.toString()))
        val externalInput = FileReadInput(path = extra.resolve("x.txt").toString())
        val perm = FileReadTool.checkPermissions(externalInput, ctx)
        assertTrue(perm is PermissionResult.Allow, "expected Allow for additional dir, got $perm")
    }

    @Test
    fun `file larger than byte cap returns truncated output`(@TempDir base: Path) = runBlocking {
        val file = base.resolve("huge.txt")
        // Write > 2MiB of text. Each line ~50 bytes → 50k lines ≈ 2.5MiB
        val sb = StringBuilder()
        repeat(50_000) { sb.append("padding-line-").append(it).append('\n') }
        file.writeText(sb.toString())
        val ctx = contextFor(base)

        val parsed = FileReadTool.parseInput(input("huge.txt"))
        val out = FileReadTool.call(parsed, ctx) { _: Progress -> }.data

        assertTrue(out.truncated, "huge file must report truncated")
        assertTrue(out.returnedLines <= FILE_READ_DEFAULT_LIMIT)
    }

    @Test
    fun `metadata predicates mark tool as read-only and concurrency-safe`() {
        val input = FileReadInput(path = "x")
        assertTrue(FileReadTool.isReadOnly(input))
        assertTrue(FileReadTool.isConcurrencySafe(input))
        assertFalse(FileReadTool.isDestructive(input))
    }

    @Test
    fun `preview returns null — read operations have no side effects`(@TempDir base: Path) = runBlocking {
        val ctx = contextFor(base)
        val preview = FileReadTool.preview(FileReadInput(path = "x"), ctx)
        assertEquals(null, preview, "FileRead should not expose a preview")
    }

    @Test
    fun `mapResult includes path and total_lines header`(@TempDir base: Path) = runBlocking {
        val file = base.resolve("z.txt")
        file.writeText("one\ntwo\n")
        val ctx = contextFor(base)

        val parsed = FileReadTool.parseInput(input("z.txt"))
        val result = FileReadTool.call(parsed, ctx) { _: Progress -> }
        val block = FileReadTool.mapResultToApiBlock(result.data, "tid")

        assertEquals("tid", block.toolUseId)
        assertFalse(block.isError)
        assertTrue(block.content.contains("path: "))
        assertTrue(block.content.contains("total_lines: 2"))
        assertNotNull(block.content.contains("1→one"))
    }
}
