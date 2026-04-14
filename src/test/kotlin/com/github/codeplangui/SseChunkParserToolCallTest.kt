package com.github.codeplangui

import com.github.codeplangui.api.SseChunkParser
import com.github.codeplangui.api.ToolCallDelta
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SseChunkParserToolCallTest {

    @Test
    fun `extractToolCallChunk returns delta on first tool_call chunk`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"run_command","arguments":""}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertEquals("call_abc", delta!!.id)
        assertEquals("run_command", delta.functionName)
        assertEquals("", delta.argumentsChunk)
    }

    @Test
    fun `extractToolCallChunk returns arguments chunk on subsequent chunks`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"co"}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertNull(delta!!.id)
        assertEquals("{\"co", delta.argumentsChunk)
    }

    @Test
    fun `extractToolCallChunk returns null for text delta`() {
        val data = """{"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractToolCallChunk(data))
    }

    @Test
    fun `extractFinishReason returns stop for normal end`() {
        val data = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertEquals("stop", SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractFinishReason returns tool_calls on tool invocation end`() {
        val data = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        assertEquals("tool_calls", SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractFinishReason returns null when finish_reason is absent`() {
        val data = """{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}"""
        assertNull(SseChunkParser.extractFinishReason(data))
    }

    @Test
    fun `extractToolCallChunk handles arguments as pre-parsed JSON object`() {
        // Some providers return arguments as an already-parsed JSON object instead of a string
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xyz","function":{"name":"run_command","arguments":{"command":"ls","description":"list files"}}}]},"finish_reason":null}]}"""
        val delta = SseChunkParser.extractToolCallChunk(data)
        assertNotNull(delta)
        assertEquals("call_xyz", delta!!.id)
        // Should produce a valid JSON string that can be re-parsed
        val argsJson = delta.argumentsChunk
        assertNotNull(argsJson)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(argsJson!!).jsonObject
        assertEquals("ls", parsed["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `extractToolCallChunks returns all tool call deltas with their indexes`() {
        val data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"run_command","arguments":"{\"command\":\"ls -la\"}"}},{"index":1,"id":"call_b","function":{"name":"run_command","arguments":"{\"command\":\"pwd\"}"}}]},"finish_reason":null}]}"""

        val deltas = SseChunkParser.extractToolCallChunks(data)

        assertEquals(2, deltas.size)
        assertEquals(0, deltas[0].index)
        assertEquals("call_a", deltas[0].id)
        assertEquals("{\"command\":\"ls -la\"}", deltas[0].argumentsChunk)
        assertEquals(1, deltas[1].index)
        assertEquals("call_b", deltas[1].id)
        assertEquals("{\"command\":\"pwd\"}", deltas[1].argumentsChunk)
    }
}
