package com.github.codeplangui.tools

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolExecutorTest {

    // --- Stub tool that cooperates with the test fixtures ----------------------

    private data class StubInput(val text: String, val fail: String? = null)
    private data class StubOutput(val echoed: String)

    private fun buildStubTool(
        validate: (StubInput) -> ValidationResult = { ValidationResult.Ok },
        permission: (StubInput) -> PermissionResult = { PermissionResult.Allow(JsonObject(emptyMap())) },
        progressEmits: List<Progress> = emptyList(),
        throwOnCall: Boolean = false,
    ): Tool<StubInput, StubOutput> = tool {
        name = "stub"
        description = "test-only tool"
        parse { raw ->
            val obj = raw as JsonObject
            StubInput(
                text = obj["text"]?.jsonPrimitive?.content ?: "",
                fail = obj["fail"]?.jsonPrimitive?.content,
            )
        }
        validate { input, _ -> validate(input) }
        checkPermissions { input, _ -> permission(input) }
        call { input, _, onProgress ->
            if (throwOnCall) error("boom: ${input.text}")
            progressEmits.forEach(onProgress)
            ToolResult(StubOutput(echoed = input.text))
        }
        mapResult { out, id ->
            ToolResultBlock(toolUseId = id, content = out.echoed)
        }
    }

    private fun fakeContext(): ToolExecutionContext = ToolExecutionContext(
        project = mockk<Project>().also { every { it.basePath } returns "/tmp/fake" },
        toolUseId = "test-id",
        abortJob = Job(),
    )

    private fun toolUse(text: String): ToolUseBlock = ToolUseBlock(
        toolUseId = "test-id",
        name = "stub",
        input = buildJsonObject { put("text", JsonPrimitive(text)) },
    )

    // --- Happy path ----------------------------------------------------------

    @Test
    fun `happy path emits Started then Completed`() = runBlocking {
        val tool = buildStubTool()
        val updates = runToolUse(tool, toolUse("hello"), fakeContext()).toList()

        assertEquals(2, updates.size)
        assertTrue(updates[0] is ToolUpdate.Started)
        assertTrue(updates[1] is ToolUpdate.Completed)
        val completed = updates[1] as ToolUpdate.Completed
        assertEquals("hello", completed.block.content)
        assertFalse(completed.block.isError)
    }

    @Test
    fun `progress emits are forwarded as ProgressEmitted events`() = runBlocking {
        val tool = buildStubTool(
            progressEmits = listOf(
                Progress.Stdout("line 1"),
                Progress.Stdout("line 2"),
                Progress.Stderr("warning"),
            ),
        )
        val updates = runToolUse(tool, toolUse("hi"), fakeContext()).toList()
        val progress = updates.filterIsInstance<ToolUpdate.ProgressEmitted>()

        assertEquals(3, progress.size)
        assertTrue(progress[0].progress is Progress.Stdout)
        assertEquals("line 1", (progress[0].progress as Progress.Stdout).line)
        assertTrue(progress[2].progress is Progress.Stderr)
    }

    // --- Validation / permission / execute failure branches ------------------

    @Test
    fun `validation failure short-circuits to Failed(VALIDATE)`() = runBlocking {
        val tool = buildStubTool(
            validate = { ValidationResult.Failed(message = "bad", errorCode = 99) },
        )
        val updates = runToolUse(tool, toolUse("anything"), fakeContext()).toList()

        assertEquals(2, updates.size)  // Started + Failed
        val failed = updates[1] as ToolUpdate.Failed
        assertEquals(ToolUpdate.Failed.Stage.VALIDATE, failed.stage)
        assertEquals("bad", failed.message)
    }

    @Test
    fun `permission deny short-circuits to Failed(PERMISSION)`() = runBlocking {
        val tool = buildStubTool(
            permission = { PermissionResult.Deny("nope") },
        )
        val updates = runToolUse(tool, toolUse("x"), fakeContext()).toList()

        val failed = updates.last() as ToolUpdate.Failed
        assertEquals(ToolUpdate.Failed.Stage.PERMISSION, failed.stage)
    }

    @Test
    fun `permission ask emits PermissionAsked and proceeds (MVP auto-approve)`() = runBlocking {
        val tool = buildStubTool(
            permission = {
                PermissionResult.Ask(
                    reason = "please confirm",
                    preview = PreviewResult(summary = "would run", risk = PreviewResult.Risk.LOW),
                )
            },
        )
        val updates = runToolUse(tool, toolUse("y"), fakeContext()).toList()

        val asked = updates.filterIsInstance<ToolUpdate.PermissionAsked>()
        assertEquals(1, asked.size)
        assertEquals("please confirm", asked[0].reason)
        assertEquals(PreviewResult.Risk.LOW, asked[0].preview?.risk)
        assertTrue(updates.last() is ToolUpdate.Completed)
    }

    @Test
    fun `execution exception becomes Failed(EXECUTE) after Started`() = runBlocking {
        val tool = buildStubTool(throwOnCall = true)
        val updates = runToolUse(tool, toolUse("kaboom"), fakeContext()).toList()

        val failed = updates.last() as ToolUpdate.Failed
        assertEquals(ToolUpdate.Failed.Stage.EXECUTE, failed.stage)
        assertTrue(failed.message.contains("kaboom"))
    }

    // --- Input parse errors ---------------------------------------------------

    @Test
    fun `parse error becomes Failed(PARSE)`() = runBlocking {
        val tool: Tool<StubInput, StubOutput> = tool {
            name = "parse-broken"
            description = "always fails parse"
            parse { _ -> throw IllegalArgumentException("bad json") }
            call { _, _, _ -> ToolResult(StubOutput("")) }
            mapResult { _, id -> ToolResultBlock(id, "") }
        }
        val raw = Json.parseToJsonElement("""{"text":"x"}""") as JsonObject
        val tu = ToolUseBlock(toolUseId = "t1", name = "parse-broken", input = raw)

        val updates = runToolUse(tool, tu, fakeContext()).toList()

        val failed = updates.last() as ToolUpdate.Failed
        assertEquals(ToolUpdate.Failed.Stage.PARSE, failed.stage)
        assertTrue(failed.message.contains("bad json"))
    }
}
