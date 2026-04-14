package com.github.codeplangui

import com.github.codeplangui.api.ToolCallAccumulator
import com.github.codeplangui.api.ToolCallDelta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolCallAccumulatorTest {

    @Test
    fun `accumulator keeps tool call argument streams isolated by index`() {
        val accumulator = ToolCallAccumulator()

        accumulator.append(ToolCallDelta(index = 0, id = "call_a", functionName = "run_command", argumentsChunk = "{"))
        accumulator.append(ToolCallDelta(index = 1, id = "call_b", functionName = "run_command", argumentsChunk = "{"))
        accumulator.append(ToolCallDelta(index = 0, id = null, functionName = null, argumentsChunk = "\"command\":\"ls -la\"}"))
        accumulator.append(ToolCallDelta(index = 1, id = null, functionName = null, argumentsChunk = "\"command\":\"pwd\"}"))

        val snapshot = accumulator.snapshot()

        assertEquals(2, snapshot.size)
        assertEquals(0, snapshot[0].index)
        assertEquals("call_a", snapshot[0].id)
        assertEquals("{\"command\":\"ls -la\"}", snapshot[0].argumentsJson)
        assertEquals(1, snapshot[1].index)
        assertEquals("call_b", snapshot[1].id)
        assertEquals("{\"command\":\"pwd\"}", snapshot[1].argumentsJson)
    }
}
