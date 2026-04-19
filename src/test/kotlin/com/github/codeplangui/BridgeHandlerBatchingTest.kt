package com.github.codeplangui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tests for the bridge micro-batching mechanism.
 *
 * The [BridgeHandler] depends on JCEF and IntelliJ platform internals that
 * cannot be instantiated in unit tests. We therefore replicate the exact
 * buffering logic ([enqueueJS] / [flushAndPush] / [flushPendingBuffer])
 * against a [ConcurrentLinkedQueue] and verify the batch semantics.
 *
 * The production code in [BridgeHandler] uses the same queue-and-flush
 * pattern tested here.
 */
class BridgeHandlerBatchingTest {

    private val pendingJs: Queue<String> = ConcurrentLinkedQueue()
    private val executedJs = mutableListOf<String>()

    // ── Replicated production logic ──────────────────────────────────

    private fun enqueueJS(js: String) {
        pendingJs.add(js)
        // In production: scheduleFlush() coalesces invokeLater calls.
        // In tests: we call flushPendingBuffer() manually to verify batching.
    }

    private fun flushAndPush(js: String) {
        flushPendingBuffer()
        // Non-streamable event pushed immediately after flush
        executedJs.add(js)
    }

    private fun flushPendingBuffer() {
        val batch = mutableListOf<String>()
        while (true) {
            val item = pendingJs.poll() ?: break
            batch.add(item)
        }
        if (batch.isNotEmpty()) {
            executedJs.add(batch.joinToString(";"))
        }
    }

    private fun notifyToken(token: String) =
        enqueueJS("window.__bridge.onToken(\"$token\")")

    private fun notifyLog(msgId: String, line: String, type: String) =
        enqueueJS("window.__bridge.onLog(\"$msgId\",\"$line\",\"$type\")")

    private fun notifyStart(msgId: String) =
        flushAndPush("window.__bridge.onStart('$msgId')")

    private fun notifyEnd(msgId: String) =
        flushAndPush("window.__bridge.onEnd('$msgId')")

    private fun notifyError(msg: String) =
        flushAndPush("window.__bridge.onError(\"$msg\")")

    private fun notifyExecutionCard(reqId: String, cmd: String, desc: String) =
        flushAndPush("window.__bridge.onExecutionCard(\"$reqId\",\"$cmd\",\"$desc\")")

    private fun notifyApprovalRequest(reqId: String, cmd: String, desc: String) =
        flushAndPush("window.__bridge.onApprovalRequest(\"$reqId\",\"$cmd\",\"$desc\")")

    private fun notifyExecutionStatus(reqId: String, status: String, result: String) =
        flushAndPush("window.__bridge.onExecutionStatus(\"$reqId\",\"$status\",\"$result\")")

    // ── Token buffering ──────────────────────────────────────────────

    @Test
    fun `notifyToken buffers and flushPendingBuffer delivers batch`() {
        notifyToken("Hello")
        notifyToken(" ")
        notifyToken("World")

        assertTrue(executedJs.isEmpty(), "No JS should execute until flush")

        flushPendingBuffer()

        assertEquals(1, executedJs.size, "Should be a single batched call")
        val batch = executedJs.first()
        assertTrue(batch.contains("onToken"))
        assertEquals(3, batch.split(";").size, "Should contain 3 token calls")
    }

    @Test
    fun `notifyLog buffers and flushPendingBuffer delivers batch`() {
        notifyLog("req-1", "line 1", "stdout")
        notifyLog("req-1", "line 2", "stdout")
        notifyLog("req-1", "error!", "stderr")

        assertTrue(executedJs.isEmpty())

        flushPendingBuffer()

        assertEquals(1, executedJs.size)
        val batch = executedJs.first()
        assertTrue(batch.contains("onLog"))
        assertEquals(3, batch.split(";").size)
    }

    // ── flushAndPush ordering ────────────────────────────────────────

