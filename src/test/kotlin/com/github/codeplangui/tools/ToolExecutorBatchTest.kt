package com.github.codeplangui.tools

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolExecutorBatchTest {

    private data class In(val tag: String)
    private data class Out(val echoed: String)

    private fun makeTool(
        n: String,
        safe: Boolean,
        onCall: suspend () -> Unit = {},
    ): Tool<In, Out> = tool {
        name = n
        description = "test tool $n"
        parse { raw ->
            val obj = raw as JsonObject
            In(tag = obj["tag"]?.jsonPrimitive?.content ?: "")
        }
        isConcurrencySafe { safe }
        call { input, _, _ ->
            onCall()
            ToolResult(Out(echoed = input.tag))
        }
        mapResult { out, id -> ToolResultBlock(id, out.echoed) }
    }

    private fun ctx(): ToolExecutionContext = ToolExecutionContext(
        project = mockk<Project>().also { every { it.basePath } returns "/tmp" },
        toolUseId = "batch",
        abortJob = Job(),
    )

    private fun use(name: String, tag: String, id: String) = ToolUseBlock(
        toolUseId = id,
        name = name,
        input = buildJsonObject { put("tag", JsonPrimitive(tag)) },
    )

    @Test
    fun `empty batch emits no updates`() = runBlocking {
        val updates = runToolUseBatch(
            toolUses = emptyList(),
            pool = ToolRegistry.getAllBaseTools(),
            context = ctx(),
        ).toList()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `unknown tool emits Started then Failed(LOOKUP)`() = runBlocking {
        val updates = runToolUseBatch(
            toolUses = listOf(use("NopeTool", "x", "u1")),
            pool = ToolRegistry.getAllBaseTools(),
            context = ctx(),
        ).toList()

        assertEquals(2, updates.size)
        assertTrue(updates[0] is ToolUpdate.Started)
        val failed = updates[1] as ToolUpdate.Failed
        assertEquals(ToolUpdate.Failed.Stage.LOOKUP, failed.stage)
        assertTrue(failed.message.contains("NopeTool"))
    }

    @Test
    fun `concurrency-safe tools execute in parallel`() = runBlocking {
        // Each tool blocks on a barrier, then completes. If they ran serially, the
        // second would observe peakInFlight == 1. If parallel, peak must reach 2.
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val barrier = CompletableDeferred<Unit>()

        val tool: Tool<In, Out> = makeTool(n = "P", safe = true) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            // First task triggers barrier release on its own schedule; both must
            // be in flight for peak to hit 2. Keep simple: release after a short
            // delay so both can enter.
            if (now == 2) barrier.complete(Unit)
            barrier.await()
            inFlight.decrementAndGet()
        }

        val updates = runToolUseBatch(
            toolUses = listOf(use("P", "a", "1"), use("P", "b", "2")),
            pool = listOf(tool),
            context = ctx(),
        ).toList()

        assertEquals(2, peak.get(), "safe tools must run in parallel; peak in-flight should be 2")
        assertEquals(
            2,
            updates.filterIsInstance<ToolUpdate.Completed>().size,
            "both tool_uses should complete",
        )
    }

    @Test
    fun `concurrency-unsafe tools execute serially`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val tool: Tool<In, Out> = makeTool(n = "U", safe = false) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(20)
            inFlight.decrementAndGet()
        }

        val updates = runToolUseBatch(
            toolUses = listOf(use("U", "a", "1"), use("U", "b", "2"), use("U", "c", "3")),
            pool = listOf(tool),
            context = ctx(),
        ).toList()

        assertEquals(1, peak.get(), "unsafe tools must serialize; peak in-flight should be 1")
        assertEquals(3, updates.filterIsInstance<ToolUpdate.Completed>().size)
    }

    @Test
    fun `mixed batch runs safe group concurrently then unsafe serially`() = runBlocking {
        val safeCounter = AtomicInteger(0)
        val unsafeCounter = AtomicInteger(0)
        val safeTool: Tool<In, Out> = makeTool("S", safe = true) { safeCounter.incrementAndGet() }
        val unsafeTool: Tool<In, Out> = makeTool("U", safe = false) { unsafeCounter.incrementAndGet() }

        val updates = runToolUseBatch(
            toolUses = listOf(
                use("S", "a", "1"),
                use("U", "b", "2"),
                use("S", "c", "3"),
                use("U", "d", "4"),
            ),
            pool = listOf(safeTool, unsafeTool),
            context = ctx(),
        ).toList()

        assertEquals(2, safeCounter.get())
        assertEquals(2, unsafeCounter.get())

        val completed = updates.filterIsInstance<ToolUpdate.Completed>()
        assertEquals(4, completed.size)

        // By design, safe tools complete before unsafe ones start. Unsafe completions
        // should appear after all safe completions.
        val safeIds = setOf("1", "3")
        val firstUnsafeIndex = completed.indexOfFirst { it.toolUseId !in safeIds }
        val lastSafeIndex = completed.indexOfLast { it.toolUseId in safeIds }
        assertTrue(
            lastSafeIndex < firstUnsafeIndex,
            "safe tools should finish before unsafe group starts",
        )
    }

    @Test
    fun `maxConcurrency bound is respected`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val tool: Tool<In, Out> = makeTool("S", safe = true) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(15)
            inFlight.decrementAndGet()
        }

        runToolUseBatch(
            toolUses = (1..5).map { use("S", "t$it", "u$it") },
            pool = listOf(tool),
            context = ctx(),
            maxConcurrency = 2,
        ).toList()

        assertTrue(peak.get() <= 2, "peak in-flight ${peak.get()} must respect maxConcurrency=2")
        assertTrue(peak.get() >= 2, "should actually reach the cap with 5 tasks")
    }

    @Test
    fun `parse failure in one tool_use does not block others`() = runBlocking {
        val tool: Tool<In, Out> = tool {
            name = "Brittle"
            description = "parse fails for special tag"
            parse { raw ->
                val obj = raw as JsonObject
                val tag = obj["tag"]?.jsonPrimitive?.content ?: ""
                if (tag == "BOOM") throw IllegalArgumentException("bad tag")
                In(tag = tag)
            }
            isConcurrencySafe { true }
            call { input, _, _ -> ToolResult(Out(echoed = input.tag)) }
            mapResult { out, id -> ToolResultBlock(id, out.echoed) }
        }

        val updates = runToolUseBatch(
            toolUses = listOf(use("Brittle", "ok", "1"), use("Brittle", "BOOM", "2"), use("Brittle", "ok2", "3")),
            pool = listOf(tool),
            context = ctx(),
        ).toList()

        val completed = updates.filterIsInstance<ToolUpdate.Completed>().map { it.toolUseId }.toSet()
        assertEquals(setOf("1", "3"), completed)

        val failed = updates.filterIsInstance<ToolUpdate.Failed>()
        assertEquals(1, failed.size)
        assertEquals(ToolUpdate.Failed.Stage.PARSE, failed[0].stage)
        assertEquals("2", failed[0].toolUseId)
    }

    @Test
    fun `per-tool_use updates remain ordered for the same id`() = runBlocking {
        val tool: Tool<In, Out> = makeTool("S", safe = true)
        val updates = runToolUseBatch(
            toolUses = listOf(use("S", "a", "1"), use("S", "b", "2")),
            pool = listOf(tool),
            context = ctx(),
        ).toList()

        // Started must come before Completed for each id, even though id order across
        // the whole flow is non-deterministic in the concurrent group.
        for (id in listOf("1", "2")) {
            val startedIdx = updates.indexOfFirst { it is ToolUpdate.Started && it.toolUseId == id }
            val completedIdx = updates.indexOfFirst { it is ToolUpdate.Completed && it.toolUseId == id }
            assertTrue(startedIdx >= 0, "Started missing for id=$id")
            assertTrue(completedIdx >= 0, "Completed missing for id=$id")
            assertTrue(startedIdx < completedIdx, "Started must precede Completed for id=$id")
        }
        assertFalse(updates.any { it is ToolUpdate.Failed })
    }
}