    @Test
    fun `flushAndPush flushes buffer before pushing its own call`() {
        notifyToken("A")
        notifyToken("B")

        assertTrue(executedJs.isEmpty())

        notifyStart("msg-1")

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("onToken"))
        assertTrue(executedJs[1].contains("onStart"))
    }

    @Test
    fun `notifyEnd flushes buffered tokens before end event`() {
        notifyToken("partial")
        notifyEnd("msg-1")

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("onToken"), "Tokens should flush before onEnd")
        assertTrue(executedJs[1].contains("onEnd"), "onEnd should come after tokens")
    }

    @Test
    fun `notifyError flushes buffered tokens before error event`() {
        notifyToken("data")
        notifyError("something went wrong")

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("onToken"))
        assertTrue(executedJs[1].contains("onError"))
    }

    @Test
    fun `notifyExecutionCard flushes buffered logs`() {
        notifyLog("req-1", "starting build", "stdout")
        notifyExecutionCard("req-1", "npm run build", "Build the project")

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("onLog"))
        assertTrue(executedJs[1].contains("onExecutionCard"))
    }

    @Test
    fun `notifyApprovalRequest flushes buffered tokens`() {
        notifyToken("thinking")
        notifyApprovalRequest("req-1", "rm -rf /", "Delete everything")

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("onToken"))
        assertTrue(executedJs[1].contains("onApprovalRequest"))
    }

    @Test
    fun `notifyExecutionStatus flushes buffered logs`() {
        notifyLog("req-1", "running...", "stdout")
        notifyExecutionStatus("req-1", "done", "{}")

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("onLog"))
        assertTrue(executedJs[1].contains("onExecutionStatus"))
    }

    // ── Mixed batching ───────────────────────────────────────────────

    @Test
    fun `mixed token and log calls batch together`() {
        notifyToken("Hello")
        notifyLog("req-1", "compiling...", "stdout")
        notifyToken(" World")

        assertTrue(executedJs.isEmpty())

        flushPendingBuffer()

        assertEquals(1, executedJs.size)
        val batch = executedJs.first()
        assertTrue(batch.contains("onToken"))
        assertTrue(batch.contains("onLog"))
        assertEquals(3, batch.split(";").size)
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `flushPendingBuffer on empty queue is a no-op`() {
        flushPendingBuffer()
        assertTrue(executedJs.isEmpty())
    }

    @Test
    fun `rapid token stream flushes as single batch`() {
        for (i in 1..100) {
            notifyToken("token-$i")
        }

        assertTrue(executedJs.isEmpty())

        flushPendingBuffer()
        assertEquals(1, executedJs.size)
        assertEquals(100, executedJs[0].split(";").size)

        executedJs.clear()
        flushPendingBuffer()
        assertTrue(executedJs.isEmpty(), "Second flush on empty queue should be no-op")
    }

    @Test
    fun `tokens after flushAndPush start new buffer`() {
        notifyToken("before")
        notifyEnd("msg-1") // flushes "before", then pushes onEnd

        notifyToken("after")

        assertEquals(2, executedJs.size)

        flushPendingBuffer()
        assertEquals(3, executedJs.size)
        assertTrue(executedJs[2].contains("after"))
    }

    @Test
    fun `queue is drained completely on flush`() {
        for (i in 1..50) {
            notifyToken("t$i")
            notifyLog("r", "l$i", "stdout")
        }
        assertEquals(100, pendingJs.size)

        flushPendingBuffer()
        assertEquals(1, executedJs.size)
        assertTrue(pendingJs.isEmpty(), "Queue should be empty after flush")
    }

    @Test
    fun `multiple flush cycles accumulate separate batches`() {
        notifyToken("batch1-a")
        notifyToken("batch1-b")
        flushPendingBuffer()

        notifyToken("batch2-a")
        flushPendingBuffer()

        assertEquals(2, executedJs.size)
        assertTrue(executedJs[0].contains("batch1-a"))
        assertTrue(executedJs[0].contains("batch1-b"))
        assertFalse(executedJs[0].contains("batch2-a"))
        assertTrue(executedJs[1].contains("batch2-a"))
    }

    @Test
    fun `flush interval constant is 16ms for 60fps target`() {
        assertEquals(16L, BridgeHandler.FLUSH_INTERVAL_MS)
    }
}
